// Outbox rows of the gateway (submission.status events).
package io.pharmavigil.submission.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);
}
