// Mutable working copy used to build the next case version: start from the latest version, change what
// is needed, then save it as a new immutable CaseVersion.
package io.pharmavigil.caseservice.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;

public class VersionDraft {

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
