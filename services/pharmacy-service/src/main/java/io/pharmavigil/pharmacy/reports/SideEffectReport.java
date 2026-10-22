// A side-effect report captured at the counter in one click (patient, drug, what happened). It is queued
// for the safety platform (intake-gateway) and keeps its delivery status.
package io.pharmavigil.pharmacy.reports;

import java.time.Instant;
import java.time.LocalDate;

import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.patients.Patient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "side_effect_report")
public class SideEffectReport {

    public enum Status { PENDING_SUBMISSION, SUBMITTED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drug_id")
    private Drug drug;

    @Column(nullable = false, length = 64)
    private String reporter;

    @Column(nullable = false, length = 4000)
    private String narrative;

    @Column(name = "onset_date")
    private LocalDate onsetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.PENDING_SUBMISSION;

    @Column(name = "external_report_id", length = 64)
    private String externalReportId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SideEffectReport() {
    }

    public SideEffectReport(Patient patient, Drug drug, String reporter, String narrative, LocalDate onsetDate,
            Instant createdAt) {
        this.patient = patient;
        this.drug = drug;
        this.reporter = reporter;
        this.narrative = narrative;
        this.onsetDate = onsetDate;
        this.createdAt = createdAt;
    }

    public void markSubmitted(String externalId) {
        this.status = Status.SUBMITTED;
        this.externalReportId = externalId;
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public Drug getDrug() { return drug; }
    public String getReporter() { return reporter; }
    public String getNarrative() { return narrative; }
    public LocalDate getOnsetDate() { return onsetDate; }
    public Status getStatus() { return status; }
    public String getExternalReportId() { return externalReportId; }
    public Instant getCreatedAt() { return createdAt; }
}
