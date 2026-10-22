// The structured, already-coded facts a narrative may use (engineering rule 6): demographics, dates, drugs,
// coded reactions and seriousness. The reporter's free text is deliberately not included, and no derived
// numbers (intervals, ages computed from dates) are added, so the language model has nothing to calculate.
package io.pharmavigil.caseservice.narrative;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.SafetyCase;

public record CaseFacts(String caseNumber, BigDecimal ageYears, String sex, String reporterType, String country,
        LocalDate receivedDate, LocalDate onsetDate, List<Drug> drugs, List<String> reactions, String seriousness,
        String seriousCriteria) {

    public record Drug(String name, String role, String dose, String route) {
    }

    public static CaseFacts of(SafetyCase c, CaseVersion v) {
        return new CaseFacts(c.getCaseNumber(), v.getPatientAge(), v.getPatientSex(), v.getReporterType(),
                v.getCountry(), c.getReceivedDate(), v.getOnsetDate(),
                v.getDrugs().stream().map(d -> new Drug(d.getDrugName(), d.getRole(), d.getDoseText(), d.getRoute()))
                        .toList(),
                v.getReactions().stream().map(r -> r.getPt() != null ? r.getPt() : "uncoded reaction").toList(),
                c.getSeriousness().name(), v.getSeriousCriteria());
    }
}
