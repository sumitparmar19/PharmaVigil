// REST intake. Pharmacy-service forwards each one-click report (already de-identified: age, sex and drug
// codes only, no names) to POST /api/intake/reports; safety reviewers upload E2B(R3) files to
// POST /api/intake/e2b. Both return receipts; GET /api/intake/receipts/{id} looks one up.
package io.pharmavigil.intake.rest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.web.NotFoundException;
import io.pharmavigil.intake.IntakeService;
import io.pharmavigil.intake.receipts.IntakeReceipt;
import io.pharmavigil.intake.receipts.IntakeReceiptRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    public record DrugIn(@Size(max = 16) String rxcui, @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "PS|SS|C|I") String role, @Size(max = 200) String doseText,
            @Size(max = 60) String route) {
    }

    public record PharmacyReportIn(
            @NotBlank @Size(max = 100) String pharmacyReportId,
            @DecimalMin("0") @DecimalMax("130") BigDecimal patientAgeYears,
            @Pattern(regexp = "M|F|UNK") String patientSex,
            @Pattern(regexp = "[A-Z]{2}") String country,
            @PastOrPresent LocalDate onsetDate,
            @NotBlank @Size(max = 4000) String narrative,
            @NotEmpty @Size(max = 20) List<@Valid DrugIn> drugs,
            @Size(max = 20) List<@NotBlank @Size(max = 200) String> reactionTerms) {
    }

    public record ReceiptView(String receiptId, String source, String sourceReportId, String status,
            boolean duplicate, boolean followUp, boolean schemaChecked, List<String> warnings) {

        static ReceiptView of(IntakeService.Accepted a) {
            IntakeReceipt r = a.receipt();
            return new ReceiptView(r.getReceiptId(), r.getSource().name(), r.getSourceReportId(), r.getStatus().name(),
                    a.duplicate(), r.isFollowUp(), r.isSchemaChecked(), a.warnings());
        }
    }

    private final IntakeService intake;
    private final IntakeReceiptRepository receipts;

    public IntakeController(IntakeService intake, IntakeReceiptRepository receipts) {
        this.intake = intake;
        this.receipts = receipts;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('PHARMACIST','ADMIN')")
    public ReceiptView report(@Valid @RequestBody PharmacyReportIn in, Authentication auth) {
        List<ReportReceived.Drug> drugs = in.drugs().stream()
                .map(d -> new ReportReceived.Drug(d.rxcui(), d.name(), d.role() == null ? "PS" : d.role(),
                        d.doseText(), d.route()))
                .toList();
        ReportReceived report = new ReportReceived(null, null, in.pharmacyReportId(), null, in.patientAgeYears(),
                in.patientSex() == null ? "UNK" : in.patientSex(), "PHARMACIST",
                in.country() == null ? "US" : in.country(), in.onsetDate(), in.narrative().strip(), drugs,
                in.reactionTerms() == null ? List.of() : in.reactionTerms(), null);
        return ReceiptView.of(intake.submitReport(report, auth.getName()));
    }

    @PostMapping(path = "/e2b", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SAFETY_REVIEWER','ADMIN')")
    public List<ReceiptView> e2b(@RequestBody String xml, Authentication auth) {
        return intake.submitE2b(xml, IntakeReceipt.Source.E2B_IMPORT, null, auth.getName(), false).stream()
                .map(ReceiptView::of).toList();
    }

    @GetMapping("/receipts/{receiptId}")
    @PreAuthorize("hasAnyRole('PHARMACIST','SAFETY_REVIEWER','ADMIN')")
    public ReceiptView receipt(@PathVariable String receiptId) {
        IntakeReceipt r = receipts.findByReceiptId(receiptId)
                .orElseThrow(() -> new NotFoundException("no receipt " + receiptId));
        return ReceiptView.of(new IntakeService.Accepted(r, false, List.of()));
    }

    @ExceptionHandler(IntakeService.RejectedException.class)
    ProblemDetail rejected(IntakeService.RejectedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Submission rejected");
        pd.setProperty("reasons", e.reasons());
        return pd;
    }
}
