// The demo replay in intake-gateway: real FAERS-shaped records from a slice file become E2B(R3) messages, three in
// four go through the SOAP endpoint over HTTP with WS-Security and one in four through REST intake, each lands as
// a receipt with the DEMO-<run>-<primaryid> id and one outbox event; a second run within the cooldown is refused
// with an explanation; the run's progress is readable; and the slice's outcome codes become E2B seriousness criteria.
package io.pharmavigil.intake.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import io.pharmavigil.common.e2b.IcsrCase;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.intake.IntakeTest;
import io.pharmavigil.intake.receipts.IntakeReceipt;

@TestPropertySource(properties = {"pv.demo.enabled=true", "pv.demo.pause-ms=0", "pv.demo.cooldown-seconds=120"})
class DemoReplayTest extends IntakeTest {

    private static final Path SLICE;

    static {
        try {
            SLICE = Files.createTempFile("replay", ".jsonl");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                b.append("{\"faers_primaryid\":\"").append(900100 + i).append("\",\"faers_caseid\":\"").append(9001 + i)
                        .append("\",\"quarter\":\"2026Q2\",\"fda_date\":\"2026-05-0").append(1 + i % 9)
                        .append("\",\"event_date\":null,\"age_years\":63.0,\"sex\":\"F\",\"reporter_type\":\"PHYSICIAN\",")
                        .append("\"country\":\"US\",\"outcomes\":[\"HO\"],\"drugs\":[{\"name\":\"ATORVASTATIN\",\"role\":\"PS\",")
                        .append("\"route\":\"ORAL\",\"dose\":\"40 MG\"}],\"reactions\":[\"myalgia\",\"rhabdomyolysis\"]}\n");
            }
            b.append("{\"faers_primaryid\":\"1\",\"faers_caseid\":\"1\",\"reporter_type\":\"OTHER\",\"drugs\":[],\"reactions\":[]}\n");
            Files.writeString(SLICE, b.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void slice(DynamicPropertyRegistry registry) {
        registry.add("pv.demo.slice-file", SLICE::toString);
    }

    @Autowired DemoReplay replay;

    @Test
    void aRunSendsRealFaersRecordsThroughSoapAndRestAndRespectsTheCooldown() throws Exception {
        String reviewer = bearer(Roles.SAFETY_REVIEWER);
        mvc.perform(get("/api/intake/demo").header("Authorization", reviewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportsAvailable").value(8)); // bad row skipped

        String body = mvc.perform(post("/api/intake/demo/runs").header("Authorization", reviewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"size\":8}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = body.replaceAll(".*\"runId\":\"([a-z0-9]+)\".*", "$1");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        DemoReplay.RunView v = replay.get(runId);
        while (v.finishedAt() == null && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            v = replay.get(runId);
        }
        assertThat(v.errors()).isEmpty();
        assertThat(v.sent()).isEqualTo(8);
        assertThat(v.accepted()).isEqualTo(8);
        assertThat(v.viaSoap()).isEqualTo(6);
        assertThat(v.viaRest()).isEqualTo(2);
        List<IntakeReceipt> mine = receipts.findAll().stream()
                .filter(r -> r.getSourceReportId().startsWith(DemoIds.PREFIX + runId + "-")).toList();
        assertThat(mine).hasSize(8);
        assertThat(mine).extracting(r -> r.getSource().name()).containsOnly("PARTNER", "E2B_IMPORT");
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(DemoIds.PREFIX + runId + "-")).hasSize(8);

        mvc.perform(get("/api/intake/demo/runs/" + runId).header("Authorization", reviewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(8));
        mvc.perform(post("/api/intake/demo/runs").header("Authorization", reviewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"size\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("before starting another run")));
        mvc.perform(post("/api/intake/demo/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/intake/demo/runs/nosuchrun").header("Authorization", reviewer))
                .andExpect(status().isNotFound());
    }

    @Test
    void faersOutcomeCodesBecomeSeriousnessCriteriaOnEveryReaction() {
        var r = new DemoReplay.SliceReport("5", "6", "2026Q2", "2026-05-02", "2026-04-28", 70.0, "M", "PHARMACIST", "GB",
                List.of("DE", "OT"), List.of(new DemoReplay.SliceDrug("METFORMIN", "SS", null, null),
                        new DemoReplay.SliceDrug("ASPIRIN", "C", "ORAL", "81 MG")), List.of("lactic acidosis"));
        IcsrCase c = replay.toIcsr("abcdefghij", "DEMO-abcdefghij-5", r);
        assertThat(c.safetyReportId()).isEqualTo("DEMO-abcdefghij-5");
        assertThat(c.worldwideCaseId()).isEqualTo("FAERS-6");
        assertThat(c.reporterQualification()).isEqualTo(2);
        assertThat(c.patientSex()).isEqualTo(1);
        assertThat(c.reactions()).singleElement().satisfies(x -> {
            assertThat(x.death()).isTrue();
            assertThat(x.otherMedicallyImportant()).isTrue();
            assertThat(x.hospitalization()).isFalse();
            assertThat(x.onset()).hasToString("2026-04-28");
        });
        assertThat(c.drugs()).extracting(IcsrCase.Drug::characterization).containsExactly(1, 2);
        assertThat(c.narrative()).isNull(); // FAERS publishes no narrative; nothing is invented
    }
}
