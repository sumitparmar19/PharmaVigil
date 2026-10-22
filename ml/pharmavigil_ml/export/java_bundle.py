"""Model bundle for the Java case-service: the ONNX graphs plus everything Java needs to build their inputs
and read their outputs, and parity vectors that Java tests replay against Python's answers.

Layout (one directory, loaded by `ModelBundle` in case-service from PV_MODEL_DIR):

    bundle.json                 kind ("fixture" or "trained"), data scope, file hashes
    seriousness.onnx            LightGBM → ONNX; input "features" float32[n, F]
    seriousness_features.json   FeatureSpec columns + age bands + outcome-revealing pattern
    duplicates.onnx             LightGBM → ONNX; input "features" float32[n, 10]
    duplicates.json             pair-feature order and decision threshold
    coder.onnx                  encoder + head; inputs input_ids/attention_mask int64[n, L]
    coder_vocab.txt             WordPiece vocabulary (one token per line, id = line number)
    coder.json                  head labels, tail labels with their embeddings, tail weight, max length
    parity.json                 inputs and Python outputs for each model

`fixture_bundle` trains tiny models on invented data (no FAERS, VAERS or MedDRA content) so the Java
pipeline can be tested on real ONNX graphs in CI. They are not trained on real reports and their scores
mean nothing; real bundles come from the Kaggle and Databricks runs (OWNER_STEPS.md).
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

from ..triage.duplicates import PAIR_FEATURES
from ..triage.duplicates import PARAMS as DUP_PARAMS
from ..triage.features import AGE_BANDS, OUTCOME_REVEALING, FeatureSpec, vectorize_one
from ..triage.seriousness import Frames, train_seriousness
from .onnx_export import export_lightgbm

# Same boundaries as the data platform's silver layer (data/pharmavigil_data/faers/silver.py): [lo, hi).
AGE_BAND_BOUNDS = ((0, 2, "0-1"), (2, 12, "2-11"), (12, 18, "12-17"), (18, 45, "18-44"),
                   (45, 65, "45-64"), (65, 75, "65-74"), (75, 130, "75+"))
assert tuple(label for _, _, label in AGE_BAND_BOUNDS) + ("unknown",) == AGE_BANDS

# E2B reporter qualification / PharmaVigil reporter type → FAERS occupation code.
OCCUPATION = {"PHYSICIAN": "MD", "PHARMACIST": "PH", "OTHER_HCP": "OT", "LAWYER": "LW", "CONSUMER": "CN"}


def age_band(years: float | None) -> str:
    if years is None or not (0 <= years < 130):
        return "unknown"
    for lo, hi, label in AGE_BAND_BOUNDS:
        if lo <= years < hi:
            return label
    return "unknown"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _lgb_proba(path: Path, x: np.ndarray) -> np.ndarray:
    import onnxruntime as ort

    out = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(None, {"features": x})
    return np.asarray(out[1])[:, 1]


# -- seriousness -------------------------------------------------------------------------------------------

def write_seriousness(booster: lgb.Booster, spec: FeatureSpec, out: Path,
                      examples: list[dict]) -> dict[str, object]:
    path = export_lightgbm(booster, len(spec.columns()), out / "seriousness.onnx")
    (out / "seriousness_features.json").write_text(json.dumps({
        "drugs": spec.drugs, "pts": spec.pts, "countries": spec.countries, "occupations": spec.occupations,
        "columns": spec.columns(), "age_bands": [list(b) for b in AGE_BAND_BOUNDS],
        "outcome_revealing_pattern": OUTCOME_REVEALING.pattern,
    }, indent=1))
    rows = []
    for e in examples:
        x = vectorize_one(age_band(e["age_years"]), e["sex"], OCCUPATION.get(e["reporter_type"]), e["country"],
                          [(d["rxcui"], d["role"]) for d in e["drugs"]], e["pts"], spec)
        rows.append({"input": e, "vector": x.tolist(),
                     "probability": float(booster.predict(x.reshape(1, -1))[0])})
    onnx_p = _lgb_proba(path, np.array([r["vector"] for r in rows], dtype=np.float32))
    return {"rows": rows, "onnx_max_abs_diff": float(np.max(np.abs(onnx_p - [r["probability"] for r in rows])))}


# -- duplicates --------------------------------------------------------------------------------------------

def write_duplicates(booster: lgb.Booster, threshold: float, out: Path, x: np.ndarray) -> dict[str, object]:
    path = export_lightgbm(booster, len(PAIR_FEATURES), out / "duplicates.onnx")
    (out / "duplicates.json").write_text(json.dumps({"features": list(PAIR_FEATURES), "threshold": threshold},
                                                    indent=1))
    p = booster.predict(x)
    onnx_p = _lgb_proba(path, x.astype(np.float32))
    return {"rows": [{"features": r.tolist(), "probability": float(v)} for r, v in zip(x, p, strict=True)],
            "onnx_max_abs_diff": float(np.max(np.abs(onnx_p - p)))}


# -- coder -------------------------------------------------------------------------------------------------

@dataclass
class CoderParts:
    model: object
    tokenizer: object
    head: list[str]
    tail: list[str]
    tail_weight: float
    max_length: int
    threshold: float  # tuned on validation data (micro-F1) for real models


def write_coder(parts: CoderParts, out: Path, texts: list[str], k: int = 5) -> dict[str, object]:
    import torch

    from ..coder.model import LabelSpace
    from ..coder.train import encode, label_embeddings, predict, rank
    from .onnx_export import coder_parity, export_coder

    batch = encode(parts.tokenizer, texts, parts.max_length)
    path = export_coder(parts.model, out / "coder.onnx", batch["input_ids"], batch["attention_mask"])
    parity, _, _ = coder_parity(parts.model, path, batch["input_ids"], batch["attention_mask"])
    vocab = sorted(parts.tokenizer.get_vocab().items(), key=lambda kv: kv[1])
    assert [i for _, i in vocab] == list(range(len(vocab))), "vocabulary ids must be dense"
    (out / "coder_vocab.txt").write_text("\n".join(t for t, _ in vocab) + "\n")
    # Real BERT tokenizers wrap each text in [CLS] ... [SEP]; a tokenizer built without a post-processor does
    # not. Record which, so Java builds the same ids.
    probe = parts.tokenizer("x")["input_ids"]
    vocab_ids = dict(vocab)
    add_special = probe[0] == vocab_ids.get("[CLS]") and probe[-1] == vocab_ids.get("[SEP]")
    space = LabelSpace(parts.head, parts.tail)
    tail_emb = label_embeddings(parts.model, parts.tokenizer, parts.tail) if parts.tail else np.zeros((0, 1))
    (out / "coder.json").write_text(json.dumps({
        "head": parts.head, "tail": parts.tail, "tail_embeddings": np.asarray(tail_emb).tolist(),
        "tail_weight": parts.tail_weight, "max_length": parts.max_length, "threshold": parts.threshold,
        "lowercase": True,
        "add_special_tokens": bool(add_special),
        "special_tokens": {"cls": "[CLS]", "sep": "[SEP]", "pad": "[PAD]", "unk": "[UNK]"},
    }))
    with torch.no_grad():
        probs, embs = predict(parts.model, parts.tokenizer, texts, parts.max_length)
    suggestions = rank(probs, embs, tail_emb, space, parts.tail_weight, k=k)
    rows = []
    for i, t in enumerate(texts):
        ids = batch["input_ids"][i][batch["attention_mask"][i] == 1].tolist()
        rows.append({"text": t, "input_ids": ids, "head_probabilities": probs[i].tolist(),
                     "suggestions": suggestions[i]})
    return {"rows": rows, "onnx_max_abs_diff": parity["max_abs_diff"], "k": k}


# -- bundle ------------------------------------------------------------------------------------------------

def finish_bundle(out: Path, kind: str, scope: str, parity: dict[str, object]) -> dict[str, object]:
    (out / "parity.json").write_text(json.dumps(parity))
    files = sorted(p for p in out.iterdir() if p.is_file() and p.name != "bundle.json")
    meta = {"kind": kind, "scope": scope, "files": {p.name: _sha256(p) for p in files}}
    (out / "bundle.json").write_text(json.dumps(meta, indent=1))
    return meta


def _fixture_frames(n: int = 1500, seed: int = 5) -> Frames:
    rng = np.random.default_rng(seed)
    reports, drugs, pts = [], [], []
    for i in range(n):
        pid = f"r{i}"
        rx = f"rx{rng.integers(0, 8)}"
        pt = f"pt{rng.integers(0, 10)}"
        serious = rng.random() < (0.75 if rx == "rx1" or pt == "pt3" else 0.15)
        reports.append({"primaryid": pid, "fda_dt": date(2020, 1, 1) + timedelta(days=i // 3),
                        "age_band": rng.choice(["18-44", "65-74"]), "sex": rng.choice(["M", "F"]),
                        "occp_cod": rng.choice(["MD", "PH", "CN"]), "reporter_country": rng.choice(["US", "GB"]),
                        "serious": serious, "outcomes": ["HO"] if serious else []})
        drugs.append({"primaryid": pid, "rxcui": rx, "role": "PS"})
        pts.append({"primaryid": pid, "pt": pt})
    return Frames(pd.DataFrame(reports), pd.DataFrame(drugs), pd.DataFrame(pts))


def _fixture_pairs(n: int = 3000, seed: int = 11) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    y = rng.random(n) < 0.3
    dj = np.where(y, rng.uniform(0.5, 1, n), rng.uniform(0, 0.6, n))
    pj = np.where(y, rng.uniform(0.4, 1, n), rng.uniform(0, 0.5, n))
    same = lambda p: np.where(rng.random(n) < p, 1.0, 0.0)  # noqa: E731
    x = np.column_stack([
        dj, pj, np.round(dj * 3), np.round(pj * 2), np.where(y, same(0.95), same(0.5)),
        np.where(y, same(0.9), same(0.3)), np.where(y, same(0.95), same(0.6)), np.where(y, same(0.8), same(0.4)),
        np.where(y, rng.integers(0, 5, n), rng.integers(0, 400, n)),
        np.where(y, rng.integers(0, 60, n), rng.integers(0, 900, n)),
    ]).astype(np.float32)
    return x, y.astype(int)


CODER_PHRASES = {"a red rash": "rash", "itchy skin": "pruritus", "a high fever": "pyrexia",
                 "a bad headache": "headache", "vomiting": "vomiting", "dizziness": "dizziness",
                 "hair loss": "alopecia"}


def _coder_texts(n: int, seed: int) -> tuple[list[str], list[list[str]]]:
    rng = np.random.default_rng(seed)
    keys = list(CODER_PHRASES)
    weights = np.array([8, 6, 6, 5, 4, 3, 0.4])
    texts, labels = [], []
    for _ in range(n):
        k = rng.choice(keys, size=rng.integers(1, 3), replace=False, p=weights / weights.sum())
        texts.append("The patient developed " + " and ".join(k) + " two days after the first dose.")
        labels.append(sorted({CODER_PHRASES[x] for x in k}))
    return texts, labels


def _fixture_coder() -> CoderParts:
    import torch
    from tokenizers import BertWordPieceTokenizer
    from transformers import BertConfig, BertModel, PreTrainedTokenizerFast

    from ..coder.model import CoderModel, choose_labels
    from ..coder.train import CoderConfig, fine_tune

    texts, labels = _coder_texts(400, 1)
    wp = BertWordPieceTokenizer(lowercase=True)
    wp.train_from_iterator(texts + list(CODER_PHRASES.values()), vocab_size=200, min_frequency=1)
    tok = PreTrainedTokenizerFast(tokenizer_object=wp._tokenizer, unk_token="[UNK]", pad_token="[PAD]",
                                  cls_token="[CLS]", sep_token="[SEP]", mask_token="[MASK]")
    torch.manual_seed(0)
    enc = BertModel(BertConfig(vocab_size=tok.vocab_size, hidden_size=32, num_hidden_layers=1, num_attention_heads=2,
                               intermediate_size=64, max_position_embeddings=64))
    space = choose_labels(labels, coverage=0.97)
    model = CoderModel(enc, 32, len(space.head))
    fine_tune(model, tok, texts, labels, space, CoderConfig(max_length=32, batch_size=32, epochs=6, lr_encoder=1e-3),
              log=lambda _: None)
    return CoderParts(model.eval(), tok, space.head, space.tail, tail_weight=0.5, max_length=32,
                      threshold=0.5)


def fixture_bundle(out: Path) -> dict[str, object]:
    """Tiny models on invented data, for Java tests only."""
    out.mkdir(parents=True, exist_ok=True)
    frames = _fixture_frames()
    trained = train_seriousness(frames, date(2021, 1, 1), out / "_work", rounds=25, scope="synthetic fixture")
    examples = [
        {"age_years": 54, "sex": "F", "reporter_type": "PHARMACIST", "country": "US",
         "drugs": [{"rxcui": "rx1", "role": "PS"}], "pts": ["pt3"]},
        {"age_years": 30, "sex": "M", "reporter_type": "CONSUMER", "country": "GB",
         "drugs": [{"rxcui": "rx5", "role": "PS"}, {"rxcui": "rx2", "role": "C"}], "pts": ["pt7", "death"]},
        {"age_years": None, "sex": "UNK", "reporter_type": "LAWYER", "country": "FR",
         "drugs": [{"rxcui": "rx99", "role": "SS"}], "pts": []},
        {"age_years": 70.5, "sex": "F", "reporter_type": "PHYSICIAN", "country": "US",
         "drugs": [{"rxcui": "rx0", "role": "PS"}, {"rxcui": "rx0", "role": "PS"}], "pts": ["pt1", "Pt1"]},
    ]
    parity: dict[str, object] = {"seriousness": write_seriousness(trained["booster"], trained["spec"], out, examples)}

    x, y = _fixture_pairs()
    dup = lgb.train(DUP_PARAMS, lgb.Dataset(x, y), num_boost_round=25)
    parity["duplicates"] = write_duplicates(dup, 0.5, out, x[:6])

    parity["coder"] = write_coder(_fixture_coder(), out, _coder_texts(4, 9)[0] + [
        "Severe ITCHY skin, then a high-fever (39°C)!", "Unrelated words: café naïve résumé"])

    for f in (out / "_work").iterdir():
        f.unlink()
    (out / "_work").rmdir()
    return finish_bundle(out, "fixture", "synthetic invented data; not trained on any real report", parity)
