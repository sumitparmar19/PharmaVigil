// Submission messages by JMS message id and by case.
package io.pharmavigil.submission.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SubmissionMessageRepository extends JpaRepository<SubmissionMessage, Long> {

    Optional<SubmissionMessage> findByMessageId(String messageId);

    /** Row lock: the QUEUED step and an early answer must not interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from SubmissionMessage m where m.messageId = :messageId")
    Optional<SubmissionMessage> lockByMessageId(@Param("messageId") String messageId);

    List<SubmissionMessage> findByCaseIdOrderByIdAsc(long caseId);
}
