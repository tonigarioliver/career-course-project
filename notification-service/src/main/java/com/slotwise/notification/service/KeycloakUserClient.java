package com.slotwise.notification.service;

import com.slotwise.notification.config.KeycloakProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

// Looks up a user's email in Keycloak's admin API with a read-only service account.
// ponytail: a new token on every lookup — cache it (until expires_in) if lookups ever get frequent.
@Component
@RequiredArgsConstructor
public class KeycloakUserClient {

    private final KeycloakProperties keycloakProperties;
    private final KeycloakApi keycloakApi;

    // Throws PermanentNotificationException when retrying can't help (unknown user, no email);
    // any other failure (network, 5xx, token problems) is an IllegalStateException = transient.
    public String findEmail(final String subject) {
        final String token = this.fetchToken();
        final KeycloakApi.KeycloakUser user;
        try {
            user = this.keycloakApi.user(this.keycloakProperties.realm(), subject, "Bearer " + token);
        } catch (HttpClientErrorException.NotFound e) {
            throw new PermanentNotificationException("Keycloak has no user " + subject);
        } catch (RestClientException e) {
            throw new IllegalStateException("Keycloak user lookup failed: " + e.getMessage(), e);
        }
        if (user == null || !StringUtils.hasText(user.email())) {
            throw new PermanentNotificationException("Keycloak user " + subject + " has no email");
        }
        return user.email();
    }

    // Any failure here is transient, including a 404 (wrong realm is a config problem, not "no such user").
    private String fetchToken() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", this.keycloakProperties.clientId());
        form.add("client_secret", this.keycloakProperties.clientSecret());
        try {
            final KeycloakApi.TokenResponse response = this.keycloakApi.token(this.keycloakProperties.realm(), form);
            if (response == null || !StringUtils.hasText(response.accessToken())) {
                throw new IllegalStateException("Keycloak token response has no access_token");
            }
            return response.accessToken();
        } catch (RestClientException e) {
            throw new IllegalStateException("Keycloak token request failed: " + e.getMessage(), e);
        }
    }
}
