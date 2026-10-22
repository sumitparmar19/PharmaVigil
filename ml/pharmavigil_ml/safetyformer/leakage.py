"""Leakage check: a shuffled train/test split versus a time-ordered one.

Reports are not independent over time (follow-ups, publicity waves, new drugs), so a random split lets
the model see the "future" of its test reports and looks better than it will be in use. Both splits
here use the same test window size and the same number of training reports, and score held-out
masked-reaction prediction (recall@10 and NLL). The gap between them is the optimism a shuffled split
would have reported.
"""

from __future__ import annotations

from datetime import date

import numpy as np

from .corpus import Corpus
from .train import TrainConfig, masked_reaction_metrics, train


def _days(d: date) -> int:
    return (d - date(1970, 1, 1)).days


def compare_splits(corpus: Corpus, test_start: date, cfg: TrainConfig, seed: int = 0,
                   eval_reports: int = 5000, log=print) -> dict[str, object]:
    rng = np.random.default_rng(seed)
    is_future = corpus.fda_days >= _days(test_start)
    n_test = int(is_future.sum())
    n_train = int((~is_future).sum())
    if n_test == 0 or n_train == 0:
        return {"note": "no reports on one side of the split", "n_test": n_test, "n_train": n_train}

    # Time-ordered: train strictly before test_start, test on/after it.
    time_train = corpus.subset(~is_future)
    time_test = corpus.subset(is_future)

    # Shuffled: a random test set of the same size drawn from all years, and a training set of the same
    # size as the time-ordered one drawn from the remaining reports.
    order = rng.permutation(len(corpus))
    test_mask = np.zeros(len(corpus), dtype=bool)
    test_mask[order[:n_test]] = True
    rest = order[n_test:]
    train_mask = np.zeros(len(corpus), dtype=bool)
    train_mask[rest[: min(n_train, len(rest))]] = True
    shuf_train, shuf_test = corpus.subset(train_mask), corpus.subset(test_mask)

    out: dict[str, object] = {"test_start": test_start.isoformat(), "n_test": n_test, "n_train": n_train}
    for name, (tr, te) in {"time_ordered": (time_train, time_test), "shuffled": (shuf_train, shuf_test)}.items():
        res = train(tr, cfg, run_name=f"leakage-{name}", log=log)
        out[name] = masked_reaction_metrics(res["model_obj"], te, eval_reports)
    t, s = out["time_ordered"], out["shuffled"]
    out["recall_at_10_optimism"] = s["recall_at_10"] - t["recall_at_10"]  # type: ignore[index]
    return out
