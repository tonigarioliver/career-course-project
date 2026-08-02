# Session log — 2026-08-02 — Lombok + Testcontainers fixes

Full narrative of what happened in this chat session, kept so the conversation itself
is disposable. If a future session (or you) needs to know "what did we already try",
read this before re-asking Claude.

## Context recovered at start of session

User pasted a partially-recovered prior conversation after losing session history. It
contained:
1. A backend-senior course roadmap (Fase 1–6: Spring Boot advanced, PostgreSQL
   internals, Redis, Kafka, Docker, Kubernetes) — now preserved in `CLAUDE.md` under
   "Course roadmap".
2. A transcript fragment showing a `booking-service` (Slotwise) Spring Boot project
   being built (entities, DTOs, services, controllers) and a Lombok build issue being
   chased.

Checked the actual working directory (`/home/thenextflow/TheNextFlow/repository/career-course-project`)
and found the real project state matched the recovered transcript — nothing was
actually lost on disk, only the chat history.

## Issue 1 — Lombok annotation processing silently no-op'd

`mvn compile` failed with "cannot find symbol: getX/setX/builder()" everywhere Lombok
was used, even though `@Getter/@Setter/@NoArgsConstructor` annotations were present and
`lombok` was a `compile`-scope (optional) dependency.

Root cause: the root `pom.xml` only imports `spring-boot-dependencies:4.0.1` as a BOM
in `dependencyManagement` — it does **not** extend `spring-boot-starter-parent`. Spring
Initializr projects get their `maven-compiler-plugin` → `annotationProcessorPaths` →
lombok wiring from extending `spring-boot-starter-parent`; a BOM-only import doesn't
bring that along. The user found a spring-initializr-generated demo pom confirming this
exact config is what was missing.

Fix (committed in `0ef25a4`): added an explicit `maven-compiler-plugin` block with
`annotationProcessorPaths` → lombok to `booking-service/pom.xml`. Verified with
`mvn -pl booking-service -am clean compile` → clean build.

If a second module is added later and hits the same error, either copy this block or
move it up to the parent's `pluginManagement`.

## Issue 2 — Testcontainers dependency resolution failure

Test-scope resolution failed: `org.testcontainers:junit-jupiter:jar:2.0.5` and
`org.testcontainers:postgresql:jar:2.0.5` "not found" against Maven Central, with
`testcontainers.version=2.0.5` pinned in the root pom.

Looked like a bad/nonexistent version pin at first. Actual root cause: Testcontainers
2.x renamed its module artifact IDs — `org.testcontainers:junit-jupiter` and
`org.testcontainers:postgresql` are the **1.x** names; in `testcontainers-bom` 2.x
they're `testcontainers-junit-jupiter` and `testcontainers-postgresql`. Confirmed by
grepping the cached `testcontainers-bom-2.0.5.pom` in `~/.m2` for artifact IDs.

Fix (uncommitted as of this log): 
- Dropped the `testcontainers.version` property and explicit `<version>` overrides
  entirely — Spring Boot 4.0.1's own BOM already imports `testcontainers-bom`
  transitively, no need to repin it.
- Updated `booking-service/pom.xml` dependencies to `testcontainers-junit-jupiter` /
  `testcontainers-postgresql`.
- Verified `mvn -pl booking-service -am clean test-compile` resolves and compiles clean.
- **Not yet verified end-to-end**: Docker Desktop's WSL integration was off for this
  distro (`docker` resolved to a Windows stub telling you to enable it), so the actual
  `@Testcontainers`-based `ReservationServiceIntegrationTest` hasn't run. User was in the
  process of enabling WSL integration in Docker Desktop settings and rebooting the
  machine when this log was written.

**Next step when resuming**: run `docker info` to confirm Docker is reachable from this
WSL shell, then `mvn -pl booking-service test` to actually execute
`ReservationServiceIntegrationTest`. If it passes, commit the testcontainers pom changes.

## Other setup done this session

- `git init`'d the repo (it wasn't one before) and made an initial commit (`0ef25a4`)
  with the Lombok fix, `.gitignore` (`target/`, `.idea/`, `.claude/settings.local.json`),
  and this project's `CLAUDE.md`.
- Local git identity for this repo only (not global): `toni.gari <tonietmoscari@gmail.com>`.
- Created `CLAUDE.md` at the project root documenting structure, known gotchas, and the
  course roadmap — this is the durable, auto-loaded record of project state and should
  be kept up to date instead of relying on chat recovery.

## Working tree state as of reboot

```
git log --oneline
0ef25a4 Initial commit: booking-service compiles clean

git status --short
 M CLAUDE.md
 M booking-service/pom.xml
 M pom.xml
```

Those 3 uncommitted files contain the testcontainers fix described above.
