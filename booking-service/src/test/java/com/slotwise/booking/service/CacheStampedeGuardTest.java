package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Generic, entity-agnostic proof of the stampede guard itself: simulates what several app
// instances sharing this Redis would each independently do on a cache miss — N concurrent
// callers race in for the same never-cached key. Without the Redisson lock every one of
// them would run the loader; with it, only the lock winner does.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CacheStampedeGuardTest {

    // Needed for the full application context to load — see the identical config in
    // ReservationServiceIntegrationTest for why (ConversionService is only auto-registered
    // in a web context, and this test stays on WebEnvironment.NONE).
    @TestConfiguration
    static class ConversionServiceTestConfig {
        @Bean
        ConversionService conversionService(List<Converter<?, ?>> converters) {
            final DefaultConversionService conversionService = new DefaultConversionService();
            converters.forEach(conversionService::addConverter);
            return conversionService;
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    // BookingServiceApplication needs a reachable replica datasource to boot at all — see
    // ReservationServiceIntegrationTest for why @ServiceConnection alone doesn't cover it.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    @Autowired
    private CacheStampedeGuard cacheStampedeGuard;

    @Test
    void getOrLoad_concurrentCallersOnAMiss_onlyOneRunsTheLoader() throws Exception {
        final Long key = 424242L;
        final AtomicInteger loadCount = new AtomicInteger();
        final Callable<String> call = () -> this.cacheStampedeGuard.getOrLoad("stampede-test", key, String.class, () -> {
            loadCount.incrementAndGet();
            return "value-" + key;
        });

        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final List<Future<String>> futures =
                    IntStream.range(0, 8).mapToObj(i -> executor.submit(call)).toList();
            for (final Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("value-" + key);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(loadCount.get()).isEqualTo(1);
    }
}
