// FEFO allocation rules, without a database.
package io.pharmavigil.pharmacy.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class FefoTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private final Drug drug = new Drug("P1", "1", "Test", null, null, false, 10);

    private Lot lot(String n, LocalDate expiry, int qty) {
        return new Lot(drug, n, expiry, qty, Instant.EPOCH);
    }

    @Test
    void takesEarliestExpiringFirstAndSpansLots() {
        Lot late = lot("late", TODAY.plusMonths(6), 10);
        Lot soon = lot("soon", TODAY.plusDays(5), 3);
        List<Fefo.Allocation> plan = Fefo.allocate(List.of(late, soon), 5, TODAY);
        assertThat(plan).extracting(a -> a.lot().getLotNumber()).containsExactly("soon", "late");
        assertThat(plan).extracting(Fefo.Allocation::units).containsExactly(3, 2);
        Fefo.apply(plan);
        assertThat(soon.getQuantity()).isZero();
        assertThat(late.getQuantity()).isEqualTo(8);
    }

    @Test
    void neverUsesExpiredOrExpiringTodayLots() {
        Lot expired = lot("expired", TODAY.minusDays(1), 100);
        Lot today = lot("today", TODAY, 100);
        Lot ok = lot("ok", TODAY.plusDays(1), 2);
        assertThat(Fefo.allocate(List.of(expired, today, ok), 3, TODAY)).isEmpty();
        assertThat(Fefo.usableStock(List.of(expired, today, ok), TODAY)).isEqualTo(2);
        assertThat(Fefo.allocate(List.of(expired, today, ok), 2, TODAY)).extracting(a -> a.lot().getLotNumber())
                .containsExactly("ok");
    }

    @Test
    void rejectsNonPositiveQuantitiesAndOverdraw() {
        assertThatThrownBy(() -> Fefo.allocate(List.of(), 0, TODAY)).isInstanceOf(IllegalArgumentException.class);
        Lot l = lot("x", TODAY.plusDays(9), 1);
        assertThatThrownBy(() -> l.take(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lot("neg", TODAY, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
