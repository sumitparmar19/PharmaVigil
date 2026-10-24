#!/usr/bin/env bash
# Deploys PharmaVigil to a fresh kind cluster (docs/KUBERNETES.md, ADR-010): builds nothing; expects the six
# images pharmavigil/<service>:$IMAGE_TAG to exist locally. Creates the cluster, loads the images, writes random
# throwaway secrets to $PV_SECRETS_FILE (never printed) and runs ops/k8s/install.sh (Strimzi, Kafka, Secret, chart).
# Used by .github/workflows/kind-e2e.yml and ops/jenkins/Jenkinsfile.
set -euo pipefail
cd "$(dirname "$0")/../.."

CLUSTER=${KIND_CLUSTER:-pv}
NS=${PV_NAMESPACE:-pharmavigil}
IMAGE_TAG=${IMAGE_TAG:-0.1.0}
STRIMZI_VERSION=${STRIMZI_VERSION:-0.51.0}
PV_SECRETS_FILE=${PV_SECRETS_FILE:-$PWD/.kind-secrets.env}
SERVICES="pharmacy-service intake-gateway case-service signal-service submission-gateway frontend"

kind create cluster --name "$CLUSTER" --config ops/kind/cluster.yaml --wait 120s
for s in $SERVICES; do
  kind load docker-image --name "$CLUSTER" "pharmavigil/$s:$IMAGE_TAG"
done
echo "--- Secret file (random values, this cluster only)"
umask 077
: > "$PV_SECRETS_FILE"
for name in ORACLE_PASSWORD PV_DB_PASSWORD JWT_SECRET PV_DEMO_PASSWORD ARTEMIS_PASSWORD; do
  value="Pv$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$value"; fi
  echo "$name=$value" >> "$PV_SECRETS_FILE"
done
echo "ARTEMIS_USER=pvbroker" >> "$PV_SECRETS_FILE"

PV_NAMESPACE="$NS" IMAGE_TAG="$IMAGE_TAG" STRIMZI_VERSION="$STRIMZI_VERSION" \
  ops/k8s/install.sh "$PV_SECRETS_FILE" -f ops/kind/values-kind.yaml
