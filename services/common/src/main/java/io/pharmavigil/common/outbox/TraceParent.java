// Carries a W3C trace context across the outbox: the writer stores the current `traceparent` with the row and
// the relay makes it current again while it publishes, so one trace runs from an HTTP request through the outbox
// and Kafka to every consumer. Without the OpenTelemetry Java agent there is no active span and this is a no-op.
package io.pharmavigil.common.outbox;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

public final class TraceParent {

    private static final String HEADER = "traceparent";

    private static final TextMapGetter<String> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return java.util.List.of(HEADER);
        }

        @Override
        public String get(String carrier, String key) {
            return HEADER.equals(key) ? carrier : null;
        }
    };

    private TraceParent() {
    }

    /** The current span's traceparent, or null when no span is active. */
    public static String current() {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        return carrier.get(HEADER);
    }

    /** Makes the stored context current (close the scope afterwards); a no-op scope for null. */
    public static Scope restore(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Scope.noop();
        }
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), traceparent, GETTER).makeCurrent();
    }
}
