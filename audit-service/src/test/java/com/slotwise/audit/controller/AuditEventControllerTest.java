package com.slotwise.audit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// No Kafka container, and the listener is switched off: without that it would connect to
// localhost:9092 (the docker-compose broker, if running) as group audit-service, writing real dev
// events into this test's DB and committing the dev group's offsets.
@Testcontainers
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class AuditEventControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void seed() {
        this.jdbcClient.sql("DELETE FROM audit_event").update();
        this.insert("reservation-created:1", 10L, "{\"n\":1}");
        this.insert("reservation-created:2", 10L, "{\"n\":2}");
        this.insert("reservation-created:3", 20L, "{\"n\":3}");
    }

    private void insert(final String eventId, final Long resourceId, final String payload) {
        this.jdbcClient.sql("INSERT INTO audit_event (event_id, event_type, resource_id, payload, received_at) "
                        + "VALUES (:e, 'reservation-created', :r, :p, now())")
                .param("e", eventId).param("r", resourceId).param("p", payload).update();
    }

    private static RequestPostProcessor role(final String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void admin_listsEventsNewestFirst() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].eventId").value("reservation-created:3"));
    }

    @Test
    void resourceIdFilter_returnsOnlyThatResource() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").param("resourceId", "10").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].resourceId").value(10));
    }

    @Test
    void resourceIdWithNoEvents_returnsEmptyPage() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").param("resourceId", "999").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pageSizeHonoured_pagesDoNotOverlap() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").param("size", "2").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        this.mockMvc.perform(get("/api/audit-events").param("size", "2").param("page", "1").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventId").value("reservation-created:1"));
    }

    @Test
    void unknownSortProperty_is400() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").param("sort", "foo").with(role("RESERVATION_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void noToken_is401() throws Exception {
        this.mockMvc.perform(get("/api/audit-events")).andExpect(status().isUnauthorized());
    }

    @Test
    void reservationUserRole_is403() throws Exception {
        this.mockMvc.perform(get("/api/audit-events").with(role("RESERVATION_USER"))).andExpect(status().isForbidden());
    }
}
