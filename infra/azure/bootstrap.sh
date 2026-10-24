#!/usr/bin/env bash
# One-time Azure setup for PharmaVigil (docs/AZURE.md step 2). Run it in Azure Cloud Shell (Bash), signed in as the
# owner of the subscription. It creates, idempotently:
#   - the resource-provider registrations AKS, ACR, Key Vault and monitoring need;
#   - resource group rg-pharmavigil-tfstate with a storage account (Entra ID access only, no keys) for Terraform state;
#   - two user-assigned managed identities that GitHub Actions signs in as through OIDC (no stored credentials):
#       id-pharmavigil-plan   trusted for pull requests      Reader + Storage Blob Data Reader (read-only)
#       id-pharmavigil-apply  trusted for environment azure  Contributor + RBAC Administrator + Blob Data Contributor
# Then it prints the values to copy into GitHub. It creates and prints no secret.
set -euo pipefail
REPO=${1:-sumitparmar19/PharmaVigil}
ENVIRONMENT=azure
LOCATION=eastus
RG=rg-pharmavigil-tfstate
ISSUER=https://token.actions.githubusercontent.com

SUB=$(az account show --query id -o tsv)
TENANT=$(az account show --query tenantId -o tsv)
OWNER=$(az ad signed-in-user show --query id -o tsv)
SCOPE="/subscriptions/$SUB"
STORAGE="pvtfstate$(echo -n "$SUB" | md5sum | cut -c1-8)"

echo "== Resource providers (registration continues in the background)"
for ns in Microsoft.ContainerService Microsoft.ContainerRegistry Microsoft.KeyVault Microsoft.OperationalInsights \
          Microsoft.OperationsManagement Microsoft.Insights Microsoft.ManagedIdentity Microsoft.Consumption \
          Microsoft.Storage Microsoft.PolicyInsights; do
  az provider register --namespace "$ns" --only-show-errors >/dev/null
done

echo "== Terraform state: $RG / $STORAGE"
az group create --name "$RG" --location "$LOCATION" --tags project=pharmavigil managed_by=bootstrap -o none
if ! az storage account show --name "$STORAGE" --resource-group "$RG" -o none 2>/dev/null; then
  az storage account create --name "$STORAGE" --resource-group "$RG" --location "$LOCATION" --sku Standard_LRS \
    --kind StorageV2 --min-tls-version TLS1_2 --allow-blob-public-access false --allow-shared-key-access false \
    --https-only true -o none
fi
STORAGE_ID=$(az storage account show --name "$STORAGE" --resource-group "$RG" --query id -o tsv)
az role assignment create --assignee-object-id "$OWNER" --assignee-principal-type User \
  --role "Storage Blob Data Contributor" --scope "$STORAGE_ID" -o none 2>/dev/null || true
for i in $(seq 1 30); do  # the role assignment takes a minute to reach the data plane
  if az storage container create --name tfstate --account-name "$STORAGE" --auth-mode login -o none 2>/dev/null; then break; fi
  echo "   waiting for the storage role to take effect ($i/30)"; sleep 10
done

identity() {  # name subject roles...
  local name=$1 subject=$2; shift 2
  az identity create --name "$name" --resource-group "$RG" --location "$LOCATION" -o none
  local principal client
  principal=$(az identity show --name "$name" --resource-group "$RG" --query principalId -o tsv)
  client=$(az identity show --name "$name" --resource-group "$RG" --query clientId -o tsv)
  az identity federated-credential create --identity-name "$name" --resource-group "$RG" --name github \
    --issuer "$ISSUER" --subject "$subject" --audiences api://AzureADTokenExchange -o none
  for role in "$@"; do
    local scope=$SCOPE
    case "$role" in "Storage Blob Data"*) scope=$STORAGE_ID ;; esac
    az role assignment create --assignee-object-id "$principal" --assignee-principal-type ServicePrincipal \
      --role "$role" --scope "$scope" -o none 2>/dev/null || true
  done
  echo "$client"
}

echo "== GitHub OIDC identities"
PLAN_ID=$(identity id-pharmavigil-plan "repo:$REPO:pull_request" "Reader" "Storage Blob Data Reader" | tail -1)
APPLY_ID=$(identity id-pharmavigil-apply "repo:$REPO:environment:$ENVIRONMENT" \
  "Contributor" "Role Based Access Control Administrator" "Storage Blob Data Contributor" | tail -1)

cat <<OUT

Done. Copy these into GitHub (docs/AZURE.md step 3). None of them is a secret.
  Repository variables:
    AZURE_TENANT_ID          $TENANT
    AZURE_SUBSCRIPTION_ID    $SUB
    AZURE_PLAN_CLIENT_ID     $PLAN_ID
    AZURE_OWNER_OBJECT_ID    $OWNER
    TFSTATE_RESOURCE_GROUP   $RG
    TFSTATE_STORAGE_ACCOUNT  $STORAGE
  Environment "$ENVIRONMENT" variable:
    AZURE_APPLY_CLIENT_ID    $APPLY_ID
OUT
