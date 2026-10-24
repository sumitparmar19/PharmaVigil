// Spring Data repository for CaseVersion.
package io.pharmavigil.caseservice.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseVersionRepository extends JpaRepository<CaseVersion, Long> {

    Optional<CaseVersion> findByCaseIdAndVersionNo(Long caseId, int versionNo);

    List<CaseVersion> findByCaseIdOrderByVersionNoAsc(Long caseId);

    /** Duplicate candidates: other cases whose current version shares a drug (RxCUI or name), received since. */
    @Query("select distinct v.caseId from CaseVersion v join v.drugs d, SafetyCase c "
            + "where c.id = v.caseId and v.versionNo = c.currentVersion and c.id <> :caseId "
            + "and c.receivedDate >= :since and (d.rxcui in :rxcuis or lower(d.drugName) in :names)")
    List<Long> findDuplicateCandidates(@Param("caseId") Long caseId, @Param("since") LocalDate since,
            @Param("rxcuis") Collection<String> rxcuis, @Param("names") Collection<String> names, Limit limit);

    /** Each given case with its current version, in one query (rows of [SafetyCase, CaseVersion]). */
    @Query("select c, v from SafetyCase c, CaseVersion v where v.caseId = c.id and v.versionNo = c.currentVersion "
            + "and c.id in :ids")
    List<Object[]> findCurrentWithCase(@Param("ids") Collection<Long> ids);
}
