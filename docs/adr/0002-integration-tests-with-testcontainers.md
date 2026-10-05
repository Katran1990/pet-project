# 0002. Integration tests against real Postgres with Testcontainers

Date: 2026-09-18
Status: accepted

## Context
The backend logic lives largely in the database: Flyway migrations, unique indexes,
check constraints, JPQL aggregates and a hand-written report SQL. Tests have to prove
that behaviour against the same Postgres version that runs in production. `CLAUDE.md`
states that integration tests use Testcontainers and that Docker is available in the dev
container.

## Decision
- Backend tests import `TestcontainersConfiguration` (`postgres:17` with
  `@ServiceConnection`) and run as `@SpringBootTest(webEnvironment = RANDOM_PORT)` with
  `RestTestClient`. Repositories and `JdbcTemplate` are autowired to check the database.
- Integration test classes without bean overrides share one cached Spring context and
  one Postgres container. Their conventions:
  - every test creates its own data with unique names (`"Food-" + UUID`);
  - no test asserts global counts, only counts before and after a single request;
  - tests that read data by month use months no other class uses, and the class comment
    lists the months it owns;
  - JSON bodies containing `null` are built with `HashMap` or raw strings, never `Map.of`.
- A class that overrides a bean (for example `@TestBean Clock`) gets its own context and
  container. This is accepted for the classes that need a fixed clock.
- Tests never use the dev-container Postgres (`postgres:5432`) and never start the
  application with `bootRun`.
- A task is done only when `cd backend && ./gradlew test` is green.

## Alternatives considered
- A `@Primary` fixed `Clock` in `TestcontainersConfiguration`: rejected, because it would
  affect every test and `bootTestRun`.
- Toxiproxy or a fixed-host-port container to simulate a late database: rejected in
  favour of a small in-test `DelayedTcpProxy`; the alternatives need an extra module or
  image, or are more flaky.
- Anything else: not recorded.

## Consequences
- Every backend test run needs Docker, locally and in CI. CI uses the self-hosted
  runner's Docker daemon (host baseline, ADR 0021); GitHub-hosted runners provide it for
  fork and Dependabot PRs.
- Each extra context (bean override) adds a container start, a few seconds per class.
- Authors of new integration tests must check which months and data the shared context
  already uses (see the class comment of `ReportControllerIT`).
- Only one Gradle run at a time should execute in `backend/`.
