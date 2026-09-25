# Pet project

## Stack
- backend/: Java 25, Spring Boot 4.1, Gradle (always via ./gradlew), Postgres 17, Flyway
- frontend/: React + TypeScript + Vite
- Virtual threads are enabled. Write plain blocking code, no WebFlux.

## Environment
- We work inside a dev container. Postgres is reachable at postgres:5432 (db/user/pass: app)
- frontend/node_modules is a Docker volume. Never delete the directory itself.
- Integration tests use Testcontainers (Docker is available inside the container).

## Commands
- Backend tests: cd backend && ./gradlew test
- Run backend:   cd backend && ./gradlew bootRun   (port 8080)
- Run frontend:  cd frontend && npm run dev         (port 5173)
- After any change to backend dependencies, run
  cd backend && ./gradlew dependencies --write-locks and commit
  backend/gradle.lockfile, otherwise the build fails (dependency locking).

## Rules
- No git operations unless I explicitly ask.
- A task is not done until the tests pass.
- Never hardcode secrets; use environment variables.
- Prefer configuration and existing library features over custom code.
- Money is BigDecimal in Java and a string with two decimals in JSON,
  never float/double.
- Table names are singular.
- API errors are RFC 9457 Problem Details (application/problem+json);
  validation errors add `errors: [{field, message}]`.
- An empty string in an optional string field is normalised to null
  in POST and PATCH. In PATCH, an omitted or null optional string
  field means "leave unchanged", an empty string means "clear".

## Language
- Everything in the repository is in English: code, comments, docs, config,
  commit messages, branch names, PR descriptions.
- Talk to me in Russian in the chat.