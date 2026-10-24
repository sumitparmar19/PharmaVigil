// FAERS age bands used by the duplicate features, identical to the data platform's silver layer
// (data/pharmavigil_data/faers/silver.py): [lo, hi) in years; anything else is "unknown".
package io.pharmavigil.caseservice.models;

import java.math.BigDecimal;

public final class AgeBands {

    private static final double[][] BOUNDS = {{0, 2}, {2, 12}, {12, 18}, {18, 45}, {45, 65}, {65, 75}, {75, 130}};
    private static final String[] LABELS = {"0-1", "2-11", "12-17", "18-44", "45-64", "65-74", "75+"};

    private AgeBands() {
    }

    public static String of(BigDecimal years) {
        if (years == null) {
            return "unknown";
        }
        double y = years.doubleValue();
        for (int i = 0; i < BOUNDS.length; i++) {
            if (y >= BOUNDS[i][0] && y < BOUNDS[i][1]) {
                return LABELS[i];
            }
        }
        return "unknown";
    }
}
