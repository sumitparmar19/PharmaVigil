// Demo mode only: how far the reports of one replay run have travelled, for the live counters on the demo
// page. Every number is counted from case-service's own audit trail (each pipeline stage writes one), so the
// counters show what really happened to these cases, not a timer. Also lists a few finished cases so a visitor
// can open one end to end.
package io.pharmavigil.caseservice.demo;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases/demo/runs")
@ConditionalOnProperty(name = "pv.demo.enabled", havingValue = "true")
@PreAuthorize("isAuthenticated()")
public class DemoRunController {

    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9]{6,32}");

    public record Stages(String runId, long received, long deduplicated, long codedByModel, long manualCoding,
            long triaged, long serious, long approved, long submitted, long acknowledged, long rejected,
            long possibleDuplicates) {
    }

    public record FinishedCase(long id, String caseNumber, String status, String seriousness) {
    }

    private static final String STAGES = """
            SELECT
              (SELECT COUNT(*) FROM safety_case c WHERE c.source_report_id LIKE :prefix) AS received,
              COUNT(DISTINCT CASE WHEN a.action IN ('DUPLICATE_CHECK','DUPLICATE') THEN a.case_id END) AS deduplicated,
              COUNT(DISTINCT CASE WHEN a.action = 'STATUS' AND a.detail LIKE '% -> CODED' THEN a.case_id END) AS coded,
              COUNT(DISTINCT CASE WHEN a.action = 'STATUS' AND a.detail LIKE '% -> NEEDS_MANUAL_CODING'
                                  THEN a.case_id END) AS manual,
              COUNT(DISTINCT CASE WHEN a.action = 'STATUS' AND a.detail LIKE '% -> TRIAGED' THEN a.case_id END) AS triaged,
              COUNT(DISTINCT CASE WHEN a.action = 'SERIOUSNESS' AND a.detail LIKE 'SERIOUS%' THEN a.case_id END) AS serious,
              COUNT(DISTINCT CASE WHEN a.action = 'STATUS' AND a.detail LIKE '% -> APPROVED' THEN a.case_id END) AS approved,
              COUNT(DISTINCT CASE WHEN a.action = 'SUBMISSION' THEN a.case_id END) AS submitted,
              COUNT(DISTINCT CASE WHEN a.action = 'STATUS' AND a.detail LIKE '% -> ACKNOWLEDGED' THEN a.case_id END) AS ack,
              COUNT(DISTINCT CASE WHEN a.action = 'NACK' THEN a.case_id END) AS nack,
              COUNT(DISTINCT CASE WHEN a.action = 'DUPLICATE' AND a.detail LIKE 'possible%' THEN a.case_id END) AS dups
            FROM audit_event a JOIN safety_case c ON c.id = a.case_id
            WHERE c.source_report_id LIKE :prefix
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DemoRunController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String prefix(String runId) {
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("unknown run id");
        }
        return DemoAutoReview.PREFIX + runId + "-%";
    }

    @GetMapping("/{runId}")
    public Stages stages(@PathVariable String runId) {
        Map<String, String> p = Map.of("prefix", prefix(runId));
        return jdbc.queryForObject(STAGES, p, (rs, n) -> new Stages(runId, rs.getLong("received"),
                rs.getLong("deduplicated"), rs.getLong("coded"), rs.getLong("manual"), rs.getLong("triaged"),
                rs.getLong("serious"), rs.getLong("approved"), rs.getLong("submitted"), rs.getLong("ack"),
                rs.getLong("nack"), rs.getLong("dups")));
    }

    @GetMapping("/{runId}/cases")
    public List<FinishedCase> finished(@PathVariable String runId) {
        return jdbc.query("""
                SELECT c.id, c.case_number, c.status, c.seriousness FROM safety_case c
                WHERE c.source_report_id LIKE :prefix AND c.status IN ('ACKNOWLEDGED','SUBMITTED','IN_REVIEW')
                ORDER BY CASE c.status WHEN 'ACKNOWLEDGED' THEN 0 WHEN 'SUBMITTED' THEN 1 ELSE 2 END, c.id
                """, Map.of("prefix", prefix(runId)),
                (rs, n) -> new FinishedCase(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .stream().limit(10).toList();
    }
}
