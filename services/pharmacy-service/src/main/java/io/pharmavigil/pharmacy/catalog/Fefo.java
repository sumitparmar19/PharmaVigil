// First-expiry-first-out allocation: take from the soonest-expiring usable lots first. Pure logic, so
// it is unit-tested apart from the database.
package io.pharmavigil.pharmacy.catalog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Fefo {

    public record Allocation(Lot lot, int units) {
    }

    private Fefo() {
    }

    /** Allocate `units` across lots; empty when the usable stock is insufficient. */
    public static List<Allocation> allocate(List<Lot> lots, int units, LocalDate today) {
        if (units <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        List<Lot> usable = lots.stream().filter(l -> l.isDispensableOn(today))
                .sorted(Comparator.comparing(Lot::getExpiryDate).thenComparing(Lot::getLotNumber)).toList();
        int available = usable.stream().mapToInt(Lot::getQuantity).sum();
        if (available < units) {
            return List.of();
        }
        List<Allocation> out = new ArrayList<>();
        int remaining = units;
        for (Lot lot : usable) {
            int take = Math.min(lot.getQuantity(), remaining);
            out.add(new Allocation(lot, take));
            remaining -= take;
            if (remaining == 0) {
                break;
            }
        }
        return out;
    }

    /** Apply an allocation (decrements the lots). */
    public static void apply(List<Allocation> allocations) {
        allocations.forEach(a -> a.lot().take(a.units()));
    }

    public static int usableStock(List<Lot> lots, LocalDate today) {
        return lots.stream().filter(l -> l.isDispensableOn(today)).mapToInt(Lot::getQuantity).sum();
    }
}
