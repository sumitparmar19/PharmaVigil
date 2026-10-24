# PharmaVigil demo script

<!-- What Sumit says while recording a screen video of the live demo (3 minutes), and a 60-second version for
     interviews. Every claim in it is something the viewer can see on screen or open on the evidence page; numbers
     are read from the evidence page during the recording, not from memory. -->

Before recording: open the live demo's landing page, the Grafana pipeline dashboard (`/grafana/`) in a second tab,
and sign in once as `demo-reviewer` so the browser remembers the page. Close other tabs. Speak slowly; the
counters take about two minutes to finish, which is the length of the middle part.

## 3-minute version

**0:00 – 0:25 · What it is (landing page)**
"This is PharmaVigil, a drug-safety platform I built. When a patient has a side effect, a pharmacist reports it in
one click. The system removes duplicates, codes the reaction to the medical dictionary, judges how serious it is,
and a reviewer approves it. The approved case goes to a regulator as the official ICH E2B(R3) XML message.
The banner says it plainly: this is a portfolio project, not for clinical use."

*Point at the architecture diagram:* "Five Spring Boot services, Kafka between the stages, Oracle underneath, and
JMS for the hand-over to a mock regulator."

**0:25 – 0:40 · Sign in**
"The demo accounts are on the page. I sign in as the reviewer." *Sign in as `demo-reviewer`, click
**Run the pipeline** in the menu.*

**0:40 – 2:10 · Run the pipeline**
*Click **Run the pipeline**.* "This sends 200 real reports from the FDA's public FAERS database through the whole
system. Three in four arrive over SOAP with WS-Security, the way a partner company would send them; the rest over
REST. Every one is an E2B(R3) message that intake checks against the official schema."

*While the tiles count up:* "Each tile is counted from the system's own audit trail, not a timer. Here they are
de-duplicated; here my models code each reaction and judge seriousness. The models run inside Java with ONNX
Runtime. Because this is a demo, an automatic reviewer accepts the suggestions so the cases can reach the
regulator; every decision is stored next to the model's suggestion, exactly as for a person."

*Switch to the Grafana tab:* "The same run on the pipeline dashboard: stage times, Kafka lag, the outbox."
*Switch back:* "And the regulator has answered: acknowledgements, and any rejections go back to review."

**2:10 – 2:40 · One case end to end**
*Click one finished case.* "One case: the reported terms, the model's code with its name, version and score,
the reviewer decision, the narrative, and here the E2B(R3) XML the regulator received, with its ACK."

**2:40 – 3:00 · Signals and evidence**
*Open **Signals**.* "Back at the pharmacy, the counter shows evidence from public FAERS data: classic
disproportionality statistics and my own Transformer model side by side, and a badge where the pair is a known
positive or negative in a published reference set. Spontaneous reports do not prove that a drug caused a
reaction, so it is evidence, never a verdict."
*Open **Evidence**.* "Every number in the project is here, with the data it was measured on and a link to the
file that produced it. Thanks for watching; the code is on GitHub."

## 60-second version (interviews)

"PharmaVigil is a pharmacovigilance platform: it turns a pharmacy side-effect report into a reviewed, schema-valid
E2B(R3) submission. Under the hood: five Spring Boot services with a transactional outbox into Kafka, Oracle with
SQL Server portability, and a JMS gateway to a mock regulator. My models — de-duplication, MedDRA coding and
seriousness triage — run inside Java through ONNX Runtime, and every suggestion is stored next to the reviewer's
decision. The live demo replays 200 real FDA FAERS reports through the full path while the counters move, on one
free Oracle Cloud ARM server under 9 GB of memory, and a GitHub Action tests the live site every day. The evidence
page lists every measured number with its data scope, and says 'pending' where something is not measured yet."

## If something goes wrong while recording

- **"Please wait … before starting another run"**: the demo allows one run per account every two minutes. Wait,
  or show the previous run's tiles.
- **The counters stop before the end**: someone else may be running the demo at the same time; open
  `/api/status` to show the services are up, then continue with a finished case.
- **Signals page says no data is loaded**: the nightly reset is running (04:30 UTC); record later.
