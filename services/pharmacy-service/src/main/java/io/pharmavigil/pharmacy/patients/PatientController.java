// Patient search at the counter and prescription entry (pharmacist only). Patients are synthetic.
package io.pharmavigil.pharmacy.patients;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.common.web.NotFoundException;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

@RestController
@RequestMapping("/api/patients")
@PreAuthorize("hasRole('PHARMACIST')")
public class PatientController {

    public record PatientView(Long id, String givenName, String familyName, LocalDate birthDate, String sex,
            boolean synthetic) {
        static PatientView of(Patient p) {
            return new PatientView(p.getId(), p.getGivenName(), p.getFamilyName(), p.getBirthDate(), p.getSex(),
                    p.isSynthetic());
        }
    }

    public record PrescriptionView(Long id, Long drugId, String drug, String prescriber, LocalDate writtenDate,
            int quantity, int remainingQuantity, String status) {
        static PrescriptionView of(Prescription p) {
            return new PrescriptionView(p.getId(), p.getDrug().getId(), p.getDrug().getName(),
                    p.getPrescriber().getName(), p.getWrittenDate(), p.getQuantity(), p.getRemainingQuantity(),
                    p.getStatus().name());
        }
    }

    public record NewPrescription(@NotNull Long drugId, @NotNull Long prescriberId, @NotNull @PastOrPresent LocalDate writtenDate,
            @Min(1) @Max(1000) int quantity) {
    }

    private final PatientRepository patients;
    private final PrescriptionRepository prescriptions;
    private final PrescriberRepository prescribers;
    private final DrugRepository drugs;

    public PatientController(PatientRepository patients, PrescriptionRepository prescriptions,
            PrescriberRepository prescribers, DrugRepository drugs) {
        this.patients = patients;
        this.prescriptions = prescriptions;
        this.prescribers = prescribers;
        this.drugs = drugs;
    }

    @GetMapping
    public List<PatientView> search(@RequestParam(defaultValue = "") String familyName) {
        return patients.findTop50ByFamilyNameStartingWithIgnoreCaseOrderByFamilyNameAscGivenNameAsc(familyName.strip())
                .stream().map(PatientView::of).toList();
    }

    @GetMapping("/{id}/prescriptions")
    @Transactional(readOnly = true)
    public List<PrescriptionView> prescriptions(@PathVariable Long id) {
        return prescriptions.findByPatientIdOrderByWrittenDateDesc(id).stream().map(PrescriptionView::of).toList();
    }

    @PostMapping("/{id}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PrescriptionView addPrescription(@PathVariable Long id, @Valid @RequestBody NewPrescription req) {
        Patient patient = patients.findById(id).orElseThrow(() -> new NotFoundException("no patient " + id));
        Drug drug = drugs.findById(req.drugId()).orElseThrow(() -> new NotFoundException("no drug " + req.drugId()));
        Prescriber prescriber = prescribers.findById(req.prescriberId())
                .orElseThrow(() -> new NotFoundException("no prescriber " + req.prescriberId()));
        return PrescriptionView.of(prescriptions.save(
                new Prescription(patient, prescriber, drug, req.writtenDate(), req.quantity())));
    }
}
