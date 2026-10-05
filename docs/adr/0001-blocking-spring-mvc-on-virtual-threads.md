# 0001. Blocking Spring MVC on virtual threads

Date: 2026-09-18
Status: accepted

## Context
The application scaffold (Java 25, Spring Boot 4.1, Postgres 17) had to settle the
programming model for the backend: classic blocking Servlet code or a reactive stack.
The decision is recorded in `CLAUDE.md` ("Virtual threads are enabled. Write plain
blocking code, no WebFlux.").

## Decision
- The backend uses Spring MVC on the Servlet stack with virtual threads enabled.
- All code is plain blocking code: Spring Data JPA, `JdbcClient`, blocking JDBC calls,
  `Thread.sleep` where waiting is needed.
- WebFlux, Reactor and other reactive libraries are not used.

## Alternatives considered
not recorded

## Consequences
- Controllers, repositories and helpers are written synchronously. No `Mono`/`Flux`,
  no reactive drivers (R2DBC).
- Blocking waits are acceptable. For example, the startup wait for the database sleeps
  between connection attempts, and the test helper `DelayedTcpProxy` uses virtual threads.
- Any library proposed later must work in a blocking Servlet application. A reactive-only
  library needs a new decision.
