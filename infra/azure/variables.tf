# Inputs of the PharmaVigil Azure environment. budget_alert_email and owner_object_id have no default; nothing
# secret is an input.

variable "location" {
  description = "Azure region for every resource."
  type        = string
  default     = "eastus"
}

variable "prefix" {
  description = "Short lowercase prefix for resource names."
  type        = string
  default     = "pharmavigil"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{2,11}$", var.prefix))
    error_message = "prefix must be 3-12 lowercase letters or digits, starting with a letter."
  }
}

variable "github_repository" {
  description = "owner/name of the GitHub repository whose deploy workflow may sign in (OIDC)."
  type        = string
  default     = "sumitparmar19/PharmaVigil"
}

variable "github_environment" {
  description = "GitHub environment (with required reviewers) that the deploy and destroy workflows run in."
  type        = string
  default     = "azure"
}

variable "aks_vm_size" {
  description = "Node size. The demo stack (five services, Oracle Free, Kafka, Artemis) needs about 10 GiB of memory."
  type        = string
  default     = "Standard_B4ms"
}

variable "aks_node_count" {
  description = "Nodes in the single (system) node pool."
  type        = number
  default     = 1
}

variable "budget_amount" {
  description = "Monthly budget in the subscription's billing currency (USD for Azure for Students)."
  type        = number
  default     = 20
}

variable "budget_alert_email" {
  description = "Where budget alerts go."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_alert_email))
    error_message = "budget_alert_email must be an e-mail address."
  }
}

variable "owner_object_id" {
  description = "Object id of the owner's Entra ID user (printed by bootstrap.sh); gets Key Vault Secrets Officer."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.owner_object_id))
    error_message = "owner_object_id must be a GUID."
  }
}

variable "tags" {
  description = "Extra tags merged into the default tags of every resource."
  type        = map(string)
  default     = {}
}
