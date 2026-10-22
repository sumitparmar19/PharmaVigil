"""Collects the numbers of one k6 load-test run from Prometheus and the k6 summary (docs/PERFORMANCE.md).

Usage: python3 ops/k6/collect_results.py --prometheus http://127.0.0.1:9090 --start <unix> --end <unix>
           --k6-summary summary.json --out analysis/m10/load_results.json
Every number is computed by a PromQL query over the run window or read from the k6 summary; the queries are
written into the output next to their results so the run can be checked.
"""

import argparse
import json
import os
import platform
import urllib.parse
import urllib.request

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # Prometheus is local; never via a proxy


def query(prom, expr, at):
    url = f"{prom}/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": at})
    with OPENER.open(url, timeout=30) as r:
        body = json.load(r)
    if body["status"] != "success":
        raise RuntimeError(f"{expr}: {body}")
    result = body["data"]["result"]
    if not result:
        return None
    value = float(result[0]["value"][1])
    return None if value != value else value  # NaN → None


def hardware():
    mem_kb = None
    try:
        with open("/proc/meminfo") as f:
            mem_kb = int(next(line for line in f if line.startswith("MemTotal")).split()[1])
    except (OSError, StopIteration):
        pass
    cpu = None
    try:
        with open("/proc/cpuinfo") as f:
            cpu = next((line.split(":", 1)[1].strip() for line in f if line.startswith("model name")), None)
    except OSError:
        pass
    return {"cpu_model": cpu, "logical_cpus": os.cpu_count(),
            "memory_gib": round(mem_kb / 1024 / 1024, 1) if mem_kb else None,
            "os": f"{platform.system()} {platform.release()}"}


def k6_numbers(summary_path):
    """Intake requests of the "reports" scenario only (the reviewer scenario is not load)."""
    with open(summary_path) as f:
        m = json.load(f)["metrics"]
    failed = m["http_req_failed{scenario:reports}"]  # a Rate: passes = failed requests, fails = successful ones
    return {
        "intake_requests": failed["passes"] + failed["fails"],
        "intake_requests_failed": failed["passes"],
        "intake_http_p95_ms": m["http_req_duration{scenario:reports}"]["p(95)"],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prometheus", default="http://127.0.0.1:9090")
    ap.add_argument("--start", type=int, required=True)
    ap.add_argument("--end", type=int, required=True)
    ap.add_argument("--k6-summary", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--strip-summary", action="store_true",
                    help="remove setup_data (the k6 sign-in tokens) from the k6 summary file in place")
    ap.add_argument("--scope", default="local docker compose stack, synthetic load-test reports, fixture models")
    args = ap.parse_args()
    if args.strip_summary:
        with open(args.k6_summary) as f:
            summary = json.load(f)
        summary.pop("setup_data", None)
        with open(args.k6_summary, "w") as f:
            json.dump(summary, f, indent=2)
            f.write("\n")
    w = f"{args.end - args.start}s"
    queries = {
        "reports_accepted_total": f'sum(increase(pv_reports_received_total{{kind!="duplicate"}}[{w}]))',
        "reports_per_min_peak_1m": 'max_over_time((sum(rate(pv_reports_received_total{kind!="duplicate"}[1m])) '
                                   f'* 60)[{w}:15s])',
        "cases_coded_total": f"sum(increase(pv_report_intake_to_coded_seconds_count[{w}]))",
        "coded_per_min_peak_1m": f"max_over_time((sum(rate(pv_report_intake_to_coded_seconds_count[1m])) * 60)[{w}:15s])",
        "intake_to_coded_p50_s": "histogram_quantile(0.50, sum by (le) "
                                 f"(increase(pv_report_intake_to_coded_seconds_bucket[{w}])))",
        "intake_to_coded_p95_s": "histogram_quantile(0.95, sum by (le) "
                                 f"(increase(pv_report_intake_to_coded_seconds_bucket[{w}])))",
        "intake_to_coded_max_s": f"max_over_time(pv_report_intake_to_coded_seconds_max[{w}])",
        "max_consumer_lag_records": 'max_over_time(max(kafka_consumer_fetch_manager_records_lag{topic=~".+[.].+"})'
                                    f"[{w}:15s])",
        "max_outbox_backlog_rows": f"max_over_time(sum(pv_outbox_backlog)[{w}:15s])",
        "coding_stage_p95_s": "histogram_quantile(0.95, sum by (le) "
                              f'(increase(pv_pipeline_stage_seconds_bucket{{stage="coding"}}[{w}])))',
        "pipeline_stage_failures": f'sum(increase(pv_pipeline_stage_seconds_count{{outcome="error"}}[{w}]))',
    }
    # Evaluate a little after the end so the last scrape is included.
    results = {k: query(args.prometheus, q, args.end + 30) for k, q in queries.items()}
    out = {
        "scope": args.scope,
        "window": {"start_unix": args.start, "end_unix": args.end, "seconds": args.end - args.start},
        "hardware": hardware(),
        "k6": k6_numbers(args.k6_summary),
        "prometheus": results,
        "queries": queries,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(out, f, indent=2)
        f.write("\n")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
