// E2B(R3) → report.received mapping: code lists translate both ways and the canonical report keeps every
// field the pipeline needs.
package io.pharmavigil.common.e2b;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.pharmavigil.common.events.ReportReceived;

class IcsrMappingTest {

    @Test
    void importedIcsrBecomesCanonicalReport() {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        ReportReceived r = IcsrMapping.toReport(E2bSamples.sample(), "PARTNER", "evt-1", now);
        assertThat(r.eventId()).isEqualTo("evt-1");
        assertThat(r.source()).isEqualTo("PARTNER");
        assertThat(r.sourceReportId()).isEqualTo(E2bSamples.SAFETY_REPORT_ID);
        assertThat(r.receivedAt()).isEqualTo(now);
        assertThat(r.patientSex()).isEqualTo("F");
        assertThat(r.reporterType()).isEqualTo("PHARMACIST");
        assertThat(r.onsetDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(r.reactionTerms()).containsExactly("muscle pain", "dark urine & weakness");
        assertThat(r.seriousCriteria()).isEqualTo("HOSPITALIZATION");
        assertThat(r.drugs()).extracting(ReportReceived.Drug::role).containsExactly("PS", "C");
    }

    @Test
    void secondSuspectIsSecondarySuspectAndPtFillsMissingVerbatim() {
        IcsrCase base = E2bSamples.sample();
        IcsrCase c = new IcsrCase(base.batchNumber(), base.messageId(), base.senderId(), base.receiverId(),
                base.createdAt(), base.safetyReportId(), base.worldwideCaseId(), 1, null, null, 9, "DE", null, null,
                List.of(new IcsrCase.Reaction(" ", "nausea", null, null, null, true, false, false, false, false, true)),
                List.of(new IcsrCase.Drug(1, "A", null, null, null), new IcsrCase.Drug(1, "B", null, null, null),
                        new IcsrCase.Drug(3, "C", null, null, null)),
                null);
        ReportReceived r = IcsrMapping.toReport(c, "E2B_IMPORT", "e", Instant.EPOCH);
        assertThat(r.drugs()).extracting(ReportReceived.Drug::role).containsExactly("PS", "SS", "I");
        assertThat(r.reactionTerms()).containsExactly("nausea");
        assertThat(r.seriousCriteria()).isEqualTo("DEATH,OTHER_MEDICALLY_IMPORTANT");
        assertThat(r.patientSex()).isEqualTo("UNK");
        assertThat(r.reporterType()).isEqualTo("CONSUMER");
        assertThat(r.onsetDate()).isNull();
    }

    @Test
    void codeListsRoundTrip() {
        for (String t : List.of("PHYSICIAN", "PHARMACIST", "OTHER_HCP", "LAWYER", "CONSUMER")) {
            assertThat(IcsrMapping.reporterType(IcsrMapping.qualification(t))).isEqualTo(t);
        }
        assertThat(IcsrMapping.qualification(null)).isEqualTo(5);
        assertThat(IcsrMapping.sex(IcsrMapping.sexCode("M"))).isEqualTo("M");
        assertThat(IcsrMapping.sex(IcsrMapping.sexCode("F"))).isEqualTo("F");
        assertThat(IcsrMapping.sexCode("UNK")).isNull();
        assertThat(IcsrMapping.sexCode(null)).isNull();
        assertThat(IcsrMapping.sex(9)).isEqualTo("UNK");
        assertThat(IcsrMapping.characterization("PS")).isEqualTo(1);
        assertThat(IcsrMapping.characterization("SS")).isEqualTo(1);
        assertThat(IcsrMapping.characterization("C")).isEqualTo(2);
        assertThat(IcsrMapping.characterization("I")).isEqualTo(3);
        assertThat(IcsrMapping.characterization(null)).isEqualTo(1);
        assertThat(IcsrMapping.seriousCriteria(List.of())).isEmpty();
    }

    @Test
    void twoMessageBatchReadsAsTwoCases() {
        List<IcsrCase> cases = new E2bReader().read(E2bSamples.twoMessageBatch("A-1", "A-2"));
        assertThat(cases).extracting(IcsrCase::safetyReportId).containsExactly("A-1", "A-2");
    }
}
