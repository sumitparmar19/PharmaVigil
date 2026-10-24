// Turns an imported E2B(R3) ICSR into the canonical report.received payload, so partner messages and E2B
// files enter the same pipeline as pharmacy reports. Code lists follow the E2B(R3) guide: reporter
// qualification C.2.r.4, ISO 5218 sex, drug characterization G.k.1 and the six seriousness criteria of E.i.3.2.
package io.pharmavigil.common.e2b;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.pharmavigil.common.events.ReportReceived;

public final class IcsrMapping {

    public static final String DEATH = "DEATH";
    public static final String LIFE_THREATENING = "LIFE_THREATENING";
    public static final String HOSPITALIZATION = "HOSPITALIZATION";
    public static final String DISABILITY = "DISABILITY";
    public static final String CONGENITAL_ANOMALY = "CONGENITAL_ANOMALY";
    public static final String OTHER_MEDICALLY_IMPORTANT = "OTHER_MEDICALLY_IMPORTANT";

    private IcsrMapping() {
    }

    public static ReportReceived toReport(IcsrCase c, String source, String eventId, Instant receivedAt) {
        List<ReportReceived.Drug> drugs = new ArrayList<>();
        boolean primaryTaken = false;
        for (IcsrCase.Drug d : c.drugs()) {
            String role = switch (d.characterization()) {
                case 2 -> "C";
                case 3 -> "I";
                default -> primaryTaken ? "SS" : "PS";
            };
            primaryTaken |= "PS".equals(role);
            drugs.add(new ReportReceived.Drug(d.rxcui(), d.name(), role, d.doseText(), d.route()));
        }
        List<String> terms = c.reactions().stream()
                .map(r -> r.reportedTerm() != null && !r.reportedTerm().isBlank() ? r.reportedTerm() : r.pt())
                .filter(t -> t != null && !t.isBlank())
                .toList();
        String serious = seriousCriteria(c.reactions());
        return new ReportReceived(eventId, source, c.safetyReportId(), receivedAt, c.patientAgeYears(),
                sex(c.patientSex()), reporterType(c.reporterQualification()), c.reporterCountry(),
                c.reactions().stream().map(IcsrCase.Reaction::onset).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                c.narrative(), drugs, terms, serious.isEmpty() ? null : serious);
    }

    /** Comma-separated criteria met by any reaction, in E2B order; empty when none. */
    public static String seriousCriteria(List<IcsrCase.Reaction> reactions) {
        List<String> out = new ArrayList<>();
        if (reactions.stream().anyMatch(IcsrCase.Reaction::death)) out.add(DEATH);
        if (reactions.stream().anyMatch(IcsrCase.Reaction::lifeThreatening)) out.add(LIFE_THREATENING);
        if (reactions.stream().anyMatch(IcsrCase.Reaction::hospitalization)) out.add(HOSPITALIZATION);
        if (reactions.stream().anyMatch(IcsrCase.Reaction::disability)) out.add(DISABILITY);
        if (reactions.stream().anyMatch(IcsrCase.Reaction::congenitalAnomaly)) out.add(CONGENITAL_ANOMALY);
        if (reactions.stream().anyMatch(IcsrCase.Reaction::otherMedicallyImportant)) out.add(OTHER_MEDICALLY_IMPORTANT);
        return String.join(",", out);
    }

    public static String sex(Integer iso5218) {
        if (iso5218 == null) {
            return "UNK";
        }
        return switch (iso5218) {
            case 1 -> "M";
            case 2 -> "F";
            default -> "UNK";
        };
    }

    public static Integer sexCode(String sex) {
        if (sex == null) {
            return null;
        }
        return switch (sex) {
            case "M" -> 1;
            case "F" -> 2;
            default -> null;
        };
    }

    public static String reporterType(int qualification) {
        return switch (qualification) {
            case 1 -> "PHYSICIAN";
            case 2 -> "PHARMACIST";
            case 3 -> "OTHER_HCP";
            case 4 -> "LAWYER";
            default -> "CONSUMER";
        };
    }

    public static int qualification(String reporterType) {
        if (reporterType == null) {
            return 5;
        }
        return switch (reporterType) {
            case "PHYSICIAN" -> 1;
            case "PHARMACIST" -> 2;
            case "OTHER_HCP" -> 3;
            case "LAWYER" -> 4;
            default -> 5;
        };
    }

    /** G.k.1 characterization for a case drug role (PS/SS suspect, C concomitant, I interacting). */
    public static int characterization(String role) {
        if (role == null) {
            return 1;
        }
        return switch (role) {
            case "C" -> 2;
            case "I" -> 3;
            default -> 1;
        };
    }
}
