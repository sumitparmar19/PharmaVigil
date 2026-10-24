// Deterministic narrative used when no language model is configured or the model call fails. It states the
// facts in the usual order of an ICSR narrative (patient, drugs, reactions, seriousness) and nothing more.
package io.pharmavigil.caseservice.narrative;

import java.util.stream.Collectors;

public final class TemplateNarrative {

    private TemplateNarrative() {
    }

    public static String render(CaseFacts f) {
        StringBuilder s = new StringBuilder();
        String sex = switch (f.sex() == null ? "UNK" : f.sex()) {
            case "F" -> "female";
            case "M" -> "male";
            default -> "patient of unreported sex";
        };
        s.append("This ").append(reporter(f.reporterType())).append(" report");
        if (f.country() != null) {
            s.append(" from ").append(f.country());
        }
        s.append(" concerns a ");
        s.append(f.ageYears() == null ? "" : f.ageYears().stripTrailingZeros().toPlainString() + "-year-old ");
        s.append(sex).append(". ");
        if (!f.drugs().isEmpty()) {
            s.append("Reported medicines: ").append(f.drugs().stream().map(TemplateNarrative::drug)
                    .collect(Collectors.joining("; "))).append(". ");
        }
        s.append("Reported reaction").append(f.reactions().size() == 1 ? "" : "s").append(": ")
                .append(f.reactions().isEmpty() ? "none coded yet" : String.join(", ", f.reactions()));
        if (f.onsetDate() != null) {
            s.append(", with onset on ").append(f.onsetDate());
        }
        s.append(". The report was received on ").append(f.receivedDate()).append(". ");
        if (f.seriousCriteria() != null && !f.seriousCriteria().isBlank()) {
            s.append("Seriousness criteria reported: ").append(f.seriousCriteria().replace(',', ' ').toLowerCase()
                    .replace('_', '-')).append(". ");
        } else {
            s.append("Seriousness assessment: ").append(f.seriousness().toLowerCase().replace('_', '-')).append(". ");
        }
        s.append("Spontaneous reports do not prove that a drug caused a reaction.");
        return s.toString();
    }

    private static String drug(CaseFacts.Drug d) {
        String role = switch (d.role() == null ? "" : d.role()) {
            case "PS" -> "primary suspect";
            case "SS" -> "secondary suspect";
            case "C" -> "concomitant";
            case "I" -> "interacting";
            default -> "role not stated";
        };
        StringBuilder s = new StringBuilder(d.name()).append(" (").append(role);
        if (d.dose() != null) {
            s.append(", ").append(d.dose());
        }
        if (d.route() != null) {
            s.append(", ").append(d.route());
        }
        return s.append(')').toString();
    }

    private static String reporter(String type) {
        return switch (type == null ? "" : type) {
            case "PHARMACIST" -> "pharmacist";
            case "PHYSICIAN" -> "physician";
            case "OTHER_HCP" -> "health-professional";
            case "CONSUMER" -> "consumer";
            case "LAWYER" -> "lawyer";
            default -> "spontaneous";
        };
    }
}
