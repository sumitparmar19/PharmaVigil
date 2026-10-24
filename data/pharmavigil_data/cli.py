"""`pv-data` command line: runs each data-platform step and writes its evidence JSON under
analysis/m2/ (engineering rule 10). Steps, in order:

  pv-data faers-bronze --first 2019Q1 --last 2026Q2   # download + convert, one quarter at a time
  pv-data faers-silver                                 # de-duplicate, derive fields
  pv-data rxnorm-map --top 40000                       # map the most frequent drug names via RxNav
  pv-data faers-gold                                   # report / report_drug / report_reaction
  pv-data refsets                                      # OMOP + EU-ADR reference sets
  pv-data quality                                      # data-quality report
  pv-data faers-versions                               # every case version (duplicate-model training)
  pv-data vaers --years 2019 2025                      # VAERS narratives with coded PTs
  pv-data p1-counts --scope "subset: FAERS 2019Q1–2026Q2"  # analysis/p1/data_counts.json (Phase 2)
  pv-data demo-slice --quarters 2026Q2 --reports 5000  # replay file for the live demo (Phase 2)
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from . import paths
from .http import SourceUnavailable
from .quarters import Quarter, quarter_range
from .tables import read_table, table_exists, write_table

ANALYSIS_DIR = Path(__file__).resolve().parents[2] / "analysis" / "m2"


def _write(name: str, payload: dict[str, object]) -> Path:
    ANALYSIS_DIR.mkdir(parents=True, exist_ok=True)
    out = ANALYSIS_DIR / name
    out.write_text(json.dumps(payload, indent=2, default=str))
    print(f"wrote {out}")
    return out


def cmd_faers_bronze(args: argparse.Namespace) -> int:
    from .faers.bronze import ingest_quarter

    done, failed = [], []
    for q in quarter_range(args.first, args.last):
        start = time.monotonic()
        try:
            m = ingest_quarter(q, keep_raw=args.keep_raw, force=args.force)
        except SourceUnavailable as exc:
            failed.append({"quarter": str(q), "error": str(exc)})
            print(f"{q}: unavailable ({exc})", file=sys.stderr)
            if args.stop_on_error:
                break
            continue
        demo = m["tables"]["demo"]["rows"]  # type: ignore[index]
        print(f"{q}: {demo} DEMO rows in {time.monotonic() - start:.0f}s")
        done.append(str(q))
    _write("bronze_run.json", {"requested": [args.first, args.last], "ingested": done, "failed": failed})
    return 0 if not failed else 2


def cmd_faers_bronze_zip(args: argparse.Namespace) -> int:
    from .faers.bronze import convert_zip

    m = convert_zip(Path(args.zip), Quarter.parse(args.quarter))
    print(json.dumps(m["tables"], indent=2))
    return 0


def cmd_faers_silver(args: argparse.Namespace) -> int:
    from .faers.silver import build_silver
    from .spark import get_spark

    result = build_silver(get_spark())
    _write("dedup_counts.json", result)
    return 0


def cmd_rxnorm_map(args: argparse.Namespace) -> int:
    from .faers.gold import name_frequencies
    from .rxnorm import RxNavClient
    from .spark import get_spark

    spark = get_spark()
    freq = name_frequencies(spark)
    total_mentions = freq.groupBy().sum("mentions").first()[0] or 0
    top = freq.limit(args.top).collect()
    client = RxNavClient(paths.lake_root() / "cache" / "rxnav.sqlite")
    mappings = client.resolve_many(r["example"] for r in top)
    by_method: dict[str, int] = {}
    for m in mappings:
        by_method[m.method] = by_method.get(m.method, 0) + 1
    rows = client.export_rows()
    if rows:
        write_table(spark.createDataFrame(rows), "silver", "rxnorm_map")
    covered = sum(r["mentions"] for r in top)
    _write("rxnorm_map_run.json", {
        "distinct_name_keys": freq.count(),
        "total_mentions": total_mentions,
        "keys_requested": len(top),
        "mentions_in_requested_keys": covered,
        "share_of_mentions_requested": covered / total_mentions if total_mentions else None,
        "results_by_method": by_method,
        "api_calls": client.api_calls,
        "cached_mapping_rows": len(rows),
    })
    return 0 if by_method.get("error", 0) == 0 else 2


def cmd_faers_gold(args: argparse.Namespace) -> int:
    from pyspark.sql.types import StringType, StructField, StructType

    from .faers.gold import build_gold
    from .spark import get_spark

    spark = get_spark()
    if table_exists(spark, "silver", "rxnorm_map"):
        rxmap = read_table(spark, "silver", "rxnorm_map")
    else:  # RxNav unreachable so far: gold tables are still built, with every drug unmapped
        columns = ("name_key", "method", "rxcui", "ingredient")
        rxmap = spark.createDataFrame([], StructType([StructField(c, StringType()) for c in columns]))
    _write("normalization_coverage.json", build_gold(spark, rxmap))
    return 0


def cmd_refsets(args: argparse.Namespace) -> int:
    from .refsets import fetch, summarize

    out: dict[str, object] = {}
    for name in ("omop", "euadr"):
        df, provenance = fetch(name)
        target = paths.bronze("refsets", f"{name}.parquet")
        target.parent.mkdir(parents=True, exist_ok=True)
        df.to_parquet(target, index=False)
        out[name] = {**summarize(df), **provenance}
    _write("refsets.json", out)
    return 0


def cmd_quality(args: argparse.Namespace) -> int:
    from .quality import gold_quality, manifest_summary
    from .spark import get_spark

    _write("quality.json", {"bronze_quarters": manifest_summary(), "gold": gold_quality(get_spark())})
    return 0


def cmd_faers_versions(args: argparse.Namespace) -> int:
    from .faers.versions import build_versions
    from .spark import get_spark

    n = build_versions(get_spark()).count()
    _write("versions_run.json", {"report_versions": n})
    return 0


def cmd_vaers(args: argparse.Namespace) -> int:
    import pandas as pd

    from .http import download
    from .vaers import VAERS_DOWNLOAD_URL, read_zip

    frames, failed = [], []
    zips = [Path(z) for z in args.zip] if args.zip else []
    for year in range(args.years[0], args.years[1] + 1) if args.years else []:
        target = paths.raw_root() / "vaers" / f"{year}VAERSData.zip"
        try:
            if not target.exists():
                download(VAERS_DOWNLOAD_URL.format(year=year), target)
            zips.append(target)
        except SourceUnavailable as exc:
            failed.append({"year": year, "error": str(exc)})
    for z in zips:
        frames.append(read_zip(z))
    if frames:
        out = pd.concat(frames, ignore_index=True)
        target = paths.bronze("vaers", "narratives.parquet")
        target.parent.mkdir(parents=True, exist_ok=True)
        out.to_parquet(target, index=False)
        summary = {"narratives": int(len(out)), "years": sorted(out["year"].dropna().unique().tolist()),
                   "distinct_pts": int(out["pts"].explode().nunique())}
    else:
        summary = {"narratives": 0}
    _write("vaers_run.json", {**summary, "failed": failed})
    return 0 if not failed else 2


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="pv-data", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    b = sub.add_parser("faers-bronze", help="download and convert FAERS quarters")
    b.add_argument("--first", default="2019Q1")
    b.add_argument("--last", required=True)
    b.add_argument("--keep-raw", action="store_true")
    b.add_argument("--force", action="store_true")
    b.add_argument("--stop-on-error", action="store_true")
    b.set_defaults(func=cmd_faers_bronze)

    z = sub.add_parser("faers-bronze-zip", help="convert an already-downloaded quarterly zip")
    z.add_argument("zip")
    z.add_argument("--quarter", required=True)
    z.set_defaults(func=cmd_faers_bronze_zip)

    sub.add_parser("faers-silver").set_defaults(func=cmd_faers_silver)
    r = sub.add_parser("rxnorm-map")
    r.add_argument("--top", type=int, default=40_000)
    r.set_defaults(func=cmd_rxnorm_map)
    sub.add_parser("faers-gold").set_defaults(func=cmd_faers_gold)
    sub.add_parser("refsets").set_defaults(func=cmd_refsets)
    sub.add_parser("quality").set_defaults(func=cmd_quality)
    sub.add_parser("faers-versions").set_defaults(func=cmd_faers_versions)
    v = sub.add_parser("vaers")
    v.add_argument("--years", type=int, nargs=2, metavar=("FIRST", "LAST"))
    v.add_argument("--zip", nargs="*", help="already-downloaded <year>VAERSData.zip files")
    v.set_defaults(func=cmd_vaers)

    pc = sub.add_parser("p1-counts", help="Phase 2: data counts at every layer, with source checksums")
    pc.add_argument("--scope", required=True)
    pc.add_argument("--out", default=str(ANALYSIS_DIR.parent / "p1" / "data_counts.json"))
    pc.set_defaults(func=cmd_p1_counts)

    ds = sub.add_parser("demo-slice", help="Phase 2: sample of de-identified FAERS reports for the demo replay")
    ds.add_argument("--quarters", nargs="+", required=True)
    ds.add_argument("--reports", type=int, default=5000)
    ds.add_argument("--out", default=str(paths.lake_root() / "demo"))
    ds.set_defaults(func=cmd_demo_slice)
    return p


def cmd_p1_counts(args: argparse.Namespace) -> int:
    from .demo_slice import data_counts
    from .spark import get_spark

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    cov = ANALYSIS_DIR / "normalization_coverage.json"
    coverage = json.loads(cov.read_text())["normalization_coverage"] if cov.exists() else None
    out.write_text(json.dumps(data_counts(get_spark(), args.scope, coverage), indent=2, default=str))
    print(f"wrote {out}")
    return 0


def cmd_demo_slice(args: argparse.Namespace) -> int:
    from .demo_slice import build_demo_slice
    from .spark import get_spark

    meta = build_demo_slice(get_spark(), Path(args.out), args.quarters, args.reports)
    print(json.dumps(meta, indent=1))
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
