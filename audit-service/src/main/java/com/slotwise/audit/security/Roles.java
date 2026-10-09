package com.slotwise.audit.security;

/**
 * Realm role names as they exist in Keycloak ({@code keycloak/realm-export.json}); constants so
 * {@code @PreAuthorize} values can reference them (annotation values must be constant expressions).
 */
public final class Roles {

    public static final String RESERVATION_ADMIN = "RESERVATION_ADMIN";
    public static final String RESERVATION_USER = "RESERVATION_USER";

    private Roles() {
    }
}
