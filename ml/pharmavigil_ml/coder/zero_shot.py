"""Zero-shot MedDRA coder for the Java bundle (Phase 2, P4): a pretrained biomedical sentence encoder
(NeuML/pubmedbert-base-embeddings, Apache-2.0, pinned revision) ranks the preferred terms that FDA publishes in FAERS
by cosine similarity to a reported term or narrative. No training labels are used at all (so none can come from
an LLM); the classification head is empty and every PT is a "tail" label scored by retrieval. The ONNX graph is
quantized to int8 to fit the demo server's memory, and the PT embeddings are computed with that same quantized graph
so queries and labels live in one space. Its accuracy is measured once labelled narratives exist (VAERS, owner step
14); until then it is reported as not evaluated.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

ENCODER = "NeuML/pubmedbert-base-embeddings"
ENCODER_REVISION = "b79526d6ef3645e0df4530322e266f24c829f5ef"


def _session(path: Path):
    import onnxruntime as ort

    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])


def _embed(session, tokenizer, texts: list[str], max_length: int) -> np.ndarray:
    """One text at a time, as case-service runs the model: int8 activations are quantized per call, so padding a
    batch would shift the embeddings away from the ones Java computes for a single reported term."""
    out = []
    for text in texts:
        enc = tokenizer([text], truncation=True, max_length=max_length, return_tensors="np")
        _, emb = session.run(None, {"input_ids": enc["input_ids"].astype(np.int64),
                                    "attention_mask": enc["attention_mask"].astype(np.int64)})
        out.append(emb[0])
    return np.vstack(out)


def build(encoder_dir: Path, pts: list[str], out: Path, threshold: float, max_length: int = 64,
          probes: list[str] | None = None) -> dict[str, object]:
    """Write coder.onnx (int8), coder_vocab.txt and coder.json into `out`; return the bundle's parity rows."""
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from transformers import AutoModel, AutoTokenizer

    from ..export.onnx_export import export_coder
    from .model import CoderModel

    out.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(encoder_dir)
    encoder = AutoModel.from_pretrained(encoder_dir)
    model = CoderModel(encoder, encoder.config.hidden_size, 0).eval()
    sample = tokenizer(["itchy skin after the new tablet"], padding="max_length", truncation=True,
                       max_length=16, return_tensors="pt")
    fp32 = out / "coder_fp32.onnx"
    with torch.no_grad():
        export_coder(model, fp32, sample["input_ids"], sample["attention_mask"])
    target = out / "coder.onnx"
    # Per-tensor int8 (per-channel quantization fails on this graph in onnxruntime 1.31); the empty head stays float.
    quantize_dynamic(str(fp32), str(target), weight_type=QuantType.QInt8, nodes_to_exclude=["/model/head/Gemm"])
    fp32.unlink()

    session = _session(target)
    tail = list(dict.fromkeys(p.strip().lower() for p in pts if p and p.strip()))
    tail_emb = _embed(session, tokenizer, tail, max_length)
    vocab = sorted(tokenizer.get_vocab().items(), key=lambda kv: kv[1])
    assert [i for _, i in vocab] == list(range(len(vocab))), "vocabulary ids must be dense"
    (out / "coder_vocab.txt").write_text("\n".join(t for t, _ in vocab) + "\n")
    (out / "coder.json").write_text(json.dumps({
        "head": [], "tail": tail, "tail_embeddings": np.round(tail_emb, 5).tolist(), "tail_weight": 1.0,
        "max_length": max_length, "threshold": threshold, "lowercase": True, "add_special_tokens": True,
        "special_tokens": {"cls": "[CLS]", "sep": "[SEP]", "pad": "[PAD]", "unk": "[UNK]"},
        "kind": "zero-shot retrieval", "encoder": ENCODER, "encoder_revision": ENCODER_REVISION,
    }, separators=(",", ":")))

    rows = []
    for text in probes or []:
        enc = tokenizer([text], truncation=True, max_length=max_length, return_tensors="np")
        _, emb = session.run(None, {"input_ids": enc["input_ids"].astype(np.int64),
                                    "attention_mask": enc["attention_mask"].astype(np.int64)})
        scores = (tail_emb @ emb[0] + 1) / 2
        best = np.argsort(-scores)[:5]
        rows.append({"text": text, "input_ids": enc["input_ids"][0].tolist(),
                     "suggestions": [[tail[j], float(scores[j])] for j in best]})
    return {"rows": rows, "k": 5, "labels": len(tail), "onnx_bytes": target.stat().st_size}
