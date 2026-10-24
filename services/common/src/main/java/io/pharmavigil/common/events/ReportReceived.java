// Payload of the `report.received` event: one adverse-event report from any intake channel (pharmacy REST,
// partner SOAP, E2B(R3) import), in one canonical shape. `sourceReportId` + `source` identify the report,
// so consumers can recognise a resent report; `eventId` identifies this message for idempotent consumers.
package io.pharmavigil.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReportReceived(
        String eventId,
        String source,            // PHARMACY | PARTNER | E2B_IMPORT
        String sourceReportId,
        Instant receivedAt,
        BigDecimal patientAgeYears,
        String patientSex,        // M | F | UNK
        String reporterType,      // PHARMACIST | PHYSICIAN | OTHER_HCP | CONSUMER | ...
        String country,           // ISO 3166-1 alpha-2
        LocalDate onsetDate,
        String narrative,
        List<Drug> drugs,
        List<String> reactionTerms,
        String seriousCriteria) {

    public static final String TOPIC = "report.received";

    /** role: PS primary suspect, SS secondary suspect, C concomitant, I interacting. */
    public record Drug(String rxcui, String name, String role, String doseText, String route) {
    }
}
