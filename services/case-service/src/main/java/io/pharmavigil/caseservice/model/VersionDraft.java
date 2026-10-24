// Mutable working copy used to build the next case version: start from the latest version, change what
// is needed, then save it as a new immutable CaseVersion.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;

public class VersionDraft {

    /** Reported-term placeholder for a reaction the coder suggested from the narrative, not from a listed term. */
    public static final String NARRATIVE_TERM = "(suggested from narrative)";

    public record DrugDraft(String rxcui, String name, String role, String doseText, String route) {
    }

    public record ReactionDraft(String reportedTerm, String pt, CodeSource codeSource, String modelName,
            String modelVersion, BigDecimal modelScore) {

        public static ReactionDraft uncoded(String term) {
            return new ReactionDraft(term, null, CodeSource.NONE, null, null, null);
        }
    }

    public BigDecimal patientAge;
    public String patientSex;
    public String reporterType;
    public String country;
    public LocalDate onsetDate;
    public String reportedText;
    public String seriousCriteria;
    public List<DrugDraft> drugs = new ArrayList<>();
    public List<ReactionDraft> reactions = new ArrayList<>();

    /** True when a report carries the same content as this draft (coding results are not compared). */
    public boolean sameReportedContent(VersionDraft o) {
        return (patientAge == null ? o.patientAge == null : o.patientAge != null && patientAge.compareTo(o.patientAge) == 0)
                && Objects.equals(patientSex, o.patientSex) && Objects.equals(reporterType, o.reporterType)
                && Objects.equals(country, o.country) && Objects.equals(onsetDate, o.onsetDate)
                && Objects.equals(reportedText, o.reportedText) && Objects.equals(seriousCriteria, o.seriousCriteria)
                && drugs.equals(o.drugs)
                && reactions.stream().map(ReactionDraft::reportedTerm).toList()
                        .equals(o.reactions.stream().map(ReactionDraft::reportedTerm).toList());
    }

    public static VersionDraft from(CaseVersion v) {
        VersionDraft d = new VersionDraft();
        d.patientAge = v.getPatientAge();
        d.patientSex = v.getPatientSex();
        d.reporterType = v.getReporterType();
        d.country = v.getCountry();
        d.onsetDate = v.getOnsetDate();
        d.reportedText = v.getReportedText();
        d.seriousCriteria = v.getSeriousCriteria();
        v.getDrugs().forEach(x -> d.drugs.add(new DrugDraft(x.getRxcui(), x.getDrugName(), x.getRole(),
                x.getDoseText(), x.getRoute())));
        v.getReactions().forEach(r -> d.reactions.add(r.toDraft()));
        return d;
    }
}
