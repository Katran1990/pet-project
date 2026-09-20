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
.github/    CI workflows (tests, Docker image builds)
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

## CI and Docker images

- `.github/workflows/ci.yml` runs on every PR: backend `./gradlew build`
  (compiles and runs tests), frontend lint and build.
- `.github/workflows/build-images.yml` pushes
  `ghcr.io/<owner>/<repo>/backend:<tag>` and
  `ghcr.io/<owner>/<repo>/frontend:<tag>` on push to `development`/`main`,
  tagged with the branch name and the commit SHA.
- Build the images locally:

  ```bash
  docker build -t pet-backend backend
  docker build -t pet-frontend frontend
  ```

- The backend image reads `DB_URL`, `DB_USER` and `DB_PASSWORD` at runtime.
- The frontend image proxies `/api` to a host named `backend:8080`, which
  must resolve when the container starts.

## Deploy (Helm + Argo CD)

- `infra/helm/pet-project` — the chart (backend, frontend, in-cluster
  Postgres, Ingress).
- `infra/argocd/apps.yaml` — two Argo CD Applications (`pet-project-dev` ->
  namespace `dev` from branch `development`, `pet-project-prod` -> namespace
  `prod` from branch `main`); applied once by hand with
  `kubectl apply -f infra/argocd/apps.yaml`.
- Environment values live on the `deploy` branch (`envs/dev/values.yaml`,
  `envs/prod/values.yaml`); `.github/workflows/update-deploy.yml` writes the
  image tag there after a successful "Build images" run.
- Add `dev.pet.local` / `pet.local` to `/etc/hosts` for the local k3d cluster.
