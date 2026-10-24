"""Training views: turn reports into (input set, target) pairs for the masked-reaction objective.

For each report one [MASK] query token is added and a random subset of its PTs stays visible; the
target is the *uniform distribution over the hidden PTs*, so with several hidden PTs the model learns
the distribution of reactions in that context rather than an arbitrary one.

"Conditioning dropout" makes the scoring query (one drug, unknown age/sex/seriousness, no other PTs)
a situation the model has seen in training:
- keep a single random drug with probability `p_single_drug`;
- replace all drugs with [NODRUG] with probability `p_no_drug` (the background used by the score);
- hide every PT with probability `p_hide_all_pts`;
- set each context token to "unknown" with probability `p_unknown_context`.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import torch

from .corpus import MASK, MASKDRUG, NODRUG, ROLES, TYPE_CONTEXT, TYPE_DRUG, TYPE_MASK, TYPE_PT, Corpus


@dataclass(frozen=True)
class ViewConfig:
    p_single_drug: float = 0.3
    p_no_drug: float = 0.05
    p_hide_all_pts: float = 0.5
    p_unknown_context: float = 0.3
    max_drugs: int = 12
    max_pts: int = 12
    masked_drug_rate: float = 0.0  # share of views that also hide one drug (masked-drug objective)


@dataclass
class Batch:
    tokens: torch.Tensor
    types: torch.Tensor
    roles: torch.Tensor
    query_pos: torch.Tensor
    target: torch.Tensor  # (B, n_pt) probabilities
    drug_query_pos: torch.Tensor | None = None
    drug_target: torch.Tensor | None = None  # drug-head index, -100 when not used


class ViewMaker:
    def __init__(self, corpus: Corpus, cfg: ViewConfig, seed: int = 0) -> None:
        self.c = corpus
        self.cfg = cfg
        self.rng = np.random.default_rng(seed)
        v = corpus.vocab
        self.pt_index = {tok: i for i, tok in enumerate(v.pt_tokens)}
        self.drug_index = {tok: i for i, tok in enumerate(v.drug_tokens)}
        self.unknown = (v.index["age:unknown"], v.index["sex:UNK"], v.index["serious:unknown"])

    def _view(self, i: int):
        cfg, rng = self.cfg, self.rng
        ctx = [self.unknown[k] if rng.random() < cfg.p_unknown_context else int(t)
               for k, t in enumerate(self.c.context[i])]
        drugs, roles = self.c.drugs(i)
        drugs, roles = list(drugs[: cfg.max_drugs]), list(roles[: cfg.max_drugs])
        if rng.random() < cfg.p_no_drug:
            drugs, roles = [NODRUG], [ROLES["none"]]
        elif len(drugs) > 1 and rng.random() < cfg.p_single_drug:
            k = int(rng.integers(len(drugs)))
            drugs, roles = [drugs[k]], [roles[k]]
        pts = list(self.c.pts(i)[: cfg.max_pts])
        if rng.random() < cfg.p_hide_all_pts or len(pts) == 1:
            visible, hidden = [], pts
        else:
            perm = rng.permutation(len(pts))
            n_visible = int(rng.integers(0, len(pts)))
            visible = [pts[j] for j in perm[:n_visible]]
            hidden = [pts[j] for j in perm[n_visible:]]
        drug_target = -100
        if cfg.masked_drug_rate and drugs[0] != NODRUG and rng.random() < cfg.masked_drug_rate:
            k = int(rng.integers(len(drugs)))
            drug_target = self.drug_index[drugs[k]]
            drugs = drugs[:k] + [MASKDRUG] + drugs[k + 1:]
        return ctx, drugs, roles, visible, hidden, drug_target

    def batch(self, indices: np.ndarray) -> Batch:
        views = [self._view(int(i)) for i in indices]
        length = max(3 + len(d) + len(v) + 1 for _, d, _, v, _, _ in views)
        n = len(views)
        tokens = np.zeros((n, length), dtype=np.int64)
        types = np.zeros((n, length), dtype=np.int64)
        roles = np.zeros((n, length), dtype=np.int64)
        query = np.zeros(n, dtype=np.int64)
        dquery = np.zeros(n, dtype=np.int64)
        dtarget = np.full(n, -100, dtype=np.int64)
        target = np.zeros((n, len(self.pt_index)), dtype=np.float32)
        for r, (ctx, drugs, rls, visible, hidden, d_t) in enumerate(views):
            seq = ctx + drugs + visible + [MASK]
            typ = [TYPE_CONTEXT] * 3 + [TYPE_DRUG] * len(drugs) + [TYPE_PT] * len(visible) + [TYPE_MASK]
            rol = [0, 0, 0] + list(rls) + [0] * (len(visible) + 1)
            tokens[r, : len(seq)] = seq
            types[r, : len(seq)] = typ
            roles[r, : len(seq)] = rol
            query[r] = len(seq) - 1
            for h in hidden:
                target[r, self.pt_index[h]] += 1.0 / len(hidden)
            if d_t >= 0:
                dtarget[r] = d_t
                dquery[r] = 3 + drugs.index(MASKDRUG)
        return Batch(torch.from_numpy(tokens), torch.from_numpy(types), torch.from_numpy(roles),
                     torch.from_numpy(query), torch.from_numpy(target),
                     torch.from_numpy(dquery), torch.from_numpy(dtarget))


def query_batch(vocab, drug_tokens: list[int]) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """Scoring inputs: [age:unknown, sex:UNK, serious:unknown, <drug or NODRUG>, MASK] per row."""
    unknown = [vocab.index["age:unknown"], vocab.index["sex:UNK"], vocab.index["serious:unknown"]]
    n = len(drug_tokens)
    tokens = torch.tensor([unknown + [d, MASK] for d in drug_tokens], dtype=torch.long)
    types = torch.tensor([[TYPE_CONTEXT] * 3 + [TYPE_DRUG, TYPE_MASK]] * n, dtype=torch.long)
    roles = torch.tensor([[0, 0, 0, ROLES["PS"] if d != NODRUG else 0, 0] for d in drug_tokens], dtype=torch.long)
    return tokens, types, roles, torch.full((n,), 4, dtype=torch.long)
