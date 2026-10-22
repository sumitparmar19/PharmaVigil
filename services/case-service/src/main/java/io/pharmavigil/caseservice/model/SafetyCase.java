// The head of a safety case: identity, workflow status, seriousness, the 15/90-day clock and a pointer to
// the latest version. Case content lives in append-only CaseVersion rows. `lockVersion` gives optimistic
// locking, so two reviewers editing at once cannot silently overwrite each other.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "safety_case")
public class SafetyCase {

    /** Days to the reporting deadline: 15 for serious (or not yet known), 90 otherwise (internal target). */
    public static final int SERIOUS_DAYS = 15;
    public static final int NON_SERIOUS_DAYS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_number", nullable = false, length = 32)
    private String caseNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "source_report_id", nullable = false, length = 100)
    private String sourceReportId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.RECEIVED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Seriousness seriousness = Seriousness.UNKNOWN;

    @Column(name = "serious_score", precision = 6, scale = 5)
    private BigDecimal seriousScore;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "duplicate_of_id")
    private Long duplicateOfId;

    @Column(name = "duplicate_score", precision = 6, scale = 5)
    private BigDecimal duplicateScore;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "intake_received_at")
    private Instant intakeReceivedAt;

    protected SafetyCase() {
    }

    public SafetyCase(String caseNumber, Source source, String sourceReportId, LocalDate receivedDate, Instant now) {
        this.caseNumber = caseNumber;
        this.source = source;
        this.sourceReportId = sourceReportId;
        this.receivedDate = receivedDate;
        this.dueDate = receivedDate.plusDays(SERIOUS_DAYS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void moveTo(Status next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("a case cannot go from " + status + " to " + next);
        }
        status = next;
        updatedAt = now;
    }

    public void setSeriousness(Seriousness s, BigDecimal score, Instant now) {
        seriousness = s;
        seriousScore = score;
        dueDate = receivedDate.plusDays(s == Seriousness.NON_SERIOUS ? NON_SERIOUS_DAYS : SERIOUS_DAYS);
        updatedAt = now;
    }

    public void markDuplicateOf(Long otherId, BigDecimal score, Instant now) {
        duplicateOfId = otherId;
        duplicateScore = score;
        updatedAt = now;
    }

    public void advanceVersion(Instant now) {
        currentVersion++;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public String getCaseNumber() { return caseNumber; }
    public Source getSource() { return source; }
    public String getSourceReportId() { return sourceReportId; }
    public Status getStatus() { return status; }
    public Seriousness getSeriousness() { return seriousness; }
    public BigDecimal getSeriousScore() { return seriousScore; }
    public LocalDate getReceivedDate() { return receivedDate; }
    public LocalDate getDueDate() { return dueDate; }
    public int getCurrentVersion() { return currentVersion; }
    public Long getDuplicateOfId() { return duplicateOfId; }
    public BigDecimal getDuplicateScore() { return duplicateScore; }
    public int getLockVersion() { return lockVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getIntakeReceivedAt() { return intakeReceivedAt; }
    public void setIntakeReceivedAt(Instant at) { this.intakeReceivedAt = at; }
    public Instant getUpdatedAt() { return updatedAt; }
}
