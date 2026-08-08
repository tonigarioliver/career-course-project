---
icon: lucide/lightbulb
---

# Decisions & gotchas

Non-obvious bugs and design calls, kept so they don't get re-debugged from scratch.
Full narrative for the oldest ones lives in `.claude/session-logs/`.

## MDC is thread-local — deliberately hand-rolled, not Micrometer Tracing (yet)

`CorrelationIdFilter` (`OncePerRequestFilter`) reads an inbound `X-Correlation-Id`
header or mints a `UUID`, puts it in SLF4J's `MDC` for the request, echoes it back on
the response, and clears it in a `finally`. `logging.pattern.console` includes
`%X{correlationId}` so Logback prints it on every line written on that thread.

### Why hand-rolled instead of reaching for a library immediately

Spring already has an industrial-strength answer to this: **Micrometer Tracing**
(`micrometer-tracing-bridge-brave`/`-otel`, the successor to the retired Spring Cloud
Sleuth). Adding that dependency gets you `traceId`/`spanId` in MDC automatically, no
filter needed, plus propagation across HTTP calls and export to a tracing backend
(Zipkin/Tempo) — strictly more capable than what's here.

It wasn't reached for immediately because this phase of the roadmap ("Logs — SLF4J,
Logback, MDC, Correlation IDs") is explicitly about learning the *mechanism*
hands-on — `MDC` is a `ThreadLocal<Map>`, and understanding that is what explains every
gotcha below it. Reaching for the library first would have skipped straight to "add a
dependency" without ever touching the thing that dependency wraps.

### Why `MDC` (and `SecurityContextHolder`) need explicit propagation off the request thread

`MDC`'s default backing is a `ThreadLocal` — scoped to *one* thread. Two consequences
that matter the moment this project stops being single-threaded-per-request:

- **Async work loses it.** `ExecutorService.submit(...)`, `CompletableFuture.supplyAsync(...)`,
  Spring's `@Async` — none of these copy the parent thread's `MDC` into the worker
  thread. Logs from that worker either show an empty `correlationId` or, worse, a
  **stale one leaked from a previous task** that ran on the same pooled thread and
  didn't clean up. Same failure mode, same fix shape, for `SecurityContextHolder`
  (`MODE_THREADLOCAL` by default) — an `@Async` method sees an anonymous
  `Authentication`, not the caller's, unless the context is propagated.
- **Propagation is manual, not automatic** — `MDC.getCopyOfContextMap()` in the parent
  thread, `MDC.setContextMap(...)` + `MDC.clear()` in a `finally` in the child. Spring's
  `TaskDecorator` (set on a `ThreadPoolTaskExecutor`) is the idiomatic way to apply that
  wrapping to every task without hand-wrapping each call site; Spring Security ships the
  equivalent for its own context (`DelegatingSecurityContextExecutor`).
- **Virtual threads (JDK 21+) don't fix this for free.** The JDK explicitly does not
  inherit `InheritableThreadLocal` into a newly created virtual thread — so plain `MDC`
  still needs the same manual copy/restore. What *does* auto-propagate into forked
  subtasks is `ScopedValue` + `StructuredTaskScope` (structured concurrency) — a
  different, newer API, not something `MDC`/`Logback` are built on. In Spring-land, the
  practical equivalent is Micrometer's **Context Propagation** library
  (`io.micrometer:context-propagation`), which Boot auto-configures with `ThreadLocalAccessor`s
  for both `MDC` and `SecurityContextHolder` when it's on the classpath — that's the
  "it just works across threads" behavior, not a JDK-level default.
- None of this is live code here yet — no `@Async`, no custom executor, no virtual
  threads in `booking-service` today, so no `TaskDecorator` was added (would be
  unused abstraction — YAGNI). Flagged here so it's not re-discovered as a surprise
  the day an async path gets added.

### Why this becomes Micrometer Tracing at Fase 4, not before

A correlation ID that only lives in one process's MDC stops being useful the moment
the roadmap splits `booking-service` into User/Notification/Audit services talking over
Kafka (Fase 4) — carrying it across a Kafka message means manually stuffing it into
every message's headers and reading it back out in every consumer, by hand, forever.
Micrometer Tracing's Kafka instrumentation does exactly that propagation automatically,
plus gives a visual trace of one request's path across all three services. Fase 1's
hand-rolled filter is the right scope for "one service, one log stream, understand the
mechanism"; Fase 4 is where the problem it solves outgrows what it can do.

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

## Cross-field validation lives in the record's compact constructor

`CreateReservationRequest`'s `startTime < endTime` check moved from a manual `if` in
`ReservationService` into the record's own compact constructor:

```java
public CreateReservationRequest {
    // null-guarded: this runs on every construction, including Jackson's JSON
    // deserialization — before @NotNull/@Valid ever get a chance to run.
    if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
        throw new IllegalArgumentException("startTime must be before endTime");
    }
}
```

The request validates its own integrity instead of the service policing it — and since
Lombok's `@Builder` on a record still calls the canonical constructor under the hood,
one guard covers every construction path (JSON body, builder in tests, direct `new`).

**Trade-off worth knowing**: the compact constructor runs *during* Jackson's
`@RequestBody` deserialization, before `@Valid` ever runs. An exception thrown there
gets wrapped by Spring in `HttpMessageNotReadableException`, not surfaced as our own
`IllegalArgumentException` — so it bypassed `GlobalExceptionHandler`'s existing handler
and returned Spring's generic error body instead of `ApiErrorResponse`. Fixed by adding
an `HttpMessageNotReadableException` handler that unwraps `getMostSpecificCause()` and
reuses the message when the cause is an `IllegalArgumentException`, falling back to a
generic "Malformed request body" otherwise (e.g. actually-broken JSON, not a failed
business check).

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

## `@ConfigurationProperties` registration: dedicated `@Configuration` class over `@ConfigurationPropertiesScan`

`ReservationProperties` (`slotwise.reservation.min-duration-minutes`/`max-duration-minutes`)
is registered via `ReservationConfig`, a `@Configuration` class in the `config` package
annotated `@EnableConfigurationProperties(ReservationProperties.class)` — not
`@ConfigurationPropertiesScan` on the main application class. Both work; the explicit
form keeps the registration list next to the properties class it registers instead of on
an unrelated `@SpringBootApplication` class, and stays greppable as more properties
classes get added (each just joins the `@EnableConfigurationProperties({...})` list, no
scanning surprises).

## `application-prod.yml`: same keys, no defaults

The `prod` Spring profile (`application-prod.yml`, `spring.config.activate.on-profile:
prod`) re-declares the same datasource/Keycloak keys as the base `application.yml` but
**without** the `${VAR:default}` fallback — `${DB_HOST}` instead of
`${DB_HOST:localhost}`. Missing an env var in a real deploy now fails Spring's property
resolution at startup instead of silently booting against `localhost`/the dev Keycloak.
Also flips `ddl-auto` to `validate` (Hibernate never mutates a prod schema — that's a
migration tool's job, Flyway once Phase 2 lands) and turns off `format_sql`.

## `SecurityFilterChain` bean needs `@ConditionalOnWebApplication`

`ReservationServiceIntegrationTest` (`@SpringBootTest(webEnvironment = NONE)`) started
failing to load its context once `SecurityConfig` was added: `securityFilterChain(HttpSecurity
http)` couldn't find an `HttpSecurity` bean to inject. Same root cause as the
`ConversionService` gotcha above — `HttpSecurity` is only auto-registered by Spring
Security's own `HttpSecurityConfiguration`, which is itself `@ConditionalOnWebApplication`,
so a `NONE`-web-environment test never gets one. Went unnoticed because nobody re-ran the
Testcontainers test (needs Docker) after the OAuth2 commit.

Fixed by adding `@ConditionalOnWebApplication` to `SecurityConfig` itself, mirroring
Spring's own condition on `HttpSecurityConfiguration` — a `SecurityFilterChain` is
inherently meaningless outside a servlet context, so the guard belongs on the bean, not
copy-pasted into every non-web test that happens to load the full application context.

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
