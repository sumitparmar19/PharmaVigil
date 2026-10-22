# Owner steps

What this file is: everything only Sumit can do (accounts, logins, secrets, GPU runs, GitHub settings),
written as one path, click-by-click. Each step ends with what to send back to the planning chat.
Never send a screenshot of a secret.

Status key: **OPEN** (waiting for Sumit) · **DONE**

---

## 1. Turn on network access for the build session: OPEN

**Why:** at the start of M0 every data host was still refused inside the build container (FDA, VAERS,
RxNav, ICH, Hugging Face, PyTorch, quay.io, Helm). Until this works, data and model numbers are built
from small test fixtures and marked *pending full data*.

1. Open the Claude Code session that is building PharmaVigil.
2. Click the **˅** next to the session title.
3. Click **Edit cloud environment**.
4. Under **Network access**, select **Custom**.
5. Tick **Also include default list of common package managers**.
6. In **Allowed domains**, check that these 14 lines are present (add any that are missing):
   ```
   fda.gov
   *.fda.gov
   vaers.hhs.gov
   *.nlm.nih.gov
   ich.org
   *.ich.org
   huggingface.co
   *.huggingface.co
   *.hf.co
   download.pytorch.org
   quay.io
   *.quay.io
   get.helm.sh
   *.githubusercontent.com
   ```
7. Click **Save**.
8. In the session, send the message: `Network saved, please re-test the hosts.`

**Send back to the planning chat:** the host table Claude Code writes into `analysis/PROGRESS.md`
after the re-test.

---

## 2. Rename the repository to PharmaVigil: OPEN

**Why:** at the start of M0, GitHub still listed the repository as `sumitparmar19/Pharmacy-Managment`.
GitHub redirects the old link after a rename, so the build keeps working either way.

1. Open https://github.com/sumitparmar19/Pharmacy-Managment
2. Click **Settings** (top bar of the repository).
3. In **Repository name**, type `PharmaVigil`.
4. Click **Rename**.

**Send back to the planning chat:** the new repository URL from your browser's address bar.

---

## 3. Turn on the Dependency graph: OPEN

**Why:** the "Dependency review" check on the pull request fails with "Dependency review is not
supported on this repository. Please ensure that Dependency graph is enabled". Only the repository
owner can switch it on.

1. Open https://github.com/sumitparmar19/Pharmacy-Managment/settings/security_analysis
2. Find **Dependency graph**.
3. Click **Enable**.
4. Open the pull request, click the **Checks** tab, select **Dependency review**, and click
   **Re-run jobs**.

**Send back to the planning chat:** whether the Dependency review check is green after the re-run.

---

## 4. Run the full FAERS history on Databricks Free Edition: OPEN

**Why:** the full 2004Q1 onward history is too large for the build container. The same code runs on
Databricks as notebooks.

1. Open https://www.databricks.com/learn/free-edition and click **Sign up**. Use your UB email.
2. After the workspace opens, click **Workspace** in the left sidebar.
3. Click **Create** → **Git folder**.
4. In **Git repository URL**, paste `https://github.com/sumitparmar19/Pharmacy-Managment` and click
   **Create Git folder**.
5. In the Git folder, open `data` → `notebooks` → `databricks` → `00_setup_and_check`.
6. At the top, set the **git_ref** box to `claude/determined-wozniak-8tf7pa` (until the pull request
   is merged, then `main`).
7. Click **Run all**. When it finishes, the last cell shows three status lines.
8. If all three show `200`, open `01_faers_bronze`, set **git_ref** the same way, set **last** to the
   newest quarter listed at https://fis.fda.gov/extensions/FPD-QDE-FAERS/FPD-QDE-FAERS.html, and click
   **Run all**. If it stops on a time-out, click **Run all** again; finished quarters are skipped.
9. Open `02_faers_silver_gold`, set **git_ref**, click **Run all**.
10. Open `03_export_for_training`, click **Run all**.

**Send back to the planning chat:** the three status lines from step 7, and the JSON printed by the
last cell of `02_faers_silver_gold`.

---

*More steps are added as milestones need them (Databricks, Kaggle, MedDRA licence, narrative labels,
Azure, Grafana Cloud, GitHub secrets, release tags).*
