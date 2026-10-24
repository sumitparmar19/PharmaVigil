// Demo mode in case-service: the demo auto-review takes a replayed (DEMO-) case through the normal reviewer calls
// as "demo-auto-review" and never touches any other case; the per-run counters count each stage from the audit
// trail; a bad run id is refused; a NACKed demo case is left in review instead of being resubmitted forever; and
// the public /api/status answers without sign-in (no peers running here, so it reports DEGRADED).
package io.pharmavigil.caseservice.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.AuditEventRepository;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import io.pharmavigil.caseservice.model.ReviewDecisionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.pipeline.NarrativeStage;
import io.pharmavigil.caseservice.pipeline.PipelineStages;
import io.pharmavigil.caseservice.review.ReviewService;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.SubmissionStatus;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"pv.models.dir=src/test/resources/model-bundle", "pv.demo.enabled=true",
    "pv.demo.auto-review-interval-ms=3600000", "pv.status.peers=", "spring.kafka.bootstrap-servers=localhost:1"})
@AutoConfigureMockMvc
class DemoModeTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired PipelineStages stages;
    @Autowired NarrativeStage narrativeStage;
    @Autowired CaseService cases;
    @Autowired SafetyCaseRepository caseRepo;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditEventRepository audit;
    @Autowired ReviewDecisionRepository decisions;
    @Autowired ReviewService reviews;
    @Autowired DemoAutoReview autoReview;
    @Autowired E2bValidator validator;
    @Autowired JsonMapper json;

    private CaseEvent lastEvent(SafetyCase c) {
        var e = outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()));
        return json.readValue(e.get(e.size() - 1).getPayload(), CaseEvent.class);
    }

    /** A partner report through dedup, coding, triage and narrative drafting, as the replay produces them. */
    private SafetyCase triaged(String sourceReportId, String seriousCriteria) {
        ReportReceived r = new ReportReceived(UUID.randomUUID().toString(), "PARTNER", sourceReportId, Instant.now(),
                new BigDecimal("58"), "F", "PHYSICIAN", "US", null, null, List.of(new ReportReceived.Drug(null,
                        "ATORVASTATIN", "PS", "40 MG", "ORAL")), List.of("myalgia", "itchy skin"), seriousCriteria);
        stages.onReportReceived(r);
        SafetyCase c = caseRepo.findBySourceAndSourceReportId(Source.PARTNER, sourceReportId).orElseThrow();
        stages.onDeduped(lastEvent(c));
        stages.onCoded(lastEvent(c));
        narrativeStage.onTriaged(lastEvent(c));
        return cases.load(c.getId());
    }

    @Test
    void autoReviewTakesAReplayedCaseThroughTheNormalReviewCallsAndCountsEveryStage() throws Exception {
        String run = "run" + Long.toString(System.nanoTime(), 36);
        SafetyCase c = triaged(DemoAutoReview.PREFIX + run + "-1001", null);

        if (validator.schemaAvailable()) {
            assertThat(autoReview.reviewOne(c.getId())).isTrue();
            assertThat(outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId())))
                    .anyMatch(e -> e.getTopic().equals(Topics.CASE_READY));
        } else { // no official schema set here: approved, then the export is refused and nothing is queued
            assertThatThrownBy(() -> autoReview.reviewOne(c.getId()))
                    .isInstanceOf(ReviewService.SubmissionBlockedException.class);
            assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.APPROVED);
        }
        assertThat(decisions.findAll()).filteredOn(d -> d.getCaseId().equals(c.getId()))
                .isNotEmpty().allMatch(d -> d.getReviewer().equals(DemoAutoReview.ACTOR));

        String auth = "Bearer " + tokens.issue("demo-reviewer", List.of(Roles.SAFETY_REVIEWER));
        mvc.perform(get("/api/cases/demo/runs/" + run).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.deduplicated").value(1))
                .andExpect(jsonPath("$.triaged").value(1))
                .andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.submitted").value(validator.schemaAvailable() ? 1 : 0));
        mvc.perform(get("/api/cases/demo/runs/" + run + "/cases").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cases/demo/runs/BAD%27RUN").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/cases/demo/runs/" + run)).andExpect(status().isUnauthorized());
    }

    @Test
    void theSweepLeavesOrdinaryCasesToPeople() {
        SafetyCase ordinary = triaged("PARTNER-" + UUID.randomUUID(), null);
        autoReview.sweep();
        assertThat(cases.load(ordinary.getId()).getStatus()).isIn(Status.TRIAGED, Status.NEEDS_MANUAL_CODING);
        assertThat(audit.findByCaseIdOrderByIdAsc(ordinary.getId()))
                .noneMatch(a -> a.getActor().equals(DemoAutoReview.ACTOR));
    }

    @Test
    void aNackedDemoCaseStaysInReview() {
        SafetyCase c = triaged(DemoAutoReview.PREFIX + "nack" + Long.toString(System.nanoTime(), 36) + "-7", "HO");
        reviews.onSubmissionStatus(new SubmissionStatus(UUID.randomUUID().toString(), c.getId(), "DEMO-X", "M-9",
                SubmissionStatus.Status.NACK, "schema error", Instant.now()));
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.IN_REVIEW);
        assertThat(autoReview.reviewOne(c.getId())).isFalse();
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.IN_REVIEW);
    }

    @Test
    void statusIsPublicAndNamesTheLoadedModels() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.services.case-service").value("UP"))
                .andExpect(jsonPath("$.modelBundle").value("fixture"))
                .andExpect(jsonPath("$.models.length()").value(3));
    }
}
