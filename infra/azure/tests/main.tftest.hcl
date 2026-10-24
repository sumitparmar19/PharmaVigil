# Plan-time tests of infra/azure with a mocked azurerm provider (no Azure account or credentials needed): names,
# default tags, the OIDC subject of the deploy identity, AKS security settings and the budget alerts.
mock_provider "azurerm" {
  override_during = plan

  mock_resource "azurerm_kubernetes_cluster" {
    defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus/providers/Microsoft.ContainerService/managedClusters/aks-pharmavigil-eastus" }
  }
  mock_resource "azurerm_resource_group" {
    defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus" }
  }
  mock_resource "azurerm_log_analytics_workspace" {
    defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus/providers/Microsoft.OperationalInsights/workspaces/log-pharmavigil-eastus" }
  }
  mock_resource "azurerm_container_registry" {
    defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus/providers/Microsoft.ContainerRegistry/registries/acrpharmavigil" }
  }
  mock_resource "azurerm_key_vault" {
    defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus/providers/Microsoft.KeyVault/vaults/kv-pharmavigil" }
  }
  mock_resource "azurerm_user_assigned_identity" {
    defaults = {
      id           = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-pharmavigil-eastus/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id-github-deploy"
      principal_id = "66666666-6666-6666-6666-666666666666"
      client_id    = "77777777-7777-7777-7777-777777777777"
    }
  }

  override_data {
    target = data.azurerm_subscription.current
    values = {
      id              = "/subscriptions/00000000-0000-0000-0000-000000000000"
      subscription_id = "00000000-0000-0000-0000-000000000000"
    }
  }
  override_data {
    target = data.azurerm_client_config.current
    values = {
      tenant_id = "11111111-1111-1111-1111-111111111111"
      object_id = "22222222-2222-2222-2222-222222222222"
    }
  }
}

variables {
  budget_alert_email = "owner@example.org"
  owner_object_id    = "33333333-3333-3333-3333-333333333333"
}

run "defaults" {
  command = plan

  assert {
    condition     = azurerm_resource_group.main.name == "rg-pharmavigil-eastus"
    error_message = "unexpected resource group name"
  }
  assert {
    condition     = can(regex("^acrpharmavigil[0-9a-f]{6}$", azurerm_container_registry.main.name))
    error_message = "ACR name must be alphanumeric with the subscription suffix"
  }
  assert {
    condition     = length(azurerm_key_vault.main.name) <= 24
    error_message = "Key Vault names are at most 24 characters"
  }
  assert {
    condition     = azurerm_resource_group.main.tags["project"] == "pharmavigil" && azurerm_resource_group.main.tags["managed_by"] == "terraform"
    error_message = "default tags missing"
  }
  assert {
    condition     = azurerm_federated_identity_credential.github_deploy.subject == "repo:sumitparmar19/PharmaVigil:environment:azure"
    error_message = "the deploy identity must only trust the protected GitHub environment"
  }
  assert {
    condition     = azurerm_kubernetes_cluster.main.sku_tier == "Free" && azurerm_kubernetes_cluster.main.local_account_disabled
    error_message = "AKS must use the free control plane and Entra ID sign-in only"
  }
  assert {
    condition     = azurerm_role_assignment.kubelet_acr_pull.principal_id == azurerm_user_assigned_identity.kubelet.principal_id
    error_message = "nodes must pull from ACR with the kubelet identity"
  }
  assert {
    condition     = azurerm_container_registry.main.admin_enabled == false
    error_message = "ACR admin user must stay off"
  }
  assert {
    condition     = azurerm_consumption_budget_resource_group.main.amount == 20 && length(azurerm_consumption_budget_resource_group.main.notification) == 3
    error_message = "budget must be $20 with three alerts"
  }
  assert {
    condition     = azurerm_role_assignment.owner_kv_secrets.principal_id == var.owner_object_id
    error_message = "the owner, not the Terraform identity, types the secrets"
  }
}

run "rejects_a_bad_email" {
  command = plan
  variables {
    budget_alert_email = "not-an-email"
  }
  expect_failures = [var.budget_alert_email]
}
