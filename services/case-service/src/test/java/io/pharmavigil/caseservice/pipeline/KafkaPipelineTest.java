// The pipeline over a real Kafka broker: a report.received message becomes a coded, triaged case through the
// outbox relay and the listeners; replaying every event creates no duplicate case, version or event; a poison
// message lands in the dead-letter topic.
package io.pharmavigil.caseservice.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.pharmavigil.caseservice.cases.CaseService;
import io.pharmavigil.caseservice.model.CaseEnums.Source;
import io.pharmavigil.caseservice.model.CaseEnums.Status;
import io.pharmavigil.caseservice.model.CaseVersionRepository;
import io.pharmavigil.caseservice.model.OutboxEvent;
import io.pharmavigil.caseservice.model.OutboxEventRepository;
import io.pharmavigil.caseservice.model.SafetyCaseRepository;
import io.pharmavigil.common.events.ReportReceived;
import io.pharmavigil.common.events.Topics;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.common.testing.TestKafka;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"pv.models.dir=src/test/resources/model-bundle", "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true", "pv.outbox.enabled=true", "pv.outbox.poll-interval=PT0.2S",
        "spring.kafka.consumer.auto-offset-reset=earliest", "pv.kafka.retry-interval-ms=100"})
class KafkaPipelineTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry);
        TestKafka.register(registry);
    }

    @Autowired SafetyCaseRepository caseRepo;
    @Autowired CaseVersionRepository versions;
    @Autowired OutboxEventRepository outbox;
    @Autowired CaseService cases;
    @Autowired JsonMapper json;

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

    @Test
    void reportBecomesTriagedCaseAndReplayChangesNothing() throws Exception {
        ReportReceived r = PipelineStagesTest.report("PHARMACY", List.of("itchy skin"), null, PipelineStagesTest.rx());
        String message = json.writeValueAsString(r);
        send(Topics.REPORT_RECEIVED, r.sourceReportId(), message);

        await("case triaged", () -> caseRepo.findBySourceAndSourceReportId(Source.PHARMACY, r.sourceReportId())
                .map(c -> c.getStatus() == Status.TRIAGED).orElse(false));
        var c = caseRepo.findBySourceAndSourceReportId(Source.PHARMACY, r.sourceReportId()).orElseThrow();
        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()));
        assertThat(events).extracting(OutboxEvent::getTopic)
                .containsExactly(Topics.REPORT_DEDUPED, Topics.REPORT_CODED, Topics.CASE_TRIAGED);
        await("all events published", () -> outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId())).stream()
                .allMatch(e -> e.getPublishedAt() != null));
        int versionCount = versions.findByCaseIdOrderByVersionNoAsc(c.getId()).size();

        // Replay every event of this case, in order.
        send(Topics.REPORT_RECEIVED, r.sourceReportId(), message);
        for (OutboxEvent e : events) {
            send(e.getTopic(), e.getAggregateId(), e.getPayload());
        }
        // A marker report sent after the replay: once it is triaged, the replayed messages have been consumed.
        ReportReceived marker = PipelineStagesTest.report("PHARMACY", List.of("rash"), null, PipelineStagesTest.rx());
        send(Topics.REPORT_RECEIVED, marker.sourceReportId(), json.writeValueAsString(marker));
        await("marker triaged", () -> caseRepo.findBySourceAndSourceReportId(Source.PHARMACY, marker.sourceReportId())
                .map(x -> x.getStatus() == Status.TRIAGED).orElse(false));

        assertThat(caseRepo.findAll().stream().filter(x -> x.getSourceReportId().equals(r.sourceReportId()))).hasSize(1);
        assertThat(versions.findByCaseIdOrderByVersionNoAsc(c.getId())).hasSize(versionCount);
        assertThat(outbox.findByAggregateIdOrderByIdAsc(Long.toString(c.getId()))).hasSize(3);
        assertThat(cases.load(c.getId()).getStatus()).isEqualTo(Status.TRIAGED);
    }

    @Test
    void poisonMessageGoesToTheDeadLetterTopic() throws Exception {
        String poison = "this is not json " + System.nanoTime();
        try (var dlq = TestKafka.consumer(List.of(Topics.dlq(Topics.REPORT_DEDUPED)))) {
            send(Topics.REPORT_DEDUPED, "poison", poison);
            var record = TestKafka.pollUntil(dlq, rec -> poison.equals(rec.value()), Duration.ofSeconds(30));
            assertThat(record).isNotNull();
            assertThat(record.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
    }
}
