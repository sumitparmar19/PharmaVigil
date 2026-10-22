"""Fine-tune and evaluate the MedDRA coder.

Metrics (validation tunes the decision threshold and the tail weight; the test split is scored once):
- micro-F1 and macro-F1 over the head labels at the tuned threshold;
- top-5 recall over **all** true PTs (head and tail): Σ |top-5 ∩ true| / Σ |true|, which matches how a
  coder uses suggestions (the human picks from the top five);
- top-5 recall split into head-label and tail-label occurrences.
SOC breakdowns wait for the MedDRA licence (no hierarchy files are used).
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from sklearn.metrics import f1_score

from .model import CoderModel, LabelSpace


@dataclass
class CoderConfig:
    max_length: int = 256
    batch_size: int = 16
    epochs: int = 3
    lr_encoder: float = 3e-5
    lr_head: float = 1e-3
    weight_decay: float = 0.01
    seed: int = 13
    device: str = "auto"


def _device(name: str) -> torch.device:
    return torch.device("cuda" if name == "auto" and torch.cuda.is_available() else
                        ("cpu" if name == "auto" else name))


def encode(tokenizer, texts: Sequence[str], max_length: int) -> dict[str, torch.Tensor]:
    enc = tokenizer(list(texts), truncation=True, max_length=max_length, padding=True, return_tensors="pt")
    return {"input_ids": enc["input_ids"], "attention_mask": enc["attention_mask"]}


def multi_hot(label_sets: Sequence[Sequence[str]], labels: Sequence[str]) -> np.ndarray:
    pos = {p: i for i, p in enumerate(labels)}
    y = np.zeros((len(label_sets), len(labels)), dtype=np.float32)
    for r, ls in enumerate(label_sets):
        for p in ls:
            if p in pos:
                y[r, pos[p]] = 1
    return y


def fine_tune(model: CoderModel, tokenizer, texts: Sequence[str], label_sets: Sequence[Sequence[str]],
              space: LabelSpace, cfg: CoderConfig, log=print) -> list[dict[str, float]]:
    torch.manual_seed(cfg.seed)
    device = _device(cfg.device)
    model.to(device).train()
    y = torch.from_numpy(multi_hot(label_sets, space.head))
    opt = torch.optim.AdamW([
        {"params": model.encoder.parameters(), "lr": cfg.lr_encoder},
        {"params": model.head.parameters(), "lr": cfg.lr_head},
    ], weight_decay=cfg.weight_decay)
    rng = np.random.default_rng(cfg.seed)
    history = []
    for epoch in range(cfg.epochs):
        order = rng.permutation(len(texts))
        total = 0.0
        for start in range(0, len(order), cfg.batch_size):
            idx = order[start:start + cfg.batch_size]
            batch = {k: v.to(device) for k, v in encode(tokenizer, [texts[i] for i in idx], cfg.max_length).items()}
            logits, _ = model(**batch)
            loss = F.binary_cross_entropy_with_logits(logits, y[idx].to(device))
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            total += loss.item() * len(idx)
        history.append({"epoch": epoch + 1, "train_loss": total / len(texts)})
        log(history[-1])
    model.cpu().eval()
    return history


@torch.no_grad()
def predict(model: CoderModel, tokenizer, texts: Sequence[str], max_length: int,
            batch_size: int = 64) -> tuple[np.ndarray, np.ndarray]:
    """(head probabilities, normalized embeddings) for each text."""
    model.eval()
    probs, embs = [], []
    for start in range(0, len(texts), batch_size):
        batch = encode(tokenizer, texts[start:start + batch_size], max_length)
        logits, emb = model(**batch)
        probs.append(torch.sigmoid(logits).numpy())
        embs.append(emb.numpy())
    return np.concatenate(probs), np.concatenate(embs)


@torch.no_grad()
def label_embeddings(model: CoderModel, tokenizer, names: Sequence[str]) -> np.ndarray:
    _, emb = predict(model, tokenizer, list(names), max_length=32)
    return emb


def rank(probs: np.ndarray, embs: np.ndarray, tail_emb: np.ndarray, space: LabelSpace, tail_weight: float,
         k: int = 5) -> list[list[str]]:
    """Top-k suggestions per text, merging head probabilities and tail retrieval scores."""
    tail_scores = tail_weight * (embs @ tail_emb.T + 1) / 2 if len(space.tail) else np.zeros((len(probs), 0))
    scores = np.concatenate([probs, tail_scores], axis=1)
    names = space.all
    top = np.argsort(-scores, axis=1)[:, :k]
    return [[names[j] for j in row] for row in top]


def top_k_recall(suggestions: Sequence[Sequence[str]], truth: Sequence[Sequence[str]],
                 restrict: set[str] | None = None) -> float | None:
    hit = total = 0
    for sug, true in zip(suggestions, truth, strict=True):
        t = {p for p in true if restrict is None or p in restrict}
        total += len(t)
        hit += len(t & set(sug))
    return hit / total if total else None


def tune_and_score(model: CoderModel, tokenizer, space: LabelSpace, val: tuple[list[str], list[list[str]]],
                   test: tuple[list[str], list[list[str]]], max_length: int) -> dict[str, object]:
    tail_emb = label_embeddings(model, tokenizer, space.tail) if space.tail else np.zeros((0, 1))
    vp, ve = predict(model, tokenizer, val[0], max_length)
    yv = multi_hot(val[1], space.head)
    thresholds = np.round(np.arange(0.05, 0.95, 0.05), 2)
    best_t = max(thresholds, key=lambda t: f1_score(yv, vp >= t, average="micro", zero_division=0))
    weights = (0.0, 0.25, 0.5, 0.75, 1.0)
    best_w = max(weights, key=lambda w: top_k_recall(rank(vp, ve, tail_emb, space, w), val[1]) or 0.0)

    tp, te = predict(model, tokenizer, test[0], max_length)
    yt = multi_hot(test[1], space.head)
    sugg = rank(tp, te, tail_emb, space, best_w)
    head, tail = set(space.head), set(space.tail)
    return {
        "threshold": float(best_t),
        "tail_weight": float(best_w),
        "micro_f1": float(f1_score(yt, tp >= best_t, average="micro", zero_division=0)),
        "macro_f1": float(f1_score(yt, tp >= best_t, average="macro", zero_division=0)),
        "top5_recall": top_k_recall(sugg, test[1]),
        "top5_recall_head_labels": top_k_recall(sugg, test[1], head),
        "top5_recall_tail_labels": top_k_recall(sugg, test[1], tail),
        "test_texts": len(test[0]),
        "head_labels": len(space.head),
        "tail_labels": len(space.tail),
    }


def save(model: CoderModel, tokenizer, space: LabelSpace, cfg: CoderConfig, out_dir: Path, metrics: dict) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), out_dir / "coder.pt")
    tokenizer.save_pretrained(str(out_dir / "tokenizer"))
    (out_dir / "labels.json").write_text(json.dumps({"head": space.head, "tail": space.tail}))
    (out_dir / "coder_config.json").write_text(json.dumps(asdict(cfg), indent=2))
    (out_dir / "coder_metrics.json").write_text(json.dumps(metrics, indent=2))
