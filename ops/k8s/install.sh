#!/usr/bin/env bash
# Installs PharmaVigil into the current kubectl context (kind or AKS; ADR-010): namespace, Strimzi operator, Kafka
# and topics, the application Secret from an env file, and the umbrella Helm chart.
#   usage: ops/k8s/install.sh <secrets.env> [extra helm args...]
#   env:   PV_NAMESPACE (pharmavigil), STRIMZI_VERSION (0.51.0), IMAGE_REGISTRY (""), IMAGE_TAG (0.1.0)
# The secrets file holds KEY=value lines (ORACLE_PASSWORD, PV_DB_PASSWORD, JWT_SECRET, PV_DEMO_PASSWORD,
# ARTEMIS_USER, ARTEMIS_PASSWORD); it is read by kubectl only and never printed.
set -euo pipefail
cd "$(dirname "$0")/../.."
SECRETS_FILE=${1:?usage: install.sh <secrets.env> [helm args]}
shift
NS=${PV_NAMESPACE:-pharmavigil}
STRIMZI_VERSION=${STRIMZI_VERSION:-0.51.0}

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

echo "--- Strimzi $STRIMZI_VERSION and Kafka"
if ! kubectl -n "$NS" get deployment strimzi-cluster-operator >/dev/null 2>&1; then
  curl -fsSL "https://github.com/strimzi/strimzi-kafka-operator/releases/download/${STRIMZI_VERSION}/strimzi-cluster-operator-${STRIMZI_VERSION}.yaml" \
    | sed "s/namespace: .*/namespace: ${NS}/" | kubectl create -n "$NS" -f -
fi
kubectl -n "$NS" rollout status deployment/strimzi-cluster-operator --timeout=300s
kubectl -n "$NS" apply -f ops/k8s/strimzi/kafka.yaml
kubectl -n "$NS" wait kafka/pv-kafka --for=condition=Ready --timeout=600s
kubectl -n "$NS" apply -f ops/k8s/strimzi/topics.yaml
kubectl -n "$NS" wait kafkatopic --all --for=condition=Ready --timeout=300s

echo "--- Secret"
kubectl -n "$NS" create secret generic pharmavigil-secrets --from-env-file="$SECRETS_FILE" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "--- Helm chart"
for c in pharmacy-service intake-gateway case-service signal-service submission-gateway frontend pharmavigil; do
  helm dependency build "ops/helm/$c" >/dev/null
done
helm upgrade --install pv ops/helm/pharmavigil -n "$NS" \
  --set global.imageRegistry="${IMAGE_REGISTRY:-}" --set global.imageTag="${IMAGE_TAG:-0.1.0}" \
  --wait --timeout 20m "$@"
kubectl -n "$NS" get pods -o wide
