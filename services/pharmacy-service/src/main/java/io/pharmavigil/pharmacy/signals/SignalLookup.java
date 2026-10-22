// Result of asking signal-service for warnings: either the evidence list, or "unavailable" so the
// dispense still completes when signal-service is down (engineering rule 2).
package io.pharmavigil.pharmacy.signals;

import java.util.List;

public record SignalLookup(boolean available, List<SafetyWarning> warnings, String note) {

    public static SignalLookup unavailable(String note) {
        return new SignalLookup(false, List.of(), note);
    }
}
