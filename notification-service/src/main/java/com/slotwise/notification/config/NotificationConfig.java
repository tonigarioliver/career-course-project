package com.slotwise.notification.config;

import com.slotwise.notification.service.KeycloakApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({NotificationProperties.class, KeycloakProperties.class})
public class NotificationConfig {

    // RestClient.Builder is Boot's (timeouts from spring.http.client.*, message converters).
    @Bean
    KeycloakApi keycloakApi(final RestClient.Builder builder, final KeycloakProperties keycloakProperties) {
        return KeycloakApi.create(builder, keycloakProperties.baseUrl());
    }
}
