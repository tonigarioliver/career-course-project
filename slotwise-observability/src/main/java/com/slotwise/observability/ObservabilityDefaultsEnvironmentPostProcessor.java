package com.slotwise.observability;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

// Default properties every service gets by depending on this module. Added *last*, so the lowest priority:
// a service's application.yml, env vars or command line override any of them. Registered in META-INF/spring.factories.
public class ObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "slotwiseObservabilityDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            // Boot's default is 0.1: 9 of 10 requests would get a traceId nobody records. Fine for a learning
            // project; lower it for real traffic.
            "management.tracing.sampling.probability", "1.0",
            // Where the spans go: the OpenTelemetry Collector from docker-compose.yml (OTLP over HTTP). Point it
            // at another collector (e.g. an ADOT one) with this property or MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT.
            "management.opentelemetry.tracing.export.otlp.endpoint", "http://localhost:4318/v1/traces",
            // Logs go the same way (Logback -> OpenTelemetryAppender -> OTLP -> Collector -> Loki).
            "management.opentelemetry.logging.export.otlp.endpoint", "http://localhost:4318/v1/logs",
            // Spans + trace propagation through the message headers, producer and listener side.
            "spring.kafka.template.observation-enabled", "true",
            "spring.kafka.listener.observation-enabled", "true",
            // ECS (Elastic Common Schema) JSON, one object per line (@timestamp, log.level, service.name,
            // trace.id, span.id...): a log platform filters by trace.id without parsing text.
            "logging.structured.format.console", "ecs");

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
    }
}
