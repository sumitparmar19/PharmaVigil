// Side-effect reports, newest first.
package io.pharmavigil.pharmacy.reports;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SideEffectReportRepository extends JpaRepository<SideEffectReport, Long> {

    @Query("select r from SideEffectReport r join fetch r.drug join fetch r.patient order by r.createdAt desc")
    List<SideEffectReport> findRecent();

    /** Oldest reports still waiting for the safety platform (the forwarder's work list). */
    List<SideEffectReport> findTop50ByStatusOrderByIdAsc(SideEffectReport.Status status);
}
