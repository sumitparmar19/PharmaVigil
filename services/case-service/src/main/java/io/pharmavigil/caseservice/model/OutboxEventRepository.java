// Spring Data repository for OutboxEvent.
package io.pharmavigil.caseservice.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);
}
