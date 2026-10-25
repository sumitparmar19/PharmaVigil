# PharmaVigil on Azure

<!-- The owner's guide to running PharmaVigil on Azure (M11): what the Terraform in infra/azure creates, how the
     GitHub workflows sign in without stored credentials, and every click from sign-up to destroy. One path. -->

Not for clinical or regulatory use. Synthetic patients only.

**Cost:** the AKS control plane is free (Free tier); the one `Standard_B4ms` node, the Basic registry, Log
Analytics (capped at 1 GB/day) and the load balancer's public IP cost roughly US$0.25 an hour while they exist
(an estimate from Azure's public list prices, not a measured bill). A **$20 monthly
budget** sends e-mails at 50% and 80% of actual and 100% of forecast spending. Deploy, test and destroy on the
same day.

## What Terraform creates (`infra/azure`)

| Resource | Notes |
|---|---|
| Resource group `rg-pharmavigil-eastus` | every resource below, tagged `project=pharmavigil`, `managed_by=terraform` |
| AKS `aks-pharmavigil-eastus` | Free control plane, one node pool (1 × B4ms), Entra ID sign-in only (local accounts off), Azure RBAC, Azure CNI overlay with network policy, Azure Policy, Key Vault CSI driver, automatic patch upgrades |
| Identities `id-…-aks`, `id-…-kubelet` | the cluster and its nodes; the kubelet identity has AcrPull on the registry |
| ACR `acrpharmavigil<suffix>` | Basic, admin user off, anonymous pull off |
| Key Vault `kv-pharmavigil-<suffix>` | Azure RBAC; **no secret values in Terraform** (they would land in the state file): you type the six values in step 5 |
| Log Analytics `log-pharmavigil-eastus` | cluster logs, 30 days |
| Budget `budget-pharmavigil-eastus` | $20 a month, three e-mail alerts |
| Identity `id-…-github-deploy` | the application deploy job signs in as it through GitHub OIDC, only from the protected `azure` environment; AcrPush, AKS cluster admin, Key Vault Secrets User |

Checked in CI on every pull request: `terraform fmt`, `validate`, `terraform test` (mocked provider: names, tags,
OIDC subject, AKS and ACR settings, budget) and **Checkov** (16 passed, 0 failed, 20 skipped; each skip has its
reason next to the resource in `main.tf`, mostly features that need paid SKUs or a private network).

## How GitHub signs in (no secrets)

Three user-assigned managed identities trust GitHub's OIDC tokens through federated credentials:

| Identity | Trusted for | Can | Used by |
|---|---|---|---|
| `id-pharmavigil-plan` | pull requests | read (Reader, Storage Blob Data Reader on the state) | *Azure plan* |
| `id-pharmavigil-apply` | environment `azure` | create the resources (Contributor, RBAC Administrator, state writer) | *Azure deploy* (infrastructure), *Azure destroy* |
| `id-…-github-deploy` | environment `azure` | push images, deploy to AKS, read the app secrets | *Azure deploy* (application) |

The first two are created once by `infra/azure/bootstrap.sh` (step 2); Terraform creates the third. The
`azure` environment requires your approval for every run. The build instructions asked for an "OIDC app
registration"; managed identities with federated credentials give the same OIDC sign-in and need no directory
(Entra ID) permissions, which university-managed student tenants often do not grant.

---

## Steps for Sumit

### 1. Get the Azure for Students subscription

1. Open https://azure.microsoft.com/free/students and click **Start free**.
2. Sign in with your UB e-mail address and finish the verification.
3. Open https://portal.azure.com, search **Subscriptions** in the top bar, and check that **Azure for Students**
   is listed and **Active**.

**Send back to the planning chat:** "Azure for Students active".

### 2. Run the bootstrap script in Cloud Shell

1. In the portal, click the **Cloud Shell** icon (`>_`) in the top bar and choose **Bash**. If it asks about
   storage, choose **No storage account required**, pick the Azure for Students subscription and click **Apply**.
2. In another tab, open `infra/azure/bootstrap.sh` in the GitHub repository, click **Raw**, select all and copy.
3. In Cloud Shell type `cat > bootstrap.sh`, press Enter, paste, press Enter, then press **Ctrl+D**.
4. Type `bash bootstrap.sh` and press Enter. It takes about two minutes and ends with a block headed
   "Copy these into GitHub". Leave Cloud Shell open.

**Send back to the planning chat:** the last line of the output above the block ("Done. Copy these into
GitHub…") and whether any line said `ERROR` (if so, the full error text).

### 3. Set up GitHub

1. In the repository on GitHub open **Settings → Environments → New environment**, name it `azure`, click
   **Configure environment**.
2. Tick **Required reviewers**, add yourself, click **Save protection rules**.
3. On the same page under **Environment variables** click **Add environment variable**: name
   `AZURE_APPLY_CLIENT_ID`, value from the bootstrap output. Click **Add variable**.
4. Open **Settings → Secrets and variables → Actions → Variables** tab. For each repository variable in the
   bootstrap output (`AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_PLAN_CLIENT_ID`, `AZURE_OWNER_OBJECT_ID`,
   `TFSTATE_RESOURCE_GROUP`, `TFSTATE_STORAGE_ACCOUNT`) click **New repository variable**, paste name and value,
   click **Add variable**.
5. Switch to the **Secrets** tab, click **New repository secret**, name `BUDGET_ALERT_EMAIL`, value: the e-mail
   address for budget alerts, click **Add secret**.

**Send back to the planning chat:** "GitHub environment and variables set" (no values).

### 4. Create the infrastructure

1. Open **Actions → Azure deploy → Run workflow**, choose part **infrastructure**, click **Run workflow**.
2. Open the run, click **Review deployments**, tick `azure`, click **Approve and deploy**.
3. Wait until it is green (about 10 minutes). Open the **Outputs** step and copy the value of
   `github_deploy_client_id`.
4. In **Settings → Environments → azure → Environment variables**, add `AZURE_DEPLOY_CLIENT_ID` with that value.

**Send back to the planning chat:** the link of the green run.

### 5. Type the application secrets into Key Vault

1. In Cloud Shell, run `openssl rand -hex 24` four times. Each line is one new random value.
2. In the portal search **Key vaults**, open `kv-pharmavigil-…`, then **Objects → Secrets → + Generate/Import**.
3. Create these six secrets (name, then the value; click **Create** after each):
   - `oracle-password`: a value from step 1
   - `pv-db-password`: another value from step 1
   - `jwt-secret`: another value from step 1
   - `artemis-password`: another value from step 1
   - `artemis-user`: `pvbroker`
   - `pv-demo-password`: a password of at least 12 characters that you will type to sign in to the demo; keep it
     in your password manager.

**Send back to the planning chat:** "Six Key Vault secrets created" (never the values, never a screenshot).

### 6. Deploy the application

The deploy does not build anything: it copies the six images that *Demo images* already published to GHCR for
`main` (the same multi-arch images the live demo runs) into the registry Terraform created. They exist once the
Phase 2 pull request is merged and are readable once the packages are public (`OWNER_STEPS.md`, step 16).

1. Open **Actions → Azure deploy → Run workflow**, choose part **application**, click **Run workflow**.
2. Approve it as in step 4.2 and wait until it is green (about 20 minutes).

**Send back to the planning chat:** the link of the green run.

### 7. Try it

1. In Cloud Shell run:
   ```bash
   az aks get-credentials --resource-group rg-pharmavigil-eastus --name aks-pharmavigil-eastus
   kubelogin convert-kubeconfig -l azurecli
   kubectl -n pharmavigil get pods
   kubectl -n pharmavigil port-forward svc/frontend 8080:8080
   ```
   Every pod should show `Running`. Leave the last command running.
2. Click **Web preview** in the Cloud Shell toolbar, choose **Configure**, enter port `8080`, click
   **Open and browse**.
3. Sign in as `pharmacist` with your demo password, dispense ibuprofen, send a side-effect report. Sign out, sign
   in as `reviewer` and open the case in the review queue (it appears within a minute).

**Send back to the planning chat:** "Pods running; report reached the review queue" (a screenshot of the review
queue is fine; it shows no secret).

### 8. Destroy everything

1. Open **Actions → Azure destroy → Run workflow**, type `destroy`, click **Run workflow**, approve it.
2. When it is green, search **Resource groups** in the portal and check that `rg-pharmavigil-eastus` is gone.
   (`rg-pharmavigil-tfstate` stays; it holds only the Terraform state and costs cents a month.)
3. Open **Cost Management → Cost analysis** for the subscription and note the cost so far.

**Send back to the planning chat:** "Destroyed" and the cost shown in Cost analysis.
