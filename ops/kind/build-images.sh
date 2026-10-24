#!/usr/bin/env bash
# Builds the six PharmaVigil images (pharmavigil/<service>:$IMAGE_TAG) from the jars in services/*/target
# (run `mvn package` first) and the frontend sources.
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE_TAG=${IMAGE_TAG:-0.1.0}
for s in pharmacy-service intake-gateway case-service signal-service submission-gateway; do
  docker build -q -f services/Dockerfile --build-arg SERVICE=$s -t "pharmavigil/$s:$IMAGE_TAG" services
done
docker build -q -t "pharmavigil/frontend:$IMAGE_TAG" frontend
