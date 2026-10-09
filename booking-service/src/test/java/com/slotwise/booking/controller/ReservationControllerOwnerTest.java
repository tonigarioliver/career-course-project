package com.slotwise.booking.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// The owner rule itself is tested in ReservationServiceIntegrationTest; this only checks the wiring: token subject
// and realm role -> Caller -> HTTP status (including the domain exception -> 403 mapping). Real security filter chain. Kafka listeners are off:
// nothing here consumes, and the outbox relay just finds no broker for the rows these tests create.
@Testcontainers
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class ReservationControllerOwnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    // See ReservationServiceIntegrationTest: @ServiceConnection doesn't cover slotwise.datasource.replica.*.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    private long resourceId;

    @BeforeEach
    void newResource() {
        this.resourceId = this.jdbcClient.sql("INSERT INTO resources (name, active) VALUES ('Room', true) RETURNING id")
                .query(Long.class).single();
    }

    private static RequestPostProcessor caller(final String subject, final String role) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String body(final String ownerSubjectJson) {
        return "{\"resourceId\":" + this.resourceId + ",\"startTime\":\"2026-08-01T10:00:00Z\","
                + "\"endTime\":\"2026-08-01T11:00:00Z\"" + ownerSubjectJson + "}";
    }

    private org.springframework.test.web.servlet.ResultActions book(final RequestPostProcessor caller, final String ownerSubjectJson)
            throws Exception {
        return this.mockMvc.perform(post("/api/reservations").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(this.body(ownerSubjectJson)));
    }

    @Test
    void omittedOwner_defaultsToCaller() throws Exception {
        this.book(caller("sub-bob", "RESERVATION_USER"), "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerSubject").value("sub-bob"));
    }

    @Test
    void nonAdminBookingForAnotherOwner_is403() throws Exception {
        this.book(caller("sub-bob", "RESERVATION_USER"), ",\"ownerSubject\":\"sub-alice\"")
                .andExpect(status().isForbidden());
    }

    @Test
    void adminBookingForAnotherOwner_isAllowed() throws Exception {
        this.book(caller("sub-alice", "RESERVATION_ADMIN"), ",\"ownerSubject\":\"sub-bob\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerSubject").value("sub-bob"));
    }
}
