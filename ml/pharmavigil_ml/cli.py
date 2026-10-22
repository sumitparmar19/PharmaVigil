"""`pv-ml` command line for the model milestones. Every command writes its evidence under analysis/.

  pv-ml baselines --scope "subset: FAERS 2019Q1–2026Q2"   # M3: PRR/ROR/IC/EBGM + reference-set AUCs
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
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
