// The safety report id every replayed report carries: DEMO-<run id>-<FAERS primaryid>. case-service counts a
// run's cases and its demo auto-review finds them by this prefix (same constant on both sides).
package io.pharmavigil.intake.demo;

public final class DemoIds {

    public static final String PREFIX = "DEMO-";

    private DemoIds() {
    }

    public static String safetyReportId(String runId, String faersPrimaryId) {
        return PREFIX + runId + "-" + faersPrimaryId;
    }
}
