# 0008. Prefer configuration and existing library features over custom code

Date: 2026-09-25
Status: accepted

## Context
The rule was added to `CLAUDE.md` together with the dependency-scanning card. Since then
every plan has had to justify custom code and new dependencies against it.

## Decision
- When Spring Boot, a library already on the classpath or a built-in tool can do the job
  through configuration, use that instead of writing code. Examples from the project:
  - `spring.mvc.problemdetails.enabled` with a `ResponseEntityExceptionHandler` subclass
    for errors (ADR 0010);
  - `ValidationConfigurationCustomizer` to give Bean Validation the application clock
    (ADR 0015);
  - `spring.jackson.deserialization.accept-float-as-int=false` instead of a custom
    deserializer (ADR 0012);
  - Gradle's built-in dependency locking (ADR 0007);
  - `JdbcClient` with its built-in record mapping (ADR 0017);
  - the Micrometer Prometheus registry instead of a hand-written exporter, and kustomize
    `configMapGenerator` for dashboards (ADR 0019, ADR 0020);
  - `@fontsource` packages instead of committed font binaries.
- A new dependency is proposed with its version, why it is needed and the cost of writing
  it by hand, and it needs the user's confirmation.
- Custom code is acceptable when configuration cannot meet the requirement, and the plan
  says why. Recorded cases:
  - the database startup wait, because Flyway `connect-retries` has no time period and
    Hikari's timeout has no configurable interval;
  - custom query binding instead of Spring Data `Pageable`, because `Pageable` silently
    clamps values (ADR 0016).

## Alternatives considered
not recorded

## Consequences
- Plans list "New dependency" with justification and by-hand cost, even when the card
  already names the artifact.
- Small helpers stay small: a ~60-line fetch wrapper instead of axios or react-query, a
  static `from(...)` factory instead of MapStruct.
- Configuration choices become behaviour that tests pin, because they are easy to change
  by accident.
