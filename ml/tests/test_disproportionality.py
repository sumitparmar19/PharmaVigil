"""PRR, ROR and IC against hand-computed values, and the zero-cell correction."""

import math

import numpy as np
import pytest

from pharmavigil_ml.signals import disproportionality as dp


def test_known_table() -> None:
    # a=10, b=90, c=100, d=9800 → N=10000
    out = dp.all_frequentist([10], [100], [110], [10000]).iloc[0]
    assert out["prr"] == pytest.approx((10 / 100) / (100 / 9900))
    assert out["ror"] == pytest.approx(10 * 9800 / (90 * 100))
    assert out["expected"] == pytest.approx(1.1)
    assert out["ic"] == pytest.approx(math.log2(10.5 / 1.6))
    assert out["prr_lo"] < out["prr"] < out["prr_hi"]
    assert out["ror_lo"] < out["ror"] < out["ror_hi"]
    assert out["ic025"] < out["ic"] < out["ic975"]


def test_zero_cell_is_finite() -> None:
    out = dp.all_frequentist([0, 5], [50, 5], [40, 40], [1000, 1000])
    assert np.isfinite(out[["prr", "prr_lo", "ror", "ror_lo", "ic"]].to_numpy()).all()
    assert out.loc[0, "prr"] < 1  # never reported together → below 1


def test_inconsistent_counts_rejected() -> None:
    with pytest.raises(ValueError):
        dp.prr([10], [5], [20], [100])
