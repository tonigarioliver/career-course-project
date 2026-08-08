package com.slotwise.booking.security;

import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

// ponytail: SecurityFilterChain only makes sense in a servlet web context — HttpSecurity
// itself is only auto-registered there. @SpringBootTest(webEnvironment = NONE) (the
// service-layer integration tests) would otherwise fail to build this bean at all.
@ConditionalOnWebApplication
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(final HttpSecurity http) {
        http
                // CSRF protects cookie-based session auth from forged browser requests; this API is
                // stateless (STATELESS below) and authenticates via a Bearer JWT header, which a
                // cross-site form/script can't attach on the victim's behalf. Safe to disable.
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        final var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRolesToAuthorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static Collection<GrantedAuthority> realmRolesToAuthorities(final Jwt jwt) {
        final var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        final var roles = (List<String>) realmAccess.getOrDefault("roles", List.of());
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
