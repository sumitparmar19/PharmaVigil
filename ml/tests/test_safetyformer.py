"""SafetyFormer on synthetic reports: corpus building, training views, a short training run that
learns the planted drug–reaction associations, scoring, the time-travel test and the leakage check.
Tiny models and few steps keep this fast; these tests check the code, not real-world performance."""

from datetime import date

import numpy as np
import pandas as pd
import pytest
import torch
from synthetic import make_gold, make_refsets, write_outcome_map

from pharmavigil_ml.safetyformer.corpus import MASK, NODRUG, TYPE_MASK, load_corpus
from pharmavigil_ml.safetyformer.score import log_prob_table, outcome_scores, pt_scores
from pharmavigil_ml.safetyformer.timetravel import (
    TimeTravelConfig,
    label_pairs_from_text,
    months_between,
    run_time_travel,
)
from pharmavigil_ml.safetyformer.train import TrainConfig, load_checkpoint, train
from pharmavigil_ml.safetyformer.views import ViewConfig, ViewMaker, query_batch
from pharmavigil_ml.signals import contingency as ct

TINY = dict(d_model=32, n_layers=1, n_heads=2, d_ff=64, batch_size=64, eval_every=100, eval_reports=300,
            warmup_steps=20, lr=3e-3, min_count=3)


@pytest.fixture(scope="module")
def gold(tmp_path_factory):
    return make_gold(tmp_path_factory.mktemp("gold"), n_reports=3000)


@pytest.fixture(scope="module")
def corpus(gold):
    return load_corpus(ct.connect(gold), min_count=3)


@pytest.fixture(scope="module")
def trained(corpus, tmp_path_factory):
    out = tmp_path_factory.mktemp("model")
    return train(corpus, TrainConfig(steps=400, **TINY), val=corpus, out_dir=out, log=lambda _: None), out


def test_corpus_shapes(corpus) -> None:
    assert len(corpus) == 3000
    assert corpus.drug_offsets[-1] == len(corpus.drug_ids)
    assert corpus.pt_offsets[-1] == len(corpus.pt_ids)
    assert np.all(np.diff(corpus.fda_days) >= 0)  # ordered by date
    assert "pt:acute kidney injury" in corpus.vocab.index


def test_before_cutoff_limits_reports_and_vocab(gold) -> None:
    early = load_corpus(ct.connect(gold), before=date(2019, 7, 1), min_count=3)
    assert 0 < len(early) < 3000
    assert early.fda_days.max() < (date(2019, 7, 1) - date(1970, 1, 1)).days


def test_view_targets_are_distributions(corpus) -> None:
    maker = ViewMaker(corpus, ViewConfig(), seed=1)
    b = maker.batch(np.arange(64))
    assert torch.allclose(b.target.sum(dim=1), torch.ones(64))
    assert (b.tokens[torch.arange(64), b.query_pos] == MASK).all()
    assert (b.types[torch.arange(64), b.query_pos] == TYPE_MASK).all()


def test_query_batch_background_row(corpus) -> None:
    tokens, types, roles, q = query_batch(corpus.vocab, [NODRUG])
    assert tokens[0, 3].item() == NODRUG and roles[0, 3].item() == 0 and q[0].item() == 4


def test_training_reduces_loss(trained) -> None:
    res, _ = trained
    hist = res["history"]
    assert hist[-1]["train_loss"] < hist[0]["train_loss"]
    assert 0 <= hist[-1]["val_recall_at_10"] <= 1
    assert len(res["checkpoint_sha256"]) == 64


def test_scores_recover_planted_signal(trained, corpus) -> None:
    res, _ = trained
    table, bg = log_prob_table(res["model_obj"], corpus.vocab)
    scores = pt_scores(table, bg, corpus.vocab).set_index(["rxcui", "pt"])["safetyformer"]
    planted = scores.loc[("rx1", "acute kidney injury")]
    others = scores.xs("acute kidney injury", level="pt").drop("rx1")
    assert planted > 0
    assert planted > others.median()
    out = outcome_scores(table, bg, corpus.vocab, {"AKI": ["acute kidney injury"], "none": ["not-a-pt"]})
    assert set(out["outcome_name"]) == {"AKI"}


def test_checkpoint_round_trip(trained, corpus) -> None:
    res, out = trained
    model, vocab = load_checkpoint(out / "safetyformer.pt")
    t, ty, r, q = query_batch(vocab, vocab.drug_tokens[:3])
    res["model_obj"].eval()
    assert torch.allclose(model(t, ty, r, q), res["model_obj"](t, ty, r, q), atol=1e-6)


def test_label_pairs_from_text() -> None:
    changes = pd.DataFrame({
        "drug_name": ["Drug1", "drug2", "Nothing", "drug3"],
        "label_date": [pd.Timestamp("2023-06-01"), pd.Timestamp("2022-01-15"), pd.Timestamp("2022-01-01"), pd.NaT],
        "text": ["Cases of Acute Kidney Injury were reported.", "rhabdomyolysis, myalgia", "x", "hepatic failure"],
    })
    ing = pd.DataFrame({"rxcui": ["rx1", "rx2", "rx3"], "ingredient": ["drug1", "drug2", "drug3"]})
    pairs, stats = label_pairs_from_text(changes, ing, ["acute kidney injury", "rhabdomyolysis", "myalgia"])
    assert set(map(tuple, pairs[["rxcui", "pt"]].to_numpy())) == {
        ("rx1", "acute kidney injury"), ("rx2", "rhabdomyolysis"), ("rx2", "myalgia")}
    assert stats["no_date"] == 1 and stats["drug_not_found"] == 1


def test_time_travel_lead_time() -> None:
    pairs = pd.DataFrame({"rxcui": ["A", "B"], "pt": ["x", "x"],
                          "label_date": [date(2023, 7, 1), date(2023, 7, 1)]})
    by_year = {
        date(2021, 1, 1): pd.DataFrame({"rxcui": ["A", "A", "B"], "pt": ["x", "y", "x"], "ebgm": [1.0, 5.0, 0.1]}),
        date(2022, 1, 1): pd.DataFrame({"rxcui": ["A", "A", "B"], "pt": ["x", "y", "x"], "ebgm": [9.0, 5.0, 0.1]}),
    }
    cfg = TimeTravelConfig(snapshots=tuple(by_year), top_n=(1,), methods=("ebgm",))
    out = run_time_travel(pairs, lambda c: by_year[c], cfg, log=lambda _: None)["methods"]["ebgm"]["top_1"]
    assert out["evaluable_pairs"] == 2 and out["flagged_pairs"] == 2  # B is the only PT of its drug
    assert out["median_lead_months"] == pytest.approx(
        np.median([months_between(date(2022, 1, 1), date(2023, 7, 1)), months_between(date(2021, 1, 1),
                                                                                       date(2023, 7, 1))]))


def test_train_and_evaluate_end_to_end(gold, tmp_path) -> None:
    from pharmavigil_ml.safetyformer.run import M4Inputs, train_and_evaluate

    inp = M4Inputs(gold, make_refsets(tmp_path / "ref"), write_outcome_map(tmp_path / "map.csv"),
                   tmp_path / "out", tmp_path / "models", "synthetic test", TrainConfig(steps=200, **TINY), n_boot=20)
    report = train_and_evaluate(inp)
    omop = report["reference_sets"]["omop"]
    assert "safetyformer" in omop["methods"] and "ebgm" in omop["methods"]
    assert (tmp_path / "models" / "safetyformer_pt_scores.parquet").exists()


def test_leakage_compare_runs(corpus) -> None:
    from pharmavigil_ml.safetyformer.leakage import compare_splits

    out = compare_splits(corpus, date(2023, 6, 1), TrainConfig(steps=60, **TINY), eval_reports=200,
                         log=lambda _: None)
    assert {"time_ordered", "shuffled", "recall_at_10_optimism"} <= set(out)


def test_onnx_parity(trained, corpus, tmp_path) -> None:
    from pharmavigil_ml.export.onnx_export import export_safetyformer, safetyformer_parity

    res, _ = trained
    sample = query_batch(corpus.vocab, corpus.vocab.drug_tokens[:6])
    path = export_safetyformer(res["model_obj"], tmp_path / "safetyformer.onnx", sample)
    parity, _ = safetyformer_parity(res["model_obj"], path, sample)
    assert parity["max_abs_diff"] < 1e-4


def test_onnx_other_batch_and_length(trained, corpus, tmp_path) -> None:
    from pharmavigil_ml.export.onnx_export import export_safetyformer, safetyformer_parity
    from pharmavigil_ml.safetyformer.views import ViewConfig, ViewMaker

    res, _ = trained
    trace = query_batch(corpus.vocab, corpus.vocab.drug_tokens[:2])
    path = export_safetyformer(res["model_obj"], tmp_path / "sf.onnx", trace)
    b = ViewMaker(corpus, ViewConfig(p_hide_all_pts=0, p_single_drug=0), seed=4).batch(np.arange(9))
    parity, _ = safetyformer_parity(res["model_obj"], path, (b.tokens, b.types, b.roles, b.query_pos))
    assert parity["max_abs_diff"] < 1e-4


def test_arrow_loader_matches_row_loader(gold, corpus) -> None:
    """The columnar loader gives exactly the corpus the original row-by-row conversion gives."""
    from pharmavigil_ml.safetyformer.corpus import _to_corpus

    con = ct.connect(gold)
    load_corpus(con, min_count=3, vocab=corpus.vocab, keep_tables=True)  # keeps the temp tables for the query
    rows = con.execute("""
        WITH d AS (SELECT primaryid, list(v.tok ORDER BY v.tok) AS dt, list(role ORDER BY v.tok) AS dr
                   FROM sf_drugs JOIN sf_drug_vocab v USING (rxcui) GROUP BY primaryid),
             p AS (SELECT primaryid, list(v.tok ORDER BY v.tok) AS pt
                   FROM sf_pts JOIN sf_pt_vocab v USING (pt) GROUP BY primaryid)
        SELECT r.primaryid, r.fda_dt, r.age_band, r.sex, r.serious, d.dt, d.dr, p.pt
        FROM sf_reports r JOIN d USING (primaryid) JOIN p USING (primaryid)
        ORDER BY r.fda_dt, r.primaryid
    """).fetchall()
    ref = _to_corpus(corpus.vocab, rows)
    for name in ("fda_days", "context", "drug_offsets", "drug_ids", "drug_roles", "pt_offsets", "pt_ids"):
        assert np.array_equal(getattr(ref, name), getattr(corpus, name)), name
    assert list(ref.report_ids) == list(corpus.report_ids)


def test_subset_matches_per_row_selection(corpus) -> None:
    mask = np.random.default_rng(4).random(len(corpus)) < 0.3
    sub = corpus.subset(mask)
    idx = np.flatnonzero(mask)
    assert len(sub) == len(idx)
    for k in (0, len(idx) // 2, len(idx) - 1):
        d_ids, d_roles = corpus.drugs(int(idx[k]))
        s_ids, s_roles = sub.drugs(k)
        assert np.array_equal(d_ids, s_ids) and np.array_equal(d_roles, s_roles)
        assert np.array_equal(corpus.pts(int(idx[k])), sub.pts(k))
    assert len(corpus.subset(np.zeros(len(corpus), dtype=bool))) == 0
