// Outbox relay (engineering rule 3): a service writes its event into outbox_event in the same transaction as
// the change; this relay publishes unpublished rows to Kafka in id order and marks them published. Delivery is
// at least once (a crash between send and mark resends a row), which is why every consumer is idempotent.
// Works with any service whose outbox_event table has the common shape (Oracle and SQL Server). The row's stored
// trace context is made current while it is sent, so the trace continues through Kafka (TraceParent).
package io.pharmavigil.common.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import io.opentelemetry.context.Scope;

public class OutboxRelay implements SmartLifecycle {

    public static final String EVENT_ID_HEADER = "pv-event-id";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record Row(long id, String eventId, String aggregateId, String topic, String payload, String traceParent) {
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

    /**
     * Publish one batch; returns how many rows were published. All rows of the batch are handed to the producer
     * first and the acknowledgements are awaited afterwards in id order (the idempotent producer keeps the order
     * per partition), so one batch costs about one broker round trip instead of one per row. Rows are marked up to
     * the first failure; the failed row and everything after it are sent again next time (at least once).
     */
    public synchronized int publishPending() {
        List<Row> rows = jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, event_id, aggregate_id, topic, payload, trace_parent "
                    + "FROM outbox_event WHERE published_at IS NULL ORDER BY id");
            ps.setMaxRows(batchSize);
            return ps;
        }, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6)));
        List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
        Exception sendError = null;
        for (Row r : rows) {
            try (Scope trace = TraceParent.restore(r.traceParent())) {
                ProducerRecord<String, String> record = new ProducerRecord<>(r.topic(), r.aggregateId(), r.payload());
                record.headers().add(EVENT_ID_HEADER, r.eventId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                acks.add(kafka.send(record));
            } catch (Exception e) {
                sendError = e; // e.g. the producer could not get metadata in time: stop handing over rows
                break;
            }
        }
        List<Object[]> published = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());
        for (int i = 0; i < acks.size(); i++) {
            Row r = rows.get(i);
            try {
                acks.get(i).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                published.add(new Object[] {now, r.id()});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return markPublished(published);
            } catch (Exception e) {
                failed(r, e);
                return markPublished(published);
            }
        }
        if (sendError != null) {
            failed(rows.get(acks.size()), sendError);
        }
        return markPublished(published);
    }

    private int markPublished(List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("UPDATE outbox_event SET published_at = ? WHERE id = ?", rows);
        }
        return rows.size();
    }

    private void failed(Row r, Exception e) {
        jdbc.update("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = ?", r.id());
        log.warn("outbox event {} to {} not published yet: {}", r.eventId(), r.topic(), e.toString());
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
                int n;
                do { // a full batch means more is waiting: keep going instead of sleeping
                    n = publishPending();
                } while (n == batchSize && !Thread.currentThread().isInterrupted());
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
