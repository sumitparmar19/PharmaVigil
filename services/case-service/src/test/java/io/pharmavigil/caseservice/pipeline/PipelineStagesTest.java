// Pipeline stages on the real database with the fixture model bundle: each stage does its work and writes
// exactly one next event; replaying an event changes nothing; model suggestions carry name, version and score;
// reported seriousness criteria win over the model; bad events are poison messages.
package io.pharmavigil.caseservice.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.OutboxEvent;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.common.events.CaseEvent;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.kafka.PoisonMessageException;
import io.pharmavigil.common.testing.TestDatabases;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "pv.models.dir=src/test/resources/model-bundle")
class PipelineStagesTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired PipelineStages stages;
    @Autowired CaseService cases;
    @Autowired SafetyCaseRepository caseRepo;
    @Autowired OutboxEventRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    /** A random RxCUI-like code (RxCUIs are at most 16 characters) so tests never share drugs by accident. */
    static String rx() {
        return Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000_000L));
    }

    static ReportReceived report(String source, List<String> terms, String serious, String rxcui) {
        return new ReportReceived(UUID.randomUUID().toString(), source, "R-" + UUID.randomUUID(), Instant.now(),
                new BigDecimal("54"), "F", "PHARMACIST", "US", LocalDate.of(2026, 9, 28),
                "The patient developed itchy skin and a high fever two days after the first dose.",
                List.of(new ReportReceived.Drug(rxcui, "Drug " + rxcui, "PS", "10 mg", "oral")), terms, serious);
    }

    private SafetyCase caseOf(ReportReceived r) {
        return caseRepo.findBySourceAndSourceReportId(
                io.pharmavigil.caseservice.model.CaseEnums.Source.valueOf(r.source()), r.sourceReportId()).orElseThrow();
    }

    private List<OutboxEvent> events(SafetyCase c) {
        return outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()));
    }

    private CaseEvent last(SafetyCase c) {
        List<OutboxEvent> e = events(c);
        return json.readValue(e.get(e.size() - 1).getPayload(), CaseEvent.class);
    }

    private CaseVersion latest(SafetyCase c) {
        return tx.execute(s -> {
            CaseVersion v = cases.latest(cases.load(c.getId()));
            v.getReactions().size();
            v.getDrugs().size();
            return v;
        });
    }

    @Test
    void reportFlowsThroughEveryStageOnceEvenWhenReplayed() {
        ReportReceived r = report("PHARMACY", List.of("itchy skin", "a high fever"), null, rx());
        stages.onReportReceived(r);
        stages.onReportReceived(r); // replay
        SafetyCase c = caseOf(r);
        assertThat(events(c)).extracting(OutboxEvent::getTopic).containsExactly(Topics.REPORT_DEDUPED);

        CaseEvent deduped = last(c);
        stages.onDeduped(deduped);
        stages.onDeduped(deduped);
        assertThat(events(c)).extracting(OutboxEvent::getTopic)
                .containsExactly(Topics.REPORT_DEDUPED, Topics.REPORT_CODED);
        CaseVersion coded = latest(c);
        assertThat(coded.getVersionNo()).isEqualTo(2);
        assertThat(coded.getReactions()).allSatisfy(x -> {
            assertThat(x.getCodeSource()).isEqualTo(CodeSource.MODEL);
            assertThat(x.getPt()).isNotBlank();
            assertThat(x.getModelName()).isEqualTo("meddra-coder");
            assertThat(x.getModelVersion()).matches("fixture:[0-9a-f]{12}");
            assertThat(x.getModelScore()).isNotNull();
        });
        assertThat(coded.getReactions()).extracting(x -> x.getReportedTerm()).containsExactly("itchy skin",
                "a high fever");
        assertThat(cases.load(c.getId()).getStatus()).isIn(Status.CODED, Status.NEEDS_MANUAL_CODING);

        CaseEvent codedEvent = last(c);
        stages.onCoded(codedEvent);
        stages.onCoded(codedEvent);
        SafetyCase triaged = cases.load(c.getId());
        assertThat(triaged.getStatus()).isEqualTo(Status.TRIAGED);
        assertThat(triaged.getSeriousness()).isIn(Seriousness.SERIOUS, Seriousness.NON_SERIOUS);
        assertThat(triaged.getSeriousScore()).isNotNull();
        assertThat(events(c)).extracting(OutboxEvent::getTopic)
                .containsExactly(Topics.REPORT_DEDUPED, Topics.REPORT_CODED, Topics.CASE_TRIAGED);
    }

    @Test
    void narrativeOnlyReportGetsSuggestionsFromTheNarrative() {
        ReportReceived r = report("PHARMACY", List.of(), null, rx());
        stages.onReportReceived(r);
        SafetyCase c = caseOf(r);
        stages.onDeduped(last(c));
        CaseVersion v = latest(c);
        assertThat(v.getReactions()).isNotEmpty().allSatisfy(x -> {
            assertThat(x.getReportedTerm()).isEqualTo(VersionDraft.NARRATIVE_TERM);
            assertThat(x.getCodeSource()).isEqualTo(CodeSource.MODEL);
        });
        // The fixture coder is deliberately tiny: only "pyrexia" clears its 0.5 threshold for this text.
        assertThat(v.getReactions()).extracting(x -> x.getPt()).containsExactly("pyrexia");
    }

    @Test
    void reportedCriteriaDecideSeriousnessWithoutTheModel() {
        ReportReceived r = report("E2B_IMPORT", List.of("rash"), "HOSPITALIZATION", rx());
        stages.onReportReceived(r);
        SafetyCase c = caseOf(r);
        stages.onDeduped(last(c));
        stages.onCoded(last(c));
        SafetyCase after = cases.load(c.getId());
        assertThat(after.getSeriousness()).isEqualTo(Seriousness.SERIOUS);
        assertThat(after.getSeriousScore()).isNull();
    }

    @Test
    void sameEventTwiceFromTwoSourcesIsFlaggedAsPossibleDuplicate() {
        String rx = rx();
        ReportReceived first = report("PHARMACY", List.of("itchy skin"), null, rx);
        stages.onReportReceived(first);
        ReportReceived second = report("E2B_IMPORT", List.of("itchy skin"), null, rx);
        stages.onReportReceived(second);
        SafetyCase dup = cases.load(caseOf(second).getId());
        assertThat(dup.getDuplicateOfId()).isEqualTo(caseOf(first).getId());
        assertThat(dup.getDuplicateScore()).isNotNull();
        // An unrelated drug gives no candidates at all.
        ReportReceived other = report("PHARMACY", List.of("itchy skin"), null, rx());
        stages.onReportReceived(other);
        assertThat(cases.load(caseOf(other).getId()).getDuplicateOfId()).isNull();
    }

    @Test
    void malformedEventsArePoison() {
        ReportReceived noId = new ReportReceived(null, "PHARMACY", "x", Instant.now(), null, null, null, null, null,
                "t", List.of(), List.of(), null);
        assertThatThrownBy(() -> stages.onReportReceived(noId)).isInstanceOf(PoisonMessageException.class);
        ReportReceived badSource = new ReportReceived(UUID.randomUUID().toString(), "FAX", "x", Instant.now(), null,
                null, null, null, null, "t", List.of(), List.of(), null);
        assertThatThrownBy(() -> stages.onReportReceived(badSource)).isInstanceOf(PoisonMessageException.class);
        CaseEvent unknown = new CaseEvent(UUID.randomUUID().toString(), -42L, "PV-X", 1, "RECEIVED", Instant.now());
        assertThatThrownBy(() -> stages.onDeduped(unknown)).isInstanceOf(PoisonMessageException.class);
    }
}
