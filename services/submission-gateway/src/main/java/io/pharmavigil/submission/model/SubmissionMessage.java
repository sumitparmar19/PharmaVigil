// One E2B(R3) message sent to the regulator: SENDING while the JMS send is in flight, QUEUED once it committed,
// then ACK or NACK with the regulator's reason.
package io.pharmavigil.submission.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "submission_message")
public class SubmissionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 100)
    private String messageId;

    @Column(name = "case_id", nullable = false)
    private long caseId;

    @Column(name = "case_number", nullable = false, length = 40)
    private String caseNumber;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "safety_report_id", nullable = false, length = 100)
    private String safetyReportId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 2000)
    private String reason;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected SubmissionMessage() {
    }

    public SubmissionMessage(String messageId, long caseId, String caseNumber, int versionNo, String safetyReportId,
            Instant queuedAt) {
        this.messageId = messageId;
        this.caseId = caseId;
        this.caseNumber = caseNumber;
        this.versionNo = versionNo;
        this.safetyReportId = safetyReportId;
        this.status = "SENDING";
        this.queuedAt = queuedAt;
    }

    /** The JMS send committed. */
    public void queued() {
        this.status = "QUEUED";
    }

    public void answer(String status, String reason, Instant at) {
        this.status = status;
        this.reason = reason == null || reason.length() <= 2000 ? reason : reason.substring(0, 2000);
        this.answeredAt = at;
    }

    public boolean answered() {
        return answeredAt != null;
    }

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public long getCaseId() { return caseId; }
    public String getCaseNumber() { return caseNumber; }
    public int getVersionNo() { return versionNo; }
    public String getSafetyReportId() { return safetyReportId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getAnsweredAt() { return answeredAt; }
}
