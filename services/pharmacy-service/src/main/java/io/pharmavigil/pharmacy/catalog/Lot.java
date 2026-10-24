// A received batch of one drug with its expiry date and on-hand quantity (never negative: DB CHECK).
package io.pharmavigil.pharmacy.catalog;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "lot")
public class Lot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drug_id")
    private Drug drug;

    @Column(name = "lot_number", nullable = false, length = 64)
    private String lotNumber;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected Lot() {
    }

    public Lot(Drug drug, String lotNumber, LocalDate expiryDate, int quantity, Instant receivedAt) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
        this.drug = drug;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.quantity = quantity;
        this.receivedAt = receivedAt;
    }

    /** Usable on `today` only if it expires after today. */
    public boolean isDispensableOn(LocalDate today) {
        return expiryDate.isAfter(today) && quantity > 0;
    }

    void take(int units) {
        if (units <= 0 || units > quantity) {
            throw new IllegalArgumentException("cannot take " + units + " from lot " + lotNumber);
        }
        quantity -= units;
    }

    public Long getId() { return id; }
    public Drug getDrug() { return drug; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public int getQuantity() { return quantity; }
    public Instant getReceivedAt() { return receivedAt; }
}
