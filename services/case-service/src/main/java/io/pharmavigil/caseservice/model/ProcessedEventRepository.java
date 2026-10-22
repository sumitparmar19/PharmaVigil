// Spring Data repository for processed-event keys (idempotent consumers).
package io.pharmavigil.caseservice.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
}
