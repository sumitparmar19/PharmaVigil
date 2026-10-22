"""Classic frequentist and Bayesian disproportionality statistics for drug–event pairs.

For a drug D and an event E, the 2×2 table over reports is:

                 E      not E
    D            a        b        a + b = n_drug
    not D        c        d
                 a + c = n_event                 N = a + b + c + d

- PRR  = [a / (a+b)] / [c / (c+d)]             (Evans et al. 2001)
- ROR  = (a·d) / (b·c)                          (van Puijenbroek et al. 2002)
- IC   = log2[(a + 0.5) / (E + 0.5)], E = (a+b)(a+c)/N   (BCPNN with Norén et al. 2006 shrinkage)

PRR and ROR get 95% confidence intervals on the log scale. Any table with a zero cell gets the
Haldane–Anscombe correction (+0.5 to every cell) so every pair has a finite score. IC credibility
limits use Norén's closed-form approximation. All functions are vectorized over numpy arrays.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

Z95 = 1.959963984540054


def table_cells(a, n_drug, n_event, n_total) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    a = np.asarray(a, dtype=float)
    b = np.asarray(n_drug, dtype=float) - a
    c = np.asarray(n_event, dtype=float) - a
    d = np.asarray(n_total, dtype=float) - a - b - c
    if np.any((b < 0) | (c < 0) | (d < 0)):
        raise ValueError("inconsistent counts: a must not exceed the margins")
    return a, b, c, d


def _haldane(a, b, c, d):
    zero = (a == 0) | (b == 0) | (c == 0) | (d == 0)
    k = np.where(zero, 0.5, 0.0)
    return a + k, b + k, c + k, d + k


def expected_count(n_drug, n_event, n_total) -> np.ndarray:
    return np.asarray(n_drug, dtype=float) * np.asarray(n_event, dtype=float) / np.asarray(n_total, dtype=float)


def prr(a, n_drug, n_event, n_total) -> pd.DataFrame:
    a_, b_, c_, d_ = _haldane(*table_cells(a, n_drug, n_event, n_total))
    est = (a_ / (a_ + b_)) / (c_ / (c_ + d_))
    se = np.sqrt(1 / a_ - 1 / (a_ + b_) + 1 / c_ - 1 / (c_ + d_))
    return pd.DataFrame({"prr": est, "prr_lo": est * np.exp(-Z95 * se), "prr_hi": est * np.exp(Z95 * se)})


def ror(a, n_drug, n_event, n_total) -> pd.DataFrame:
    a_, b_, c_, d_ = _haldane(*table_cells(a, n_drug, n_event, n_total))
    est = (a_ * d_) / (b_ * c_)
    se = np.sqrt(1 / a_ + 1 / b_ + 1 / c_ + 1 / d_)
    return pd.DataFrame({"ror": est, "ror_lo": est * np.exp(-Z95 * se), "ror_hi": est * np.exp(Z95 * se)})


def information_component(a, n_drug, n_event, n_total) -> pd.DataFrame:
    a = np.asarray(a, dtype=float)
    e = expected_count(n_drug, n_event, n_total)
    ic = np.log2((a + 0.5) / (e + 0.5))
    s = a + 0.5
    return pd.DataFrame({
        "ic": ic,
        "ic025": ic - 3.3 * s**-0.5 - 2.0 * s**-1.5,
        "ic975": ic + 2.4 * s**-0.5 - 0.5 * s**-1.5,
    })


def all_frequentist(a, n_drug, n_event, n_total) -> pd.DataFrame:
    """PRR, ROR and IC side by side, plus the expected count."""
    out = pd.concat(
        [prr(a, n_drug, n_event, n_total), ror(a, n_drug, n_event, n_total),
         information_component(a, n_drug, n_event, n_total)],
        axis=1,
    )
    out.insert(0, "expected", expected_count(n_drug, n_event, n_total))
    return out
