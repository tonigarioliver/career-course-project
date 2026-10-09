package com.slotwise.notification.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

// Declarative HTTP client: Spring generates the implementation. Only the two Keycloak endpoints
// this service needs. HTTP errors surface as RestClientException subclasses; KeycloakUserClient
// decides which of them are permanent.
@HttpExchange
public interface KeycloakApi {

    @PostExchange(url = "/realms/{realm}/protocol/openid-connect/token",
            contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    TokenResponse token(@PathVariable String realm, @RequestBody MultiValueMap<String, String> form);

    @GetExchange("/admin/realms/{realm}/users/{id}")
    KeycloakUser user(@PathVariable String realm, @PathVariable String id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    record TokenResponse(@JsonProperty("access_token") @Nullable String accessToken) {
    }

    record KeycloakUser(@Nullable String email) {
    }

    // Same factory for the real bean and the tests (which point it at a stub server).
    static KeycloakApi create(final RestClient.Builder builder, final String baseUrl) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.baseUrl(baseUrl).build()))
                .build()
                .createClient(KeycloakApi.class);
    }
}
