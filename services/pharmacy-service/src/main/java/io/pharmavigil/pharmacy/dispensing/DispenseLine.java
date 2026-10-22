// The quantity of one lot used by a dispense order (an order can span lots when the first runs out).
package io.pharmavigil.pharmacy.dispensing;

import io.pharmavigil.pharmacy.catalog.Lot;
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
@Table(name = "dispense_line")
public class DispenseLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DispenseOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id")
    private Lot lot;

    @Column(nullable = false)
    private int quantity;

    protected DispenseLine() {
    }

    public DispenseLine(DispenseOrder order, Lot lot, int quantity) {
        this.order = order;
        this.lot = lot;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public Lot getLot() { return lot; }
    public int getQuantity() { return quantity; }
}
