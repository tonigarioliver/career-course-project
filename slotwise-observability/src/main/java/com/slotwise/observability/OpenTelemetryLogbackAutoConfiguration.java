package com.slotwise.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.Bean;

// Connects the Logback OpenTelemetryAppender (declared in logback-spring.xml) to the OpenTelemetry bean, which is
// what makes the log records leave the process. Activated by the .imports file, like the other auto-configurations here.
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
@ConditionalOnClass(OpenTelemetryAppender.class)
@ConditionalOnBean(OpenTelemetry.class)
public class OpenTelemetryLogbackAutoConfiguration {

    @Bean
    InitializingBean installOpenTelemetryAppender(final OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
