// Idempotent-consumer bookkeeping for the gateway's Kafka consumer.
package io.pharmavigil.submission.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
}
