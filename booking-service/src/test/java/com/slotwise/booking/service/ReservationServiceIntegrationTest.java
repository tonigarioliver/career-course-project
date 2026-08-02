package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
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
            DefaultConversionService conversionService = new DefaultConversionService();
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
        ResourceDto resource = resourceService.create(
                CreateResourceRequest.builder().name("Room A").build());

        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        Instant end = Instant.parse("2026-01-01T11:00:00Z");

        reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        CreateReservationRequest overlapping = CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start.plusSeconds(1800))
                .endTime(end.plusSeconds(1800))
                .ownerSubject("user-2")
                .build();

        assertThatThrownBy(() -> reservationService.create(overlapping))
                .isInstanceOf(ReservationConflictException.class);
    }

    @Test
    void create_backToBackReservation_succeeds() {
        ResourceDto resource = resourceService.create(
                CreateResourceRequest.builder().name("Room B").build());

        Instant start = Instant.parse("2026-01-02T10:00:00Z");
        Instant end = Instant.parse("2026-01-02T11:00:00Z");

        reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(start)
                .endTime(end)
                .ownerSubject("user-1")
                .build());

        ReservationDto backToBack = reservationService.create(CreateReservationRequest.builder()
                .resourceId(resource.id())
                .startTime(end)
                .endTime(end.plusSeconds(3600))
                .ownerSubject("user-2")
                .build());

        assertThat(backToBack.id()).isNotNull();
    }
}
