package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ReservationDto;
import com.slotwise.booking.model.ResourceDto;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReservationServiceIntegrationTest {

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

    // ResourceService (used to create resources below) now needs a real RedissonClient
    // bean (Fase 3 stampede guard) — without this, RedissonConfig would eagerly try to
    // connect to the application.yml default of localhost:6379 and fail.
    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    // @ServiceConnection above only wires spring.datasource.* (what DataSourceConfig's
    // primaryDataSource reads) — it has no idea about slotwise.datasource.replica.*, our
    // own property prefix, so without this a @Transactional(readOnly = true) call (e.g.
    // listByResource) would try the real localhost:5433 default and fail outside an
    // environment that happens to have a docker-compose replica running. There's no real
    // standby to test replication itself here (that's verified by hand against the actual
    // docker-compose primary+replica) — this test is about ReservationService's logic, so
    // pointing "replica" at the same Testcontainers instance is the correct test double.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    private final ResourceService resourceService;
    private final ReservationService reservationService;
    private final RedissonClient redissonClient;

    @Autowired
    ReservationServiceIntegrationTest(
            ResourceService resourceService, ReservationService reservationService, RedissonClient redissonClient) {
        this.resourceService = resourceService;
        this.reservationService = reservationService;
        this.redissonClient = redissonClient;
    }

    @Test
    void create_publishesReservationCreatedEvent() throws InterruptedException {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room PubSub").build());

        final CountDownLatch received = new CountDownLatch(1);
        final ReservationCreatedEvent[] captured = new ReservationCreatedEvent[1];
        final int listenerId = this.redissonClient.getTopic(ReservationService.RESERVATION_EVENTS_CHANNEL)
                .addListener(ReservationCreatedEvent.class, (channel, event) -> {
                    captured[0] = event;
                    received.countDown();
                });
        try {
            final ReservationDto reservation = this.reservationService.create(CreateReservationRequest.builder()
                    .resourceId(resource.id())
                    .startTime(Instant.parse("2026-02-01T10:00:00Z"))
                    .endTime(Instant.parse("2026-02-01T11:00:00Z"))
                    .ownerSubject("user-pubsub")
                    .build());

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(captured[0].reservationId()).isEqualTo(reservation.id());
            assertThat(captured[0].resourceId()).isEqualTo(resource.id());
        } finally {
            this.redissonClient.getTopic(ReservationService.RESERVATION_EVENTS_CHANNEL).removeListener(listenerId);
        }
    }

    @Test
    void create_overlappingReservation_throwsConflict() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room A").build());

        final Instant start = Instant.parse("2026-01-01T10:00:00Z");
        final Instant end = Instant.parse("2026-01-01T11:00:00Z");

        this.reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        final CreateReservationRequest overlapping = CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start.plusSeconds(1800))
                .endTime(end.plusSeconds(1800))
                .ownerSubject("user-2")
                .build();

        assertThatThrownBy(() -> this.reservationService.create(overlapping))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void create_backToBackReservation_succeeds() {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room B").build());

        final Instant start = Instant.parse("2026-01-02T10:00:00Z");
        final Instant end = Instant.parse("2026-01-02T11:00:00Z");

        this.reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        final ReservationDto backToBack = this.reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(end)
                .endTime(end.plusSeconds(3600))
                .ownerSubject("user-2")
                .build());

        assertThat(backToBack.id()).isNotNull();
    }

    @Test
    void create_concurrentOverlappingReservations_onlyOneSucceeds() throws Exception {
        final ResourceDto resource = this.resourceService.create(
                CreateResourceRequest.builder().name("Room C").build());
        final Instant start = Instant.parse("2026-01-03T10:00:00Z");
        final Instant end = Instant.parse("2026-01-03T11:00:00Z");

        final Callable<ReservationDto> createA = () -> this.reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-A")
                .build());
        final Callable<ReservationDto> createB = () -> this.reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start.plusSeconds(1800))
                .endTime(end.plusSeconds(1800))
                .ownerSubject("user-B")
                .build());

        // No sleep/latch needed to force the race: findByIdForUpdate's row lock serializes
        // the two create() calls on its own — whichever thread loses the race for the lock
        // simply blocks until the winner commits, then sees its reservation and conflicts.
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<ReservationDto> futureA = executor.submit(createA);
            final Future<ReservationDto> futureB = executor.submit(createB);

            final int failures = countConflictFailures(futureA) + countConflictFailures(futureB);
            assertThat(failures).isEqualTo(1);
        } finally {
            executor.shutdown();
        }

        assertThat(this.reservationService.listByResource(resource.id(), Pageable.unpaged())
                .getTotalElements()).isEqualTo(1);
    }

    private static int countConflictFailures(Future<ReservationDto> future) throws InterruptedException {
        try {
            future.get();
            return 0;
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ReservationConflictException.class);
            return 1;
        }
    }
}
