package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.CreateResourceRequest;
import com.slotwise.booking.model.ReservationDto;
import com.slotwise.booking.model.ResourceDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Pageable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

    private final ResourceService resourceService;
    private final ReservationService reservationService;

    @Autowired
    ReservationServiceIntegrationTest(ResourceService resourceService, ReservationService reservationService) {
        this.resourceService = resourceService;
        this.reservationService = reservationService;
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
