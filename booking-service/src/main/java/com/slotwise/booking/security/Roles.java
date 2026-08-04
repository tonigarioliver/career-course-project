package com.slotwise.booking.security;

/**
 * Realm role names as they exist in Keycloak ({@code keycloak/realm-export.json}) —
 * kept as compile-time constants so {@code @PreAuthorize} annotation values (which the
 * JLS requires to be constant expressions) can reference them instead of repeating the
 * raw role name as a string literal at each usage site.
 */
public final class Roles {

    public static final String RESERVATION_ADMIN = "RESERVATION_ADMIN";
    public static final String RESERVATION_USER = "RESERVATION_USER";

    private Roles() {
    }
}
