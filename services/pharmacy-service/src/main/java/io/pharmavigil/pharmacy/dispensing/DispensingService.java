// Dispensing at the counter. In one transaction: check the Rx rule, row-lock the drug's in-date lots in
// FEFO order, allocate, decrement stock and the prescription, and record the order. Expired lots are never
// selected, stock cannot go negative (lock + CHECK constraint), and the signal lookup never blocks a dispense.
package io.pharmavigil.pharmacy.dispensing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.common.web.ConflictException;
import io.pharmavigil.common.web.NotFoundException;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import io.pharmavigil.pharmacy.catalog.Fefo;
import io.pharmavigil.pharmacy.catalog.Lot;
import io.pharmavigil.pharmacy.catalog.LotRepository;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.PatientRepository;
import io.pharmavigil.pharmacy.patients.Prescription;
import io.pharmavigil.pharmacy.patients.PrescriptionRepository;

@Service
public class DispensingService {

    public record DispenseCommand(Long patientId, Long drugId, Long prescriptionId, int quantity) {
    }

    public record LineView(String lotNumber, LocalDate expiryDate, int quantity) {
    }

    public record DispenseResult(Long orderId, String drug, String rxcui, int quantity, long totalCents,
            List<LineView> lots) {
    }

    private final DrugRepository drugs;
    private final LotRepository lots;
    private final PatientRepository patients;
    private final PrescriptionRepository prescriptions;
    private final DispenseOrderRepository orders;
    private final Clock clock;

    public DispensingService(DrugRepository drugs, LotRepository lots, PatientRepository patients,
            PrescriptionRepository prescriptions, DispenseOrderRepository orders, Clock clock) {
        this.drugs = drugs;
        this.lots = lots;
        this.patients = patients;
        this.prescriptions = prescriptions;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional
    public DispenseResult dispense(DispenseCommand cmd, String pharmacist) {
        if (cmd.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Drug drug = drugs.findById(cmd.drugId()).orElseThrow(() -> new NotFoundException("no drug " + cmd.drugId()));
        Patient patient = patients.findById(cmd.patientId())
                .orElseThrow(() -> new NotFoundException("no patient " + cmd.patientId()));
        Prescription rx = checkPrescription(drug, patient, cmd);

        LocalDate today = LocalDate.now(clock);
        List<Lot> locked = lots.lockDispensable(drug.getId(), today);
        List<Fefo.Allocation> plan = Fefo.allocate(locked, cmd.quantity(), today);
        if (plan.isEmpty()) {
            throw new ConflictException("Only " + Fefo.usableStock(locked, today) + " in-date units of " + drug.getName()
                    + " are in stock; " + cmd.quantity() + " were requested.");
        }
        Fefo.apply(plan);
        if (rx != null) {
            rx.fill(cmd.quantity());
        }
        DispenseOrder order = new DispenseOrder(patient, rx, drug, cmd.quantity(), pharmacist, clock.instant());
        plan.forEach(a -> order.addLine(new DispenseLine(order, a.lot(), a.units())));
        orders.save(order);
        return new DispenseResult(order.getId(), drug.getName(), drug.getRxcui(), cmd.quantity(), order.getTotalCents(),
                plan.stream().map(a -> new LineView(a.lot().getLotNumber(), a.lot().getExpiryDate(), a.units())).toList());
    }

    private Prescription checkPrescription(Drug drug, Patient patient, DispenseCommand cmd) {
        if (!drug.isRxOnly()) {
            return null; // over-the-counter
        }
        if (cmd.prescriptionId() == null) {
            throw new IllegalArgumentException(drug.getName() + " is prescription-only; a prescription is required");
        }
        Prescription rx = prescriptions.lockById(cmd.prescriptionId())
                .orElseThrow(() -> new NotFoundException("no prescription " + cmd.prescriptionId()));
        if (!rx.getPatient().getId().equals(patient.getId()) || !rx.getDrug().getId().equals(drug.getId())) {
            throw new IllegalArgumentException("the prescription is for a different patient or drug");
        }
        if (rx.getStatus() != Prescription.Status.ACTIVE || rx.getRemainingQuantity() < cmd.quantity()) {
            throw new ConflictException("the prescription has " + rx.getRemainingQuantity() + " units remaining ("
                    + rx.getStatus() + ")");
        }
        return rx;
    }
}
