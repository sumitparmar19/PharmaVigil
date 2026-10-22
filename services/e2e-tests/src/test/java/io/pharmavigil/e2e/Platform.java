// Starts the whole platform for the end-to-end test: one Oracle with a schema per service, one Kafka, one
// Artemis, and the five Spring Boot services in their own contexts on random ports. Each service gets its
// configuration explicitly here (no application.properties is read, because all five jars carry one at the same
// classpath path); only the infrastructure addresses and test credentials differ from production.
package io.pharmavigil.e2e;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.pharmavigil.caseservice.CaseServiceApplication;
import io.pharmavigil.common.testing.TestArtemis;
import io.pharmavigil.common.testing.TestDatabases;
import io.pharmavigil.common.testing.TestKafka;
import io.pharmavigil.intake.IntakeGatewayApplication;
import io.pharmavigil.pharmacy.PharmacyServiceApplication;
import io.pharmavigil.signal.SignalServiceApplication;
import io.pharmavigil.submission.SubmissionGatewayApplication;

final class Platform implements AutoCloseable {

    final String jwtSecret = UUID.randomUUID() + "" + UUID.randomUUID();
    final ConfigurableApplicationContext intake;
    final ConfigurableApplicationContext cases;
    final ConfigurableApplicationContext submission;
    final ConfigurableApplicationContext signals;
    final ConfigurableApplicationContext pharmacy;
    private final List<ConfigurableApplicationContext> all = new ArrayList<>();

    Platform() {
        var db = TestDatabases.container();
        String password = "Pv" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        for (String schema : List.of("pv_intake", "pv_case", "pv_submission", "pv_signal", "pv_pharmacy")) {
            createSchema(db.getJdbcUrl(), db.getPassword(), schema, password);
        }
        intake = start(IntakeGatewayApplication.class, "intake-gateway", "pv_intake", password, "intake", Map.of(
                "spring.webservices.path", "/ws",
                "pv.soap.partner-user", "partner-e2e",
                "pv.soap.partner-password", "test-only-partner-password"));
        cases = start(CaseServiceApplication.class, "case-service", "pv_case", password, "case", Map.of(
                "pv.models.dir", "../case-service/src/test/resources/model-bundle",
                "pv.narrative.api-key", "",
                "pv.narrative.model", ""));
        submission = start(SubmissionGatewayApplication.class, "submission-gateway", "pv_submission", password,
                "submission", Map.of("pv.regulator.mock.enabled", "true"));
        signals = start(SignalServiceApplication.class, "signal-service", "pv_signal", password, "signal", Map.of(
                "pv.signals.dir", "../signal-service/src/test/resources/signal-export",
                "pv.outbox.enabled", "false"));
        pharmacy = start(PharmacyServiceApplication.class, "pharmacy-service", "pv_pharmacy", password, "pharmacy",
                Map.of("pv.intake.url", "http://localhost:" + port(intake),
                        "pv.intake.forward-interval", "PT0.5S",
                        "pv.signal-service.url", "http://localhost:" + port(signals),
                        "pv.outbox.enabled", "false"));
    }

    static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    private ConfigurableApplicationContext start(Class<?> app, String name, String user, String password,
            String flyway, Map<String, String> extra) {
        var db = TestDatabases.container();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("spring.config.name", "pv-e2e-no-config-file");
        p.put("spring.application.name", name);
        p.put("server.port", "0");
        p.put("spring.datasource.url", db.getJdbcUrl());
        p.put("spring.datasource.username", user);
        p.put("spring.datasource.password", password);
        p.put("spring.flyway.locations", "classpath:db/" + flyway + "/{vendor}");
        p.put("spring.jpa.hibernate.ddl-auto", "validate");
        p.put("spring.jpa.open-in-view", "false");
        p.put("spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type", "TIMESTAMP");
        p.put("pv.jwt.secret", jwtSecret);
        p.put("spring.kafka.bootstrap-servers", TestKafka.bootstrap());
        p.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        p.put("spring.kafka.producer.acks", "all");
        p.put("pv.outbox.poll-interval", "PT0.2S");
        p.put("pv.kafka.retry-interval-ms", "100");
        p.put("spring.artemis.mode", "native");
        p.put("spring.artemis.broker-url", TestArtemis.container().getBrokerUrl());
        p.put("spring.artemis.user", TestArtemis.container().getUser());
        p.put("spring.artemis.password", TestArtemis.container().getPassword());
        p.put("spring.jms.template.session.transacted", "true");
        p.put("spring.jms.listener.session.transacted", "true");
        p.put("pv.e2b.xsd-dir", System.getenv().getOrDefault("PV_E2B_XSD_DIR", ""));
        p.put("logging.level.org.apache.kafka", "WARN");
        p.putAll(extra);
        String[] args = p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(app).run(args);
        all.add(ctx);
        return ctx;
    }

    private static void createSchema(String url, String systemPassword, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, "system", systemPassword);
                Statement s = c.createStatement()) {
            s.execute("CREATE USER " + user + " IDENTIFIED BY \"" + password + "\" QUOTA UNLIMITED ON USERS");
            s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO " + user);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create schema " + user + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        for (int i = all.size() - 1; i >= 0; i--) {
            all.get(i).close();
        }
    }
}
