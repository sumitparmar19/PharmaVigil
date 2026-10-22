// The reviewer workflow over the REST API: a triaged case gets a template narrative draft, every model suggestion
// must be confirmed or corrected (and each decision keeps the suggestion beside the final value), approval checks
// its preconditions, submission is refused unless the export passes the official schemas, and submission results
// move the case (QUEUED → SUBMITTED, ACK → ACKNOWLEDGED, NACK → back to review) exactly once.
package io.pharmavigil.caseservice.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.e2b.E2bExportService;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.Narrative;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import io.pharmavigil.caseservice.model.ReviewDecision;
import io.pharmavigil.caseservice.model.ReviewDecisionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.SubmissionEventRepository;
import io.pharmavigil.caseservice.pipeline.NarrativeStage;
import io.pharmavigil.caseservice.pipeline.PipelineStages;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.SubmissionStatus;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "pv.models.dir=src/test/resources/model-bundle")
@AutoConfigureMockMvc
class ReviewWorkflowTest {

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
    @Autowired NarrativeRepository narratives;
    @Autowired ReviewDecisionRepository decisions;
    @Autowired SubmissionEventRepository submissions;
    @Autowired ReviewService reviews;
    @Autowired E2bValidator validator;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    private String bearer(String role) {
        return "Bearer " + tokens.issue("reviewer-" + role.toLowerCase(), List.of(role));
    }

    private CaseEvent lastEvent(SafetyCase c) {
        var e = outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()));
        return json.readValue(e.get(e.size() - 1).getPayload(), CaseEvent.class);
    }

    /** Runs a report through dedup, coding, triage and narrative drafting. */
    private SafetyCase triagedCase() {
        ReportReceived r = new ReportReceived(UUID.randomUUID().toString(), "PHARMACY", "R-" + UUID.randomUUID(),
                Instant.now(), new java.math.BigDecimal("61"), "M", "PHARMACIST", "US", null,
                "Itchy skin after the new tablet.", List.of(new ReportReceived.Drug(
                        Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)),
                        "Testamycin", "PS", "1 tablet", "oral")), List.of("itchy skin", "a high fever"), null);
        stages.onReportReceived(r);
        SafetyCase c = caseRepo.findBySourceAndSourceReportId(Source.PHARMACY, r.sourceReportId()).orElseThrow();
        stages.onDeduped(lastEvent(c));
        stages.onCoded(lastEvent(c));
        CaseEvent triaged = lastEvent(c);
        narrativeStage.onTriaged(triaged);
        narrativeStage.onTriaged(triaged); // replay: still one draft
        return cases.load(c.getId());
    }

    private CaseVersion latest(Long id) {
        return tx.execute(s -> {
            CaseVersion v = cases.latest(cases.load(id));
            v.getReactions().size();
            return v;
        });
    }

    private void post200(String url, String body) throws Exception {
        mvc.perform(post(url).header("Authorization", bearer(Roles.SAFETY_REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful());
    }

    /** Accept each model code (re-reading the lock version every time) and decide seriousness/duplicate. */
    private void reviewEverything(SafetyCase c) throws Exception {
        int n = latest(c.getId()).getReactions().size();
        for (int i = 0; i < n; i++) {
            String pt = latest(c.getId()).getReactions().get(i).getPt();
            int lock = cases.load(c.getId()).getLockVersion();
            post200("/api/cases/" + c.getId() + "/reactions/" + i + "/code",
                    "{\"pt\":\"" + (i == 0 ? pt : "Pyrexia (corrected)") + "\",\"lockVersion\":" + lock + "}");
        }
        post200("/api/cases/" + c.getId() + "/seriousness", "{\"value\":\"NON_SERIOUS\"}");
        post200("/api/cases/" + c.getId() + "/duplicate", "{\"duplicateOf\":null}");
    }

    @Test
    void reviewerConfirmsEverySuggestionApprovesAndSubmits() throws Exception {
        SafetyCase c = triagedCase();
        List<Narrative> drafts = narratives.findByCaseIdOrderByIdAsc(c.getId());
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).getSource()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(drafts.get(0).getBody()).contains("61-year-old male").contains("do not prove");

        String auth = bearer(Roles.SAFETY_REVIEWER);
        mvc.perform(post("/api/cases/" + c.getId() + "/approve").header("Authorization", auth))
                .andExpect(status().isConflict());

        reviewEverything(c);
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.IN_REVIEW);
        List<ReviewDecision> ds = decisions.findByCaseIdOrderByIdAsc(c.getId());
        assertThat(ds).filteredOn(d -> d.getField().equals("PT")).extracting(ReviewDecision::isAccepted)
                .containsExactly(true, false);
        assertThat(ds).filteredOn(d -> d.getField().equals("PT")).allSatisfy(d -> {
            assertThat(d.getModelName()).isEqualTo("meddra-coder");
            assertThat(d.getModelScore()).isNotNull();
        });
        assertThat(latest(c.getId()).getReactions().get(1).getPt()).isEqualTo("Pyrexia (corrected)");

        mvc.perform(post("/api/cases/" + c.getId() + "/approve").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("narrative")));
        post200("/api/cases/" + c.getId() + "/narratives/" + drafts.get(0).getId() + "/approve", "");
        mvc.perform(post("/api/cases/" + c.getId() + "/approve").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        if (validator.schemaAvailable()) {
            mvc.perform(post("/api/cases/" + c.getId() + "/submit").header("Authorization", auth))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REQUESTED"));
            assertThat(outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId())))
                    .anyMatch(e -> e.getTopic().equals(Topics.CASE_READY) && e.getPayload().contains("MCCI_IN200100UV01"));
            mvc.perform(post("/api/cases/" + c.getId() + "/submit").header("Authorization", auth))
                    .andExpect(status().isConflict());
        } else {
            mvc.perform(post("/api/cases/" + c.getId() + "/submit").header("Authorization", auth))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("fetch-e2b-xsd")));
            assertThat(outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId())))
                    .noneMatch(e -> e.getTopic().equals(Topics.CASE_READY));
        }

        // Results from the submission gateway, each delivered twice.
        SubmissionStatus queued = new SubmissionStatus(UUID.randomUUID().toString(), c.getId(), "US-X", "M-1",
                SubmissionStatus.Status.QUEUED, null, Instant.now());
        reviews.onSubmissionStatus(queued);
        reviews.onSubmissionStatus(queued);
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.SUBMITTED);
        SubmissionStatus ack = new SubmissionStatus(UUID.randomUUID().toString(), c.getId(), "US-X", "M-1",
                SubmissionStatus.Status.ACK, null, Instant.now());
        reviews.onSubmissionStatus(ack);
        reviews.onSubmissionStatus(ack);
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.ACKNOWLEDGED);
        mvc.perform(get("/api/cases/" + c.getId() + "/submissions").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status").value(org.hamcrest.Matchers.hasItems("QUEUED", "ACK")))
                .andExpect(jsonPath("$[?(@.status == 'ACK')]", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void nackSendsTheCaseBackToReviewWithTheReason() throws Exception {
        SafetyCase c = triagedCase();
        reviewEverything(c);
        Long draft = narratives.findByCaseIdOrderByIdAsc(c.getId()).get(0).getId();
        reviews.approveNarrative(c.getId(), draft, "reviewer-1");
        reviews.approve(c.getId(), "reviewer-1");
        reviews.onSubmissionStatus(new SubmissionStatus(UUID.randomUUID().toString(), c.getId(), "US-X", "M-2",
                SubmissionStatus.Status.NACK, "line 3: element notAnE2bElement not allowed", Instant.now()));
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.IN_REVIEW);
        assertThat(submissions.findByCaseIdOrderByIdAsc(c.getId())).singleElement()
                .satisfies(s -> assertThat(s.getReason()).contains("notAnE2bElement"));
    }

    @Test
    void guardrailsAndRoles() throws Exception {
        SafetyCase c = triagedCase();
        String auth = bearer(Roles.SAFETY_REVIEWER);
        mvc.perform(post("/api/cases/" + c.getId() + "/seriousness").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cases/" + c.getId() + "/reactions/9/code").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pt\":\"Rash\",\"lockVersion\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/cases/" + c.getId() + "/reactions/0/code").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pt\":\"Rash\",\"lockVersion\":-1}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/cases/" + c.getId() + "/duplicate").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"duplicateOf\":" + c.getId() + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cases/" + c.getId() + "/approve").header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases/" + c.getId() + "/submit").header("Authorization", auth))
                .andExpect(status().isConflict());
        // Model codes must be confirmed one by one before approval.
        post200("/api/cases/" + c.getId() + "/seriousness", "{\"value\":\"SERIOUS\"}");
        Long draft = narratives.findByCaseIdOrderByIdAsc(c.getId()).get(0).getId();
        post200("/api/cases/" + c.getId() + "/narratives/" + draft + "/approve", "");
        mvc.perform(post("/api/cases/" + c.getId() + "/approve").header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("suggestions")));
        mvc.perform(post("/api/cases/" + c.getId() + "/narratives").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Reviewer text.\"}"))
                .andExpect(status().isCreated());
    }
}
