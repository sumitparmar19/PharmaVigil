# The PharmaVigil Azure environment (M11, ADR-010): one resource group with AKS (free control plane, one node
# pool), ACR for the images, Key Vault for the application secrets (values typed in by the owner, never by
# Terraform), Log Analytics for cluster logs, a monthly budget alert, and a user-assigned identity that the
# GitHub deploy workflow signs in as through OIDC (no stored credentials). Validated in CI; applied only by the
# owner (docs/AZURE.md).

data "azurerm_client_config" "current" {}

data "azurerm_subscription" "current" {}

locals {
  # Short, stable suffix for names that must be globally unique (ACR, Key Vault).
  suffix = substr(md5(data.azurerm_subscription.current.id), 0, 6)
  name   = "${var.prefix}-${var.location}"
  tags = merge({
    project     = "pharmavigil"
    environment = "demo"
    managed_by  = "terraform"
    repository  = var.github_repository
    note        = "Not for clinical or regulatory use"
  }, var.tags)

  # Secrets the deploy workflow reads from Key Vault into the Kubernetes Secret (docs/AZURE.md, step 6).
  app_secret_names = ["oracle-password", "pv-db-password", "jwt-secret", "pv-demo-password", "artemis-user",
  "artemis-password"]
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.name}"
  location = var.location
  tags     = local.tags
}

resource "azurerm_log_analytics_workspace" "main" {
  name                = "log-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  daily_quota_gb      = 1 # caps ingestion cost on a student budget
  tags                = local.tags
}

resource "azurerm_container_registry" "main" {
  #checkov:skip=CKV_AZURE_139:Premium is needed for private endpoints and network rules; it costs more than the whole $20 budget.
  #checkov:skip=CKV_AZURE_165:Geo-replication needs the Premium SKU (see CKV_AZURE_139).
  #checkov:skip=CKV_AZURE_166:Image quarantine needs the Premium SKU (see CKV_AZURE_139).
  #checkov:skip=CKV_AZURE_167:Untagged-manifest retention needs the Premium SKU (see CKV_AZURE_139).
  #checkov:skip=CKV_AZURE_163:Defender for Containers scanning is a paid plan; images are scanned in CI instead (CodeQL, dependency review).
  #checkov:skip=CKV_AZURE_164:Content trust (Docker Content Trust) needs the Premium SKU.
  #checkov:skip=CKV_AZURE_233:Zone redundancy needs the Premium SKU.
  #checkov:skip=CKV_AZURE_237:Dedicated data endpoints need the Premium SKU.
  name                          = "acr${var.prefix}${local.suffix}"
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  sku                           = "Basic"
  admin_enabled                 = false
  anonymous_pull_enabled        = false
  public_network_access_enabled = true
  tags                          = local.tags
}

# Cluster identities: the control plane runs as "aks" and the nodes (kubelet) as "kubelet". Creating them first lets
# AcrPull be granted before any node pulls an image, and keeps the same identity if the cluster is rebuilt.
resource "azurerm_user_assigned_identity" "aks" {
  name                = "id-${local.name}-aks"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "kubelet" {
  name                = "id-${local.name}-kubelet"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# The control plane must be allowed to assign the kubelet identity to the nodes.
resource "azurerm_role_assignment" "aks_kubelet_operator" {
  scope                            = azurerm_user_assigned_identity.kubelet.id
  role_definition_name             = "Managed Identity Operator"
  principal_id                     = azurerm_user_assigned_identity.aks.principal_id
  skip_service_principal_aad_check = true
}

# Nodes pull images from ACR with the kubelet identity (no registry passwords).
resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                            = azurerm_container_registry.main.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_user_assigned_identity.kubelet.principal_id
  skip_service_principal_aad_check = true
}

resource "azurerm_kubernetes_cluster" "main" {
  #checkov:skip=CKV_AZURE_6:GitHub-hosted runners have no fixed IP ranges; the API server accepts Entra ID (Azure RBAC) sign-ins only (local accounts disabled).
  #checkov:skip=CKV_AZURE_115:A private cluster needs a VNet, private DNS and a self-hosted runner; out of scope for a student budget.
  #checkov:skip=CKV_AZURE_117:A customer-managed disk encryption set needs a Key Vault key and Premium-tier spending; platform-managed encryption is used.
  #checkov:skip=CKV_AZURE_170:The paid SLA tier (Standard) costs about $73 a month; the Free control plane is enough for a demo.
  #checkov:skip=CKV_AZURE_226:Ephemeral OS disks need a VM size with a large enough cache; B-series nodes keep managed OS disks.
  #checkov:skip=CKV_AZURE_227:Host encryption needs a subscription feature registration (EncryptionAtHost) that Azure for Students may not allow.
  #checkov:skip=CKV_AZURE_232:One node pool only (cost); system and user pods share it.
  name                              = "aks-${local.name}"
  location                          = azurerm_resource_group.main.location
  resource_group_name               = azurerm_resource_group.main.name
  dns_prefix                        = "pv${local.suffix}"
  sku_tier                          = "Free"
  automatic_upgrade_channel         = "patch"
  node_os_upgrade_channel           = "NodeImage"
  local_account_disabled            = true
  role_based_access_control_enabled = true
  oidc_issuer_enabled               = true
  workload_identity_enabled         = true
  azure_policy_enabled              = true

  default_node_pool {
    name                        = "system"
    vm_size                     = var.aks_vm_size
    node_count                  = var.aks_node_count
    max_pods                    = 50
    os_disk_size_gb             = 64
    temporary_name_for_rotation = "systemtmp"
    tags                        = local.tags

    upgrade_settings {
      max_surge = "1"
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks.id]
  }

  kubelet_identity {
    client_id                 = azurerm_user_assigned_identity.kubelet.client_id
    object_id                 = azurerm_user_assigned_identity.kubelet.principal_id
    user_assigned_identity_id = azurerm_user_assigned_identity.kubelet.id
  }

  # Fixed node pool (no node auto-provisioning), so cost stays predictable.
  node_provisioning_profile {
    mode = "Manual"
  }

  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_policy      = "azure"
    load_balancer_sku   = "standard"
  }

  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.main.id
    msi_auth_for_monitoring_enabled = true
  }

  key_vault_secrets_provider {
    secret_rotation_enabled = true
  }

  tags = local.tags

  depends_on = [azurerm_role_assignment.aks_kubelet_operator, azurerm_role_assignment.kubelet_acr_pull]
}

resource "azurerm_key_vault" "main" {
  #checkov:skip=CKV_AZURE_109:GitHub-hosted runners have no fixed IPs, so the firewall cannot list them; access is Azure RBAC only.
  #checkov:skip=CKV_AZURE_189:Public network access is needed for the same reason as CKV_AZURE_109.
  #checkov:skip=CKV2_AZURE_32:A private endpoint needs a VNet and a self-hosted runner; out of scope for a student budget.
  #checkov:skip=CKV_AZURE_110:Purge protection would block re-creating this demo vault (same name) after a destroy for the retention period.
  #checkov:skip=CKV_AZURE_42:Same as CKV_AZURE_110; soft delete itself is always on.
  name                       = "kv-${var.prefix}-${local.suffix}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false
  soft_delete_retention_days = 7

  network_acls {
    bypass         = "AzureServices"
    default_action = "Allow"
  }

  tags = local.tags
}

# The owner types the application secrets into the vault (docs/AZURE.md step 5), so the owner's own Entra ID user
# gets the data-plane role; Terraform itself never reads or writes secret values.
resource "azurerm_role_assignment" "owner_kv_secrets" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = var.owner_object_id
}

# The owner checks the running application from Cloud Shell (docs/AZURE.md step 7) with kubectl.
resource "azurerm_role_assignment" "owner_aks_user" {
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service Cluster User Role"
  principal_id         = var.owner_object_id
}

resource "azurerm_role_assignment" "owner_aks_admin" {
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = var.owner_object_id
}

# --- Identity of the GitHub deploy workflow (OIDC, environment-scoped)

resource "azurerm_user_assigned_identity" "github_deploy" {
  name                = "id-${local.name}-github-deploy"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "github_deploy" {
  name                      = "github-${var.github_environment}"
  user_assigned_identity_id = azurerm_user_assigned_identity.github_deploy.id
  issuer                    = "https://token.actions.githubusercontent.com"
  audience                  = ["api://AzureADTokenExchange"]
  # Only jobs that run in the protected GitHub environment can sign in as this identity.
  subject = "repo:${var.github_repository}:environment:${var.github_environment}"
}

resource "azurerm_role_assignment" "github_acr_push" {
  scope                            = azurerm_container_registry.main.id
  role_definition_name             = "AcrPush"
  principal_id                     = azurerm_user_assigned_identity.github_deploy.principal_id
  skip_service_principal_aad_check = true
}

resource "azurerm_role_assignment" "github_aks_user" {
  scope                            = azurerm_kubernetes_cluster.main.id
  role_definition_name             = "Azure Kubernetes Service Cluster User Role"
  principal_id                     = azurerm_user_assigned_identity.github_deploy.principal_id
  skip_service_principal_aad_check = true
}

resource "azurerm_role_assignment" "github_aks_admin" {
  # Helm installs CRDs (Strimzi) and namespaces, which needs the cluster-wide admin role.
  scope                            = azurerm_kubernetes_cluster.main.id
  role_definition_name             = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id                     = azurerm_user_assigned_identity.github_deploy.principal_id
  skip_service_principal_aad_check = true
}

resource "azurerm_role_assignment" "github_kv_read" {
  scope                            = azurerm_key_vault.main.id
  role_definition_name             = "Key Vault Secrets User"
  principal_id                     = azurerm_user_assigned_identity.github_deploy.principal_id
  skip_service_principal_aad_check = true
}

# --- Cost guard

resource "azurerm_consumption_budget_resource_group" "main" {
  name              = "budget-${local.name}"
  resource_group_id = azurerm_resource_group.main.id
  amount            = var.budget_amount
  time_grain        = "Monthly"

  time_period {
    # Budgets start on the first day of a month; the start is fixed at creation (ignore_changes below).
    start_date = formatdate("YYYY-MM-01'T'00:00:00Z", timestamp())
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 50
    threshold_type = "Actual"
    contact_emails = [var.budget_alert_email]
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 80
    threshold_type = "Actual"
    contact_emails = [var.budget_alert_email]
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 100
    threshold_type = "Forecasted"
    contact_emails = [var.budget_alert_email]
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}
