// Outbox rows for the relay (M8) and for tests that check exactly one event per accepted report.
package io.pharmavigil.intake.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateId(String aggregateId);
}
