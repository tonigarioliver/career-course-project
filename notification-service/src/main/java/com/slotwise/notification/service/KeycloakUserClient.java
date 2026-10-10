package com.slotwise.notification.service;

import com.slotwise.notification.config.KeycloakProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

// Looks up a user's email in Keycloak's admin API. The token comes from KeycloakTokenProvider; if Keycloak rejects
// the cached one early (revoked, realm key rotated) the lookup is retried once with a fresh token.
@Component
@RequiredArgsConstructor
public class KeycloakUserClient {

    private final KeycloakProperties keycloakProperties;
    private final KeycloakApi keycloakApi;
    private final KeycloakTokenProvider keycloakTokenProvider;

    // Throws PermanentNotificationException when retrying can't help (unknown user, no email);
    // any other failure (network, 5xx, token problems) is an IllegalStateException = transient.
    public String findEmail(final String subject) {
        try {
            return this.lookup(subject, this.keycloakTokenProvider.token());
        } catch (HttpClientErrorException.Unauthorized e) {
            // The cached token was refused before its time: one refresh and one retry. A 401 on a token that was
            // just issued is not a stale cache (bad credentials or permissions), so it is transient, not a loop.
            try {
                return this.lookup(subject, this.keycloakTokenProvider.refresh());
            } catch (HttpClientErrorException.Unauthorized again) {
                throw new IllegalStateException("Keycloak rejected a freshly issued token: " + again.getMessage(), again);
            }
        }
    }

    private String lookup(final String subject, final String token) {
        final KeycloakApi.KeycloakUser user;
        try {
            user = this.keycloakApi.user(this.keycloakProperties.realm(), subject, "Bearer " + token);
        } catch (HttpClientErrorException.NotFound e) {
            throw new PermanentNotificationException("Keycloak has no user " + subject);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw e; // findEmail decides whether to refresh the token and retry
        } catch (RestClientException e) {
            throw new IllegalStateException("Keycloak user lookup failed: " + e.getMessage(), e);
        }
        if (user == null || !StringUtils.hasText(user.email())) {
            throw new PermanentNotificationException("Keycloak user " + subject + " has no email");
        }
        return user.email();
    }
}
