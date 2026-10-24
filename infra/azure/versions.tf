# Terraform and provider versions for the PharmaVigil Azure environment (M11). State lives in an Azure Storage
# container that the owner creates once (docs/AZURE.md); its names are passed with -backend-config, so nothing
# about the subscription is committed here.
terraform {
  required_version = ">= 1.16.5"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.9.0"
    }
  }

  backend "azurerm" {
    use_oidc         = true
    use_azuread_auth = true
  }
}

provider "azurerm" {
  # Sign-in comes from GitHub OIDC (ARM_USE_OIDC, ARM_CLIENT_ID, ARM_TENANT_ID, ARM_SUBSCRIPTION_ID) or the
  # owner's `az login`; there are no client secrets.
  features {
    key_vault {
      # The vault is a demo vault that is destroyed after each test; purge it so the same name can be reused.
      purge_soft_delete_on_destroy = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
  }
}
