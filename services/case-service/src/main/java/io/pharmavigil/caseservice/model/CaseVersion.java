// One immutable snapshot of a case's content. Any change creates the next version (append-only); Hibernate
// is told the entity is @Immutable, so even an accidental update is never written.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "case_version")
public class CaseVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(name = "patient_age", precision = 5, scale = 1)
    private BigDecimal patientAge;

    @Column(name = "patient_sex", length = 8)
    private String patientSex;

    @Column(name = "reporter_type", length = 32)
    private String reporterType;

    @Column(length = 2)
    private String country;

    @Column(name = "onset_date")
    private LocalDate onsetDate;

    @Column(name = "reported_text", nullable = false, length = 4000)
    private String reportedText;

    @Column(name = "serious_criteria", length = 200)
    private String seriousCriteria;

    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<CaseDrug> drugs = new ArrayList<>();

    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<CaseReaction> reactions = new ArrayList<>();

    protected CaseVersion() {
    }

    public CaseVersion(Long caseId, int versionNo, String createdBy, Instant createdAt, String reason, VersionDraft d) {
        this.caseId = caseId;
        this.versionNo = versionNo;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.reason = reason;
        this.patientAge = d.patientAge;
        this.patientSex = d.patientSex;
        this.reporterType = d.reporterType;
        this.country = d.country;
        this.onsetDate = d.onsetDate;
        this.reportedText = d.reportedText;
        this.seriousCriteria = d.seriousCriteria;
        d.drugs.forEach(x -> drugs.add(new CaseDrug(x.rxcui(), x.name(), x.role(), x.doseText(), x.route())));
        d.reactions.forEach(r -> reactions.add(new CaseReaction(r)));
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public int getVersionNo() { return versionNo; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReason() { return reason; }
    public BigDecimal getPatientAge() { return patientAge; }
    public String getPatientSex() { return patientSex; }
    public String getReporterType() { return reporterType; }
    public String getCountry() { return country; }
    public LocalDate getOnsetDate() { return onsetDate; }
    public String getReportedText() { return reportedText; }
    public String getSeriousCriteria() { return seriousCriteria; }
    public List<CaseDrug> getDrugs() { return drugs; }
    public List<CaseReaction> getReactions() { return reactions; }
}
