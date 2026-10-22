// Submission history per case, oldest first.
package io.pharmavigil.caseservice.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionEventRepository extends JpaRepository<SubmissionEvent, Long> {

    List<SubmissionEvent> findByCaseIdOrderByIdAsc(Long caseId);
}
