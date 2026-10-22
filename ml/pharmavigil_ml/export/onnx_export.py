"""ONNX export for every PharmaVigil model, with Python parity checks and test vectors for Java.

The Java services run the models with ONNX Runtime (no Python in the live path, ADR-008). For each model
this module (1) exports the ONNX graph, (2) runs the same inputs through the original model and through
ONNX Runtime and records the maximum absolute difference, and (3) writes a small JSON file of inputs and
expected outputs that the Java parity tests replay.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

OPSET = 17
LIGHTGBM_OPSET = 15  # highest opset the onnxmltools LightGBM converter supports


def _session(path: Path) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def write_test_vectors(path: Path, inputs: dict[str, np.ndarray], outputs: dict[str, np.ndarray],
                       note: str) -> None:
    path.write_text(json.dumps({
        "note": note,
        "inputs": {k: {"shape": list(v.shape), "dtype": str(v.dtype), "data": v.reshape(-1).tolist()}
                   for k, v in inputs.items()},
        "outputs": {k: {"shape": list(v.shape), "data": v.reshape(-1).tolist()} for k, v in outputs.items()},
    }))


# -- LightGBM models (seriousness, duplicates) -----------------------------------------------------------

def export_lightgbm(booster, n_features: int, path: Path) -> Path:
    from onnxmltools import convert_lightgbm
    from onnxmltools.convert.common.data_types import FloatTensorType

    onx = convert_lightgbm(booster, initial_types=[("features", FloatTensorType([None, n_features]))],
                           zipmap=False, target_opset=LIGHTGBM_OPSET)
    path.write_bytes(onx.SerializeToString())
    return path


def lightgbm_parity(booster, path: Path, x: np.ndarray) -> dict[str, float]:
    expected = booster.predict(x).astype(np.float64)
    out = _session(path).run(None, {"features": x.astype(np.float32)})
    got = np.asarray(out[1])[:, 1].astype(np.float64)  # outputs: label, probabilities
    return {"rows": int(len(x)), "max_abs_diff": float(np.max(np.abs(expected - got))) if len(x) else 0.0}


# -- SafetyFormer -----------------------------------------------------------------------------------------

class _SafetyFormerLogProbs(torch.nn.Module):
    def __init__(self, model) -> None:
        super().__init__()
        self.model = model

    def forward(self, tokens, types, roles, query_pos):
        return torch.log_softmax(self.model(tokens, types, roles, query_pos), dim=-1)


def export_safetyformer(model, path: Path, sample: Sequence[torch.Tensor]) -> Path:
    """torch.export-based exporter with symbolic batch and sequence sizes (the legacy tracer bakes the
    attention reshape to the traced shape for nn.TransformerEncoder)."""
    model.eval()
    batch, seq = torch.export.Dim("batch"), torch.export.Dim("seq")
    program = torch.onnx.export(
        _SafetyFormerLogProbs(model), tuple(sample), dynamo=True, opset_version=18,
        input_names=["tokens", "types", "roles", "query_pos"], output_names=["log_probs"],
        dynamic_shapes=({0: batch, 1: seq}, {0: batch, 1: seq}, {0: batch, 1: seq}, {0: batch}),
    )
    program.save(str(path))
    return path


@torch.no_grad()
def safetyformer_parity(model, path: Path, sample: Sequence[torch.Tensor]) -> tuple[dict[str, float], np.ndarray]:
    expected = _SafetyFormerLogProbs(model.eval())(*sample).numpy()
    names = ["tokens", "types", "roles", "query_pos"]
    got = _session(path).run(None, {n: t.numpy() for n, t in zip(names, sample, strict=True)})[0]
    return {"rows": int(expected.shape[0]), "max_abs_diff": float(np.max(np.abs(expected - got)))}, expected


# -- MedDRA coder -----------------------------------------------------------------------------------------

def export_coder(model, path: Path, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> Path:
    from ..coder.model import OnnxCoder

    model.eval()
    torch.onnx.export(
        OnnxCoder(model), (input_ids, attention_mask), str(path), dynamo=False, opset_version=OPSET,
        input_names=["input_ids", "attention_mask"], output_names=["probabilities", "embedding"],
        dynamic_axes={"input_ids": {0: "batch", 1: "seq"}, "attention_mask": {0: "batch", 1: "seq"},
                      "probabilities": {0: "batch"}, "embedding": {0: "batch"}},
    )
    return path


@torch.no_grad()
def coder_parity(model, path: Path, input_ids: torch.Tensor,
                 attention_mask: torch.Tensor) -> tuple[dict[str, float], np.ndarray, np.ndarray]:
    from ..coder.model import OnnxCoder

    probs, emb = OnnxCoder(model.eval())(input_ids, attention_mask)
    got = _session(path).run(None, {"input_ids": input_ids.numpy(), "attention_mask": attention_mask.numpy()})
    diff = max(float(np.max(np.abs(probs.numpy() - got[0]))), float(np.max(np.abs(emb.numpy() - got[1]))))
    return {"rows": int(probs.shape[0]), "max_abs_diff": diff}, probs.numpy(), emb.numpy()
