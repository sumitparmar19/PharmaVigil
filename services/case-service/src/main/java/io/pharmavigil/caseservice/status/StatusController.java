// Public status of the platform (GET /api/status, no sign-in): whether each service answers its health check,
// how far case-service's Kafka consumers lag behind, and which model versions are loaded. Used by the live
// demo's deploy health check, the daily live smoke test and visitors. It contains no secrets and no user or case
// data; every dependency check has a short timeout and reports "UNKNOWN" instead of failing the page.
package io.pharmavigil.caseservice.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.pharmavigil.caseservice.models.ModelBundle;
import io.pharmavigil.caseservice.models.ModelInfo;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    public record Status(String status, String checkedAt, Map<String, String> services, Long kafkaLag,
            String modelBundle, List<ModelInfo> models) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String GROUP = "case-service";

    private final ModelBundle bundle;
    private final Clock clock;
    private final Map<String, String> peers;
    private final String bootstrap;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public StatusController(ModelBundle bundle, Clock clock,
            @Value("${pv.status.peers:pharmacy-service=http://pharmacy-service:8081,"
                    + "intake-gateway=http://intake-gateway:8082,signal-service=http://signal-service:8084,"
                    + "submission-gateway=http://submission-gateway:8085}") String peers,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.bundle = bundle;
        this.clock = clock;
        this.peers = new LinkedHashMap<>();
        for (String p : peers.split(",")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2) {
                this.peers.put(kv[0], kv[1]);
            }
        }
        this.bootstrap = bootstrap;
    }

    @GetMapping
    public Status status() {
        Map<String, String> services = new LinkedHashMap<>();
        services.put("case-service", "UP");
        peers.forEach((name, base) -> services.put(name, health(base)));
        List<ModelInfo> models = new java.util.ArrayList<>();
        bundle.coder().ifPresent(m -> models.add(m.info()));
        bundle.seriousness().ifPresent(m -> models.add(m.info()));
        bundle.duplicates().ifPresent(m -> models.add(m.info()));
        boolean up = services.values().stream().allMatch("UP"::equals);
        return new Status(up ? "UP" : "DEGRADED", clock.instant().toString(), services, kafkaLag(), bundle.kind(),
                models);
    }

    private String health(String base) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                    .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"UP\"") ? "UP" : "DOWN";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNKNOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    /** Messages on case-service's topics that its consumer group has not processed yet; null when unknown. */
    Long kafkaLag() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis() * 2);
        try (Admin admin = Admin.create(p)) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(4, TimeUnit.SECONDS);
            if (committed.isEmpty()) {
                return 0L;
            }
            Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
            committed.keySet().forEach(tp -> latest.put(tp, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(latest).all()
                    .get(4, TimeUnit.SECONDS);
            long lag = 0;
            for (var e : committed.entrySet()) {
                if (e.getValue() != null && ends.containsKey(e.getKey())) {
                    lag += Math.max(0, ends.get(e.getKey()).offset() - e.getValue().offset());
                }
            }
            return lag;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
