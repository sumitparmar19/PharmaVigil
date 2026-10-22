// One status change of an E2B(R3) submission (QUEUED, ACK, NACK with the regulator's reason). Append-only.
package io.pharmavigil.caseservice.model;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "submission_event")
public class SubmissionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "safety_report_id", nullable = false, length = 100)
    private String safetyReportId;

    @Column(name = "message_id", length = 100)
    private String messageId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 2000)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SubmissionEvent() {
    }

    public SubmissionEvent(Long caseId, int versionNo, String safetyReportId, String messageId, String status,
            String reason, Instant occurredAt) {
        this.caseId = caseId;
        this.versionNo = versionNo;
        this.safetyReportId = safetyReportId;
        this.messageId = messageId;
        this.status = status;
        this.reason = reason == null || reason.length() <= 2000 ? reason : reason.substring(0, 2000);
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public int getVersionNo() { return versionNo; }
    public String getSafetyReportId() { return safetyReportId; }
    public String getMessageId() { return messageId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
