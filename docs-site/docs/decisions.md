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
