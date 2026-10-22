// M7 import/export on a real database: an E2B(R3) file becomes a case, a changed resend becomes a new version
// that keeps confirmed codes and reopens review, an identical resend changes nothing; an approved case exports
// to E2B(R3) XML that reads back to the same content and is queueable only when the official schemas pass it.
package io.pharmavigil.caseservice.e2b;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.support.TransactionTemplate;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.CodeSource;
import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersion;
import io.pharmavigil.caseservice.model.SafetyCase;
import io.pharmavigil.caseservice.model.VersionDraft;
import io.pharmavigil.common.e2b.E2bReader;
import io.pharmavigil.common.e2b.E2bSamples;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.e2b.IcsrCase;
import io.pharmavigil.common.e2b.IcsrMapping;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.common.web.ConflictException;

@SpringBootTest
@AutoConfigureMockMvc
class E2bImportExportTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
    }

    @Autowired ReportIntake intake;
    @Autowired CaseService cases;
    @Autowired E2bExportService exports;
    @Autowired E2bValidator validator;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;

    /** An E2B(R3) file as the intake gateway would deliver it: read, mapped, wrapped in report.received. */
    private static ReportReceived imported(String reportId, String narrative) {
        String xml = new E2bWriter().write(E2bSamples.sample(reportId, narrative));
        IcsrCase icsr = new E2bReader().read(xml).get(0);
        return IcsrMapping.toReport(icsr, "E2B_IMPORT", UUID.randomUUID().toString(), Instant.now());
    }

    private CaseVersion latest(Long caseId) {
        return tx.execute(s -> {
            CaseVersion v = cases.latest(cases.load(caseId));
            v.getDrugs().size();
            v.getReactions().size();
            return v;
        });
    }

    private SafetyCase approve(SafetyCase c, boolean withNarrative) {
        cases.moveTo(c.getId(), Status.TRIAGED, "test");
        cases.moveTo(c.getId(), Status.IN_REVIEW, "test");
        if (withNarrative) {
            var n = cases.addNarrative(c.getId(), "A 54-year-old woman reported muscle pain after atorvastatin.",
                    NarrativeSource.REVIEWER, null, "reviewer-1");
            cases.approveNarrative(n.getId(), "reviewer-1");
        }
        return cases.moveTo(c.getId(), Status.APPROVED, "reviewer-1");
    }

    private String bearer(String role) {
        return "Bearer " + tokens.issue("user-" + UUID.randomUUID(), List.of(role));
    }

    @Test
    void importedFileBecomesCaseAndFollowUpBecomesNewVersion() {
        String id = "US-PARTNER-" + UUID.randomUUID();
        var first = intake.accept(imported(id, "Initial report."));
        assertThat(first.created()).isTrue();
        SafetyCase c = first.safetyCase();
        CaseVersion v1 = latest(c.getId());
        assertThat(v1.getReporterType()).isEqualTo("PHARMACIST");
        assertThat(v1.getSeriousCriteria()).isEqualTo("HOSPITALIZATION");
        assertThat(v1.getDrugs()).extracting(d -> d.getRole()).containsExactly("PS", "C");
        assertThat(v1.getReactions()).extracting(r -> r.getReportedTerm())
                .containsExactly("muscle pain", "dark urine & weakness");

        // A reviewer confirms a code; the case is approved.
        cases.revise(c.getId(), cases.load(c.getId()).getLockVersion(), "reviewer-1", "code confirmed", d ->
                d.reactions.set(0, new VersionDraft.ReactionDraft("muscle pain", "Myalgia", CodeSource.REVIEWER,
                        null, null, null)));
        approve(cases.load(c.getId()), true);

        var resend = intake.accept(imported(id, "Initial report."));
        assertThat(resend.created()).isFalse();
        assertThat(resend.followUp()).isFalse();
        assertThat(cases.load(c.getId()).getCurrentVersion()).isEqualTo(2);

        var followUp = intake.accept(imported(id, "Follow-up: the patient recovered."));
        assertThat(followUp.followUp()).isTrue();
        SafetyCase after = cases.load(c.getId());
        assertThat(after.getCurrentVersion()).isEqualTo(3);
        assertThat(after.getStatus()).isEqualTo(Status.IN_REVIEW);
        CaseVersion v3 = latest(c.getId());
        assertThat(v3.getReportedText()).isEqualTo("Follow-up: the patient recovered.");
        assertThat(v3.getReactions().get(0).getPt()).isEqualTo("Myalgia");
        assertThat(v3.getReactions().get(0).getCodeSource()).isEqualTo(CodeSource.REVIEWER);
        assertThat(v3.getReactions().get(1).getPt()).isNull();
    }

    @Test
    void approvedCaseExportsToE2bThatReadsBack() {
        SafetyCase c = intake.accept(imported("US-PARTNER-" + UUID.randomUUID(), "Rash.")).safetyCase();
        approve(c, true);
        E2bExportService.Export e = exports.export(c.getId());
        IcsrCase back = new E2bReader().read(e.xml()).get(0);
        assertThat(back.safetyReportId()).isEqualTo("US-PHARMAVIGIL-" + c.getCaseNumber());
        assertThat(back.worldwideCaseId()).isEqualTo(c.getSourceReportId());
        assertThat(back.narrative()).isEqualTo("A 54-year-old woman reported muscle pain after atorvastatin.");
        assertThat(back.reporterQualification()).isEqualTo(2);
        assertThat(back.patientSex()).isEqualTo(2);
        assertThat(back.patientAgeYears()).isEqualByComparingTo("54");
        assertThat(back.drugs()).extracting(IcsrCase.Drug::characterization).containsExactly(1, 2);
        assertThat(back.reactions()).allMatch(IcsrCase.Reaction::hospitalization);
        assertThat(back.reactions()).noneMatch(IcsrCase.Reaction::death);

        if (validator.schemaAvailable()) {
            assertThat(e.validation().errors()).isEmpty();
            assertThat(e.queueable()).isTrue();
        } else {
            assertThat(e.validation().outcome()).isEqualTo(E2bValidator.Outcome.SCHEMA_UNAVAILABLE);
            assertThat(e.queueable()).isFalse();
        }
    }

    @Test
    void onlyApprovedCasesWithApprovedNarrativeExport() {
        SafetyCase open = intake.accept(imported("US-P-" + UUID.randomUUID(), "x")).safetyCase();
        assertThatThrownBy(() -> exports.export(open.getId())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only approved cases");
        SafetyCase noNarrative = approve(intake.accept(imported("US-P-" + UUID.randomUUID(), "y")).safetyCase(), false);
        assertThatThrownBy(() -> exports.export(noNarrative.getId())).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Approve a narrative");
    }

    @Test
    void exportEndpointsServeXmlAndValidation() throws Exception {
        SafetyCase c = approve(intake.accept(imported("US-P-" + UUID.randomUUID(), "z")).safetyCase(), true);
        String expected = validator.schemaAvailable() ? "VALID" : "SCHEMA_UNAVAILABLE";
        mvc.perform(get("/api/cases/" + c.getId() + "/e2b").header("Authorization", bearer(Roles.SAFETY_REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-E2B-Validation", expected))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(c.getCaseNumber())));
        mvc.perform(get("/api/cases/" + c.getId() + "/e2b/validation")
                        .header("Authorization", bearer(Roles.SAFETY_REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(expected))
                .andExpect(jsonPath("$.queueable").value(validator.schemaAvailable()));
        mvc.perform(get("/api/cases/" + c.getId() + "/e2b").header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isForbidden());
        SafetyCase open = intake.accept(imported("US-P-" + UUID.randomUUID(), "w")).safetyCase();
        mvc.perform(get("/api/cases/" + open.getId() + "/e2b/validation")
                        .header("Authorization", bearer(Roles.SAFETY_REVIEWER)))
                .andExpect(status().isConflict());
    }
}
