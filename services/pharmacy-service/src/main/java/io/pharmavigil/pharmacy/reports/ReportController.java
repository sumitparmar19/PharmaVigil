// REST API for side-effect reports (pharmacist creates; pharmacist/admin list).
package io.pharmavigil.pharmacy.reports;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    public record ReportRequest(@NotNull Long patientId, @NotNull Long drugId,
            @NotBlank @Size(max = 4000) String narrative, LocalDate onsetDate) {
    }

    public record ReportView(Long id, Long patientId, String drug, String rxcui, String narrative, LocalDate onsetDate,
            String status, String reporter, String createdAt) {

        static ReportView of(SideEffectReport r) {
            return new ReportView(r.getId(), r.getPatient().getId(), r.getDrug().getName(), r.getDrug().getRxcui(),
                    r.getNarrative(), r.getOnsetDate(), r.getStatus().name(), r.getReporter(),
                    r.getCreatedAt().toString());
        }
    }

    private final ReportService service;
    private final SideEffectReportRepository reports;

    public ReportController(ReportService service, SideEffectReportRepository reports) {
        this.service = service;
        this.reports = reports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PHARMACIST')")
    public ReportView create(@Valid @RequestBody ReportRequest req, Principal user) {
        return ReportView.of(service.create(req.patientId(), req.drugId(), req.narrative(), req.onsetDate(),
                user.getName()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACIST','ADMIN')")
    public List<ReportView> recent() {
        return reports.findRecent().stream().limit(200).map(ReportView::of).toList();
    }
}
