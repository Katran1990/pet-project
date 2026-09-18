# Pet project

A small full-stack playground: a Spring Boot REST backend on Postgres and a
React single-page frontend.

## Stack

- **Backend** — Java 25, Spring Boot 4.1, Gradle, Postgres 17, Flyway. Virtual
  threads are enabled, so the code is plain blocking MVC (no WebFlux).
- **Frontend** — React 19, TypeScript, Vite.
- **Tests** — JUnit 5 with Testcontainers for integration tests (Docker required).

## Layout

```
backend/    Spring Boot application (dev.katran.pet)
frontend/   Vite + React single-page app
infra/      infrastructure files
```

## Prerequisites

Everything runs inside the dev container defined in `.devcontainer/`. It already
provides the JDK, Node.js, Docker and a Postgres instance reachable at
`postgres:5432` (database, user and password all `app`).

## Running

Backend — starts on http://localhost:8080, Flyway applies the migrations on boot:

```bash
cd backend && ./gradlew bootRun
```

Frontend — starts on http://localhost:5173 and proxies `/api` to the backend:

```bash
cd frontend && npm install   # first run only
cd frontend && npm run dev
```

## Tests

```bash
cd backend && ./gradlew test
```

## Configuration

The database connection is read from the environment; the defaults in
`backend/src/main/resources/application.properties` point at the dev container
Postgres and are meant for local development only.

| Variable      | Default                                    |
| ------------- | ------------------------------------------ |
| `DB_URL`      | `jdbc:postgresql://postgres:5432/app`      |
| `DB_USER`     | `app`                                      |
| `DB_PASSWORD` | `app`                                      |

## API

| Method | Path            | Description                     |
| ------ | --------------- | ------------------------------- |
| `GET`  | `/api/greeting` | Returns the stored greeting     |
| `POST` | `/api/greetings` | Creates a greeting ({"message": "..."}, 1-200 chars) |
| `GET`  | `/actuator/health` | Application health           |
