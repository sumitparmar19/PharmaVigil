#!/usr/bin/env bash
# Deploys PharmaVigil to a fresh kind cluster (docs/KUBERNETES.md, ADR-010): builds nothing; expects the six
# images pharmavigil/<service>:$IMAGE_TAG to exist locally. Creates the cluster, loads the images, installs the
# Strimzi operator, Kafka and topics, a Secret with random throwaway values (written to $PV_SECRETS_FILE, never
# printed), and the umbrella chart. Used by .github/workflows/kind-e2e.yml and ops/jenkins/Jenkinsfile.
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
kubectl create namespace "$NS"

echo "--- Strimzi $STRIMZI_VERSION and Kafka"
curl -fsSL "https://github.com/strimzi/strimzi-kafka-operator/releases/download/${STRIMZI_VERSION}/strimzi-cluster-operator-${STRIMZI_VERSION}.yaml" \
  | sed "s/namespace: .*/namespace: ${NS}/" | kubectl create -n "$NS" -f -
kubectl -n "$NS" rollout status deployment/strimzi-cluster-operator --timeout=300s
kubectl -n "$NS" apply -f ops/k8s/strimzi/kafka.yaml
kubectl -n "$NS" wait kafka/pv-kafka --for=condition=Ready --timeout=600s
kubectl -n "$NS" apply -f ops/k8s/strimzi/topics.yaml
kubectl -n "$NS" wait kafkatopic --all --for=condition=Ready --timeout=300s

echo "--- Secret (random values, this cluster only)"
umask 077
: > "$PV_SECRETS_FILE"
for name in ORACLE_PASSWORD PV_DB_PASSWORD JWT_SECRET PV_DEMO_PASSWORD ARTEMIS_PASSWORD; do
  value="Pv$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$value"; fi
  echo "$name=$value" >> "$PV_SECRETS_FILE"
done
echo "ARTEMIS_USER=pvbroker" >> "$PV_SECRETS_FILE"
kubectl -n "$NS" create secret generic pharmavigil-secrets --from-env-file="$PV_SECRETS_FILE"

echo "--- Helm chart"
for c in pharmacy-service intake-gateway case-service signal-service submission-gateway frontend pharmavigil; do
  helm dependency build "ops/helm/$c" >/dev/null
done
helm install pv ops/helm/pharmavigil -n "$NS" -f ops/kind/values-kind.yaml \
  --set global.imageTag="$IMAGE_TAG" --wait --timeout 20m
kubectl -n "$NS" get pods -o wide
