"""SafetyFormer signal scores (ADR-005).

For each drug d in the vocabulary the model is asked "which reaction is hidden?" with only that drug
present (age, sex and seriousness unknown); the background asks the same with no drug. The score is
the log ratio of the two predicted probabilities, summed over an outcome's PT list:

    score(d, O) = log Σ_{pt∈O} p(pt | d) − log Σ_{pt∈O} p(pt | no drug)
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence

import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F

from .corpus import NODRUG, Vocab
from .model import SafetyFormer
from .views import query_batch


@torch.no_grad()
def log_prob_table(model: SafetyFormer, vocab: Vocab, batch: int = 1024) -> tuple[np.ndarray, np.ndarray]:
    """(n_drug × n_pt) log p(pt | drug) and the (n_pt,) background log p(pt | no drug)."""
    model.eval()
    rows = []
    drugs = list(vocab.drug_tokens)
    for start in range(0, len(drugs), batch):
        t, ty, r, q = query_batch(vocab, drugs[start:start + batch])
        rows.append(F.log_softmax(model(t, ty, r, q), dim=-1).numpy())
    t, ty, r, q = query_batch(vocab, [NODRUG])
    background = F.log_softmax(model(t, ty, r, q), dim=-1).numpy()[0]
    table = np.concatenate(rows) if rows else np.zeros((0, vocab.n_pt), dtype=np.float32)
    return table, background


def pt_scores(table: np.ndarray, background: np.ndarray, vocab: Vocab) -> pd.DataFrame:
    """Long table of (rxcui, pt, safetyformer) for every vocabulary drug and PT."""
    pmi = table - background[None, :]
    rx = np.repeat(np.array(vocab.drug_rxcuis(), dtype=object), vocab.n_pt)
    pt = np.tile(np.array(vocab.pt_names(), dtype=object), len(vocab.drug_tokens))
    return pd.DataFrame({"rxcui": rx, "pt": pt, "safetyformer": pmi.reshape(-1)})


def outcome_scores(table: np.ndarray, background: np.ndarray, vocab: Vocab,
                   outcome_pts: Mapping[str, Sequence[str]]) -> pd.DataFrame:
    """(rxcui, outcome_name, safetyformer) for every vocabulary drug and outcome PT list."""
    pt_pos = {p: i for i, p in enumerate(vocab.pt_names())}
    rows = []
    rxcuis = vocab.drug_rxcuis()
    for outcome, pts in outcome_pts.items():
        cols = [pt_pos[p.lower()] for p in pts if p.lower() in pt_pos]
        if not cols:
            continue
        drug_lse = _logsumexp(table[:, cols])
        bg_lse = _logsumexp(background[None, cols])[0]
        rows.append(pd.DataFrame({"rxcui": rxcuis, "outcome_name": outcome, "safetyformer": drug_lse - bg_lse}))
    return pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=["rxcui", "outcome_name",
                                                                                "safetyformer"])


def _logsumexp(x: np.ndarray) -> np.ndarray:
    m = x.max(axis=1, keepdims=True)
    return (m + np.log(np.exp(x - m).sum(axis=1, keepdims=True)))[:, 0]
