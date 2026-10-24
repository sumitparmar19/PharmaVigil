#!/usr/bin/env bash
# Prints cluster state after a failed kind run: pods, recent events and the last 200 log lines of every pod.
NS=${PV_NAMESPACE:-pharmavigil}
kubectl get pods -A -o wide || true
# Why containers restarted (OOMKilled, exit codes) and probe failures
kubectl -n "$NS" describe pods | grep -E "^Name:|Last State|Reason:|Exit Code|Restart Count|Liveness|Readiness|Warning" || true
kubectl -n "$NS" get events --sort-by=.lastTimestamp | tail -60 || true
for p in $(kubectl -n "$NS" get pods -o name 2>/dev/null); do
  echo "===== $p"; kubectl -n "$NS" logs "$p" --all-containers --tail=200 || true
done
