// Records a one-click side-effect report. Text is stored exactly as typed (never rendered as HTML by the
// server; the UI escapes it). Delivery to the safety platform is added with the Kafka outbox (M8).
package io.pharmavigil.pharmacy.reports;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.pharmavigil.common.web.NotFoundException;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.PatientRepository;

@Service
public class ReportService {

    private final SideEffectReportRepository reports;
    private final PatientRepository patients;
    private final DrugRepository drugs;
    private final Clock clock;

    public ReportService(SideEffectReportRepository reports, PatientRepository patients, DrugRepository drugs,
            Clock clock) {
        this.reports = reports;
        this.patients = patients;
        this.drugs = drugs;
        this.clock = clock;
    }

    @Transactional
    public SideEffectReport create(Long patientId, Long drugId, String narrative, LocalDate onsetDate, String reporter) {
        Patient patient = patients.findById(patientId).orElseThrow(() -> new NotFoundException("no patient " + patientId));
        Drug drug = drugs.findById(drugId).orElseThrow(() -> new NotFoundException("no drug " + drugId));
        if (onsetDate != null && onsetDate.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("onset date cannot be in the future");
        }
        return reports.save(new SideEffectReport(patient, drug, reporter, narrative.strip(), onsetDate, clock.instant()));
    }
}
