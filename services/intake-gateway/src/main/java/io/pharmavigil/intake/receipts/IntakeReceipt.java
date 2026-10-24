// One accepted submission (pharmacy report, partner SOAP message or E2B file). The receipt id is what the
// sender keeps; the payload hash recognises an identical resend, and a new payload for a known report id is
// recorded as a follow-up (it becomes a new version of the same case downstream).
package io.pharmavigil.intake.receipts;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "intake_receipt")
public class IntakeReceipt {

    public enum Source { PHARMACY, PARTNER, E2B_IMPORT }

    /** RECEIVED: event written for the pipeline. Later states arrive from case events (M8). */
    public enum Status { RECEIVED, IN_REVIEW, SUBMITTED, ACKNOWLEDGED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false, length = 36)
    private String receiptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "source_report_id", nullable = false, length = 100)
    private String sourceReportId;

    @Column(name = "partner_reference", length = 100)
    private String partnerReference;

    @Column(name = "submitted_by", nullable = false, length = 100)
    private String submittedBy;

    @Column(name = "payload_sha256", nullable = false, length = 64)
    private String payloadSha256;

    @Column(name = "follow_up", nullable = false)
    private boolean followUp;

    @Column(name = "schema_checked", nullable = false)
    private boolean schemaChecked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected IntakeReceipt() {
    }

    public IntakeReceipt(String receiptId, Source source, String sourceReportId, String partnerReference,
            String submittedBy, String payloadSha256, boolean followUp, boolean schemaChecked, Instant receivedAt) {
        this.receiptId = receiptId;
        this.source = source;
        this.sourceReportId = sourceReportId;
        this.partnerReference = partnerReference;
        this.submittedBy = submittedBy;
        this.payloadSha256 = payloadSha256;
        this.followUp = followUp;
        this.schemaChecked = schemaChecked;
        this.status = Status.RECEIVED;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public String getReceiptId() { return receiptId; }
    public Source getSource() { return source; }
    public String getSourceReportId() { return sourceReportId; }
    public String getPartnerReference() { return partnerReference; }
    public String getSubmittedBy() { return submittedBy; }
    public String getPayloadSha256() { return payloadSha256; }
    public boolean isFollowUp() { return followUp; }
    public boolean isSchemaChecked() { return schemaChecked; }
    public Status getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
}
