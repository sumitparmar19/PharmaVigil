// Shared Kafka broker for tests: one apache/kafka container (KRaft) per test JVM, started on first use.
package io.pharmavigil.common.testing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

public final class TestKafka {

    /** Latest GA broker image on Docker Hub (4.4.0 is published there only as release candidates so far). */
    public static final String IMAGE = "apache/kafka:4.3.2";

    private static KafkaContainer container;

    private TestKafka() {
    }

    public static synchronized KafkaContainer container() {
        if (container == null) {
            container = new KafkaContainer(IMAGE).withStartupTimeout(Duration.ofMinutes(3));
            container.start();
        }
        return container;
    }

    public static String bootstrap() {
        return container().getBootstrapServers();
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", TestKafka::bootstrap);
    }

    /** A consumer in a fresh group that reads the given topics from the beginning. */
    public static KafkaConsumer<String, String> consumer(List<String> topics) {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        c.subscribe(topics);
        return c;
    }

    /** Poll until a record matches or the timeout passed; returns the match, or null. */
    public static ConsumerRecord<String, String> pollUntil(KafkaConsumer<String, String> c,
            java.util.function.Predicate<ConsumerRecord<String, String>> match, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Poll until n records arrived or the timeout passed. */
    public static List<ConsumerRecord<String, String>> poll(KafkaConsumer<String, String> c, int n, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new java.util.ArrayList<>();
        long end = System.nanoTime() + timeout.toNanos();
        while (out.size() < n && System.nanoTime() < end) {
            c.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        return out;
    }
}
