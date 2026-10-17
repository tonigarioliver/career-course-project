package com.slotwise.booking.service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

// Carries a trace across the outbox: the request thread stores its traceparent in the row (capture), the relay —
// another thread, later — reopens it around the Kafka send (continueTrace). Only the standard W3C header is stored.
@Component
@RequiredArgsConstructor
class OutboxTraceContext {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    // The current span's traceparent, or null when there is no active trace (e.g. a test calling the service directly).
    @Nullable String capture() {
        final Span current = this.tracer.currentSpan();
        if (current == null) {
            return null;
        }
        final Map<String, String> carrier = new HashMap<>();
        this.propagator.inject(current.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    // Opens a span that continues the stored trace (or a new trace if none was stored); close() ends it.
    Scope continueTrace(final @Nullable String traceparent) {
        final Span.Builder builder = traceparent == null
                ? this.tracer.spanBuilder()
                : this.propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
        final Span span = builder.name("outbox publish").start();
        return new Scope(span, this.tracer.withSpan(span));
    }

    // shortcut: a failed send does not mark the span as an error; add span.error(e) when traces get exported.
    record Scope(Span span, Tracer.SpanInScope inScope) implements AutoCloseable {
        @Override
        public void close() {
            this.inScope.close();
            this.span.end();
        }
    }
}
