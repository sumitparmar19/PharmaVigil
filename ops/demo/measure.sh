#!/usr/bin/env bash
# Measures the demo stack's memory (P6): container memory at idle, then every 5 s while a replay of N real reports
# runs through the full pipeline (sign-in as demo-reviewer with the password the landing page shows, start a run,
# follow its counters until the regulator has answered every accepted report or the time limit passes). Writes the
# idle and peak usage per container, the totals, the run's final counters and the scope to the JSON file given.
#
#   ops/demo/measure.sh <base-url> <reports> <out.json> "<scope label>"
#   e.g. ops/demo/measure.sh https://localhost 200 analysis/p6/memory_amd64.json "local amd64, 4 vCPU, demo data v1"
set -euo pipefail
base="${1:?base url}"; n="${2:?reports}"; out="${3:?output json}"; scope="${4:?scope label}"
curl_() { curl -fsS -k -m 20 "$@"; }
stats() { docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' | sed 's#pharmavigil-demo-##; s#-1\t#\t#'; }
to_mib() { python3 -c '
import sys,re
v=sys.argv[1].split("/")[0].strip()
m=re.match(r"([0-9.]+)\s*([KMG]i?B|B)",v); n=float(m.group(1)); u=m.group(2)
print(round(n*{"B":1/2**20,"KiB":1/1024,"KB":1/1024,"MiB":1,"MB":1,"GiB":1024,"GB":1024}[u],1))' "$1"; }

samples="$(mktemp)"; trap 'rm -f "$samples"' EXIT
sleep 30
stats | while IFS=$'\t' read -r name mem; do echo "idle $name $(to_mib "$mem")"; done >> "$samples"

password="$(curl_ "$base/api/auth/demo" | python3 -c 'import sys,json; print(json.load(sys.stdin)["password"])')"
token="$(curl_ -H 'Content-Type: application/json' -d "{\"username\":\"demo-reviewer\",\"password\":\"$password\"}" \
  "$base/api/auth/login" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')"
run="$(curl_ -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "{\"size\":$n}" \
  "$base/api/intake/demo/runs" | python3 -c 'import sys,json; print(json.load(sys.stdin)["runId"])')"
start=$(date +%s)
stages="{}"
while [ $(( $(date +%s) - start )) -lt 1200 ]; do
  stats | while IFS=$'\t' read -r name mem; do echo "run $name $(to_mib "$mem")"; done >> "$samples"
  intake="$(curl_ -H "Authorization: Bearer $token" "$base/api/intake/demo/runs/$run" || echo '{}')"
  stages="$(curl_ -H "Authorization: Bearer $token" "$base/api/cases/demo/runs/$run" || echo '{}')"
  done_=$(python3 -c '
import json,sys
i=json.loads(sys.argv[1] or "{}"); s=json.loads(sys.argv[2] or "{}")
acc=i.get("accepted",0); fin=i.get("finishedAt")
ans=s.get("acknowledged",0)+s.get("rejected",0)
print(1 if fin and acc>0 and ans>=acc else 0)' "$intake" "$stages")
  [ "$done_" = 1 ] && break
  sleep 5
done
seconds=$(( $(date +%s) - start ))
python3 - "$samples" "$out" "$scope" "$n" "$seconds" "$intake" "$stages" <<'PY'
import json, sys, platform, os, collections
path, out, scope, n, seconds, intake, stages = sys.argv[1:8]
idle, peak = {}, collections.defaultdict(float)
for line in open(path):
    kind, name, mib = line.split()
    if kind == "idle":
        idle[name] = float(mib)
    else:
        peak[name] = max(peak[name], float(mib))
result = {
    "scope": scope,
    "machine": {"arch": platform.machine(), "cpus": os.cpu_count()},
    "reports_replayed": int(n), "seconds_until_all_answered": int(seconds),
    "intake": json.loads(intake or "{}"), "stages": json.loads(stages or "{}"),
    "idle_mib": dict(sorted(idle.items())), "peak_mib": dict(sorted(peak.items())),
    "idle_total_mib": round(sum(idle.values()), 1), "peak_total_mib": round(sum(peak.values()), 1),
    "note": "docker stats memory usage (cgroup, page cache excluded); peak_total sums each container's own peak",
}
os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
json.dump(result, open(out, "w"), indent=2)
print(json.dumps({k: result[k] for k in ("idle_total_mib", "peak_total_mib", "seconds_until_all_answered")}))
PY
