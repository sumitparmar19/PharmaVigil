#!/usr/bin/env bash
# One command for the local stack: builds the service jars, fetches the official E2B(R3) schemas (best effort),
# creates .env with random local-only values if it does not exist yet (file mode 600; values are never printed),
# and starts docker compose. Pass --observability to add Prometheus, Grafana and Jaeger with tracing on.
# Needs Docker with compose, Java 21 and Maven. Not for clinical or regulatory use; synthetic patients only.
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE_ARGS=()
if [ "${1:-}" = "--observability" ]; then
  PROFILE_ARGS=(--profile observability)
  export PV_TRACING=true
fi

echo "== Building the service jars"
(cd services && mvn -B -q -DskipTests package)

if [ ! -f .e2b-xsd/multicacheschemas/MCCI_IN200100UV01.xsd ]; then
  echo "== Fetching the ICH E2B(R3) schemas (without them nothing can be submitted)"
  scripts/fetch-e2b-xsd.sh .e2b-xsd || echo "   schemas not fetched; intake works, submission stays disabled"
fi

if [ ! -f .env ]; then
  echo "== Creating .env with random local values"
  umask 077
  rand() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
  {
    echo "# Local-only values created by scripts/dev-up.sh; never commit this file."
    echo "ORACLE_PASSWORD=Pv$(rand)"
    echo "PV_DB_PASSWORD=Pv$(rand)"
    echo "JWT_SECRET=$(rand)$(rand)"
    echo "PV_DEMO_PASSWORD=Pv$(rand)"
    echo "ARTEMIS_USER=pvbroker"
    echo "ARTEMIS_PASSWORD=Pv$(rand)"
  } > .env
fi

echo "== Starting the stack (first start takes a few minutes: Oracle creates its database)"
docker compose "${PROFILE_ARGS[@]}" up -d --build --wait --wait-timeout 900

cat <<'OUT'

PharmaVigil is up: http://localhost:8080
Sign in as pharmacist, reviewer or admin; the password is PV_DEMO_PASSWORD in .env.
With --observability: Grafana http://127.0.0.1:3000, Prometheus http://127.0.0.1:9090, Jaeger http://127.0.0.1:16686
Stop with: docker compose down        (add -v to delete the database)
OUT
