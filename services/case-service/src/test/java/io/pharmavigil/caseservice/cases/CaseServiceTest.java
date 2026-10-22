// Case model rules on a real database (Oracle, or SQL Server with -Pmssql).
package io.pharmavigil.caseservice.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.model.AuditEventRepository;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Seriousness;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.CaseVersionRepository;
import io.pharmavigil.caseservice.model.ReviewDecision;
import io.pharmavigil.caseservice.model.ReviewDecisionRepository;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;

@SpringBootTest
@AutoConfigureMockMvc
class CaseServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired CaseService service;
    @Autowired SafetyCaseRepository cases;
    @Autowired CaseVersionRepository versions;
    @Autowired AuditEventRepository audit;
    @Autowired ReviewDecisionRepository decisions;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;

    private CaseService.IncomingReport report(String id) {
        return new CaseService.IncomingReport(Source.PHARMACY, id, LocalDate.of(2026, 10, 1), new BigDecimal("54"),
                "F", "PHARMACIST", "US", LocalDate.of(2026, 9, 28), "Muscle pain and dark urine after starting the statin",
                List.of(new VersionDraft.DrugDraft("83367", "Atorvastatin", "PS", "20 mg daily", "oral")),
                List.of("muscle pain", "dark urine"), null);
    }

    private SafetyCase newCase() {
        return service.receive(report(UUID.randomUUID().toString()), "intake").safetyCase();
    }

    @Test
    void receiveCreatesVersionOneAndIsIdempotent() {
        String id = UUID.randomUUID().toString();
        var first = service.receive(report(id), "intake");
        var again = service.receive(report(id), "intake");
        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.safetyCase().getId()).isEqualTo(first.safetyCase().getId());
        assertThat(versions.findByCaseIdOrderByVersionNoAsc(first.safetyCase().getId())).hasSize(1);
        CaseVersion v1 = tx.execute(s -> {
            CaseVersion v = service.latest(service.load(first.safetyCase().getId()));
            v.getDrugs().size();
            v.getReactions().size();
            return v;
        });
        assertThat(v1.getVersionNo()).isEqualTo(1);
        assertThat(v1.getDrugs()).extracting(d -> d.getRole()).containsExactly("PS");
        assertThat(v1.getReactions()).extracting(r -> r.getCodeSource()).containsOnly(CodeSource.NONE);
        assertThat(first.safetyCase().getCaseNumber()).startsWith("PV-");
        assertThat(audit.findByCaseIdOrderByIdAsc(first.safetyCase().getId())).extracting(a -> a.getAction())
                .containsExactly("RECEIVED");
    }

    @Test
    void revisionsAreAppendOnly() {
        SafetyCase c = newCase();
        int lock = service.load(c.getId()).getLockVersion();
        service.revise(c.getId(), lock, "coder-model", "coded by model", d -> d.reactions.replaceAll(r ->
                new VersionDraft.ReactionDraft(r.reportedTerm(), "myalgia", CodeSource.MODEL, "meddra-coder", "0.1.0",
                        new BigDecimal("0.91234"))));
        List<CaseVersion> all = tx.execute(s -> {
            List<CaseVersion> list = versions.findByCaseIdOrderByVersionNoAsc(c.getId());
            list.forEach(v -> v.getReactions().size());
            return list;
        });
        assertThat(all).extracting(CaseVersion::getVersionNo).containsExactly(1, 2);
        assertThat(all.get(0).getReactions()).extracting(r -> r.getPt()).containsOnlyNulls();
        assertThat(all.get(1).getReactions()).allSatisfy(r -> {
            assertThat(r.getPt()).isEqualTo("myalgia");
            assertThat(r.getModelName()).isEqualTo("meddra-coder");
            assertThat(r.getModelScore()).isEqualByComparingTo("0.91234");
        });
        assertThat(service.load(c.getId()).getCurrentVersion()).isEqualTo(2);
    }

    @Test
    void immutableVersionCannotBeChangedInPlace() {
        SafetyCase c = newCase();
        tx.executeWithoutResult(s -> {
            CaseVersion v1 = versions.findByCaseIdAndVersionNo(c.getId(), 1).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(v1, "reportedText", "tampered");
        });
        assertThat(versions.findByCaseIdAndVersionNo(c.getId(), 1).orElseThrow().getReportedText())
                .startsWith("Muscle pain");
    }

    @Test
    void staleEditIsRejected() {
        SafetyCase c = newCase();
        int lock = service.load(c.getId()).getLockVersion();
        service.revise(c.getId(), lock, "reviewer-a", "edit A", d -> d.country = "CA");
        assertThatThrownBy(() -> service.revise(c.getId(), lock, "reviewer-b", "edit B", d -> d.country = "GB"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(versions.findByCaseIdOrderByVersionNoAsc(c.getId())).hasSize(2);
    }

    @Test
    void statusMachineAndDeadlines() {
        SafetyCase c = newCase();
        assertThat(c.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 16)); // unknown counts as serious: 15 days
        assertThatThrownBy(() -> service.moveTo(c.getId(), Status.SUBMITTED, "x"))
                .isInstanceOf(IllegalStateException.class);
        service.moveTo(c.getId(), Status.CODED, "pipeline");
        service.moveTo(c.getId(), Status.TRIAGED, "pipeline");
        SafetyCase nonSerious = service.setSeriousness(c.getId(), Seriousness.NON_SERIOUS, new BigDecimal("0.12"), "triage");
        assertThat(nonSerious.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(audit.findByCaseIdOrderByIdAsc(c.getId())).extracting(a -> a.getAction())
                .containsExactly("RECEIVED", "STATUS", "STATUS", "SERIOUSNESS");
    }

    @Test
    void decisionsKeepSuggestionAndBecomeFeedbackWhenChanged() {
        SafetyCase c = newCase();
        var suggestion = new ReviewDecision.Suggestion("myalgia", "meddra-coder", "0.1.0", new BigDecimal("0.8"));
        assertThat(service.decide(c.getId(), "rev", ReviewDecision.Field.PT, "muscle pain", suggestion, "myalgia")
                .isAccepted()).isTrue();
        ReviewDecision changed = service.decide(c.getId(), "rev", ReviewDecision.Field.PT, "dark urine", suggestion,
                "chromaturia");
        assertThat(changed.isAccepted()).isFalse();
        assertThat(decisions.findByAcceptedFalseOrderByIdAsc()).extracting(ReviewDecision::getId)
                .contains(changed.getId());
    }

    @Test
    void narrativesAreAppendedAndApprovedOnce() {
        SafetyCase c = newCase();
        var draft = service.addNarrative(c.getId(), "Template narrative", NarrativeSource.TEMPLATE, null, "pipeline");
        var edited = service.addNarrative(c.getId(), "Reviewer narrative", NarrativeSource.REVIEWER, null, "rev");
        assertThat(edited.getId()).isNotEqualTo(draft.getId());
        assertThat(service.approveNarrative(edited.getId(), "rev").getApprovedBy()).isEqualTo("rev");
        assertThatThrownBy(() -> service.approveNarrative(edited.getId(), "rev2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queueOrdersSeriousFirstThenDeadline() {
        SafetyCase a = newCase();
        SafetyCase b = newCase();
        for (SafetyCase c : List.of(a, b)) {
            service.moveTo(c.getId(), Status.TRIAGED, "pipeline");
        }
        service.setSeriousness(a.getId(), Seriousness.NON_SERIOUS, null, "triage");
        service.setSeriousness(b.getId(), Seriousness.SERIOUS, null, "triage");
        List<Long> ids = service.queue().stream().map(SafetyCase::getId).filter(i -> i.equals(a.getId())
                || i.equals(b.getId())).toList();
        assertThat(ids).containsExactly(b.getId(), a.getId());
        service.markDuplicate(a.getId(), b.getId(), new BigDecimal("0.97"), "dup-model");
        assertThat(service.load(a.getId()).getDuplicateOfId()).isEqualTo(b.getId());
    }

    @Test
    void reviewerApiShowsCaseAndEnforcesRoles() throws Exception {
        SafetyCase c = newCase();
        String reviewer = "Bearer " + tokens.issue("rev", List.of(Roles.SAFETY_REVIEWER));
        mvc.perform(get("/api/cases/{id}", c.getId()).header("Authorization", reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head.caseNumber").value(c.getCaseNumber()))
                .andExpect(jsonPath("$.drugs[0].rxcui").value("83367"))
                .andExpect(jsonPath("$.disclaimer").value("Spontaneous reports do not prove that a drug caused a reaction."));
        mvc.perform(get("/api/cases/queue").header("Authorization", reviewer)).andExpect(status().isOk());
        mvc.perform(get("/api/cases/{id}", c.getId())
                .header("Authorization", "Bearer " + tokens.issue("ph", List.of(Roles.PHARMACIST))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cases/{id}", 987654321L).header("Authorization", reviewer))
                .andExpect(status().isNotFound());
    }
}
