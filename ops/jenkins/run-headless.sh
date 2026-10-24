#!/usr/bin/env bash
# Proves the Jenkinsfile runs (M10): builds and starts the Jenkins controller from this folder (JCasC preloads the
# "pharmavigil" job), triggers one build of the current commit through the REST API, waits for it and writes the
# console log, preceded by the Jenkins and plugin versions, to $1 (default jenkins-run.log). Exit code 0 only when
# the build result is SUCCESS. The admin password is random for this run and never printed.
set -euo pipefail
LOG=$(realpath -m "${1:-jenkins-run.log}")
cd "$(dirname "$0")"
URL=http://127.0.0.1:8090

export JENKINS_ADMIN_PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$JENKINS_ADMIN_PASSWORD"; fi
export DOCKER_GID="$(stat -c %g /var/run/docker.sock)"
export PV_REPO_PATH="$(git rev-parse --show-toplevel)"
export PV_GIT_REF="$(git rev-parse HEAD)"
AUTH="admin:$JENKINS_ADMIN_PASSWORD"
JAR=$(mktemp)
trap 'rm -f "$JAR"; docker compose down -v >/dev/null 2>&1 || true' EXIT

docker compose up -d --build
echo "waiting for Jenkins and the pharmavigil job"
timeout 600 bash -c "until curl -fs -u '$AUTH' '$URL/job/pharmavigil/api/json' >/dev/null; do sleep 5; done"

{
  echo "# PharmaVigil Jenkins pipeline, headless run ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  echo "# commit: $PV_GIT_REF"
  echo "# jenkins: $(curl -fsI -u "$AUTH" "$URL/" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-jenkins" {print $2}')"
  echo "# plugins: $(curl -fs -u "$AUTH" "$URL/pluginManager/api/json?depth=1&tree=plugins\[shortName,version\]" \
      | python3 -c 'import sys,json; print(", ".join(sorted(p["shortName"]+" "+p["version"] for p in json.load(sys.stdin)["plugins"])))')"
  echo
} > "$LOG"

CRUMB=$(curl -fs -c "$JAR" -u "$AUTH" "$URL/crumbIssuer/api/json" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["crumbRequestField"]+":"+d["crumb"])')
curl -fs -b "$JAR" -u "$AUTH" -H "$CRUMB" -X POST "$URL/job/pharmavigil/buildWithParameters?DEPLOY_TO_KIND=true" >/dev/null
echo "build triggered; waiting (up to 120 minutes)"
timeout 600 bash -c "until curl -fs -u '$AUTH' '$URL/job/pharmavigil/1/api/json' >/dev/null; do sleep 5; done"
timeout 7200 bash -c "until curl -fs -u '$AUTH' '$URL/job/pharmavigil/1/api/json' | grep -q '\"building\":false'; do sleep 20; done"

curl -fs -u "$AUTH" "$URL/job/pharmavigil/1/consoleText" >> "$LOG"
RESULT=$(curl -fs -u "$AUTH" "$URL/job/pharmavigil/1/api/json" | python3 -c 'import sys,json; print(json.load(sys.stdin)["result"])')
echo "Jenkins build result: $RESULT"
[ "$RESULT" = "SUCCESS" ]
