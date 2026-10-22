"""Runs every query of the Grafana dashboards and every alert rule against a Prometheus fed by the running
stack, and records whether it parses and how many series it returns (docs/OBSERVABILITY.md).

Usage: python3 ops/grafana/check_queries.py --prometheus http://127.0.0.1:9090 --out analysis/m10/dashboard_queries.json
Exit code 1 if any query fails to parse or evaluate.
"""

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).parent
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def run(prom, expr):
    expr = expr.replace("$__range", "1h")
    url = f"{prom}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with OPENER.open(url, timeout=30) as r:
            body = json.load(r)
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": json.load(e).get("error", str(e))}
    result = body["data"]["result"]
    values = [float(x["value"][1]) for x in result]
    return {"ok": True, "series": len(result), "has_data": any(v == v for v in values)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prometheus", default="http://127.0.0.1:9090")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    rows = []
    for f in sorted((ROOT / "dashboards").glob("*.json")):
        d = json.loads(f.read_text())
        for p in d["panels"]:
            for t in p.get("targets", []):
                rows.append({"source": f"{d['uid']} / {p['title']}", "expr": t["expr"], **run(args.prometheus, t["expr"])})
    rules = (ROOT / "alerts" / "pharmavigil.rules.yml").read_text()
    for name, expr in re.findall(r"- alert: (\S+)\n\s+expr: (>\n(?:\s{10}.*\n)+|.*\n)", rules):
        expr = " ".join(line.strip() for line in expr.replace(">\n", "").splitlines())
        rows.append({"source": f"alert {name}", "expr": expr, **run(args.prometheus, expr)})
    failed = [r for r in rows if not r["ok"]]
    summary = {"queries": len(rows), "failed": len(failed), "with_data": sum(1 for r in rows if r.get("has_data")),
               "scope": "local docker compose stack after the k6 run (synthetic data, fixture models)"}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps({"summary": summary, "results": rows}, indent=2) + "\n")
    print(json.dumps(summary))
    for r in rows:
        alert = r["source"].startswith("alert ")
        state = ("firing " if r.get("has_data") else "quiet  ") if alert else ("data   " if r.get("has_data") else "empty  ")
        print(("OK  " if r["ok"] else "ERR ") + state + r["source"])
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
