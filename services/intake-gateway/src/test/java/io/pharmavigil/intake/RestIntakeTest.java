// REST intake: pharmacy reports and E2B(R3) file uploads become receipts plus one report.received event,
// resends are recognised, bad input is explained, and roles are enforced.
package io.pharmavigil.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import io.micrometer.core.instrument.MeterRegistry;
import io.pharmavigil.common.e2b.E2bSamples;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.security.Roles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RestIntakeTest extends IntakeTest {

    @Autowired MeterRegistry meters;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String report(String id, String narrative) {
        return """
                {"pharmacyReportId":"%s","patientAgeYears":61,"patientSex":"M","onsetDate":"2026-09-30",
                 "narrative":"%s",
                 "drugs":[{"rxcui":"83367","name":"Atorvastatin 20 mg tablet","role":"PS","doseText":"20 mg daily"}],
                 "reactionTerms":["leg cramps"]}""".formatted(id, narrative);
    }

    @Test
    void pharmacyReportBecomesReceiptAndOneEvent() throws Exception {
        String id = unique("PH");
        String body = mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(report(id, "Leg cramps since Tuesday.")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.source").value("PHARMACY"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String receiptId = JSON.readTree(body).get("receiptId").asString();

        var events = outbox.findByAggregateId(receiptId);
        assertThat(events).hasSize(1);
        JsonNode event = JSON.readTree(events.get(0).getPayload());
        assertThat(event.get("source").asString()).isEqualTo("PHARMACY");
        assertThat(event.get("sourceReportId").asString()).isEqualTo(id);
        assertThat(event.get("reporterType").asString()).isEqualTo("PHARMACIST");
        assertThat(event.get("country").asString()).isEqualTo("US");
        assertThat(event.get("drugs").get(0).get("role").asString()).isEqualTo("PS");
        assertThat(event.get("eventId").asString()).isEqualTo(events.get(0).getEventId());

        mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON).content(report(id, "Leg cramps since Tuesday.")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receiptId").value(receiptId))
                .andExpect(jsonPath("$.duplicate").value(true));
        assertThat(outbox.findByAggregateId(receiptId)).hasSize(1);
        assertThat(meters.counter("pv.reports.received", "source", "PHARMACY", "kind", "new").count()).isPositive();
        assertThat(meters.counter("pv.reports.received", "source", "PHARMACY", "kind", "duplicate").count())
                .isPositive();

        mvc.perform(get("/api/intake/receipts/" + receiptId).header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        mvc.perform(get("/api/intake/receipts/nope").header("Authorization", bearer(Roles.PHARMACIST)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidReportsAreExplained() throws Exception {
        mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pharmacyReportId\":\"x\",\"narrative\":\" \",\"drugs\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(unique("PH"), "ok").replace("\"M\"", "\"male\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(unique("PH"), "ok").replace("2026-09-30", "2999-01-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        mvc.perform(post("/api/intake/reports").contentType(MediaType.APPLICATION_JSON).content(report("x", "y")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/intake/reports").header("Authorization", bearer(Roles.PATIENT))
                        .contentType(MediaType.APPLICATION_JSON).content(report(unique("PH"), "y")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/intake/e2b").header("Authorization", bearer(Roles.PHARMACIST))
                        .contentType(MediaType.APPLICATION_XML).content(new E2bWriter().write(E2bSamples.sample())))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewerImportsE2bBatchFile() throws Exception {
        String a = unique("US-FILE");
        String b = unique("US-FILE");
        mvc.perform(post("/api/intake/e2b").header("Authorization", bearer(Roles.SAFETY_REVIEWER))
                        .contentType(MediaType.APPLICATION_XML).content(E2bSamples.twoMessageBatch(a, b)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sourceReportId").value(a))
                .andExpect(jsonPath("$[1].sourceReportId").value(b))
                .andExpect(jsonPath("$[0].source").value("E2B_IMPORT"));
        assertThat(receipts.findFirstBySourceAndSourceReportIdOrderByIdDesc(
                io.pharmavigil.intake.receipts.IntakeReceipt.Source.E2B_IMPORT, b)).isPresent();

        mvc.perform(post("/api/intake/e2b").header("Authorization", bearer(Roles.SAFETY_REVIEWER))
                        .contentType(MediaType.APPLICATION_XML).content("<ichicsr/>"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reasons[0]", containsString("MCCI_IN200100UV01")));
    }
}
