# Values the owner copies into GitHub (environment variables, docs/AZURE.md) and that the deploy workflow reads.

output "resource_group" {
  value = azurerm_resource_group.main.name
}

output "aks_name" {
  value = azurerm_kubernetes_cluster.main.name
}

output "acr_login_server" {
  value = azurerm_container_registry.main.login_server
}

output "key_vault_name" {
  value = azurerm_key_vault.main.name
}

output "key_vault_secret_names" {
  description = "Secrets the owner creates in the vault (values never pass through Terraform)."
  value       = local.app_secret_names
}

output "github_deploy_client_id" {
  description = "Client id for the AZURE_DEPLOY_CLIENT_ID variable of the GitHub environment."
  value       = azurerm_user_assigned_identity.github_deploy.client_id
}
