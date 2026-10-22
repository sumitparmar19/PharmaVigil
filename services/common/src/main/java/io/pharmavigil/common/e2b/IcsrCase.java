// The subset of an ICH E2B(R3) Individual Case Safety Report that PharmaVigil maps (see docs/E2B_R3.md).
// Plain records, independent of XML, shared by the exporter (case-service), the importer (intake-gateway)
// and the mock regulator (submission-gateway).
package io.pharmavigil.common.e2b;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record IcsrCase(
        String batchNumber,          // N.1.2
        String messageId,            // N.2.r.1
        String senderId,             // N.1.3 / N.2.r.2
        String receiverId,           // N.1.4 / N.2.r.3
        OffsetDateTime createdAt,    // N.1.5 / N.2.r.4 / C.1.2
        String safetyReportId,       // C.1.1 sender's (case) safety report unique identifier
        String worldwideCaseId,      // C.1.8.1 worldwide unique case identification number
        int reportType,              // C.1.3 type of report (1 = spontaneous report)
        LocalDate firstReceived,     // C.1.4 date report was first received from source
        LocalDate mostRecentReceived, // C.1.5 date of most recent information
        int reporterQualification,   // C.2.r.4 (1 physician, 2 pharmacist, 3 other HCP, 4 lawyer, 5 consumer)
        String reporterCountry,      // C.2.r.3 ISO 3166-1 alpha-2
        BigDecimal patientAgeYears,  // D.2.2a/b (unit "a")
        Integer patientSex,          // D.5 ISO 5218 (1 male, 2 female); null when unknown
        List<Reaction> reactions,    // E.i
        List<Drug> drugs,            // G.k
        String narrative) {          // H.1 case narrative

    /** E.i: one reaction. MedDRA codes are optional until the MedDRA licence is active (docs/E2B_R3.md). */
    public record Reaction(String reportedTerm, String pt, String meddraCode, String meddraVersion,
            LocalDate onset, boolean death, boolean lifeThreatening, boolean hospitalization, boolean disability,
            boolean congenitalAnomaly, boolean otherMedicallyImportant) {

        public boolean serious() {
            return death || lifeThreatening || hospitalization || disability || congenitalAnomaly
                    || otherMedicallyImportant;
        }
    }

    /** G.k: one drug. Characterization G.k.1: 1 suspect, 2 concomitant, 3 interacting. */
    public record Drug(int characterization, String name, String rxcui, String doseText, String route) {
    }
}
