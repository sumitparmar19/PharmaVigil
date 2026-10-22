// JMS queue depth for Prometheus (pv_jms_queue_depth{queue=...}): the gateway browses its queues on a timer
// (browsing does not consume). While the broker is unreachable the gauge reads NaN instead of a stale number.
package io.pharmavigil.submission.gateway;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class QueueDepth {

    private static final Logger log = LoggerFactory.getLogger(QueueDepth.class);
    static final List<String> QUEUES = List.of(Jms.OUTBOUND, Jms.ACK, "DLQ");

    private final JmsTemplate jms;
    private final Map<String, Double> depth = new ConcurrentHashMap<>();

    public QueueDepth(JmsTemplate jms, MeterRegistry registry) {
        this.jms = jms;
        for (String q : QUEUES) {
            depth.put(q, Double.NaN);
            Gauge.builder("pv.jms.queue.depth", depth, d -> d.get(q)).description("Messages waiting in a JMS queue")
                    .tag("queue", q).register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${pv.metrics.queue-depth-interval:PT15S}")
    public void refresh() {
        for (String q : QUEUES) {
            try {
                Integer n = jms.browse(q, (session, browser) -> Collections.list(browser.getEnumeration()).size());
                depth.put(q, n == null ? Double.NaN : n.doubleValue());
            } catch (RuntimeException e) {
                depth.put(q, Double.NaN);
                log.debug("cannot browse {}: {}", q, e.toString());
            }
        }
    }

    double depth(String queue) {
        return depth.get(queue);
    }
}
