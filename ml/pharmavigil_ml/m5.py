"""M5 runners: train the seriousness, duplicate and MedDRA-coder models on real data, export each to
ONNX, check Python/ONNX Runtime parity, and write evidence under analysis/m5/ with its data scope."""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from .export.onnx_export import (
    coder_parity,
    export_coder,
    export_lightgbm,
    lightgbm_parity,
    write_test_vectors,
)
from .signals import contingency as ct
from .triage.duplicates import PAIR_FEATURES, load_versions, train_duplicates
from .triage.seriousness import load_frames, train_seriousness


def _write(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, default=str))


def _merge_parity(out_dir: Path, name: str, parity: dict[str, float]) -> None:
    path = out_dir / "parity.json"
    data = json.loads(path.read_text()) if path.exists() else {}
    data[name] = parity
    _write(path, data)


def run_triage(gold: Path, out_dir: Path, models_dir: Path, test_from: date, scope: str,
               sample: int | None = None) -> dict[str, object]:
    frames = load_frames(ct.connect(gold), limit=sample)
    res = train_seriousness(frames, test_from, models_dir, scope=scope)
    onnx_path = export_lightgbm(res["booster"], res["x_test"].shape[1], models_dir / "seriousness.onnx")
    probe = res["x_test"][:500]
    parity = lightgbm_parity(res["booster"], onnx_path, probe)
    write_test_vectors(models_dir / "seriousness_vectors.json", {"features": probe[:20]},
                       {"probability": res["booster"].predict(probe[:20])}, "seriousness: features → P(serious)")
    _merge_parity(out_dir, "seriousness", parity)
    _write(out_dir / "triage.json", {**res["metrics"], "sampled_reports": sample, "onnx_parity": parity})
    return res["metrics"]


def run_duplicates(versions_path: Path, out_dir: Path, models_dir: Path, scope: str,
                   sample_cases: int | None = None) -> dict[str, object]:
    versions = load_versions(versions_path, sample_cases=sample_cases)
    res = train_duplicates(versions, models_dir, scope=scope)
    onnx_path = export_lightgbm(res.booster, len(PAIR_FEATURES), models_dir / "duplicates.onnx")
    probe = res.x_test[:500]
    parity = lightgbm_parity(res.booster, onnx_path, probe)
    write_test_vectors(models_dir / "duplicates_vectors.json", {"features": probe[:20]},
                       {"probability": res.booster.predict(probe[:20])}, "duplicates: pair features → P(same event)")
    _merge_parity(out_dir, "duplicates", parity)
    _write(out_dir / "duplicates.json", {**res.metrics, "onnx_parity": parity})
    return res.metrics


def run_coder(narratives: Path, out_dir: Path, models_dir: Path, scope: str, checkpoint: str,
              test_year: int, epochs: int = 3, max_train: int | None = None, max_length: int = 256,
              batch_size: int = 16) -> dict[str, object]:
    """Time split by VAERS receipt year: train before `test_year − 1`, validate on `test_year − 1`,
    test on `test_year`."""
    from transformers import AutoTokenizer

    from .coder.model import build_model, choose_labels
    from .coder.train import CoderConfig, encode, fine_tune, save, tune_and_score

    df = pd.read_parquet(narratives)
    df["pts"] = df["pts"].map(list)
    train_df = df[df["year"] < test_year - 1]
    val_df, test_df = df[df["year"] == test_year - 1], df[df["year"] == test_year]
    if max_train and len(train_df) > max_train:
        train_df = train_df.sample(max_train, random_state=13)
    space = choose_labels(train_df["pts"].tolist())
    tokenizer = AutoTokenizer.from_pretrained(checkpoint)
    model = build_model(checkpoint, len(space.head))
    cfg = CoderConfig(max_length=max_length, batch_size=batch_size, epochs=epochs)
    history = fine_tune(model, tokenizer, train_df["text"].tolist(), train_df["pts"].tolist(), space, cfg)
    metrics = tune_and_score(model, tokenizer, space, (val_df["text"].tolist(), val_df["pts"].tolist()),
                             (test_df["text"].tolist(), test_df["pts"].tolist()), cfg.max_length)
    metrics.update({"scope": scope, "checkpoint": checkpoint, "train_texts": len(train_df),
                    "validation_texts": len(val_df), "history": history, "test_year": test_year,
                    "soc_breakdown": "skipped until the MedDRA licence arrives"})
    save(model, tokenizer, space, cfg, models_dir, metrics)
    probe = encode(tokenizer, test_df["text"].head(8).tolist(), cfg.max_length)
    onnx_path = export_coder(model, models_dir / "coder.onnx", probe["input_ids"], probe["attention_mask"])
    parity, probs, emb = coder_parity(model, onnx_path, probe["input_ids"], probe["attention_mask"])
    write_test_vectors(models_dir / "coder_vectors.json",
                       {"input_ids": probe["input_ids"].numpy(), "attention_mask": probe["attention_mask"].numpy()},
                       {"probabilities": probs, "embedding": emb}, "coder: token ids → head probabilities")
    _merge_parity(out_dir, "coder", parity)
    metrics["onnx_parity"] = parity
    _write(out_dir / "coder.json", metrics)
    return metrics


def score_drug_narratives(labelled_csv: Path, models_dir: Path, out_dir: Path) -> dict[str, object]:
    """Domain-shift check on Sumit's hand-labelled drug narratives (only rows with labels count)."""
    import torch
    from transformers import AutoTokenizer

    from .coder.model import CoderModel, LabelSpace
    from .coder.train import label_embeddings, predict, rank, top_k_recall

    df = pd.read_csv(labelled_csv).fillna("")
    df = df[df["sumit_pts"].str.strip() != ""]
    if df.empty:
        return {"note": "no labelled rows yet"}
    truth = [[p.strip().lower() for p in s.split(";") if p.strip()] for s in df["sumit_pts"]]
    labels = json.loads((models_dir / "labels.json").read_text())
    space = LabelSpace(labels["head"], labels["tail"])
    tokenizer = AutoTokenizer.from_pretrained(str(models_dir / "tokenizer"))
    cfg = json.loads((models_dir / "coder_config.json").read_text())
    from transformers import AutoModel

    encoder = AutoModel.from_pretrained(json.loads((models_dir / "coder_metrics.json").read_text())["checkpoint"])
    model = CoderModel(encoder, encoder.config.hidden_size, len(space.head))
    model.load_state_dict(torch.load(models_dir / "coder.pt", map_location="cpu"))
    probs, embs = predict(model, tokenizer, df["narrative"].tolist(), cfg["max_length"])
    tail = label_embeddings(model, tokenizer, space.tail) if space.tail else np.zeros((0, embs.shape[1]))
    tail_weight = json.loads((models_dir / "coder_metrics.json").read_text())["tail_weight"]
    out = {"labelled_narratives": int(len(df)), "top5_recall": top_k_recall(rank(probs, embs, tail, space,
                                                                                  tail_weight), truth)}
    _write(out_dir / "coder_drug_narratives.json", out)
    return out
