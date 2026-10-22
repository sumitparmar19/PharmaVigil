// One dispensing event at the counter: what was given to whom, by which pharmacist, from which lots.
package io.pharmavigil.pharmacy.dispensing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.Prescription;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "dispense_order")
public class DispenseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id")
    private Prescription prescription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drug_id")
    private Drug drug;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(nullable = false, length = 64)
    private String pharmacist;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DispenseLine> lines = new ArrayList<>();

    protected DispenseOrder() {
    }

    public DispenseOrder(Patient patient, Prescription prescription, Drug drug, int quantity, String pharmacist,
            Instant createdAt) {
        this.patient = patient;
        this.prescription = prescription;
        this.drug = drug;
        this.quantity = quantity;
        this.totalCents = (long) quantity * drug.getUnitPriceCents();
        this.pharmacist = pharmacist;
        this.createdAt = createdAt;
    }

    public void addLine(DispenseLine line) {
        lines.add(line);
    }

    public Long getId() { return id; }
    public Patient getPatient() { return patient; }
    public Prescription getPrescription() { return prescription; }
    public Drug getDrug() { return drug; }
    public int getQuantity() { return quantity; }
    public long getTotalCents() { return totalCents; }
    public String getPharmacist() { return pharmacist; }
    public Instant getCreatedAt() { return createdAt; }
    public List<DispenseLine> getLines() { return lines; }
}
