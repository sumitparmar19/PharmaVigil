// M8 "done when": a pharmacist's one-click report travels pharmacy-service → intake-gateway → Kafka →
// case-service (dedup, coding, triage, narrative) → reviewer → E2B(R3) export → Kafka → submission-gateway →
// JMS → mock regulator → ACK, and the case ends ACKNOWLEDGED. Also: replaying every Kafka event creates no
// duplicate anything; a poison message lands in the dead-letter topic; an invalid E2B file gets a NACK that sends
// the case back to review. Needs the official E2B(R3) schemas (PV_E2B_XSD_DIR, fetched in CI), because nothing
// unvalidated can be submitted.
package io.pharmavigil.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums;
import io.pharmavigil.caseservice.model.CaseVersionRepository;
import io.pharmavigil.caseservice.model.NarrativeRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.SubmissionEventRepository;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.caseservice.review.ReviewService;
import io.pharmavigil.common.e2b.E2bSamples;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.events.CaseReady;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.common.testing.TestKafka;
import io.pharmavigil.intake.receipts.IntakeReceiptRepository;
import io.pharmavigil.pharmacy.catalog.Drug;
import io.pharmavigil.pharmacy.catalog.DrugRepository;
import io.pharmavigil.pharmacy.patients.Patient;
import io.pharmavigil.pharmacy.patients.PatientRepository;
import io.pharmavigil.submission.model.SubmissionMessageRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EndToEndTest {

    static Platform platform;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue("oracle".equals(TestDatabases.engine()), "end-to-end test runs on Oracle only");
        String xsd = System.getenv("PV_E2B_XSD_DIR");
        Assumptions.assumeTrue(xsd != null && Files.isDirectory(Path.of(xsd)),
                "official E2B(R3) schemas not fetched (scripts/fetch-e2b-xsd.sh); nothing could be submitted");
        platform = new Platform();
    }

    @AfterAll
    static void stop() {
        if (platform != null) {
            platform.close();
        }
    }

    private static <T> T bean(org.springframework.context.ApplicationContext ctx, Class<T> type) {
        return ctx.getBean(type);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void send(String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestKafka.bootstrap()), new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** A pharmacist reports a side effect through pharmacy-service's REST API; returns the pharmacy report id. */
    private long pharmacistReports(String narrative) {
        var drugs = bean(platform.pharmacy, DrugRepository.class);
        var patients = bean(platform.pharmacy, PatientRepository.class);
        Drug drug = drugs.save(new Drug("E2E-" + UUID.randomUUID().toString().substring(0, 8), "83367",
                "Atorvastatin 20 mg tablet", "20 mg", "tablet", true, 10));
        Patient patient = patients.save(new Patient("syn-e2e-" + UUID.randomUUID(), "Test", "Patient",
                LocalDate.of(1971, 3, 4), "F"));
        String token = bean(platform.pharmacy, TokenService.class).issue("pharmacist-e2e", List.of(Roles.PHARMACIST));
        JsonNode created = RestClient.create("http://localhost:" + Platform.port(platform.pharmacy)).post()
                .uri("/api/reports").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("patientId", patient.getId(), "drugId", drug.getId(), "narrative", narrative,
                        "onsetDate", LocalDate.of(2026, 9, 30).toString()))
                .retrieve().body(JsonNode.class);
        return created.path("id").asLong();
    }

    private SafetyCase triagedCase(long pharmacyReportId) throws InterruptedException {
        var repo = bean(platform.cases, SafetyCaseRepository.class);
        var narratives = bean(platform.cases, NarrativeRepository.class);
        String sourceId = "PH-" + pharmacyReportId;
        await("case " + sourceId + " triaged with a narrative draft", () -> repo
                .findBySourceAndSourceReportId(CaseEnums.Source.PHARMACY, sourceId)
                .map(c -> c.getStatus() == CaseEnums.Status.TRIAGED
                        || c.getStatus() == CaseEnums.Status.NEEDS_MANUAL_CODING ? c : null)
                .map(c -> !narratives.findByCaseIdOrderByIdAsc(c.getId()).isEmpty()).orElse(false));
        return repo.findBySourceAndSourceReportId(CaseEnums.Source.PHARMACY, sourceId).orElseThrow();
    }

    /** The reviewer confirms every suggestion, approves the draft narrative and the case. */
    private void review(SafetyCase c) {
        var cases = bean(platform.cases, CaseService.class);
        var reviews = bean(platform.cases, ReviewService.class);
        var tx = bean(platform.cases, TransactionTemplate.class);
        String reviewer = "reviewer-e2e";
        List<String> pts = tx.execute(s -> cases.latest(cases.load(c.getId())).getReactions().stream()
                .map(r -> r.getPt() == null ? "Pyrexia" : r.getPt()).toList());
        if (pts.isEmpty()) {
            // The tiny fixture coder found nothing in the narrative: the reviewer codes it by hand.
            cases.advance(c.getId(), CaseEnums.Status.IN_REVIEW, reviewer);
            cases.revise(c.getId(), cases.load(c.getId()).getLockVersion(), reviewer, "manual coding",
                    d -> d.reactions.add(new VersionDraft.ReactionDraft("dizziness", "Dizziness",
                            CaseEnums.CodeSource.REVIEWER, null, null, null)));
        }
        for (int i = 0; i < pts.size(); i++) {
            reviews.codeReaction(c.getId(), i, pts.get(i), cases.load(c.getId()).getLockVersion(), reviewer);
        }
        reviews.decideSeriousness(c.getId(), CaseEnums.Seriousness.NON_SERIOUS, reviewer);
        reviews.decideDuplicate(c.getId(), null, reviewer);
        Long draft = bean(platform.cases, NarrativeRepository.class).findByCaseIdOrderByIdAsc(c.getId()).get(0).getId();
        reviews.approveNarrative(c.getId(), draft, reviewer);
        reviews.approve(c.getId(), reviewer);
    }

    private CaseEnums.Status status(long caseId) {
        return bean(platform.cases, CaseService.class).load(caseId).getStatus();
    }

    @Test
    void reportBecomesAcknowledgedSubmissionAndReplayChangesNothing() throws Exception {
        long reportId = pharmacistReports("Muscle pain and dark urine three weeks after starting the tablet.");
        SafetyCase c = triagedCase(reportId);

        var intakeReceipts = bean(platform.intake, IntakeReceiptRepository.class);
        assertThat(intakeReceipts.findFirstBySourceAndSourceReportIdOrderByIdDesc(
                io.pharmavigil.intake.receipts.IntakeReceipt.Source.PHARMACY, "PH-" + reportId)).isPresent();
        assertThat(c.getSeriousness()).isIn(CaseEnums.Seriousness.SERIOUS, CaseEnums.Seriousness.NON_SERIOUS);

        review(c);
        bean(platform.cases, ReviewService.class).submit(c.getId(), "reviewer-e2e");
        await("ACK for case " + c.getCaseNumber(), () -> status(c.getId()) == CaseEnums.Status.ACKNOWLEDGED);
        assertThat(bean(platform.cases, SubmissionEventRepository.class).findByCaseIdOrderByIdAsc(c.getId()))
                .extracting(e -> e.getStatus()).containsExactly("REQUESTED", "QUEUED", "ACK");

        // Replay every pipeline event from the beginning of each topic.
        Snapshot before = snapshot(c.getId());
        List<ConsumerRecord<String, String>> history = new ArrayList<>();
        try (var consumer = TestKafka.consumer(Topics.ALL)) {
            history.addAll(TestKafka.poll(consumer, Integer.MAX_VALUE, Duration.ofSeconds(10)));
        }
        assertThat(history).extracting(ConsumerRecord::topic).contains(Topics.REPORT_RECEIVED, Topics.REPORT_DEDUPED,
                Topics.REPORT_CODED, Topics.CASE_TRIAGED, Topics.CASE_READY, Topics.SUBMISSION_STATUS);
        for (ConsumerRecord<String, String> r : history) {
            send(r.topic(), r.key(), r.value());
        }
        // A fresh report sent after the replay: once it is triaged, the replayed messages ahead of it are consumed.
        triagedCase(pharmacistReports("Marker report after the replay: dizziness."));
        Thread.sleep(3000); // submission topics have no marker; give their consumers a moment
        // Only the marker report is new: one case and one intake receipt more, nothing else anywhere.
        assertThat(snapshot(c.getId())).isEqualTo(new Snapshot(before.cases() + 1, before.versions(),
                before.narratives(), before.intakeReceipts() + 1, before.gatewayMessages(), before.submissionEvents()));
        assertThat(status(c.getId())).isEqualTo(CaseEnums.Status.ACKNOWLEDGED);
    }

    @Test
    void invalidE2bFileIsNackedAndTheCaseGoesBackToReview() throws Exception {
        SafetyCase c = triagedCase(pharmacistReports("Headache after the new tablet."));
        review(c);
        String broken = new E2bWriter().write(E2bSamples.sample())
                .replaceFirst("<investigationEvent([^>]*)>", "<investigationEvent$1><notAnE2bElement/>");
        String eventId = UUID.randomUUID().toString();
        send(Topics.CASE_READY, Long.toString(c.getId()), JSON.writeValueAsString(new CaseReady(eventId, c.getId(),
                c.getCaseNumber(), c.getCurrentVersion(), "US-PHARMAVIGIL-" + c.getCaseNumber(), broken, Instant.now())));
        await("NACK for case " + c.getCaseNumber(), () -> status(c.getId()) == CaseEnums.Status.IN_REVIEW);
        var history = bean(platform.cases, SubmissionEventRepository.class).findByCaseIdOrderByIdAsc(c.getId());
        assertThat(history).extracting(e -> e.getStatus()).containsExactly("QUEUED", "NACK");
        assertThat(history.get(1).getReason()).contains("schema validation failed").contains("notAnE2bElement");
    }

    @Test
    void poisonMessageLandsInTheDeadLetterTopic() throws Exception {
        String poison = "not a report " + UUID.randomUUID();
        try (var dlq = TestKafka.consumer(List.of(Topics.dlq(Topics.REPORT_RECEIVED)))) {
            send(Topics.REPORT_RECEIVED, "poison", poison);
            assertThat(TestKafka.pollUntil(dlq, r -> poison.equals(r.value()), Duration.ofSeconds(60))).isNotNull();
        }
    }

    record Snapshot(long cases, int versions, int narratives, long intakeReceipts, long gatewayMessages,
            int submissionEvents) {
    }

    private Snapshot snapshot(long caseId) {
        var tx = bean(platform.cases, TransactionTemplate.class);
        int versions = tx.execute(s -> bean(platform.cases, CaseVersionRepository.class)
                .findByCaseIdOrderByVersionNoAsc(caseId).size());
        return new Snapshot(bean(platform.cases, SafetyCaseRepository.class).count(), versions,
                bean(platform.cases, NarrativeRepository.class).findByCaseIdOrderByIdAsc(caseId).size(),
                bean(platform.intake, IntakeReceiptRepository.class).count(),
                bean(platform.submission, SubmissionMessageRepository.class).count(),
                bean(platform.cases, SubmissionEventRepository.class).findByCaseIdOrderByIdAsc(caseId).size());
    }
}
