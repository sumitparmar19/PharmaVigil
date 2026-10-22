// Dispensing on a real database: FEFO across lots, expired lots never sold, the Rx rule, prescription
// draw-down, graceful signal fallback, and 50 concurrent dispenses that can never oversell.
package io.pharmavigil.pharmacy.dispensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.web.ConflictException;
import io.pharmavigil.pharmacy.DbTest;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.Lot;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.Prescription;
import io.pharmavigil.pharmacy.patients.PrescriptionRepository;

class DispensingIntegrationTest extends DbTest {

    @Autowired DispensingService service;
    @Autowired PrescriptionRepository prescriptions;

    private String body(Long patient, Long drug, Long rx, int qty) {
        return "{\"patientId\":" + patient + ",\"drugId\":" + drug + ",\"prescriptionId\":" + rx
                + ",\"quantity\":" + qty + "}";
    }

    @Test
    void dispensesFefoAcrossLotsAndShowsSafetyFallback() throws Exception {
        Drug d = drug(false);
        Lot soon = lot(d, LocalDate.now().plusDays(20), 3);
        Lot later = lot(d, LocalDate.now().plusMonths(8), 50);
        Patient p = patient();
        mvc.perform(post("/api/dispenses").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(body(p.getId(), d.getId(), null, 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispense.lots[0].lotNumber").value(soon.getLotNumber()))
                .andExpect(jsonPath("$.dispense.lots[0].quantity").value(3))
                .andExpect(jsonPath("$.dispense.lots[1].quantity").value(2))
                .andExpect(jsonPath("$.dispense.totalCents").value(125))
                .andExpect(jsonPath("$.safety.available").value(false))
                .andExpect(jsonPath("$.disclaimers[0]").value("Spontaneous reports do not prove that a drug caused a reaction."));
        assertThat(lots.findById(soon.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(lots.findById(later.getId()).orElseThrow().getQuantity()).isEqualTo(48);
    }

    @Test
    void expiredLotIsNeverDispensedEvenWhenItIsTheOnlyStock() throws Exception {
        Drug d = drug(false);
        Lot expired = lot(d, LocalDate.now().minusDays(1), 100);
        Patient p = patient();
        mvc.perform(post("/api/dispenses").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(body(p.getId(), d.getId(), null, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Only 0 in-date units")));
        assertThat(lots.findById(expired.getId()).orElseThrow().getQuantity()).isEqualTo(100);
    }

    @Test
    void prescriptionOnlyDrugNeedsAMatchingPrescription() throws Exception {
        Drug d = drug(true);
        lot(d, LocalDate.now().plusMonths(3), 30);
        Patient p = patient();
        Patient someoneElse = patient();
        mvc.perform(post("/api/dispenses").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(body(p.getId(), d.getId(), null, 1)))
                .andExpect(status().isBadRequest());
        Prescription rx = prescriptions.save(new Prescription(someoneElse, prescriber(), d, LocalDate.now(), 10));
        mvc.perform(post("/api/dispenses").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(body(p.getId(), d.getId(), rx.getId(), 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prescriptionIsDrawnDownAndCompleted() {
        Drug d = drug(true);
        lot(d, LocalDate.now().plusMonths(3), 30);
        Patient p = patient();
        Prescription rx = prescriptions.save(new Prescription(p, prescriber(), d, LocalDate.now(), 4));
        service.dispense(new DispensingService.DispenseCommand(p.getId(), d.getId(), rx.getId(), 3), "ph");
        try {
            service.dispense(new DispensingService.DispenseCommand(p.getId(), d.getId(), rx.getId(), 2), "ph");
        } catch (ConflictException expected) {
            assertThat(expected.getMessage()).contains("1 units remaining");
        }
        service.dispense(new DispensingService.DispenseCommand(p.getId(), d.getId(), rx.getId(), 1), "ph");
        assertThat(prescriptions.findById(rx.getId()).orElseThrow().getStatus()).isEqualTo(Prescription.Status.COMPLETED);
    }

    @Test
    void fiftyConcurrentDispensesNeverOversell() throws Exception {
        Drug d = drug(false);
        Lot a = lot(d, LocalDate.now().plusDays(30), 10);
        Lot b = lot(d, LocalDate.now().plusDays(90), 20);
        Lot expired = lot(d, LocalDate.now().minusDays(3), 1000);
        Patient p = patient();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Callable<Boolean> task = () -> {
                try {
                    service.dispense(new DispensingService.DispenseCommand(p.getId(), d.getId(), null, 1), "ph");
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        int ok = 0;
        for (Future<Boolean> f : results) {
            ok += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(30);
        assertThat(lots.findById(a.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(lots.findById(b.getId()).orElseThrow().getQuantity()).isZero();
        assertThat(lots.findById(expired.getId()).orElseThrow().getQuantity()).isEqualTo(1000);
    }
}
