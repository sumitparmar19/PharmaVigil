// Spring Data repository for CaseVersion.
package io.pharmavigil.caseservice.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseVersionRepository extends JpaRepository<CaseVersion, Long> {

    Optional<CaseVersion> findByCaseIdAndVersionNo(Long caseId, int versionNo);

    List<CaseVersion> findByCaseIdOrderByVersionNoAsc(Long caseId);
}
