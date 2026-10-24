// REST API for dispensing. The response carries the FEFO lots used and the safety evidence for the drug.
package io.pharmavigil.pharmacy.dispensing;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.common.Disclaimers;
import io.pharmavigil.pharmacy.signals.SignalClient;
import io.pharmavigil.pharmacy.signals.SignalLookup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class DispensingController {

    public record DispenseRequest(@NotNull Long patientId, @NotNull Long drugId, Long prescriptionId,
            @Min(1) @Max(1000) int quantity) {
    }

    public record DispenseResponse(DispensingService.DispenseResult dispense, SignalLookup safety,
            List<String> disclaimers) {
    }

    public record OrderView(Long id, String drug, int quantity, long totalCents, String pharmacist, String createdAt) {
    }

    private final DispensingService service;
    private final SignalClient signals;
    private final DispenseOrderRepository orders;

    public DispensingController(DispensingService service, SignalClient signals, DispenseOrderRepository orders) {
        this.service = service;
        this.signals = signals;
        this.orders = orders;
    }

    @PostMapping("/dispenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PHARMACIST')")
    public DispenseResponse dispense(@Valid @RequestBody DispenseRequest req, Principal user) {
        var result = service.dispense(new DispensingService.DispenseCommand(req.patientId(), req.drugId(),
                req.prescriptionId(), req.quantity()), user.getName());
        // Looked up after the transaction commits: a slow or failing signal-service never blocks the dispense.
        return new DispenseResponse(result, signals.warningsFor(result.rxcui()),
                List.of(Disclaimers.NO_CAUSATION, Disclaimers.NOT_FOR_CLINICAL_USE, Disclaimers.SYNTHETIC_PATIENTS));
    }

    @GetMapping("/patients/{patientId}/dispenses")
    @PreAuthorize("hasRole('PHARMACIST') or (hasRole('PATIENT') and @patientAccess.isSelf(#patientId, authentication))")
    public List<OrderView> history(@PathVariable Long patientId) {
        return orders.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(o -> new OrderView(o.getId(), o.getDrug().getName(), o.getQuantity(), o.getTotalCents(),
                        o.getPharmacist(), o.getCreatedAt().toString()))
                .toList();
    }
}
