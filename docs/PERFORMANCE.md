# Performance

<!-- k6 load test of the report pipeline: what was run, on what hardware, the numbers, and the two bottlenecks the
     metrics exposed and the fixes. Every number is in analysis/m10/load_results_*.json with the PromQL behind it. -->

**Scope of every number on this page:** the local docker compose stack (one container per service, Oracle Free,
single-node Kafka 4.3.2, Artemis) on one build machine, synthetic load-test reports, the synthetic **fixture**
model bundle. They describe this setup, not a production deployment, and say nothing about model quality.

## Hardware

Intel Xeon @ 2.10 GHz, **4 logical CPUs, 15.7 GiB RAM**, Linux 6.18 (a shared cloud build container). The load
generator (k6 2.3.0 in Docker) ran on the same machine as the whole stack, Prometheus, Grafana and Jaeger.

## The test (`ops/k6/report-load.js`)

- **reports**: de-identified pharmacy reports posted to `POST /api/intake/reports` through the UI's nginx, at a
  ramping arrival rate of 5 → 10 → 20 → 40 → 40 reports/s (one minute per step, 5 minutes in all). Each report
  names one of four drugs and one of eight reaction terms.
- **review**: one reviewer working the queue at the same time (keeps 4 of 5 model codes, corrects the fifth,
  confirms seriousness), which feeds the AI-quality metrics and creates real contention with the pipeline.
- Thresholds: intake HTTP p95 < 1 s and < 1% failed requests (both passed in every run).
- Afterwards `ops/k6/collect_results.py` queries Prometheus over the run window: accepted reports, cases coded,
  intake→coded percentiles (from `pv_report_intake_to_coded_seconds`), max consumer lag, max outbox backlog.

```bash
PV_TRACING=true docker compose --profile observability up -d --build
docker run --rm --network pharmacy-managment_default -e PV_DEMO_PASSWORD -e PV_BASE_URL=http://frontend:8080 \
  -v "$PWD/ops/k6:/scripts:ro" -v "$PWD/out:/out" grafana/k6:2.3.0 run --summary-export /out/summary.json /scripts/report-load.js
python3 ops/k6/collect_results.py --start <unix> --end <unix> --k6-summary out/summary.json --strip-summary --out <file>
```

## Results

Same test, fresh stack each time; each row is one committed file in `analysis/m10/`.

| Run | Intake requests (failed) | Intake HTTP p95 | Accepted, peak /min | Coded in window | Coded, peak /min | Intake→coded p50 / p95 / max | Max consumer lag | Max outbox backlog |
|---|---|---|---|---|---|---|---|---|
| 1 baseline | 5,730 (0) | 21.1 ms | 2,400 | 2,870 | 628 | 27.0 s / 103.1 s / 100.4 s | 592 | 46 |
| 2 batched duplicate check (+ triage fix below) | 5,729 (0) | 20.3 ms | 2,399 | 5,779 | 1,927 | 4.3 s / 20.9 s / 23.0 s | 0 | 1,599 |
| 3 + pipelined outbox relay | 5,730 (0) | 22.4 ms | 2,400 | 5,802 | 2,303 | **0.94 s / 7.5 s / 10.8 s** | 0 | 73 |

Notes on reading the table:

- *Accepted* counts reports committed by intake-gateway (Prometheus); *intake requests* are k6's HTTP calls. They
  differ slightly because the counters are sampled every 15 s at the window edges.
- p95 values come from Prometheus histogram buckets, so they are bucket-interpolated estimates; the max is
  exact. Run 1's p95 lies above its max for that reason.
- Run 1 did not finish coding within the window: only 2,870 of about 5,700 reports were coded, the rest
  afterwards. Runs 2 and 3 kept up with intake.

## What the metrics showed, and the fixes

1. **Duplicate check, one query per candidate (run 1 → 2).** The dedup stage's p95 (131 ms) was 6× the coding
   stage's, and the duplicate model ran 139,670 times for 2,870 cases: every new case compared itself with up to
   50 earlier cases sharing a drug, loading each candidate and its current version separately (four round trips
   per candidate). Now all candidates and their current versions come back in one query, and their drugs and
   reactions load in batches (`hibernate.default_batch_fetch_size=64`). Cases coded in the window doubled and
   p95 intake→coded fell from 103 s to 21 s. Kafka lag disappeared; the queue moved to the outbox (backlog 1,599).
2. **Outbox relay, one broker round trip per event (run 2 → 3).** The relay waited for each event's
   acknowledgement before sending the next. It now hands the whole batch (100 rows) to the idempotent producer
   and then awaits the acknowledgements in id order, marks the published rows in one batch update, and keeps
   draining while batches come back full. Order is kept per partition, and a failure still stops the batch at the
   failed row (at-least-once, as before; the unit tests for order and a down broker still pass). Max backlog
   fell from 1,599 to 73 and p95 intake→coded from 21 s to 7.5 s.

The load test also exposed a **correctness bug**, fixed with a test (`triageNeverOverwritesAReviewerWhoTookTheCaseFirst`):
a case the coder cannot code enters the review queue as NEEDS_MANUAL_CODING *before* triage runs. If a reviewer
opened it first, triage tried to set the model's seriousness on a case already IN_REVIEW and failed
(it would otherwise have overwritten the reviewer). Triage now leaves such cases to the reviewer and records
it (`SKIPPED_IN_REVIEW`). Stage failures (all retried successfully; nothing reached a dead-letter topic): 4.1,
5.4 and 1.1 in the three runs (Prometheus `increase()` estimates); the remaining ones are optimistic-lock
retries when the reviewer and the pipeline touch the same case at the same moment.

## Limits

- One machine running generator, stack and monitoring together; no network between services; single Kafka
  broker with three partitions per topic and one consumer per service.
- Fixture models (ONNX inference p95 < 1 ms here); real models will be slower.
- No E2B(R3) schemas in this container, so the JMS submission path was not part of the load.
- The narrative stage used the template (no `ANTHROPIC_API_KEY` in this test); a model call would add its
  latency after coding, outside the intake→coded measure.
