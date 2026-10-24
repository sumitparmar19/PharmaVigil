// Spring Data repository for AuditEvent.
package io.pharmavigil.caseservice.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByCaseIdOrderByIdAsc(Long caseId);
}
