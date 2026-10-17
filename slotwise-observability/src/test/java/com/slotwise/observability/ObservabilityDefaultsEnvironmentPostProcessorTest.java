package com.slotwise.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityDefaultsEnvironmentPostProcessorTest {

    private final ObservabilityDefaultsEnvironmentPostProcessor processor = new ObservabilityDefaultsEnvironmentPostProcessor();

    @Test
    void addsDefaults_whenNothingSetThem() {
        final var environment = new MockEnvironment();

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(environment.getProperty("management.opentelemetry.logging.export.otlp.endpoint"))
                .isEqualTo("http://localhost:4318/v1/logs");
    }

    @Test
    void aServiceProperty_winsOverTheDefault() {
        final var environment = new MockEnvironment().withProperty("management.tracing.sampling.probability", "0.1");

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }

    @Test
    void defaultsAreTheLowestPrioritySource() {
        final var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("later", java.util.Map.of()));

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        // addLast on top of an existing last source: ours ends up after it (checked by name order).
        final var names = environment.getPropertySources().stream().map(s -> s.getName()).toList();
        assertThat(names.indexOf(ObservabilityDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isGreaterThan(names.indexOf("later"));
    }
}
