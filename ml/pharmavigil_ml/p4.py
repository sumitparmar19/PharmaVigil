"""Phase 2 (P4): the case-service models trained or built on real FAERS data, their measured results, and the
model bundle the live demo loads.

- Seriousness triage (LightGBM) on real FAERS seriousness, with the outcome-leakage guard, time-split test set.
- Duplicate detection (LightGBM) on real FAERS case versions; the label is a proxy (consecutive versions of one
  case are "duplicates"), stated in the results and the model card.
- MedDRA coder: zero-shot retrieval over the FAERS preferred terms (coder/zero_shot.py); not evaluated until
  labelled narratives exist (VAERS, owner step 14).

Writes analysis/p4/{triage,duplicates,coder}.json and a bundle directory (bundle.json kind "trained", with each
file's SHA-256) in the layout export/java_bundle.py documents.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import numpy as np

from .coder import zero_shot
from .export.java_bundle import finish_bundle, write_duplicates, write_seriousness
from .m5 import run_duplicates, run_triage
from .signals import contingency as ct


def _examples(spec) -> list[dict]:
    """Four parity examples built from features the trained model really has (the commonest drugs and PTs)."""
    d, p = spec.drugs, spec.pts
    return [
        {"age_years": 67, "sex": "F", "reporter_type": "PHYSICIAN", "country": "US",
         "drugs": [{"rxcui": d[0], "role": "PS"}], "pts": [p[0]]},
        {"age_years": 34, "sex": "M", "reporter_type": "CONSUMER", "country": "GB",
         "drugs": [{"rxcui": d[1], "role": "PS"}, {"rxcui": d[2], "role": "C"}], "pts": [p[1], "death"]},
        {"age_years": None, "sex": "UNK", "reporter_type": "PHARMACIST", "country": "FR",
         "drugs": [{"rxcui": "no-such-drug", "role": "SS"}], "pts": []},
        {"age_years": 81.5, "sex": "F", "reporter_type": "OTHER_HCP", "country": "US",
         "drugs": [{"rxcui": d[3], "role": "PS"}, {"rxcui": d[0], "role": "C"}], "pts": [p[2], p[3]]},
    ]


def top_pts(gold: Path, n: int) -> list[str]:
    con = ct.connect(gold)
    return [r[0] for r in con.execute(
        f"SELECT pt FROM src_report_reaction GROUP BY pt ORDER BY count(*) DESC, pt LIMIT {int(n)}").fetchall()]


def run(gold: Path, versions: Path, encoder_dir: Path, out_dir: Path, models_dir: Path, bundle_dir: Path,
        scope: str, test_from: date, triage_sample: int, dup_sample_cases: int, coder_labels: int,
        coder_threshold: float) -> dict[str, object]:
    import lightgbm as lgb

    from .triage.features import FeatureSpec

    out_dir.mkdir(parents=True, exist_ok=True)
    bundle_dir.mkdir(parents=True, exist_ok=True)

    triage = run_triage(gold, out_dir, models_dir, test_from, scope, sample=triage_sample)
    dups = run_duplicates(versions, out_dir, models_dir, scope, sample_cases=dup_sample_cases)

    # Bundle: re-read the saved boosters so the bundle holds exactly what was evaluated.
    spec = FeatureSpec.load(models_dir / "seriousness_features.json")
    ser = lgb.Booster(model_file=str(models_dir / "seriousness.lgb.txt"))
    parity: dict[str, object] = {"seriousness": write_seriousness(ser, spec, bundle_dir, _examples(spec))}
    dup = lgb.Booster(model_file=str(models_dir / "duplicates.lgb.txt"))
    vec = json.loads((models_dir / "duplicates_vectors.json").read_text())["inputs"]["features"]
    probe = np.asarray(vec["data"], dtype=np.float32).reshape(vec["shape"])[:6]
    parity["duplicates"] = write_duplicates(dup, float(dups["best_f1_threshold"]), bundle_dir, probe)

    pts = top_pts(gold, coder_labels)
    coder = zero_shot.build(encoder_dir, pts, bundle_dir, coder_threshold,
                            probes=["itchy skin", "a high fever", "muscle pain after starting the statin",
                                    "Rhabdomyolysis", "my head hurts badly"])
    parity["coder"] = coder
    coder_result = {
        "scope": scope,
        "kind": "zero-shot retrieval (no training labels)",
        "encoder": zero_shot.ENCODER, "encoder_revision": zero_shot.ENCODER_REVISION,
        "label_space": f"the {len(pts)} most reported FAERS preferred terms in the scope",
        "quantization": "int8 dynamic (per tensor)",
        "threshold_for_coded_without_review": coder_threshold,
        "evaluation": "pending: needs narratives with human-assigned PTs (VAERS, owner step 14)",
        "examples": coder["rows"],
    }
    (out_dir / "coder.json").write_text(json.dumps(coder_result, indent=2))
    meta = finish_bundle(bundle_dir, "trained", scope, parity)
    return {"triage": triage, "duplicates": dups, "coder": coder_result, "bundle_files": meta["files"]}
