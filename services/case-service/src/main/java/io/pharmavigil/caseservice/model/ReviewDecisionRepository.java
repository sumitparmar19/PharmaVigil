// Spring Data repository for ReviewDecision.
package io.pharmavigil.caseservice.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {

    List<ReviewDecision> findByCaseIdOrderByIdAsc(Long caseId);

    /** Feedback for retraining: decisions where the reviewer changed the model's suggestion. */
    List<ReviewDecision> findByAcceptedFalseOrderByIdAsc();
}
