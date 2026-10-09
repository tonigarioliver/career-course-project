package com.slotwise.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slotwise.notification.config.KeycloakProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

// Keycloak replaced by a JDK HttpServer: real HTTP and real JSON, no mocks of the client itself.
class KeycloakUserClientTest {

    private HttpServer server;
    private KeycloakUserClient client;

    @BeforeEach
    void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.createContext("/realms/slotwise/protocol/openid-connect/token", exchange -> {
            final String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (form.contains("grant_type=client_credentials") && form.contains("client_id=nc")
                    && form.contains("client_secret=sec")) {
                reply(exchange, 200, "{\"access_token\":\"tok\"}");
            } else {
                reply(exchange, 400, "{}");
            }
        });
        this.server.createContext("/admin/realms/slotwise/users/", exchange -> {
            if (!"Bearer tok".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, "{}");
                return;
            }
            final String id = exchange.getRequestURI().getPath().substring("/admin/realms/slotwise/users/".length());
            switch (id) {
                case "u1" -> reply(exchange, 200, "{\"id\":\"u1\",\"email\":\"a@slotwise.local\"}");
                case "nomail" -> reply(exchange, 200, "{\"id\":\"nomail\",\"username\":\"n\"}");
                case "missing" -> reply(exchange, 404, "{}");
                default -> reply(exchange, 500, "{}");
            }
        });
        this.server.start();
        final String baseUrl = "http://localhost:" + this.server.getAddress().getPort();
        this.client = new KeycloakUserClient(
                new KeycloakProperties(baseUrl, "slotwise", "nc", "sec"),
                KeycloakApi.create(RestClient.builder(), baseUrl));
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    private static void reply(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void ok_returnsEmail() {
        assertThat(this.client.findEmail("u1")).isEqualTo("a@slotwise.local");
    }

    @Test
    void notFound_isPermanent() {
        assertThatThrownBy(() -> this.client.findEmail("missing")).isInstanceOf(PermanentNotificationException.class);
    }

    @Test
    void noEmail_isPermanent() {
        assertThatThrownBy(() -> this.client.findEmail("nomail")).isInstanceOf(PermanentNotificationException.class);
    }

    @Test
    void serverError_isTransient() {
        assertThatThrownBy(() -> this.client.findEmail("boom"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PermanentNotificationException.class);
    }

    @Test
    void tokenRequestRejected_isTransient() {
        final String baseUrl = "http://localhost:" + this.server.getAddress().getPort();
        final KeycloakUserClient wrongSecret = new KeycloakUserClient(
                new KeycloakProperties(baseUrl, "slotwise", "nc", "wrong"), KeycloakApi.create(RestClient.builder(), baseUrl));

        // Misconfiguration (bad secret): not "no such user", so it must not be classified as permanent.
        assertThatThrownBy(() -> wrongSecret.findEmail("u1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PermanentNotificationException.class);
    }
}
