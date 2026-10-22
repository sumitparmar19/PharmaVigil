"""`pv-ml` command line for the model milestones. Every command writes its evidence under analysis/.

  pv-ml baselines --scope "subset: FAERS 2019Q1–2026Q2"      # M3: PRR/ROR/IC/EBGM + reference-set AUCs
  pv-ml safetyformer --scope "..." --steps 20000               # M4: train + compare on reference sets
  pv-ml time-travel --scope "..." --label-changes srlc.csv     # M4: yearly snapshots vs label changes
  pv-ml leakage --scope "..." --test-start 2025-01-01          # M4: shuffled vs time-ordered split
  pv-ml triage --scope "..." --test-from 2025-07-01            # M5: seriousness LightGBM + ONNX
  pv-ml duplicates --scope "..." --versions <silver versions>  # M5: duplicate model + ONNX
  pv-ml coder --scope "..." --narratives <vaers parquet>       # M5: MedDRA coder fine-tune + ONNX (GPU)
"""

from __future__ import annotations

import argparse
import os
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_LAKE = Path(os.environ.get("PV_LAKE", REPO / "data" / "lake"))
DEFAULT_OUTCOME_MAP = REPO / "data" / "pharmavigil_data" / "resources" / "outcome_pt_map.csv"


def cmd_baselines(args: argparse.Namespace) -> int:
    from .signals.baselines import BaselineInputs, run

    run(BaselineInputs(
        gold_dir=Path(args.gold),
        refsets_dir=Path(args.refsets),
        outcome_map=Path(args.outcome_map),
        out_dir=Path(args.out),
        signals_dir=Path(args.signals_dir) if args.signals_dir else None,
        scope=args.scope,
        before=date.fromisoformat(args.before) if args.before else None,
        n_boot=args.n_boot,
    ))
    print(f"wrote {Path(args.out) / 'baselines.json'}")
    return 0


def _m4_inputs(args: argparse.Namespace):
    from .safetyformer.run import M4Inputs
    from .safetyformer.train import TrainConfig

    cfg = TrainConfig(steps=args.steps, batch_size=args.batch_size, d_model=args.d_model, n_layers=args.n_layers,
                      n_heads=args.n_heads, d_ff=args.d_model * 4, min_count=args.min_count, seed=args.seed,
                      eval_every=max(1, min(500, args.steps)), masked_drug_objective=args.masked_drug)
    return M4Inputs(gold_dir=Path(args.gold), refsets_dir=Path(args.refsets), outcome_map=Path(args.outcome_map),
                    out_dir=Path(args.out), models_dir=Path(args.models_dir), scope=args.scope, train=cfg,
                    before=date.fromisoformat(args.before) if args.before else None,
                    tracking_uri=args.tracking_uri)


def cmd_safetyformer(args: argparse.Namespace) -> int:
    from .safetyformer.run import train_and_evaluate

    train_and_evaluate(_m4_inputs(args))
    print(f"wrote {Path(args.out) / 'safetyformer.json'}")
    return 0


def cmd_time_travel(args: argparse.Namespace) -> int:
    import pandas as pd

    from .safetyformer.run import time_travel

    changes = pd.read_csv(args.label_changes, parse_dates=["label_date"])
    snapshots = [date(y, 1, 1) for y in range(args.first_year, args.last_year + 1)]
    time_travel(_m4_inputs(args), changes, snapshots)
    print(f"wrote {Path(args.out) / 'time_travel.json'}")
    return 0


def cmd_leakage(args: argparse.Namespace) -> int:
    from .safetyformer.run import leakage

    leakage(_m4_inputs(args), date.fromisoformat(args.test_start))
    print(f"wrote {Path(args.out) / 'leakage.json'}")
    return 0


def _add_m4_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--gold", default=str(DEFAULT_LAKE / "gold" / "faers"))
    p.add_argument("--refsets", default=str(DEFAULT_LAKE / "bronze" / "refsets"))
    p.add_argument("--outcome-map", default=str(DEFAULT_OUTCOME_MAP))
    p.add_argument("--out", default=str(REPO / "analysis" / "m4"))
    p.add_argument("--models-dir", default=str(REPO / "ml" / "artifacts" / "safetyformer"))
    p.add_argument("--tracking-uri", default=f"file:{REPO / 'ml' / 'mlruns'}")
    p.add_argument("--scope", required=True)
    p.add_argument("--before")
    p.add_argument("--steps", type=int, default=20000)
    p.add_argument("--batch-size", type=int, default=256)
    p.add_argument("--d-model", type=int, default=256)
    p.add_argument("--n-layers", type=int, default=4)
    p.add_argument("--n-heads", type=int, default=8)
    p.add_argument("--min-count", type=int, default=5)
    p.add_argument("--seed", type=int, default=13)
    p.add_argument("--masked-drug", action="store_true")


def cmd_triage(args: argparse.Namespace) -> int:
    from .m5 import run_triage

    run_triage(Path(args.gold), Path(args.out), Path(args.models_dir) / "seriousness",
               date.fromisoformat(args.test_from), args.scope, sample=args.sample)
    return 0


def cmd_duplicates(args: argparse.Namespace) -> int:
    from .m5 import run_duplicates

    run_duplicates(Path(args.versions), Path(args.out), Path(args.models_dir) / "duplicates", args.scope,
                   sample_cases=args.sample_cases)
    return 0


def cmd_coder(args: argparse.Namespace) -> int:
    from .coder.model import DEFAULT_CHECKPOINT
    from .m5 import run_coder

    run_coder(Path(args.narratives), Path(args.out), Path(args.models_dir) / "coder", args.scope,
              args.checkpoint or DEFAULT_CHECKPOINT, args.test_year, epochs=args.epochs, max_train=args.max_train,
              batch_size=args.batch_size)
    return 0


def cmd_coder_drug_narratives(args: argparse.Namespace) -> int:
    from .m5 import score_drug_narratives

    print(score_drug_narratives(Path(args.csv), Path(args.models_dir) / "coder", Path(args.out)))
    return 0


def cmd_export_signals(args: argparse.Namespace) -> int:
    from .signals.export import export_signals

    meta = export_signals(Path(args.gold), Path(args.signals_dir), Path(args.out), args.scope,
                          Path(args.safetyformer) if args.safetyformer else None,
                          Path(args.baselines_json) if args.baselines_json else None)
    print(f"wrote {meta['pairs']} signal pairs to {args.out}")
    return 0


def cmd_java_bundle(args: argparse.Namespace) -> int:
    from .export.java_bundle import fixture_bundle

    meta = fixture_bundle(Path(args.out))
    print(f"wrote {len(meta['files'])} files to {args.out} ({meta['kind']})")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="pv-ml", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    b = sub.add_parser("baselines", help="M3 signal baselines and reference-set evaluation")
    b.add_argument("--gold", default=str(DEFAULT_LAKE / "gold" / "faers"))
    b.add_argument("--refsets", default=str(DEFAULT_LAKE / "bronze" / "refsets"))
    b.add_argument("--outcome-map", default=str(DEFAULT_OUTCOME_MAP))
    b.add_argument("--out", default=str(REPO / "analysis" / "m3"))
    b.add_argument("--signals-dir", default=str(DEFAULT_LAKE / "gold" / "signals"))
    b.add_argument("--scope", required=True, help='data scope label, e.g. "subset: FAERS 2019Q1–2026Q2"')
    b.add_argument("--before", help="only reports received before this date (YYYY-MM-DD)")
    b.add_argument("--n-boot", type=int, default=1000)
    b.set_defaults(func=cmd_baselines)

    sf = sub.add_parser("safetyformer", help="M4: train SafetyFormer and evaluate on the reference sets")
    _add_m4_args(sf)
    sf.set_defaults(func=cmd_safetyformer)

    tt = sub.add_parser("time-travel", help="M4: yearly snapshots against FDA label changes")
    _add_m4_args(tt)
    tt.add_argument("--label-changes", required=True, help="CSV with drug_name, label_date, text")
    tt.add_argument("--first-year", type=int, required=True)
    tt.add_argument("--last-year", type=int, required=True)
    tt.set_defaults(func=cmd_time_travel)

    lk = sub.add_parser("leakage", help="M4: shuffled vs time-ordered split")
    _add_m4_args(lk)
    lk.add_argument("--test-start", required=True)
    lk.set_defaults(func=cmd_leakage)

    m5_out, m5_models = str(REPO / "analysis" / "m5"), str(REPO / "ml" / "artifacts")
    tr = sub.add_parser("triage", help="M5: seriousness model")
    tr.add_argument("--gold", default=str(DEFAULT_LAKE / "gold" / "faers"))
    tr.add_argument("--out", default=m5_out)
    tr.add_argument("--models-dir", default=m5_models)
    tr.add_argument("--scope", required=True)
    tr.add_argument("--test-from", required=True)
    tr.add_argument("--sample", type=int, help="reservoir-sample this many reports (CPU runs)")
    tr.set_defaults(func=cmd_triage)

    du = sub.add_parser("duplicates", help="M5: duplicate model")
    du.add_argument("--versions", default=str(DEFAULT_LAKE / "silver" / "faers" / "versions"))
    du.add_argument("--out", default=m5_out)
    du.add_argument("--models-dir", default=m5_models)
    du.add_argument("--scope", required=True)
    du.add_argument("--sample-cases", type=int)
    du.set_defaults(func=cmd_duplicates)

    co = sub.add_parser("coder", help="M5: MedDRA coder (GPU recommended)")
    co.add_argument("--narratives", default=str(DEFAULT_LAKE / "bronze" / "vaers" / "narratives.parquet"))
    co.add_argument("--out", default=m5_out)
    co.add_argument("--models-dir", default=m5_models)
    co.add_argument("--scope", required=True)
    co.add_argument("--checkpoint")
    co.add_argument("--test-year", type=int, required=True)
    co.add_argument("--epochs", type=int, default=3)
    co.add_argument("--batch-size", type=int, default=16)
    co.add_argument("--max-train", type=int)
    co.set_defaults(func=cmd_coder)

    cd = sub.add_parser("coder-drug-narratives", help="M5: domain-shift check on hand-labelled drug narratives")
    cd.add_argument("--csv", default=str(REPO / "ml" / "coder" / "drug_narratives_template.csv"))
    cd.add_argument("--out", default=m5_out)
    cd.add_argument("--models-dir", default=m5_models)
    cd.set_defaults(func=cmd_coder_drug_narratives)

    es = sub.add_parser("export-signals", help="M8: signal tables for signal-service (CSV + JSON)")
    es.add_argument("--gold", required=True)
    es.add_argument("--signals-dir", required=True, help="folder with baselines_primary_suspect.parquet")
    es.add_argument("--out", required=True)
    es.add_argument("--scope", required=True, help='data scope label, e.g. "FAERS 2019Q1-2026Q2"')
    es.add_argument("--safetyformer", help="safetyformer_pt_scores.parquet from the M4 run")
    es.add_argument("--baselines-json", help="baselines.json from the M3 run (evaluation summary)")
    es.set_defaults(func=cmd_export_signals)

    jb = sub.add_parser("java-bundle", help="M8: tiny synthetic model bundle for the Java case-service tests")
    jb.add_argument("--out", required=True)
    jb.set_defaults(func=cmd_java_bundle)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
