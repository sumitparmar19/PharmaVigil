// Evidence shown at the counter for one drug–reaction signal. Never a verdict: counts, scores and the
// mandatory causation disclaimer travel together.
package io.pharmavigil.pharmacy.signals;

public record SafetyWarning(String rxcui, String pt, long reports, Double ebgm, Double eb05, Double safetyformer,
        String labelText, String disclaimer) {
}
