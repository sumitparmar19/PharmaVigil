// Spring Data repository for SafetyCase.
package io.pharmavigil.caseservice.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyCaseRepository extends JpaRepository<SafetyCase, Long> {

    Optional<SafetyCase> findBySourceAndSourceReportId(Source source, String sourceReportId);

    List<SafetyCase> findByStatusIn(List<Status> statuses);

    Optional<SafetyCase> findByCaseNumber(String caseNumber);

    /** Demo mode: replayed cases waiting for the demo auto-review, oldest first. */
    List<SafetyCase> findTop50BySourceReportIdStartingWithAndStatusInOrderByIdAsc(String prefix,
            Collection<Status> statuses);
}
