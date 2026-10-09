package com.slotwise.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "slotwise.keycloak")
public record KeycloakProperties(String baseUrl, String realm, String clientId, String clientSecret) {
}
