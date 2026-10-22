// A case narrative text. Every edit is a new row (append-only); one row is approved by a reviewer before
// submission. `source` says whether it began as the template, an LLM draft or the reviewer's own text.
package io.pharmavigil.caseservice.model;

import java.time.Instant;

import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "narrative")
public class Narrative {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 4000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NarrativeSource source;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected Narrative() {
    }

    public Narrative(Long caseId, int versionNo, String body, NarrativeSource source, String modelName,
            String createdBy, Instant createdAt) {
        this.caseId = caseId;
        this.versionNo = versionNo;
        this.body = body;
        this.source = source;
        this.modelName = modelName;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public void approve(String reviewer, Instant at) {
        if (approvedBy != null) {
            throw new IllegalStateException("narrative already approved");
        }
        approvedBy = reviewer;
        approvedAt = at;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public int getVersionNo() { return versionNo; }
    public String getBody() { return body; }
    public NarrativeSource getSource() { return source; }
    public String getModelName() { return modelName; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
