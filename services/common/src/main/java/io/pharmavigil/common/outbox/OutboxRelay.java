// Outbox relay (engineering rule 3): a service writes its event into outbox_event in the same transaction as
// the change; this relay publishes unpublished rows to Kafka in id order and marks them published. Delivery is
// at least once (a crash between send and mark resends a row), which is why every consumer is idempotent.
// Works with any service whose outbox_event table has the common shape (Oracle and SQL Server).
package io.pharmavigil.common.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

public class OutboxRelay implements SmartLifecycle {

    public static final String EVENT_ID_HEADER = "pv-event-id";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record Row(long id, String eventId, String aggregateId, String topic, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;
    private final Duration sendTimeout;
    private ScheduledExecutorService executor;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock, Duration interval,
            int batchSize, Duration sendTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.interval = interval;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /** Publish one batch; returns how many rows were published. Stops at the first failure to keep order. */
    public synchronized int publishPending() {
        List<Row> rows = jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, event_id, aggregate_id, topic, payload FROM outbox_event "
                    + "WHERE published_at IS NULL ORDER BY id");
            ps.setMaxRows(batchSize);
            return ps;
        }, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        int sent = 0;
        for (Row r : rows) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(r.topic(), r.aggregateId(), r.payload());
                record.headers().add(EVENT_ID_HEADER, r.eventId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                jdbc.update("UPDATE outbox_event SET published_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), r.id());
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            } catch (Exception e) {
                jdbc.update("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = ?", r.id());
                log.warn("outbox event {} to {} not published yet: {}", r.eventId(), r.topic(), e.toString());
                return sent;
            }
        }
        return sent;
    }

    /** Unpublished rows (exposed as a health/metrics signal and for tests). */
    public long backlog() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                publishPending();
            } catch (RuntimeException e) {
                log.warn("outbox relay pass failed: {}", e.toString());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
