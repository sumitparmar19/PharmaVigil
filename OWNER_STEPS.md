# Owner steps

What this file is: everything only Sumit can do (accounts, logins, secrets, GPU runs, GitHub settings),
written as one path, click-by-click. Each step ends with what to send back to the planning chat.
Never send a screenshot of a secret.

Status key: **OPEN** (waiting for Sumit) · **DONE**

---

## 1. Turn on network access for the build session: DONE

**Done:** re-tested at the start of Phase 2 (P0); FAERS, VAERS, RxNav, Hugging Face, PyTorch, quay.io and Helm
are reachable (`analysis/PROGRESS.md`, "Start of Phase 2").

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

## 2. Rename the repository to PharmaVigil: DONE

**Done:** during M2 GitHub reported the new location https://github.com/sumitparmar19/PharmaVigil.
The steps are kept for the record.

1. Open https://github.com/sumitparmar19/Pharmacy-Managment
2. Click **Settings** (top bar of the repository).
3. In **Repository name**, type `PharmaVigil`.
4. Click **Rename**.

**Send back to the planning chat:** the new repository URL from your browser's address bar.

---

## 3. Turn on the Dependency graph: DONE

**Done:** Dependency review green with 0 vulnerable packages.

**Done:** the Dependency review check passed on `6cd7b95` (run 40063279746).

**Why:** the "Dependency review" check on the pull request fails with "Dependency review is not
supported on this repository. Please ensure that Dependency graph is enabled". Only the repository
owner can switch it on.

1. Open https://github.com/sumitparmar19/PharmaVigil/settings/security_analysis
2. Find **Dependency graph**.
3. Click **Enable**.
4. Open the pull request, click the **Checks** tab, select **Dependency review**, and click
   **Re-run jobs**.

**Send back to the planning chat:** whether the Dependency review check is green after the re-run.

---

## 4. Run the full FAERS history on Databricks Free Edition: OPEN

**Why:** the full 2004Q1 onward history is too large for the build container. The same code runs on
Databricks as notebooks.

1. Open https://docs.databricks.com/aws/en/getting-started/free-edition and follow its **sign up** link
   to the Free Edition sign-up page. Sign up with your UB email.
2. After the workspace opens, click **Workspace** in the left sidebar.
3. Click **Create** → **Git folder**.
4. In **Git repository URL**, paste `https://github.com/sumitparmar19/PharmaVigil` and click
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

## 5. Train SafetyFormer at full scale on a Kaggle GPU: OPEN (after step 4)

**Why:** full-scale training needs a GPU; Kaggle gives free GPU hours.

1. Open https://www.kaggle.com and click **Register**. Verify your phone number when asked (GPU access
   needs it): click your profile picture → **Settings** → **Phone verification**.
2. In Databricks, open **Catalog** → **workspace** → **pharmavigil** → **Volumes** → **lake** →
   **export**. For each of the folders `report`, `report_drug`, `report_reaction`, open it and download
   every `.parquet` file into a folder on your computer with the same name.
3. In Kaggle, click **Create** → **New Dataset**. Name it `pharmavigil-faers-gold`. Drag the three
   folders in. Click **Create**.
4. Open https://github.com/sumitparmar19/PharmaVigil/blob/main/ml/notebooks/kaggle/safetyformer_full.ipynb
   and click the **Download raw file** button.
5. In Kaggle, click **Create** → **New Notebook** → **File** → **Import Notebook**, and choose the file
   from step 4.
6. In the right panel: **Session options** → **Accelerator** → **GPU T4 x2**; **Internet** → **On**;
   **Input** → **Add Input** → search `pharmavigil-faers-gold` → **Add**.
7. In the first code cell, set `GIT_REF` to `claude/determined-wozniak-8tf7pa` until the pull request is
   merged, and set `SCOPE` to the quarters you loaded in step 4 (for example `full: FAERS 2004Q1–2026Q2`).
8. Click **Run All**. When it finishes, open the **Output** tab and download
   `pharmavigil_m4_evidence.zip`.

**Send back to the planning chat:** the file `pharmavigil_m4_evidence.zip` and the `sha256sum` line
printed by the last cell.

---

## 6. Train the MedDRA coder on a Kaggle GPU: OPEN

**Why:** the coder needs the VAERS narratives and a pretrained biomedical model, and both hosts are
blocked in the build container. Kaggle has both and a free GPU.

1. Open https://vaers.hhs.gov/data/datasets.html in your browser.
2. Download the yearly zip files **2019 to 2025** (one `<year>VAERSData.zip` per year; complete any check
   the site shows before the download starts).
3. In Kaggle, click **Create** → **New Dataset**, name it `pharmavigil-vaers`, drag in the seven zip
   files, and click **Create**.
4. Open https://github.com/sumitparmar19/PharmaVigil/blob/main/ml/notebooks/kaggle/meddra_coder.ipynb and
   click **Download raw file**.
5. In Kaggle, click **Create** → **New Notebook** → **File** → **Import Notebook**, and choose the file.
6. Right panel: **Accelerator** → **GPU T4 x2**; **Internet** → **On**; **Add Input** →
   `pharmavigil-vaers`.
7. In the first code cell, set `GIT_REF` to `claude/determined-wozniak-8tf7pa` until the pull request is
   merged, and `TEST_YEAR` to `2025`.
8. Click **Run All**. When it finishes, download `pharmavigil_m5_coder.zip` from the **Output** tab.

**Send back to the planning chat:** `pharmavigil_m5_coder.zip` and the `sha256sum` line from the last
cell.

---

## 7. Label the 50 drug narratives: OPEN

**Why:** the coder is trained on vaccine reports; your labels on drug narratives measure how well it
transfers. Labels must be yours, never an AI model's.

1. Open `ml/coder/drug_narratives_template.csv` in a spreadsheet program.
2. For each row, type in the `sumit_pts` column the MedDRA Preferred Terms you would code for the
   narrative, separated by `;` (for example `Myalgia; Muscular weakness`). Use the MedDRA browser once
   your licence is active (step 8); until then, use the PT names you know from FAERS.
3. Save as CSV with the same file name and commit it to the pull-request branch (GitHub: open the file →
   **Edit** pencil → paste → **Commit changes**).

**Send back to the planning chat:** the commit link.

---

## 8. Request the MedDRA academic licence: OPEN

The exact form address could not be checked from the build environment, so this step starts at the
site's home page.

1. Open https://www.meddra.org.
2. Open the **Subscription** section from the top menu and choose the free subscription for
   **academic / non-profit** users.
3. Fill in the form with your UB email and "University at Buffalo" as the institution, and submit.
4. Wait for the confirmation email with your MedDRA ID.

**Send back to the planning chat:** only the sentence "MedDRA licence active" (never the login details).

## 9. Run PharmaVigil on Azure for a day: OPEN

**Why:** the Azure code (Terraform, workflows) is validated in CI but has never been applied; only you can create
the subscription and approve the deploy.

Follow `docs/AZURE.md`, steps 1 to 8, in order. Each step there ends with what to send back. Destroy (step 8) on
the same day you deploy.

**Send back to the planning chat:** the eight "send back" lines from `docs/AZURE.md`.

## 10. Optional: let Claude draft case narratives: OPEN

**Why:** without a key, narratives come from a fixed template (everything still works). With a key, case-service
drafts them with Claude from the coded facts only; a reviewer still approves every narrative.

1. Open https://console.anthropic.com, sign in, open **API Keys**, click **Create Key**, name it `pharmavigil-local`,
   copy it.
2. On your computer, open the `.env` file in the repository folder (created by `scripts/dev-up.sh`) and add two
   lines: `ANTHROPIC_API_KEY=` followed by the key, and `CLAUDE_MODEL=` followed by a current model id from
   https://docs.anthropic.com (Models overview).
3. Run `docker compose up -d case-service` and send a new report from the pharmacy page.

**Send back to the planning chat:** whether the new case's narrative is labelled "Draft by …" (Claude) or
"Template draft" (never the key).

## 11. Optional: import the dashboards into Grafana Cloud: OPEN

**Why:** the three dashboards run locally with `scripts/dev-up.sh --observability`; Grafana Cloud's free tier keeps
a copy you can show without running the stack (it shows data only while a Prometheus sends to it).

1. Sign up at https://grafana.com (free tier) and open your Grafana stack.
2. Open **Dashboards → New → Import**, click **Upload dashboard JSON file**, choose
   `ops/grafana/dashboards/pipeline-health.json`, pick your Prometheus data source, click **Import**.
3. Repeat for `ai-quality.json` and `safety-signals.json`.

**Send back to the planning chat:** the three dashboard titles as Grafana lists them.

## 12. Create the v1.0 release after the pull request is merged: DONE

**Done:** PR #1 merged; release `v1.0` published.

**Why:** the build container cannot push tags.

1. After the pull request is merged, open the repository on GitHub, click **Releases → Draft a new release**.
2. Click **Choose a tag**, type `v1.0`, click **Create new tag: v1.0 on publish**, target `main`.
3. Title `PharmaVigil v1.0`; in the description paste the "Summary" section of `analysis/PROJECT_REPORT.md`.
4. Click **Publish release**.

**Send back to the planning chat:** the release URL.

## 13. Decide on the two CodeQL alerts: DONE

**Done:** both alerts dismissed (false positive / won't fix, with the agreed comments).

**Why:** the "CodeQL" check on the pull request is red because of two alerts that Claude Code judged false
positives (reasons in `analysis/COMPLETE_REPORT.md`, part A2, and in the replies on the pull request). Only the
repository owner can dismiss an alert.

1. Open https://github.com/sumitparmar19/PharmaVigil/security/code-scanning
2. Click **Clear-text logging of sensitive information** → **Dismiss alert** → **False positive** → comment
   `aggregate statistics of public FAERS data` → **Dismiss alert**.
3. Click **Disabled Spring CSRF protection** → **Dismiss alert** → **Won't fix** → comment
   `stateless bearer-token API, no cookies` → **Dismiss alert**.
4. Open the pull request and check that the **CodeQL** check is green.

**Send back to the planning chat:** whether the CodeQL check is green (or that you want code changes instead).

---

*More steps are added as milestones need them (Databricks, Kaggle, MedDRA licence, narrative labels,
Azure, Grafana Cloud, GitHub secrets, release tags).*
