"""Empirical Bayes Geometric Mean (EBGM), the Multi-item Gamma Poisson Shrinker used by FDA
(DuMouchel 1999).

Model: the observed count n of a drug–event pair is Poisson(λ·E), E the expected count. The ratio λ
has a prior that is a mixture of two gamma distributions,
    λ ~ p·Gamma(α1, β1) + (1 − p)·Gamma(α2, β2),
fitted to all pairs by maximum marginal likelihood (each pair's marginal is a two-component
negative-binomial mixture). Pairs are fitted with the zero-truncated likelihood over n ≥ 1, as in
openEBGM, because the database only stores pairs that occur. The posterior is again a gamma mixture:
    EBGM = exp(E[log λ | n]),  EB05 / EB95 = 5th / 95th posterior percentiles.
Small counts are pulled ("shrunk") toward the prior, which is what makes EBGM robust to noise.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np
from scipy import optimize, special, stats

# openEBGM's default starting point, plus further starts: on real FAERS the likelihood is flat enough that
# L-BFGS-B from one start ends in different local optima depending on the order of the pairs.
DEFAULT_START = (0.2, 0.1, 2.0, 4.0, 1 / 3)
STARTS = (DEFAULT_START, (0.5, 0.5, 2.0, 1.0, 0.5), (1.0, 2.0, 3.0, 1.0, 0.8), (0.05, 0.05, 1.0, 1.0, 0.9),
          (2.0, 4.0, 0.2, 0.1, 0.2))


@dataclass(frozen=True)
class Prior:
    alpha1: float
    beta1: float
    alpha2: float
    beta2: float
    p: float

    def as_dict(self) -> dict[str, float]:
        return asdict(self)


def _log_nb(n: np.ndarray, e: np.ndarray, alpha: float, beta: float) -> np.ndarray:
    """log P(N=n) for N ~ NegBin(size=alpha, prob=beta/(beta+E))."""
    return (
        special.gammaln(alpha + n) - special.gammaln(alpha) - special.gammaln(n + 1)
        + alpha * np.log(beta / (beta + e)) + n * np.log(e / (beta + e))
    )


def _component_logs(n, e, prior: Prior) -> tuple[np.ndarray, np.ndarray]:
    l1 = np.log(prior.p) + _log_nb(n, e, prior.alpha1, prior.beta1)
    l2 = np.log1p(-prior.p) + _log_nb(n, e, prior.alpha2, prior.beta2)
    return l1, l2


def negative_log_likelihood(theta: np.ndarray, n: np.ndarray, e: np.ndarray, zero_truncated: bool = True) -> float:
    a1, b1, a2, b2, p = theta
    prior = Prior(a1, b1, a2, b2, p)
    l1, l2 = _component_logs(n, e, prior)
    log_marg = np.logaddexp(l1, l2)
    if zero_truncated:
        z1, z2 = _component_logs(np.zeros_like(n), e, prior)
        log_p0 = np.logaddexp(z1, z2)
        log_marg = log_marg - np.log1p(-np.exp(np.minimum(log_p0, -1e-12)))
    return float(-np.sum(log_marg))


def fit_prior(n, e, start=None, zero_truncated: bool = True, max_pairs: int | None = 2_000_000,
              seed: int = 0, screen_pairs: int = 200_000) -> Prior:
    """Fit the gamma-mixture prior. Very large inputs are subsampled (documented in ADR-005)."""
    return fit_prior_report(n, e, start, zero_truncated, max_pairs, seed, screen_pairs)[0]


def fit_prior_report(n, e, start=None, zero_truncated: bool = True, max_pairs: int | None = 2_000_000,
                     seed: int = 0, screen_pairs: int = 200_000) -> tuple[Prior, dict[str, object]]:
    """Fit the prior and say how: the pairs are sorted first (so the result does not depend on the order a
    query returned them in), every start in STARTS (or the one given) is fitted on a fixed random sample of
    `screen_pairs` pairs, and the start with the lowest negative log-likelihood is refined on all pairs."""
    n = np.asarray(n, dtype=float)
    e = np.asarray(e, dtype=float)
    keep = (n >= 1) if zero_truncated else np.ones_like(n, dtype=bool)
    n, e = n[keep], e[keep]
    order = np.lexsort((e, n))
    n, e = n[order], e[order]
    rng = np.random.default_rng(seed)
    if max_pairs is not None and len(n) > max_pairs:
        idx = np.sort(rng.choice(len(n), size=max_pairs, replace=False))
        n, e = n[idx], e[idx]
    bounds = [(1e-5, 50), (1e-5, 50), (1e-5, 50), (1e-5, 50), (1e-4, 1 - 1e-4)]

    def fit(x0, nn, ee):
        r = optimize.minimize(negative_log_likelihood, np.asarray(x0, float), args=(nn, ee, zero_truncated),
                              method="L-BFGS-B", bounds=bounds)
        return r.x, float(r.fun)

    starts = STARTS if start is None else (tuple(start),)
    if len(starts) > 1 and len(n) > screen_pairs:
        sidx = np.sort(rng.choice(len(n), size=screen_pairs, replace=False))
        sn, se = n[sidx], e[sidx]
    else:
        sn, se = n, e
    screened = [(tuple(x0), *fit(x0, sn, se)) for x0 in starts]
    best_start, best_x, _ = min(screened, key=lambda t: t[2])
    x, nll = fit(best_x, n, e) if len(starts) > 1 and len(n) > screen_pairs else (best_x, screened[0][2])
    a1, b1, a2, b2, p = x
    report = {"pairs": int(len(n)), "screen_pairs": int(min(screen_pairs, len(n))),
              "starts": [{"start": list(s0), "nll_on_screen": round(v, 3)} for s0, _, v in screened],
              "chosen_start": list(best_start), "nll": round(nll, 3)}
    return Prior(float(a1), float(b1), float(a2), float(b2), float(p)), report


def posterior_weight(n, e, prior: Prior) -> np.ndarray:
    """Q = posterior probability that the pair's λ came from the first gamma component."""
    l1, l2 = _component_logs(np.asarray(n, float), np.asarray(e, float), prior)
    return np.exp(l1 - np.logaddexp(l1, l2))


def ebgm(n, e, prior: Prior) -> np.ndarray:
    n = np.asarray(n, float)
    e = np.asarray(e, float)
    q = posterior_weight(n, e, prior)
    e_log = q * (special.digamma(prior.alpha1 + n) - np.log(prior.beta1 + e)) + (1 - q) * (
        special.digamma(prior.alpha2 + n) - np.log(prior.beta2 + e)
    )
    return np.exp(e_log)


def posterior_quantile(n, e, prior: Prior, prob: float, iterations: int = 60) -> np.ndarray:
    """Quantile of each pair's posterior gamma mixture, by vectorized bisection.

    The mixture quantile always lies between the two component quantiles, which bracket the search.
    """
    n = np.asarray(n, float)
    e = np.asarray(e, float)
    q = posterior_weight(n, e, prior)
    shape1, scale1 = prior.alpha1 + n, 1 / (prior.beta1 + e)
    shape2, scale2 = prior.alpha2 + n, 1 / (prior.beta2 + e)
    p1 = stats.gamma.ppf(prob, shape1, scale=scale1)
    p2 = stats.gamma.ppf(prob, shape2, scale=scale2)
    lo, hi = np.minimum(p1, p2), np.maximum(p1, p2)
    for _ in range(iterations):
        mid = (lo + hi) / 2
        cdf = q * stats.gamma.cdf(mid, shape1, scale=scale1) + (1 - q) * stats.gamma.cdf(mid, shape2, scale=scale2)
        below = cdf < prob
        lo = np.where(below, mid, lo)
        hi = np.where(below, hi, mid)
    return (lo + hi) / 2


def score(n, e, prior: Prior, quantiles: bool = True) -> dict[str, np.ndarray]:
    out = {"ebgm": ebgm(n, e, prior)}
    if quantiles:
        out["eb05"] = posterior_quantile(n, e, prior, 0.05)
        out["eb95"] = posterior_quantile(n, e, prior, 0.95)
    return out
