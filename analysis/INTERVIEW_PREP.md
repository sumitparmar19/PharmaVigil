# Interview preparation

<!-- For Sumit: a one-page plain-English explanation of PharmaVigil, 25 questions an interviewer may ask with
     answers grounded in this repository (file names to point at), and three STAR stories from real problems hit
     while building it. Numbers are the ones in analysis/; anything pending is said to be pending. -->

## PharmaVigil in one page

When a medicine harms someone after it is on the market, the only early warning most countries have is
"spontaneous reports": a doctor, pharmacist or patient tells the regulator. The FDA's database of these reports
(FAERS) has tens of millions of them, and turning each one into a usable case is slow, manual work: find
duplicates, translate the symptoms into the standard medical dictionary (MedDRA), decide whether it is serious,
write a summary, and send it to the regulator in the required format, which since October 2026 is the ICH E2B(R3)
XML standard.

PharmaVigil does that pipeline end to end. A pharmacist dispenses a drug in the app and, if the patient reports a
side effect, sends it in one click. The report goes through a chain of services connected by Kafka: duplicate
check, MedDRA coding, seriousness triage and a draft narrative. Each of those steps is a suggestion from a model;
a human safety reviewer accepts or corrects every one. The approved case is written as an E2B(R3) file, checked
against the official schemas, and sent over JMS to a mock regulator, which answers ACK or NACK.

The other direction matters too: statistics over the public FAERS data (PRR, ROR, EBGM, and a Transformer I
designed to learn from the reports themselves) find drug–event pairs reported more often than chance. When the
pharmacist next dispenses that drug, the app shows that evidence, always with the reminder that spontaneous
reports do not prove the drug caused the reaction.

What is proven today: the whole software path, end to end, in automated tests (including against the official
E2B schemas), on Kubernetes and under load. What is not yet proven: the models' accuracy on real data, because the
build environment cannot download FAERS; the code and notebooks to do it are ready.

## 25 questions

**Signals and models**

1. *PRR vs EBGM?* PRR compares how often an event is reported with a drug against all other drugs; with few
   reports it is noisy and explodes for rare pairs. EBGM (MGPS) shrinks each observed/expected ratio toward a
   prior fitted to all pairs (two-gamma mixture), so small counts are pulled toward 1; EB05 is its lower 5% bound,
   which PharmaVigil uses for warnings (EB05 ≥ 2). Code: `ml/.../signals/disproportionality.py`, `ebgm.py`.
2. *Why a self-supervised model for signals?* There are no labels for "this drug causes this event" at scale; the
   reports themselves are the signal. SafetyFormer learns by hiding a reaction in a report and predicting it from
   the drugs and the rest, so it learns co-occurrence beyond simple counts (e.g. confounding by co-medication).
   Score definition fixed before results (ADR-005). Results on real data: pending.
3. *How do you evaluate a signal score?* Against reference sets of known positive and negative drug–event pairs
   (OMOP 399 pairs, EU-ADR 93, from OHDSI MethodEvaluation at a pinned commit), with AUC and bootstrap CIs, and a
   time-travel test (would it have flagged label changes before they happened? ADR-006).
4. *Leakage risks?* Training on reports that arrived after the evaluation date (handled by yearly snapshots and a
   received-before cutoff), and a shuffled split being optimistic (the leakage test compares shuffled vs
   time-ordered splits of equal size). For triage: outcome fields and outcome-revealing terms (e.g. "death") are
   never features (`triage/features.py`).
5. *How does the MedDRA coder work?* A biomedical encoder with a classification head for frequent terms plus
   retrieval over PT names for the long tail; the two are merged and ranked (ADR-007). It only suggests; the
   reviewer confirms each code.
6. *Why is AI only a suggestion?* Safety decisions are regulated and the cost of a wrong code or a missed serious
   case is high. Every suggestion stores the model name, version and score next to the reviewer's final value
   (`ReviewDecision`), and the acceptance rate is tracked (`pv_review_decisions_total`).
7. *Where do training labels come from?* Human coding already in FAERS/VAERS and my own hand-labelled set, never
   from an LLM (also an Anthropic commercial-terms rule).
8. *What does the LLM do?* Drafts the case narrative from structured, already-coded facts only (no free text, no
   arithmetic). Refusal, truncation or any API error falls back to a deterministic template; a reviewer approves
   every narrative (`NarrativeDrafter`).

**Architecture and messaging**

9. *Why the outbox pattern?* Writing to the database and then to Kafka separately can lose or invent events on a
   crash. The event is written in the same transaction as the change and a relay publishes it (ADR-001). Kafka
   can be down for hours and nothing is lost.
10. *At-least-once means duplicates. How?* Every event has an id; every consumer records (event id, consumer) in
    `processed_event` in the same transaction as its work and skips repeats. The test replays every event of a
    case and checks that nothing changes.
11. *What is a dead-letter topic for?* Messages that keep failing (or can never succeed, like bad JSON) are moved
    aside after three tries so one poison message cannot block a partition; DLQs are monitored.
12. *Kafka vs JMS: why both?* Kafka is the replayable log between our services; the regulator hand-over is a
    point-to-point exchange with per-message redelivery and an answer, which JMS models well (real gateways use
    AS2; ADR-002).
13. *A race you found in the gateway?* The regulator's answer could arrive before the gateway had recorded the
    message as queued. Fix: record SENDING before sending and lock the row when answering (see STAR 1).
14. *SOAP vs REST?* Partners in this domain still exchange ICSRs as XML documents; SOAP gives a contract (WSDL),
    standard faults and WS-Security. REST is used for our own UI and pharmacy reports (ADR-009).
15. *How is SOAP secured?* WS-Security UsernameToken plus Timestamp (replay window); without configured
    credentials every call is refused (fail closed).
16. *What is E2B(R3) and how do you know your files are right?* The ICH standard ICSR message (HL7 v3). Exports
    are validated against the official schema set (fetched from EMA in CI, not committed) and only a valid export
    can be queued; a deliberately broken message must fail, so the check is not vacuous. XSD validation does not
    prove business rules (ADR-004).

**Data and Java**

17. *Why Oracle, and how portable is it?* Oracle is the database behind the market-leading safety system; the
    schema is Flyway-managed per vendor and the same JPA code passes the test suite on SQL Server 2022 (ADR-003).
    Differences hit: Oracle 23's native BOOLEAN, VARCHAR2 vs CHAR.
18. *FEFO dispensing and concurrency?* The server picks lots first-expiry-first-out, never expired ones, under a
    lock so two counters cannot sell the same unit (`FefoTest`, dispensing integration tests).
19. *How do models run in Java?* Exported to ONNX and run with ONNX Runtime inside case-service; the pre-processing
    (WordPiece tokenizer, feature vectors) is re-implemented in Java and a parity test compares against Python
    outputs (ADR-008).
20. *What happens if a model file is missing?* That model alone is disabled; cases go to manual coding or
    "seriousness unknown" and keep flowing (engineering rule 2, `PipelineWithoutModelsTest`).

**Operations**

21. *How did you find the performance bottlenecks?* Stage-time histograms and an intake-to-coded timer in
    Prometheus during a k6 ramp: the dedup stage was six times slower than coding, and later the outbox backlog
    grew while Kafka lag was zero (see STAR 2).
22. *How do traces cross Kafka when events go through an outbox?* The writer's W3C traceparent is stored with the
    outbox row and the relay restores it while sending, so one trace runs from the pharmacy request through every
    stage (182 spans in `analysis/m10/trace_example.json`).
23. *What does Helm buy you here?* One library chart gives every service the same probes, non-root security and
    secret wiring; one umbrella install; values per environment (kind, AKS). Kafka is run by the Strimzi operator
    with topics as code (ADR-010).
24. *How do you keep secrets out of the repository?* Only environment variables; charts reference a Secret by
    name; CI generates throwaway values per run and masks them; Azure sign-in is GitHub OIDC (no keys); Terraform
    never sees secret values (they would end up in state); gitleaks scans the whole history in CI. I found and
    removed sign-in tokens that k6's summary export embeds.
25. *What would you do next?* Run the real FAERS/VAERS pipeline and report the model numbers; check E2B code
    values against the ICH implementation guide; run one Azure deployment; add a second Kafka broker and a real
    duplicate-labelled set.

## STAR stories

**1. The answer that arrived before the question (correctness under concurrency)**
- *Situation:* the submission gateway sends an E2B file over JMS and the mock regulator replies ACK/NACK.
- *Task:* every submission must end in exactly one recorded answer, even under redelivery.
- *Action:* the integration test sometimes failed: the reply could be processed before the gateway had saved its
  own record of the message, so the answer was "for an unknown message". I changed the order to record the message
  (status SENDING) before sending, mark it QUEUED afterwards unless an answer already did, and lock the row while
  recording an answer so a redelivered answer is ignored.
- *Result:* the race and a duplicate QUEUED event disappeared; the test now covers it (`SubmissionGatewayTest`,
  `EndToEndTest`), and the same pattern is described in ADR-002.

**2. From 103 seconds to 7.5 (measuring before optimising)**
- *Situation:* the pipeline worked, but nobody had measured it under load.
- *Task:* find how many reports per minute it handles and where it slows down.
- *Action:* added Prometheus metrics for each stage and for intake-to-coded time, ran a k6 ramp to 40 reports/s
  with a reviewer working in parallel. The dedup stage was the slowest (each new case loaded up to 50 candidates
  one query at a time); after batching that, Kafka lag vanished but the outbox backlog grew: the relay waited for
  each Kafka acknowledgement before sending the next. I pipelined the sends per batch while keeping order and
  at-least-once delivery, and re-ran the same test each time on a fresh stack.
- *Result:* p95 intake-to-coded fell from 103 s to 21 s to 7.5 s, cases coded per minute rose from 628 to 2,303 at
  peak (local 4-vCPU stack, synthetic data). The test also exposed a correctness bug (triage overwriting a case a
  reviewer had already opened), fixed with a new test. All three runs are committed (`analysis/m10/`).

**3. A database that could not restart (debugging on Kubernetes)**
- *Situation:* the first Kubernetes run on kind failed: every service crash-looped with "no listener" from
  Oracle.
- *Task:* make the deployment come up reliably on a fresh cluster.
- *Action:* from the CI logs, the Oracle container had died once and its restart in the same pod failed with
  ORA-01081 (shared memory still held), so the database never opened; meanwhile the services kept crashing and
  backing off. I gave Oracle enough memory headroom and added an init container to every service that waits until
  the database and brokers accept connections (a Kubernetes Service only routes to ready pods), and made the
  diagnostics print restart reasons.
- *Result:* the next problem was in the volume: an emptyDir mounted over Oracle's data directory hid the
  database that the image ships pre-built, so Oracle had nothing to open. Without it, the kind workflow is green
  (whole stack on Strimzi Kafka, Playwright smoke test 2/2), and the same install script now also passes inside
  the Jenkins pipeline; the Azure deploy uses it too.

## Phase 2: the live demo (hosting, memory, proof)

26. *Why Oracle Cloud Always Free and not AKS for the always-on demo?* A demo must run every day for months at no
    cost. Always Free gives one Ampere A1 server with 2 OCPUs and 12 GB (Oracle cut it from 4/24 in June 2026), which
    is enough for the whole stack in docker compose; AKS stays a one-day proof of the Kubernetes path (owner step 9)
    because even the smallest node costs money every hour.
27. *How do you fit Oracle, Kafka, Artemis, five JVMs and Grafana into 12 GB?* An explicit memory limit on every
    container (total of the limits 8.3 GiB, under the 9 GB target), JVM heaps as a percentage of the limit with the
    serial GC and capped metaspace, Kafka 384 MB heap, Artemis 256 MB, an int8-quantized coder, and a swap file for
    safety. The real usage is measured with `docker stats` during a replay (`ops/demo/measure.sh`, `analysis/p6/`).
28. *Why multi-arch images?* The demo server is arm64; CI builds every image for amd64 and arm64 with buildx (the UI
    bundle is built once on the native platform) and a native arm64 runner starts the whole stack and runs a replay
    to the regulator's answer before anything is deployed.
29. *How does the "Run the pipeline" button stay honest?* It sends real FAERS reports through the real intake (SOAP
    with WS-Security and REST), and every counter is counted from case-service's own audit trail. Replayed cases are
    auto-reviewed so they can reach the regulator, but through the normal review calls as "demo-auto-review",
    labelled as such on the case page.
30. *How is a public demo protected?* No sign-up, shared read-mostly accounts, rate limits per IP and account, a
    cooldown and size cap on replays, 16 KB request bodies, no uploads or SOAP from the internet, no outbound calls
    from user input, secrets generated on the server, and a nightly reset.
31. *How do you know it still works next week?* A Playwright test runs against the public URL every day and the
    README shows its badge; `/api/status` reports each service, Kafka lag and the loaded model versions.

## Phase 2: results on real FAERS (scope: subset FAERS 2019Q1–2026Q2)

32. *What data did you actually process?* 30 quarterly FDA files: 12,902,877 report rows, 10,848,477 unique cases
    after FDA's de-duplication rule (latest version, deleted cases removed), 98.5% of drug mentions mapped to an
    RxNorm ingredient. Spark + Delta in a 4-CPU container: silver 6 min, gold 6 min; the slow step was 45,179 RxNav
    calls (one hour). (`analysis/p1/data_counts.json`)
33. *Which signal method won?* None clearly. On 388 OMOP pairs IC has the best point estimate (AUC 0.821) and the
    lower-bound PRR/ROR are about 0.81, but the intervals overlap. The bigger effect is the counting rule: using
    only primary-suspect drugs beats counting every drug on the report (0.821 vs 0.688). (`analysis/p2/README.md`)
34. *Did your Transformer beat the baselines?* No. The CPU-sized SafetyFormer reached 0.765 on OMOP (IC 0.821) and
    0.813 on EU-ADR (baselines about 0.92) on the same pairs, and the README says so. Possible reasons: a small
    model, a quarter of one pass over the data, and a score that mixes in what a drug is prescribed for. The GPU
    run is the next test. (`analysis/p3/safetyformer.json`)
35. *What is the leakage check and what did it show?* Train and test once on a random split and once on a split by
    date (test = 2025 onward). The random split overstated recall@10 by 0.061 (0.498 vs 0.437), because reports
    from the same period share drugs, reactions and reporting waves. That is why every split here is by date.
36. *Your triage AUC is 0.96; is that real?* Partly a reporting rule. FDA receives foreign reports mostly when they
    are serious (95% of non-US test reports vs 35% of US ones), and the reporter country is the top feature. Within
    US reports alone the AUC is 0.924, within non-US 0.923; both numbers are in the model card next to the 0.96.
37. *Tell me about a bug you found only on real data.* The EBGM prior: two runs on identical data gave different
    priors, because the optimizer started once and stopped in different local optima depending on the row order
    the database returned (likelihoods 4,432,544 vs 4,499,742). Fix: sort the pairs, try five starts on a fixed
    sample, refine the best, and record every start's likelihood. Others: a dense matrix and Python tuples that
    did not fit in 15 GiB, an unreadable schema folder that stopped every submission, Artemis blocking a nearly full
    disk.
38. *Why is the duplicate model's 0.998 not impressive?* Its positives are follow-up versions of one FAERS case,
    which repeat most of the first report. It measures follow-up matching; genuine cross-case duplicates are not
    labelled in FAERS, and the model card says so.
39. *How does the coder work without labels?* Zero-shot retrieval: a pretrained biomedical sentence encoder
    (Apache-2.0) embeds the reported term and the 3,000 most reported FAERS terms, and ranks by cosine similarity.
    Exact wording scores 1.0, lay wording is weak ("a high fever" ranks "rheumatic fever" first), so the auto-code
    threshold is high and everything else goes to manual coding. It is not evaluated until labelled VAERS data
    arrives.
40. *Does the real stack fit the free server?* Measured with the real models and 810,403 signal pairs: 5,748 MiB
    idle, 6,055 MiB peak during a 200-report replay, every report acknowledged in 42 s (`analysis/p6/`); the free
    VM has 12 GB.
