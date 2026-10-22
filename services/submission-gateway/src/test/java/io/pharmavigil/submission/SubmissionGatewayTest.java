// The gateway on real Oracle (or SQL Server), Kafka and Artemis: case.ready becomes a JMS message, the mock
// regulator answers, and the answer flows back as submission.status (QUEUED, then ACK or NACK). An invalid file
// is NACKed with its schema errors; a replayed case.ready sends nothing new; poison messages end in the Kafka
// dead-letter topic or the broker's DLQ.
package io.pharmavigil.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.pharmavigil.common.e2b.E2bSamples;
import io.pharmavigil.common.e2b.E2bValidator;
import io.pharmavigil.common.e2b.E2bWriter;
import io.pharmavigil.common.events.CaseReady;
import io.pharmavigil.common.events.SubmissionStatus;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.security.Roles;
import io.pharmavigil.common.security.TokenService;
import io.pharmavigil.common.testing.TestArtemis;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.common.testing.TestKafka;
import io.pharmavigil.submission.gateway.Jms;
import io.pharmavigil.submission.model.OutboxEvent;
import io.pharmavigil.submission.model.OutboxEventRepository;
import io.pharmavigil.submission.model.SubmissionMessage;
import io.pharmavigil.submission.model.SubmissionMessageRepository;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SubmissionGatewayTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
        TestKafka.register(registry);
        TestArtemis.register(registry);
    }

    @Autowired SubmissionMessageRepository messages;
    @Autowired OutboxEventRepository outbox;
    @Autowired E2bValidator validator;
    @Autowired JmsTemplate jms;
    @Autowired JsonMapper json;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;

    static long nextCaseId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);

    private static void send(String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestKafka.bootstrap()), new StringSerializer(), new StringSerializer())) {
            p.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private CaseReady ready(long caseId, String xml) {
        return new CaseReady(UUID.randomUUID().toString(), caseId, "PV-2026-" + caseId, 3,
                "US-PHARMAVIGIL-PV-2026-" + caseId, xml, Instant.now());
    }

    private List<SubmissionStatus> statuses(long caseId) {
        return outbox.findByAggregateIdOrderByIdAsc(Long.toString(caseId)).stream().map(OutboxEvent::getPayload)
                .map(p -> json.readValue(p, SubmissionStatus.class)).toList();
    }

    private SubmissionMessage answered(long caseId) throws InterruptedException {
        await("answer for case " + caseId, () -> messages.findByCaseIdOrderByIdAsc(caseId).stream()
                .anyMatch(SubmissionMessage::answered));
        return messages.findByCaseIdOrderByIdAsc(caseId).get(0);
    }

    @Test
    void validFileIsAcknowledgedWhenTheRegulatorCanValidate() throws Exception {
        long caseId = nextCaseId++;
        CaseReady r = ready(caseId, new E2bWriter().write(E2bSamples.sample()));
        String payload = json.writeValueAsString(r);
        send(Topics.CASE_READY, Long.toString(caseId), payload);
        SubmissionMessage m = answered(caseId);
        if (validator.schemaAvailable()) {
            assertThat(m.getStatus()).isEqualTo("ACK");
            assertThat(m.getReason()).isNull();
        } else {
            assertThat(m.getStatus()).isEqualTo("NACK");
            assertThat(m.getReason()).startsWith("regulator cannot validate");
        }
        assertThat(m.getMessageId()).startsWith("PV-2026-" + caseId + "-V3-");
        assertThat(statuses(caseId)).extracting(SubmissionStatus::status)
                .containsExactly(SubmissionStatus.Status.QUEUED, SubmissionStatus.Status.valueOf(m.getStatus()));

        // Replay: the same event again sends nothing and records nothing.
        send(Topics.CASE_READY, Long.toString(caseId), payload);
        long marker = nextCaseId++;
        send(Topics.CASE_READY, Long.toString(marker), json.writeValueAsString(ready(marker,
                new E2bWriter().write(E2bSamples.sample()))));
        answered(marker);
        assertThat(messages.findByCaseIdOrderByIdAsc(caseId)).hasSize(1);
        assertThat(statuses(caseId)).hasSize(2);

        mvc.perform(get("/api/submissions/case/" + caseId).header("Authorization",
                        "Bearer " + tokens.issue("rev", List.of(Roles.SAFETY_REVIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value(m.getStatus()));
    }

    @Test
    void invalidFileIsNacked() throws Exception {
        long caseId = nextCaseId++;
        String broken = new E2bWriter().write(E2bSamples.sample())
                .replaceFirst("<investigationEvent([^>]*)>", "<investigationEvent$1><notAnE2bElement/>");
        send(Topics.CASE_READY, Long.toString(caseId), json.writeValueAsString(ready(caseId, broken)));
        SubmissionMessage m = answered(caseId);
        assertThat(m.getStatus()).isEqualTo("NACK");
        if (validator.schemaAvailable()) {
            assertThat(m.getReason()).startsWith("schema validation failed").contains("notAnE2bElement");
        }
        assertThat(statuses(caseId).get(1).reason()).isEqualTo(m.getReason());
    }

    @Test
    void poisonMessagesEndInDeadLetters() throws Exception {
        String poison = "{not json " + UUID.randomUUID();
        try (var dlq = TestKafka.consumer(List.of(Topics.dlq(Topics.CASE_READY)))) {
            send(Topics.CASE_READY, "poison", poison);
            assertThat(TestKafka.pollUntil(dlq, rec -> poison.equals(rec.value()), Duration.ofSeconds(30))).isNotNull();
        }
        String badAck = "{\"messageId\":\"no-such-message-" + UUID.randomUUID() + "\",\"outcome\":\"ACK\"}";
        jms.send(Jms.ACK, s -> s.createTextMessage(badAck));
        jms.setReceiveTimeout(1000);
        await("bad acknowledgement in the broker DLQ", () -> {
            Object m = jms.receiveSelectedAndConvert("DLQ", null);
            return badAck.equals(m);
        });
    }
}
