// A reaction in one case version: the reporter's words, the MedDRA PT once coded, and where the code came
// from (model suggestion with name/version/score, reviewer, or import). Immutable like its version.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "case_reaction")
public class CaseReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reported_term", nullable = false, length = 400)
    private String reportedTerm;

    @Column(length = 200)
    private String pt;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_source", nullable = false, length = 16)
    private CodeSource codeSource;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "model_score", precision = 6, scale = 5)
    private BigDecimal modelScore;

    protected CaseReaction() {
    }

    CaseReaction(VersionDraft.ReactionDraft r) {
        this.reportedTerm = r.reportedTerm();
        this.pt = r.pt();
        this.codeSource = r.codeSource();
        this.modelName = r.modelName();
        this.modelVersion = r.modelVersion();
        this.modelScore = r.modelScore();
    }

    public VersionDraft.ReactionDraft toDraft() {
        return new VersionDraft.ReactionDraft(reportedTerm, pt, codeSource, modelName, modelVersion, modelScore);
    }

    public Long getId() { return id; }
    public String getReportedTerm() { return reportedTerm; }
    public String getPt() { return pt; }
    public CodeSource getCodeSource() { return codeSource; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public BigDecimal getModelScore() { return modelScore; }
}
