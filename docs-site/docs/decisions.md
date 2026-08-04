---
icon: lucide/lightbulb
---

# Decisions & gotchas

Non-obvious bugs and design calls, kept so they don't get re-debugged from scratch.
Full narrative for the oldest ones lives in `.claude/session-logs/`.

## Lombok annotation processing silently no-ops

??? info "Root cause and fix"

    `mvn compile` failed with "cannot find symbol: getX/setX/builder()" everywhere
    Lombok was used. The root `pom.xml` only imports `spring-boot-dependencies` as a
    BOM — it does **not** extend `spring-boot-starter-parent`, which is where the
    `annotationProcessorPaths` → Lombok wiring normally comes from.

    Fixed by adding an explicit `maven-compiler-plugin` block with
    `annotationProcessorPaths` → lombok to `booking-service/pom.xml`. If a second
    module hits the same error, copy the block or move it to the parent's
    `pluginManagement`.

## Testcontainers 2.x renamed its artifact IDs

??? info "Root cause and fix"

    `org.testcontainers:junit-jupiter` / `org.testcontainers:postgresql` are the
    **1.x** artifact IDs. In `testcontainers-bom` 2.x they're
    `testcontainers-junit-jupiter` / `testcontainers-postgresql`. Fixed by switching
    `booking-service/pom.xml` to the renamed IDs and dropping the redundant
    `testcontainers.version` pin (Spring Boot 4.0.1's BOM already manages it).

    Verified end-to-end: `mvn -pl booking-service test` runs a real Testcontainers
    Postgres container, 2/2 tests pass.

## `ConversionService` is only auto-registered in a web context

??? info "Root cause and fix"

    `ResourceService`/`ReservationService` inject `ConversionService` to run the
    `@Component Converter<...>` beans. In production that bean exists only because
    `WebMvcAutoConfiguration` registers one (`mvcConversionService`) when the app
    boots as a servlet web app. A `@SpringBootTest(webEnvironment = NONE)` skips that
    autoconfiguration entirely, so the context failed to load.

    Fixed with a nested `@TestConfiguration` in `ReservationServiceIntegrationTest`
    that builds a `DefaultConversionService` and registers every `Converter<?, ?>`
    bean into it — keeps the test on `WebEnvironment.NONE` (cheaper, no servlet
    startup) instead of forcing a full web context just to get one bean. Pattern
    borrowed from a reference project's `AircraftModelServiceTest`.

## Reads skip the entity + `ConversionService`

For read endpoints (`getById`, `list`, `listByResource`), query straight into the
`record` DTO with a JPQL constructor expression instead of loading the entity and
converting it:

```java
@Query("""
    SELECT new com.slotwise.booking.model.ResourceDto(r.id, r.name, r.description, r.active)
    FROM Resource r
    WHERE r.id = :id
    """)
Optional<ResourceDto> findSummaryById(@Param("id") Long id);
```

Faster (only the needed columns, no persistence-context overhead for rows that'll
never be saved) and skips the entity → DTO conversion step entirely. Create/update/
delete keep entity + `ConversionService`, since those paths actually mutate and
persist state.

## `@Valid` alone does not reject a `null` argument

`@Valid` only cascades validation into an object's own fields — if the argument
itself is `null`, there's nothing to cascade into, so no violation is raised. Every
`@Valid`-annotated request body parameter also needs `@NotNull` alongside it:

```java
public ReservationDto create(@NotNull @Valid CreateReservationRequest request) { ... }
```

## `@Validated` on services, not just controllers

Bean Validation annotations on a service method's parameters are silently ignored
unless the class is annotated `@Validated`. Validation only "worked" before via the
controller's `@Valid @RequestBody` — calling the service directly (another service, a
test) bypassed it entirely. `@Validated` throws `ConstraintViolationException`
(different from the controller-only `MethodArgumentNotValidException`) —
`GlobalExceptionHandler` handles both.

## Spring's own `@NonNullApi`/`@NonNullFields` are deprecated under Spring 7

Spring Framework 7 (pulled in by Spring Boot 4.0.1) deprecated its own
`org.springframework.lang.NonNullApi`/`NonNullFields` package-level annotations in
favor of [JSpecify](https://jspecify.dev)'s single `@NullMarked`. Every
`package-info.java` in `booking-service` now reads:

```java
@NullMarked
package com.slotwise.booking.xxx;

import org.jspecify.annotations.NullMarked;
```

`org.jspecify:jspecify` doesn't need adding as a dependency — it already ships
transitively via `spring-core`.

## Keycloak realm import needs `firstName`/`lastName` or direct-grant fails

Keycloak 26's User Profile feature requires `firstName`/`lastName` on every user by
default. A realm imported via `--import-realm` (`keycloak/realm-export.json`) with
users that only set `username`/`credentials`/`realmRoles` imports "successfully" (no
error in the logs) but the password grant then fails with
`invalid_grant: Account is not fully set up` — Keycloak treats the missing profile
fields as an implicit pending required action, but it's invisible in
`GET /admin/realms/{realm}/users` (`requiredActions` shows empty). Fixed by adding
`email`/`firstName`/`lastName` to every user in the realm export.

Also: **re-importing after editing `realm-export.json` needs a fresh container**,
not just a restart — `--import-realm` skips realms that already exist by name.
`docker compose up -d --force-recreate keycloak` forces it (no named volume is
defined for Keycloak's own storage, so recreating the container discards its
internal dev-mode H2 database and forces the import to run again from a clean
slate).

## OAuth2/OIDC Resource Server + Method Security, end-to-end

`booking-service` is a pure OAuth2 **Resource Server** (`spring-boot-starter-
oauth2-resource-server`) — it validates JWTs issued by a local Keycloak (OIDC
Authorization Server, `docker-compose.yml`), it never issues tokens itself.

- `spring.security.oauth2.resourceserver.jwt.issuer-uri` points at Keycloak's realm
  (`http://localhost:8081/realms/slotwise`); Spring lazily fetches
  `/.well-known/openid-configuration` and the JWK set from there on first token
  validation — no manual key management.
- Keycloak puts role names under the `realm_access.roles` claim, not the `scope`/
  `scp` claim Spring's default `JwtAuthenticationConverter` expects. `SecurityConfig`
  wires a custom `Converter<Jwt, Collection<GrantedAuthority>>` that reads
  `realm_access.roles` and prefixes each with `ROLE_` (what `hasRole(...)` expects).

    ??? note "How the JWT → authorities mapping actually works"

        A validated `Jwt` is just a bag of claims — it doesn't know what "roles" or
        "authorities" mean. `JwtAuthenticationConverter` is the class whose job is
        `Jwt` → `Authentication` (the object Spring puts in the `SecurityContext` to
        answer "who is this" and "what can they do"). By default, it derives
        authorities from the `scope`/`scp` claim — but Keycloak doesn't put roles
        there, so we override that one piece:

        ```java
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
        ```

        Given a token payload like `"realm_access": {"roles": ["RESERVATION_ADMIN"]}`:

        1. `jwt.getClaimAsMap("realm_access")` reads that claim as a `Map<String,
           Object>` — `getClaimAsMap` (not `getClaimAsStringList` or similar) because
           the claim's value is itself a JSON object (`{"roles": [...]}`), not a bare
           array.
        2. If the claim is missing (e.g. a token from a client with no realm roles),
           return `List.of()` instead of throwing `NullPointerException`.
        3. `(List<String>) realmAccess.getOrDefault("roles", List.of())` pulls out the
           role list. The cast is unavoidable — `getClaimAsMap` returns `Map<String,
           Object>` because Jackson can't know at compile time that this particular
           value is a `List<String>` — hence `@SuppressWarnings("unchecked")` on the
           method: a deliberate, narrow suppression because *we* know the shape of a
           Keycloak token, even though the compiler can't verify it.
        4. `"ROLE_" + role` — **not optional**. `hasRole('RESERVATION_ADMIN')` in
           `@PreAuthorize` internally looks for the authority string
           `"ROLE_RESERVATION_ADMIN"`. Skip the prefix here and `hasRole(...)` would
           never match (you'd have to use `hasAuthority('RESERVATION_ADMIN')`,
           unprefixed, instead).

        None of `"realm_access"`, `"roles"`, or `"ROLE_"` are extracted to named
        constants — each string appears exactly once, in this one method. Spring
        Security doesn't expose the `"ROLE_"` default as a public constant either
        (`GrantedAuthorityDefaults` only has a constructor/getter for a
        *configurable* prefix, no `static final` field) — there's nothing to reuse
        from the framework here.
- `@EnableMethodSecurity` + `@PreAuthorize("hasRole('RESERVATION_ADMIN')")` on
  `ResourceController`'s `create`/`update`/`delete` restrict resource management to
  admins; reservation endpoints stay open to any authenticated user (both realm
  roles), so no `@PreAuthorize` needed there beyond the blanket
  `authorizeHttpRequests().anyRequest().authenticated()`.

Verified end-to-end against a real Keycloak container: no token → `401`; `bob`
(`RESERVATION_USER`) POSTing a resource → `403`; `alice` (`RESERVATION_ADMIN`) → `201`.

## `@PathVariable Long id` needs the compiler's `-parameters` flag

`GET /api/resources/{id}/reservations` (`@PathVariable Long resourceId`, no explicit
name in the annotation) failed with `400`: *"Name for argument of type
[java.lang.Long] not specified, and parameter name information not available via
reflection. Ensure that the compiler uses the '-parameters' flag."* Spring resolves
an unnamed `@PathVariable`'s binding by reading the parameter name from the
`MethodParameters` bytecode attribute — which only exists if compiled with
`-parameters`. `spring-boot-starter-parent` sets that by default; this project
doesn't extend it (see the Lombok gotcha above), so it was missing. Fixed by adding
`<parameters>true</parameters>` to `booking-service/pom.xml`'s
`maven-compiler-plugin` config.

**Gotcha inside the gotcha:** after adding it, `mvn compile` alone didn't pick it up
— Maven's incremental compiler didn't consider the sources stale from a
config-only change. Needed `mvn clean compile` to force a full recompile and
actually get the `MethodParameters` attribute. Verify with:
`javap -v -classpath target/classes <Controller> | grep -A3 MethodParameters`.

## `HttpSecurity.build()` doesn't declare `throws Exception` in Spring Security 7

Countless Spring Security tutorials write the `SecurityFilterChain` bean method as
`securityFilterChain(HttpSecurity http) throws Exception`. That was true for the
`SecurityBuilder<O>.build()` contract in Spring Security 5.x/6.x — checked via
`javap` against this project's actual `spring-security-core:7.0.5` (pulled in by
Spring Boot 4.0.1), `build()` no longer declares `throws Exception`:

```
public interface org.springframework.security.config.annotation.SecurityBuilder<O> {
    public abstract O build();
}
```

So `throws Exception` on the bean method is dead: SonarLint correctly flags it
(`S112`/`S1130`, "cannot be thrown from method's body") — it's not a false positive,
it's copy-pasted boilerplate from an older API version. Removed. **When copying a
Spring Security config snippet from docs/tutorials, don't assume `throws Exception`
is still required — check against the actual major version in `pom.xml`.**
