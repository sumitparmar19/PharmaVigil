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

*More steps are added as milestones need them (Databricks, Kaggle, MedDRA licence, narrative labels,
Azure, Grafana Cloud, GitHub secrets, release tags).*
