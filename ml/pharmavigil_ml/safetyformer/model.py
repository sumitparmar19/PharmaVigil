"""SafetyFormer: a small Transformer encoder over a report's token *set*, trained from scratch with a
self-supervised masked-reaction objective (and an optional masked-drug objective).

Input per report: context tokens (age band, sex, seriousness), drug tokens with a role embedding, the
visible reaction (PT) tokens, and one [MASK] query token. There are no position embeddings because a
report is a set; a type embedding (context / drug / PT / mask) tells tokens apart. The encoder output
at the [MASK] token feeds a linear head over the PT vocabulary, giving p(PT | rest of the report).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
from torch import nn

from .corpus import ROLES


@dataclass(frozen=True)
class ModelConfig:
    vocab_size: int
    n_pt: int
    n_drug: int
    d_model: int = 256
    n_layers: int = 4
    n_heads: int = 8
    d_ff: int = 1024
    dropout: float = 0.1
    masked_drug_objective: bool = False

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


class SafetyFormer(nn.Module):
    def __init__(self, cfg: ModelConfig) -> None:
        super().__init__()
        self.cfg = cfg
        self.tok = nn.Embedding(cfg.vocab_size, cfg.d_model, padding_idx=0)
        self.typ = nn.Embedding(5, cfg.d_model)
        self.role = nn.Embedding(len(ROLES), cfg.d_model)
        layer = nn.TransformerEncoderLayer(
            cfg.d_model, cfg.n_heads, cfg.d_ff, cfg.dropout, batch_first=True, norm_first=True,
            activation="gelu",
        )
        self.encoder = nn.TransformerEncoder(layer, cfg.n_layers, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(cfg.d_model)
        self.pt_head = nn.Linear(cfg.d_model, cfg.n_pt)
        self.drug_head = nn.Linear(cfg.d_model, cfg.n_drug) if cfg.masked_drug_objective else None
        self.apply(self._init)

    @staticmethod
    def _init(m: nn.Module) -> None:
        if isinstance(m, nn.Linear):
            nn.init.normal_(m.weight, std=0.02)
            if m.bias is not None:
                nn.init.zeros_(m.bias)
        elif isinstance(m, nn.Embedding):
            nn.init.normal_(m.weight, std=0.02)

    def encode(self, tokens: torch.Tensor, types: torch.Tensor, roles: torch.Tensor) -> torch.Tensor:
        x = self.tok(tokens) + self.typ(types) + self.role(roles)
        h = self.encoder(x, src_key_padding_mask=tokens.eq(0))
        return self.norm(h)

    def forward(self, tokens: torch.Tensor, types: torch.Tensor, roles: torch.Tensor,
                query_pos: torch.Tensor) -> torch.Tensor:
        """PT logits at each report's [MASK] position (query_pos: index of the mask per row)."""
        h = self.encode(tokens, types, roles)
        q = h[torch.arange(h.size(0), device=h.device), query_pos]
        return self.pt_head(q)

    def drug_logits(self, tokens, types, roles, drug_query_pos) -> torch.Tensor:
        if self.drug_head is None:
            raise RuntimeError("model was built without the masked-drug objective")
        h = self.encode(tokens, types, roles)
        return self.drug_head(h[torch.arange(h.size(0), device=h.device), drug_query_pos])
