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
  (compiles and runs tests), frontend lint and build, `helm lint` and
  `helm template` of `infra/helm/pet-project` against the `envs/dev` and
  `envs/prod` values from `pet-project-deploy@main`, and actionlint over
  `.github/workflows/`. Run the same checks locally with
  `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color`
  and `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 lint infra/helm/pet-project`.
  The `helm template` check additionally needs the `envs/dev` and
  `envs/prod` values files from `pet-project-deploy@main`; download them and
  render locally with:

  ```bash
  curl -fsSL https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/dev/values.yaml -o /tmp/dev-values.yaml
  docker run --rm -v "$PWD:/apps" -v "/tmp:/vals" -w /apps alpine/helm:3.22.0 \
    template pet-project-dev infra/helm/pet-project -n dev -f /vals/dev-values.yaml > /dev/null
  ```
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
  `kubectl apply -f infra/argocd/apps.yaml`. Each app has two sources: the
  chart from this repo and the environment values from the deploy repo.
- Environment values live in a separate repository,
  `https://github.com/Katran1990/pet-project-deploy` (branch `main`, files
  `envs/dev/values.yaml` and `envs/prod/values.yaml`). They were moved out of
  the `deploy` branch of this repo because Argo CD rejected a multi-source
  Application that referenced two revisions of the same repository.
- `.github/workflows/update-deploy.yml` writes the image tag into that repo
  after a successful "Build images" run, using the repository secret
  `DEPLOY_REPO_TOKEN` (a token with write access to `pet-project-deploy`).
- The `deploy` branch of this repository is superseded once this change
  reaches `main` - `workflow_run` workflows always run the copy of the
  workflow file from the default branch, so until then the old workflow
  keeps writing to that branch. After that, nothing reads or writes it any
  more; it is kept as-is for history.
- Add `dev.pet.local` / `pet.local` to `/etc/hosts` for the local k3d cluster.
