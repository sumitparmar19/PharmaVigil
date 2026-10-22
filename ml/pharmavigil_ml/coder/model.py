"""MedDRA coder model: a pretrained biomedical text encoder fine-tuned with a multi-label head over the
frequent PTs, plus embedding retrieval over PT names for the rare ones (ADR-007).

- Head labels: the most frequent PTs that together cover `coverage` of all label occurrences.
- Classification: sigmoid scores from a linear head on the mean-pooled encoder output.
- Retrieval: the same encoder embeds every PT name; a narrative's embedding is compared by cosine
  similarity, which lets the model suggest PTs it has rarely or never seen as labels.
- Ranking: head PTs by probability, tail PTs by `tail_weight · (cos + 1) / 2`; the weight is tuned on
  validation data for top-5 recall.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass

import torch
import torch.nn.functional as F
from torch import nn

DEFAULT_CHECKPOINT = "microsoft/BiomedNLP-BiomedBERT-base-uncased-abstract-fulltext"


@dataclass(frozen=True)
class LabelSpace:
    head: list[str]
    tail: list[str]

    @property
    def all(self) -> list[str]:
        return self.head + self.tail


def choose_labels(label_sets: Sequence[Sequence[str]], coverage: float = 0.95, max_head: int = 2000) -> LabelSpace:
    """Head = most frequent PTs covering `coverage` of label occurrences (capped at max_head)."""
    counts = Counter(pt for labels in label_sets for pt in labels)
    total = sum(counts.values())
    head, running = [], 0
    for pt, c in counts.most_common():
        if running / max(total, 1) >= coverage or len(head) >= max_head:
            break
        head.append(pt)
        running += c
    head_set = set(head)
    tail = sorted(pt for pt in counts if pt not in head_set)
    return LabelSpace(head=head, tail=tail)


def mean_pool(hidden: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    m = mask.unsqueeze(-1).to(hidden.dtype)
    return (hidden * m).sum(1) / m.sum(1).clamp(min=1e-6)


class CoderModel(nn.Module):
    def __init__(self, encoder: nn.Module, hidden_size: int, n_head_labels: int, dropout: float = 0.1) -> None:
        super().__init__()
        self.encoder = encoder
        self.dropout = nn.Dropout(dropout)
        self.head = nn.Linear(hidden_size, n_head_labels)

    def embed(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        return mean_pool(out.last_hidden_state, attention_mask)

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """(head logits, L2-normalized embedding)."""
        e = self.embed(input_ids, attention_mask)
        return self.head(self.dropout(e)), F.normalize(e, dim=-1)


class OnnxCoder(nn.Module):
    """Export wrapper: returns sigmoid probabilities and the normalized embedding (eval mode)."""

    def __init__(self, model: CoderModel) -> None:
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        logits, emb = self.model(input_ids, attention_mask)
        return torch.sigmoid(logits), emb


def build_model(checkpoint: str, n_head_labels: int, encoder: nn.Module | None = None,
                hidden_size: int | None = None) -> CoderModel:
    """Load the pretrained encoder by name (Kaggle), or wrap a given encoder (tests)."""
    if encoder is None:
        from transformers import AutoModel

        encoder = AutoModel.from_pretrained(checkpoint)
        hidden_size = encoder.config.hidden_size
    return CoderModel(encoder, int(hidden_size), n_head_labels)
