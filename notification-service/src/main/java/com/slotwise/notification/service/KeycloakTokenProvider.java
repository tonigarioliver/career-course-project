package com.slotwise.notification.service;

import com.slotwise.notification.config.KeycloakProperties;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

// Owns the service-account token's lifecycle: requests it with client credentials, caches it until shortly
// before it expires, and hands out a fresh one on demand (when Keycloak rejects the cached one early).
@Component
@RequiredArgsConstructor
public class KeycloakTokenProvider {

    // Renew this long before expires_in so a token never expires between being picked and being used.
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final KeycloakProperties keycloakProperties;
    private final KeycloakApi keycloakApi;
    private volatile @Nullable CachedToken cachedToken;

    private record CachedToken(String value, Instant validUntil) {
    }

    // The cached token while it is still valid, otherwise a new one.
    public String token() {
        final CachedToken cached = this.cachedToken;
        return cached != null && Instant.now().isBefore(cached.validUntil()) ? cached.value() : this.refresh();
    }

    // Always requests a new token and replaces the cache.
    // ponytail: synchronized so concurrent workers don't each request one; a single lock is plenty at this volume.
    public synchronized String refresh() {
        this.cachedToken = null;
        final CachedToken fresh = this.fetchToken();
        this.cachedToken = fresh;
        return fresh.value();
    }

    // Any failure here is transient, including a 404 (wrong realm is a config problem, not "no such user").
    private CachedToken fetchToken() {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", this.keycloakProperties.clientId());
        form.add("client_secret", this.keycloakProperties.clientSecret());
        try {
            final KeycloakApi.TokenResponse response = this.keycloakApi.token(this.keycloakProperties.realm(), form);
            if (response == null || !StringUtils.hasText(response.accessToken())) {
                throw new IllegalStateException("Keycloak token response has no access_token");
            }
            final int lifetimeSeconds = response.expiresIn() == null ? 0 : response.expiresIn();
            return new CachedToken(response.accessToken(),
                    Instant.now().plusSeconds(lifetimeSeconds).minus(EXPIRY_MARGIN));
        } catch (RestClientException e) {
            throw new IllegalStateException("Keycloak token request failed: " + e.getMessage(), e);
        }
    }
}
