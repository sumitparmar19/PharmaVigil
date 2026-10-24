// A reviewer's decision on one AI suggestion (a PT code, seriousness, a duplicate flag, a narrative):
// the model's suggestion with name/version/score next to the reviewer's final value. Rows where the
// reviewer changed the suggestion are the feedback set for retraining.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;
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
@Table(name = "review_decision")
public class ReviewDecision {

    public enum Field { PT, SERIOUSNESS, DUPLICATE, NARRATIVE }

    public record Suggestion(String value, String modelName, String modelVersion, BigDecimal score) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 64)
    private String reviewer;

    @Column(nullable = false, length = 24)
    private String field;

    @Column(name = "item_ref", length = 400)
    private String itemRef;

    @Column(name = "model_suggestion", length = 400)
    private String modelSuggestion;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "model_score", precision = 6, scale = 5)
    private BigDecimal modelScore;

    @Column(name = "final_value", length = 400)
    private String finalValue;

    @Column(nullable = false)
    private boolean accepted;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ReviewDecision() {
    }

    public ReviewDecision(Long caseId, int versionNo, String reviewer, Field field, String itemRef,
            Suggestion suggestion, String finalValue, Instant decidedAt) {
        this.caseId = caseId;
        this.versionNo = versionNo;
        this.reviewer = reviewer;
        this.field = field.name();
        this.itemRef = itemRef;
        if (suggestion != null) {
            this.modelSuggestion = suggestion.value();
            this.modelName = suggestion.modelName();
            this.modelVersion = suggestion.modelVersion();
            this.modelScore = suggestion.score();
        }
        this.finalValue = finalValue;
        this.accepted = suggestion != null && java.util.Objects.equals(suggestion.value(), finalValue);
        this.decidedAt = decidedAt;
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public int getVersionNo() { return versionNo; }
    public String getReviewer() { return reviewer; }
    public String getField() { return field; }
    public String getItemRef() { return itemRef; }
    public String getModelSuggestion() { return modelSuggestion; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public BigDecimal getModelScore() { return modelScore; }
    public String getFinalValue() { return finalValue; }
    public boolean isAccepted() { return accepted; }
    public Instant getDecidedAt() { return decidedAt; }
}
