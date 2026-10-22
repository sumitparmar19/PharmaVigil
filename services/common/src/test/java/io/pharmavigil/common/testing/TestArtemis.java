// Shared ActiveMQ Artemis broker for tests: one container per test JVM, started on first use. Its default
// broker configuration has a dead-letter address "DLQ" and redelivers a rolled-back message up to 10 times.
package io.pharmavigil.common.testing;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.activemq.ArtemisContainer;

public final class TestArtemis {

    /** Latest image on Docker Hub; the client (2.53.0, managed by Spring Boot) speaks the same core protocol. */
    public static final String IMAGE = "apache/activemq-artemis:2.44.0-alpine";

    private static ArtemisContainer container;

    private TestArtemis() {
    }

    public static synchronized ArtemisContainer container() {
        if (container == null) {
            container = new ArtemisContainer(IMAGE).withStartupTimeout(Duration.ofMinutes(3))
                    // Test brokers only: Artemis blocks producers above 90% disk use of the host filesystem, which
                    // shared build machines can exceed without the broker being short of space.
                    .withEnv("JAVA_ARGS_APPEND", "-Dbrokerconfig.maxDiskUsage=-1");
            container.start();
        }
        return container;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.artemis.broker-url", () -> container().getBrokerUrl());
        registry.add("spring.artemis.user", () -> container().getUser());
        registry.add("spring.artemis.password", () -> container().getPassword());
    }
}
