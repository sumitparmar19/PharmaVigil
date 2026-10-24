# ADR-001: Transactional outbox instead of writing to the database and Kafka separately

<!-- Records why every PharmaVigil event leaves through an outbox table and a relay, how consumers stay
     idempotent, and what the load test showed about the relay. -->

- **Status:** accepted
- **Milestone:** M8 (relay throughput revised in M10)

## Context

A report changes state in a database (intake receipt, case version, submission record) and must also announce
that change on Kafka so the next stage can run. If a service writes the row and then publishes the event in two
separate steps ("dual write"), a crash, a timeout or a Kafka outage between them leaves the two disagreeing: a
case nobody codes, or an event about a row that was rolled back. In a safety system a lost report is the worst
outcome, and engineering rule 2 requires cases to keep moving while Kafka is down.

## Options considered

1. **Dual write** (save, then `kafkaTemplate.send`). Pros: simplest. Cons: no atomicity; a Kafka outage fails
   the request or silently drops the event.
2. **Kafka transactions only.** Pros: exactly-once between Kafka topics. Cons: the database is not part of the
   Kafka transaction, so the row and the event can still disagree.
3. **Change data capture** (Debezium on the database log). Pros: no relay code. Cons: another system to run per
   database (Oracle LogMiner, SQL Server CDC), heavy for a free-tier demo.
4. **Transactional outbox.** The service writes the event into its own `outbox_event` table in the same
   transaction as the change; a relay publishes unpublished rows in id order and marks them published.
   Pros: the row and the event commit or roll back together; Kafka can be down for hours. Cons: at-least-once
   delivery, so every consumer must be idempotent; a relay to maintain.

## Decision

Option 4. `common/outbox/OutboxRelay` is shared by intake-gateway, case-service and submission-gateway (each has
its own `outbox_event` table). Every event carries an `eventId` (also in the `pv-event-id` header); every consumer
records `(event id, consumer)` in `processed_event` in the same transaction as its work and skips events it has
already seen. Failed messages are retried three times and then go to `<topic>.dlq`; messages that can never be
processed (bad JSON, unknown case) go there at once.

## Consequences

- Tested: replaying every event of a case through Kafka creates no duplicate case, version or event
  (`KafkaPipelineTest`, `EndToEndTest`); with the broker down, rows stay pending and the attempts are counted
  (`OutboxRelayTest`).
- The backlog is visible (`pv_outbox_backlog`, alert `PvOutboxBacklogGrowing`).
- The relay first waited for each event's acknowledgement before sending the next; under the k6 load test this
  became the bottleneck (backlog 1,599 rows). It now hands a whole batch to the idempotent producer and awaits the
  acknowledgements in order, which keeps per-partition order and at-least-once semantics (docs/PERFORMANCE.md,
  run 3: backlog 73, p95 intake→coded 7.5 s on the local stack).
- The trace context is stored with each row so traces continue through the relay (docs/OBSERVABILITY.md).
- If events ever had to be delivered exactly once to an external party, the outbox alone would not be enough;
  the regulator path therefore has its own record-before-send and row lock (ADR-002).
