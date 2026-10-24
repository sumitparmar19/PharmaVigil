# ADR-002: Kafka for the internal pipeline, JMS for the regulator gateway

<!-- Records why PharmaVigil uses two messaging systems and what each one is responsible for. -->

- **Status:** accepted
- **Milestone:** M8

## Context

Two kinds of messaging appear in pharmacovigilance systems. Inside the company, cases move through a chain of
stages (intake, duplicate check, coding, triage, narrative) that must be replayable and observable. Towards a
regulator, an ICSR is handed over once as an individual message and an acknowledgement (ACK or NACK) comes back;
the real gateways (EMA's EudraVigilance gateway, FDA's Electronic Submissions Gateway) do this over AS2.
PharmaVigil has no regulator connection, so a mock regulator stands in, and the hand-over is modelled with
point-to-point JMS queues. The build instructions ask for both Kafka and JMS (ActiveMQ Artemis), so each must
have a real job rather than being decoration.

## Options considered

1. **Kafka for everything.** Pros: one system. Cons: a regulator gateway is a point-to-point request/answer
   exchange with per-message redelivery and a dead-letter queue, which Kafka models poorly (no per-message
   acknowledgement, retries block a partition).
2. **JMS for everything.** Pros: one system, per-message semantics. Cons: no replayable log of the pipeline, no
   consumer groups per stage, harder to rebuild a read model or re-run a stage.
3. **Kafka inside, JMS at the edge.** Pros: each tool where it is strongest. Cons: two systems to run and monitor.

## Decision

Option 3.

- **Kafka** (`report.received → report.deduped → report.coded → case.triaged`, then `case.ready` and
  `submission.status`, each with a `.dlq` twin): the replayable backbone between PharmaVigil's own services, fed
  through the outbox (ADR-001), consumed idempotently.
- **JMS** (Artemis queues `regulatory.outbound`, `regulatory.ack`): submission-gateway's link to the (mock)
  regulator. It records the message (SENDING) before sending, sends in a transacted session (QUEUED), and records
  the answer once under a row lock; a failing JMS message is redelivered by the broker and ends in its DLQ.
  ACK or NACK goes back to Kafka as `submission.status`.

## Consequences

- An answer can arrive before the gateway has marked the message QUEUED; recording SENDING first and locking the
  row when answering made that race safe (found and fixed in M8, `SubmissionGatewayTest`).
- Both systems are monitored: consumer lag and DLQ topics for Kafka; queue depth (`pv_jms_queue_depth`,
  including the broker DLQ) and ACK/NACK counts for JMS.
- On Kubernetes, Kafka is run by Strimzi and Artemis by a small demo chart (ADR-010).
- A real regulator connection (AS2) would replace the gateway's JMS side only; the Kafka pipeline would not
  change.
