// Narrative drafting against a local stand-in for the Messages API (no real API calls in tests): the request
// carries the configured model, the system rules and only structured facts; a normal answer is used, while a
// refusal, a truncated answer, a server error or missing configuration all fall back to the template.
package io.pharmavigil.caseservice.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.pharmavigil.caseservice.model.CaseEnums.NarrativeSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NarrativeDrafterTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final CaseFacts FACTS = new CaseFacts("PV-2026-ABC", new BigDecimal("54"), "F", "PHARMACIST", "US",
            LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 28),
            List.of(new CaseFacts.Drug("Atorvastatin", "PS", "20 mg daily", "oral")), List.of("myalgia"),
            "NON_SERIOUS", null);

    HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final List<JsonNode> requests = new ArrayList<>();
    final List<String> headers = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            requests.add(JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            // The SDK sends the key as x-api-key or as a bearer token depending on its version.
            String key = ex.getRequestHeaders().getFirst("x-api-key");
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            headers.add((key != null ? key : auth == null ? null : auth.replaceFirst("^Bearer ", "")) + "|"
                    + ex.getRequestHeaders().getFirst("anthropic-version"));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NarrativeDrafter drafter(String key, String model) {
        return new NarrativeDrafter(key, model, "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5), 2000, JSON);
    }

    private static String message(String text, String stopReason) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"test-model",
                 "content":[{"type":"text","text":%s}],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":20}}""".formatted(JSON.writeValueAsString(text), stopReason);
    }

    @Test
    void modelDraftIsUsedAndSeesOnlyStructuredFacts() {
        reply.set(message("A 54-year-old female patient reported myalgia.", "end_turn"));
        NarrativeDrafter d = drafter("test-key-not-real", "test-model");
        NarrativeDrafter.Draft draft = d.draft(FACTS);
        assertThat(draft.source()).isEqualTo(NarrativeSource.LLM);
        assertThat(draft.modelName()).isEqualTo("test-model");
        assertThat(draft.body()).isEqualTo("A 54-year-old female patient reported myalgia.");

        JsonNode req = requests.get(0);
        assertThat(req.path("model").asString()).isEqualTo("test-model");
        assertThat(req.toString()).contains("Do not calculate anything").contains("Do not state or suggest");
        JsonNode facts = JSON.readTree(req.path("messages").get(0).path("content").asString());
        assertThat(facts.path("reactions").get(0).asString()).isEqualTo("myalgia");
        assertThat(facts.has("reportedText")).isFalse();
        assertThat(headers.get(0)).startsWith("test-key-not-real|").doesNotEndWith("|null");
        d.destroy();
    }

    @Test
    void refusalTruncationAndErrorsFallBackToTheTemplate() {
        NarrativeDrafter d = drafter("test-key-not-real", "test-model");
        reply.set(message("", "refusal"));
        assertThat(d.draft(FACTS).source()).isEqualTo(NarrativeSource.TEMPLATE);
        reply.set(message("A 54-year-old", "max_tokens"));
        assertThat(d.draft(FACTS).source()).isEqualTo(NarrativeSource.TEMPLATE);
        status.set(400);
        reply.set("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
        assertThat(d.draft(FACTS).source()).isEqualTo(NarrativeSource.TEMPLATE);
        d.destroy();
    }

    @Test
    void withoutKeyOrModelTheTemplateIsUsedAndNothingIsCalled() {
        assertThat(drafter("", "test-model").usesModel()).isFalse();
        assertThat(drafter("k", "").usesModel()).isFalse();
        NarrativeDrafter.Draft draft = drafter("", "").draft(FACTS);
        assertThat(draft.source()).isEqualTo(NarrativeSource.TEMPLATE);
        assertThat(draft.body()).contains("54-year-old female").contains("Atorvastatin (primary suspect, 20 mg daily, oral)")
                .contains("myalgia").contains("do not prove");
        assertThat(requests).isEmpty();
    }
}
