// Sample ICSRs shared by the E2B tests here and, through the common test-jar, by the services that import or
// export E2B(R3) messages. All content is invented (synthetic patient, no real report).
package io.pharmavigil.common.e2b;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class E2bSamples {

    public static final String SAFETY_REPORT_ID = "US-PHARMAVIGIL-PV-2026-0000000001";

    private E2bSamples() {
    }

    public static IcsrCase sample() {
        return sample(SAFETY_REPORT_ID, "A 54-year-old woman developed muscle pain <three> weeks after starting atorvastatin.");
    }

    public static IcsrCase sample(String safetyReportId, String narrative) {
        return new IcsrCase("PV-BATCH-1", "PV-MSG-1", "PHARMAVIGIL", "MOCK-REGULATOR",
                OffsetDateTime.of(2026, 10, 2, 9, 30, 0, 0, ZoneOffset.UTC), safetyReportId, safetyReportId, 1,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), 2, "US", new BigDecimal("54"), 2,
                List.of(new IcsrCase.Reaction("muscle pain", "myalgia", null, null, LocalDate.of(2026, 9, 28),
                                false, false, false, false, false, false),
                        new IcsrCase.Reaction("dark urine & weakness", "rhabdomyolysis", null, null, null,
                                false, false, true, false, false, false)),
                List.of(new IcsrCase.Drug(1, "Atorvastatin", "83367", "20 mg daily", null),
                        new IcsrCase.Drug(2, "Lisinopril", "29046", null, null)),
                narrative);
    }

    /** Two ICSR messages in one batch (the second message spliced in after the first). */
    public static String twoMessageBatch(String firstId, String secondId) {
        String first = new E2bWriter().write(sample(firstId, "First report."));
        String second = new E2bWriter().write(sample(secondId, "Second report."));
        int start = second.indexOf("<PORR_IN049016UV");
        int end = second.indexOf("</PORR_IN049016UV>") + "</PORR_IN049016UV>".length();
        return first.replaceFirst("</PORR_IN049016UV>", "</PORR_IN049016UV>"
                + java.util.regex.Matcher.quoteReplacement(second.substring(start, end)));
    }
}
