// E2B(R3) export for reviewers: the XML of an approved case, with its schema check in a response header, and
// the check alone as JSON (what the console shows before the reviewer submits).
package io.pharmavigil.caseservice.e2b;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases/{id}/e2b")
@PreAuthorize("hasAnyRole('SAFETY_REVIEWER','ADMIN')")
public class E2bController {

    public record ValidationView(String safetyReportId, String outcome, boolean queueable, List<String> errors) {
    }

    private final E2bExportService exports;

    public E2bController(E2bExportService exports) {
        this.exports = exports;
    }

    @GetMapping(produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> xml(@PathVariable Long id) {
        E2bExportService.Export e = exports.export(id);
        return ResponseEntity.ok()
                .header("X-E2B-Validation", e.validation().outcome().name())
                .header("Content-Disposition", "attachment; filename=\"" + e.safetyReportId() + ".xml\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(e.xml());
    }

    @GetMapping(path = "/validation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ValidationView validation(@PathVariable Long id) {
        E2bExportService.Export e = exports.export(id);
        return new ValidationView(e.safetyReportId(), e.validation().outcome().name(), e.queueable(),
                e.validation().errors());
    }
}
