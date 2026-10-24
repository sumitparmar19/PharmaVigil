#!/usr/bin/env bash
# Deploys the application to the AKS cluster that infra/azure created (docs/AZURE.md): copies the six images that
# "Demo images" published to GHCR (the same multi-arch images the live demo runs) into ACR (push rights only), signs
# in to the cluster with Entra ID (kubelogin), copies the six application secrets from Key Vault into a private temp
# file (values masked in GitHub logs, never printed), and runs ops/k8s/install.sh.
#   env: RESOURCE_GROUP, AKS_NAME, ACR_LOGIN_SERVER, KEY_VAULT_NAME, IMAGE_TAG
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${RESOURCE_GROUP:?}" "${AKS_NAME:?}" "${ACR_LOGIN_SERVER:?}" "${KEY_VAULT_NAME:?}" "${IMAGE_TAG:?}"

# GHCR tags are the first 12 characters of the commit SHA; "demo" is the latest main.
IMAGE_TAG="${IMAGE_TAG:0:12}"
if ! docker buildx imagetools inspect "ghcr.io/sumitparmar19/pharmavigil-frontend:$IMAGE_TAG" >/dev/null 2>&1; then
  echo "no GHCR images for $IMAGE_TAG (not built from main yet); using the latest main images (tag demo)"
  IMAGE_TAG=demo
fi
echo "--- Images from GHCR to $ACR_LOGIN_SERVER (tag $IMAGE_TAG, every platform)"
az acr login --name "${ACR_LOGIN_SERVER%%.*}"
for s in pharmacy-service intake-gateway case-service signal-service submission-gateway frontend; do
  docker buildx imagetools create --tag "$ACR_LOGIN_SERVER/pharmavigil/$s:$IMAGE_TAG" \
    "ghcr.io/sumitparmar19/pharmavigil-$s:$IMAGE_TAG"
done

echo "--- Cluster credentials (Entra ID, no local accounts)"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$AKS_NAME" --overwrite-existing
kubelogin convert-kubeconfig -l azurecli

echo "--- Application secrets from Key Vault"
SECRETS_FILE=$(mktemp)
trap 'rm -f "$SECRETS_FILE"' EXIT
for pair in ORACLE_PASSWORD=oracle-password PV_DB_PASSWORD=pv-db-password JWT_SECRET=jwt-secret \
            PV_DEMO_PASSWORD=pv-demo-password ARTEMIS_USER=artemis-user ARTEMIS_PASSWORD=artemis-password; do
  key=${pair%%=*}
  value=$(az keyvault secret show --vault-name "$KEY_VAULT_NAME" --name "${pair#*=}" --query value -o tsv)
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::add-mask::$value"; fi
  printf '%s=%s\n' "$key" "$value" >> "$SECRETS_FILE"
done

IMAGE_REGISTRY="$ACR_LOGIN_SERVER" IMAGE_TAG="$IMAGE_TAG" ops/k8s/install.sh "$SECRETS_FILE" -f ops/azure/values-aks.yaml
