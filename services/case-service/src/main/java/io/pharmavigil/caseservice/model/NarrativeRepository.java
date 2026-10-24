// Spring Data repository for Narrative.
package io.pharmavigil.caseservice.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NarrativeRepository extends JpaRepository<Narrative, Long> {

    List<Narrative> findByCaseIdOrderByIdAsc(Long caseId);
}
