#!/usr/bin/env bash
# Nightly reset of the live demo (cron on the server, set up by cloud-init): stops the stack, deletes the database,
# Kafka and metrics volumes, and starts it again, so every day begins from the seed state (synthetic patients,
# demo accounts, the loaded signal export, an empty case list). Takes a few minutes while Oracle recreates its
# schemas. The TLS certificate volume is kept.
set -euo pipefail
cd "$(dirname "$0")/../.."
COMPOSE=(docker compose -f docker-compose.demo.yml --env-file ops/demo/.env)
echo "$(date -u +%FT%TZ) demo reset: start"
"${COMPOSE[@]}" down
docker volume rm -f pharmavigil-demo_oracle-data pharmavigil-demo_kafka-data pharmavigil-demo_prometheus-data >/dev/null
"${COMPOSE[@]}" up -d --wait --wait-timeout 1200
echo "$(date -u +%FT%TZ) demo reset: done"
