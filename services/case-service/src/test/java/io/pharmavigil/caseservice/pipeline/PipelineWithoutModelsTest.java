// Graceful degradation (engineering rule 2): with no model bundle the case still moves through the pipeline,
// lands in NEEDS_MANUAL_CODING with seriousness UNKNOWN, and the audit trail says why.
package io.pharmavigil.caseservice.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.AuditEvent;
import io.pharmavigil.caseservice.model.AuditEventRepository;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.testing.TestDatabases;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc // same context as the other default-configuration tests
class PipelineWithoutModelsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired PipelineStages stages;
    @Autowired CaseService cases;
    @Autowired SafetyCaseRepository caseRepo;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditEventRepository audit;
    @Autowired JsonMapper json;

    @Test
    void casesKeepMovingToManualCodingWithUnknownSeriousness() {
        ReportReceived r = PipelineStagesTest.report("PHARMACY", List.of("rash"), null, PipelineStagesTest.rx());
        stages.onReportReceived(r);
        var c = caseRepo.findBySourceAndSourceReportId(Source.PHARMACY, r.sourceReportId()).orElseThrow();
        for (int i = 0; i < 2; i++) {
            var events = outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()));
            CaseEvent e = json.readValue(events.get(events.size() - 1).getPayload(), CaseEvent.class);
            if (i == 0) {
                stages.onDeduped(e);
                assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.NEEDS_MANUAL_CODING);
            } else {
                stages.onCoded(e);
            }
        }
        var after = cases.load(c.getId());
        assertThat(after.getStatus()).isEqualTo(Status.TRIAGED);
        assertThat(after.getSeriousness()).isEqualTo(Seriousness.UNKNOWN);
        assertThat(audit.findByCaseIdOrderByIdAsc(c.getId())).extracting(AuditEvent::getDetail)
                .anyMatch(d -> d.contains("duplicate model unavailable"))
                .anyMatch(d -> d.contains("coder unavailable"))
                .anyMatch(d -> d.contains("seriousness model unavailable"));
    }
}
