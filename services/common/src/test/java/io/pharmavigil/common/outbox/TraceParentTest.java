// TraceParent: an active span is written as a W3C traceparent and restored as the current context; without a
// span (no OpenTelemetry agent) nothing is stored and restoring is a no-op.
package io.pharmavigil.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

class TraceParentTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String SPAN = "00f067aa0ba902b7";

    @Test
    void activeSpanRoundTrips() {
        Span span = Span.wrap(SpanContext.create(TRACE, SPAN, TraceFlags.getSampled(), TraceState.getDefault()));
        String stored;
        try (Scope s = Context.root().with(span).makeCurrent()) {
            stored = TraceParent.current();
        }
        assertThat(stored).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        try (Scope s = TraceParent.restore(stored)) {
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(TRACE);
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(SPAN);
            assertThat(Span.current().getSpanContext().isRemote()).isTrue();
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void withoutASpanNothingIsStored() {
        assertThat(TraceParent.current()).isNull();
        try (Scope s = TraceParent.restore(null)) {
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
        try (Scope s = TraceParent.restore("not-a-traceparent")) {
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
    }
}
