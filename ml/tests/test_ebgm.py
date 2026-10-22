"""EBGM: the fitted prior beats the default start, shrinkage pulls noisy small counts toward the
prior, and EB05 < EBGM < EB95."""

import numpy as np
import pytest

from pharmavigil_ml.signals import ebgm as eb


def _simulate(n_pairs: int = 20000, seed: int = 3):
    rng = np.random.default_rng(seed)
    e = rng.gamma(0.5, 2.0, size=n_pairs)
    true = eb.Prior(0.5, 0.5, 3.0, 1.0, 0.8)
    from_first = rng.random(n_pairs) < true.p
    lam = np.where(from_first, rng.gamma(true.alpha1, 1 / true.beta1, n_pairs),
                   rng.gamma(true.alpha2, 1 / true.beta2, n_pairs))
    n = rng.poisson(lam * e)
    return n, e, true


def test_fit_improves_likelihood() -> None:
    n, e, _ = _simulate()
    keep = n >= 1
    fitted = eb.fit_prior(n, e)
    start = np.asarray(eb.DEFAULT_START)
    theta = np.array([fitted.alpha1, fitted.beta1, fitted.alpha2, fitted.beta2, fitted.p])
    assert eb.negative_log_likelihood(theta, n[keep].astype(float), e[keep]) <= eb.negative_log_likelihood(
        start, n[keep].astype(float), e[keep])


def test_shrinkage_and_ordering() -> None:
    prior = eb.Prior(0.5, 0.5, 3.0, 1.0, 0.8)
    n = np.array([1.0, 200.0])
    e = np.array([0.01, 10.0])
    s = eb.score(n, e, prior)
    # One report against an expectation of 0.01 is a raw ratio of 100; EBGM shrinks it hard.
    assert s["ebgm"][0] < 10
    # With lots of data the posterior follows the observed ratio of 20.
    assert s["ebgm"][1] == pytest.approx(20, rel=0.1)
    assert np.all(s["eb05"] < s["ebgm"]) and np.all(s["ebgm"] < s["eb95"])


def test_quantile_matches_single_component_gamma() -> None:
    from scipy import stats

    prior = eb.Prior(2.0, 1.0, 2.0, 1.0, 0.5)  # identical components → plain gamma posterior
    q = eb.posterior_quantile(np.array([4.0]), np.array([2.0]), prior, 0.05)
    assert q[0] == pytest.approx(stats.gamma.ppf(0.05, 6.0, scale=1 / 3.0), rel=1e-6)
