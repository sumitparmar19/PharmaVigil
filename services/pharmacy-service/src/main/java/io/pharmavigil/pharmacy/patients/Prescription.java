// A prescription for an Rx-only drug: dispensing draws down `remainingQuantity` until it is completed.
package io.pharmavigil.pharmacy.patients;

import java.time.LocalDate;

import io.pharmavigil.pharmacy.catalog.Drug;
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
@Table(name = "prescription")
public class Prescription {

    public enum Status { ACTIVE, COMPLETED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescriber_id")
    private Prescriber prescriber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drug_id")
    private Drug drug;

    @Column(name = "written_date", nullable = false)
    private LocalDate writtenDate;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    protected Prescription() {
    }

    public Prescription(Patient patient, Prescriber prescriber, Drug drug, LocalDate writtenDate, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("prescription quantity must be positive");
        }
        this.patient = patient;
        this.prescriber = prescriber;
        this.drug = drug;
        this.writtenDate = writtenDate;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
    }

    /** Draw down the prescription; it completes when nothing remains. */
    public void fill(int units) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("prescription is " + status);
        }
        if (units > remainingQuantity) {
            throw new IllegalStateException("only " + remainingQuantity + " units remain on the prescription");
        }
        remainingQuantity -= units;
        if (remainingQuantity == 0) {
            status = Status.COMPLETED;
        }
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public Prescriber getPrescriber() { return prescriber; }
    public Drug getDrug() { return drug; }
    public LocalDate getWrittenDate() { return writtenDate; }
    public int getQuantity() { return quantity; }
    public int getRemainingQuantity() { return remainingQuantity; }
    public Status getStatus() { return status; }
}
