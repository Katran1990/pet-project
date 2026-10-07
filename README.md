# Pet project

A small full-stack playground: a Spring Boot REST backend on Postgres and a
React single-page frontend.

## Stack

- **Backend** — Java 25, Spring Boot 4.1, Gradle, Postgres 17, Flyway. Virtual
  threads are enabled, so the code is plain blocking MVC (no WebFlux).
- **Frontend** — React 19, TypeScript, Vite.
- **Tests** — JUnit 5 with Testcontainers for integration tests (Docker required);
  Vitest with Testing Library (jsdom) for the frontend.

## Layout

```
.github/    CI workflows (tests, Docker image builds)
backend/    Spring Boot application (dev.katran.pet)
e2e/        Playwright end-to-end tests (run against dev after deploy)
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
cd frontend && npm test   # Vitest, jsdom, fetch is mocked: no backend needed
```

## Configuration

Configuration is read from the environment; the defaults in
`backend/src/main/resources/application.properties` point at the dev container
Postgres and are meant for local development only.

| Variable                   | Default                                    |
| -------------------------- | ------------------------------------------ |
| `DB_URL`                   | `jdbc:postgresql://postgres:5432/app`      |
| `DB_USER`                  | `app`                                      |
| `DB_PASSWORD`              | `app`                                      |
| `DB_STARTUP_WAIT_TIMEOUT`  | `60s`                                      |
| `DB_STARTUP_WAIT_INTERVAL` | `2s`                                       |
| `APP_TIME_ZONE`            | `Europe/Warsaw`                            |

On startup the backend waits up to `DB_STARTUP_WAIT_TIMEOUT` for Postgres to
accept connections, trying again every `DB_STARTUP_WAIT_INTERVAL`.
Authentication or unknown-database errors fail immediately instead of
waiting out the timeout. Setting `DB_STARTUP_WAIT_TIMEOUT=0` restores
fail-fast (a single attempt).

The `DB_USER`/`DB_PASSWORD` defaults apply only to local runs. In Kubernetes,
they always come from the Secret `postgres-credentials` created by Sealed
Secrets, and there is no fallback.

`APP_TIME_ZONE` is the IANA zone in which "today" is evaluated (an expense's
`spentOn` must not be after today in this zone); an invalid zone fails
startup. It also defines the "current month" that `GET /api/expenses` lists
by default, and the date of expenses created by `POST /api/quick-templates/{id}/apply`.

## API

| Method | Path            | Description                     |
| ------ | --------------- | ------------------------------- |
| `GET`  | `/api/greeting` | Returns the stored greeting     |
| `POST` | `/api/greetings` | Creates a greeting ({"message": "..."}, 1-200 chars) |
| `POST` | `/api/categories` | Creates a category ({"name": "...", "icon": "..."}); name is stripped, empty icon stored as null; 409 on duplicate name, case-insensitive |
| `GET`  | `/api/categories` | Lists active categories; ?includeArchived=true includes archived ones |
| `GET`  | `/api/categories/{id}` | Returns one category (archived ones too); 404 if unknown |
| `PATCH` | `/api/categories/{id}` | Updates name, icon and/or archived; omitted or null fields are unchanged, "icon": "" clears the icon |
| `POST` | `/api/expenses` | Creates an expense ({"amount": "200.00", "categoryId": 1, "spentOn": "2026-06-16", "note": "..."}); amount > 0 with at most two decimals, spentOn not after today (APP_TIME_ZONE); 400 on unknown category, 409 on archived category |
| `GET` | `/api/expenses` | Lists expenses: ?from=&to= (ISO dates, inclusive; default: current month in APP_TIME_ZONE), ?categoryIds=1,4,7 (empty = all; unknown id → 400), ?page= (0-based) and ?size= (1-200, default 50); sorted by spentOn desc, createdAt desc; returns {items, page, size, totalItems, totalAmount} with totalAmount summed over all matching rows |
| `GET` | `/api/expenses/{id}` | Returns one expense with its category embedded as {id, name, icon}; 404 if unknown |
| `PUT` | `/api/expenses/{id}` | Replaces amount, categoryId, spentOn and note (an omitted note clears it); 409 when switching to an archived category |
| `DELETE` | `/api/expenses/{id}` | Deletes an expense; 204, or 404 if unknown |
| `PUT` | `/api/budget-limits` | Sets the limit of a category for a month ({"categoryId": 1, "month": "2026-07", "amount": "1500.00"}); creates or updates (upsert) and always returns 200 with the stored limit; amount > 0 with at most two decimals; 400 on malformed month or unknown category, 409 on archived category |
| `GET` | `/api/budget-limits?month=YYYY-MM` | Lists the limits of a month (month is required) with the category embedded as {id, name, icon}, ordered by category id |
| `DELETE` | `/api/budget-limits/{id}` | Deletes a limit; 204, or 404 if unknown |
| `GET` | `/api/reports/by-category?month=YYYY-MM` | Monthly report (month is required): {month, totalAmount, rows}; one row per category with expenses and/or a limit in that month: {category {id, name, icon}, amount, share (percent of totalAmount, one decimal, "0.0" when the total is zero), limit (null if none), remaining (limit minus amount, null if no limit, negative when exceeded)}; sorted by amount desc, then category id; archived categories included |
| `POST` | `/api/quick-templates` | Creates a quick template ({"name": "Coffee", "categoryId": 1, "amount": "12.50", "sortOrder": 0}); all fields required; name is stripped (1-64 chars, not unique); amount > 0 with at most two decimals; sortOrder is an integer; 400 on unknown category, 409 on archived category |
| `GET` | `/api/quick-templates` | Lists all quick templates sorted by sortOrder, then id, with the category embedded as {id, name, icon, archived}; templates of archived categories are included with archived: true (applying them returns 409) |
| `GET` | `/api/quick-templates/{id}` | Returns one quick template; 404 if unknown |
| `PATCH` | `/api/quick-templates/{id}` | Updates name, amount, categoryId and/or sortOrder; omitted or null fields, and "" for amount, categoryId or sortOrder, are left unchanged; 409 when switching to an archived category |
| `DELETE` | `/api/quick-templates/{id}` | Deletes a template; expenses created from it are kept; 204, or 404 if unknown |
| `POST` | `/api/quick-templates/{id}/apply` | Creates an expense dated today (APP_TIME_ZONE) with the template's amount and category; optional JSON body {"amount": "...", "note": "..."} overrides amount and note (a null or "" amount means no override; a body without Content-Type application/json is 415); 201 with the created expense (same body as POST /api/expenses); 409 if the template's category is archived |
| `GET`  | `/actuator/health` | Application health           |
| `GET`  | `/actuator/info` | Git commit of the build (`git.commit.id`, `git.commit.id.abbrev`, `git.commit.time`); no branch, author or message. Behind nginx, `/api/actuator/health` and `/api/actuator/info` are the only reachable actuator paths (ADR 0022) |
| `GET`  | `/actuator/prometheus` | Metrics in the Prometheus text format (Micrometer), e.g. `http_server_requests_seconds`, `jvm_memory_used_bytes`; unauthenticated, intended for in-cluster scraping only (see "Deploy") |

Errors are returned as RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`, `detail` and `instance`. Validation errors (400) additionally contain `errors: [{"field": "...", "message": "..."}]`.

Money amounts are JSON strings with two decimals (`"200.00"`). `currency` is always `PLN` for now and is not accepted in requests. Percentages (`share`) are JSON strings with one decimal (`"64.8"`).

In JSON request bodies, an empty string (`""`) in a numeric field is read as `null`: a required field then fails with `errors[{field, "must not be null"}]`, a PATCH field stays unchanged, and an apply override is not applied. Integer fields such as `categoryId` and `sortOrder` reject any JSON number with a fraction or an exponent (`1.5`, `7.0`, `1e1`) with 400 without `errors[]`.

## E2E tests

- **What runs and when:** `.github/workflows/e2e.yml` runs after "Update deploy manifests"
  succeeded for `development`. It waits up to 10 minutes until `/api/actuator/info` of dev
  reports the deployed commit, then runs the Playwright tests in `e2e/` (a smoke test and a
  create category / create expense / report / clean up scenario). It is not a gate: a red run
  blocks nothing. Decision: [ADR 0022](docs/adr/0022-post-deploy-e2e-tests-on-dev.md).
- **Base URL:** the repository variable `E2E_BASE_URL` (Settings > Secrets and variables >
  Actions > Variables), e.g. `http://dev.pet.local`. It is never hard-coded.
- **Run locally:**

  ```bash
  cd e2e && npm ci
  npx playwright install chromium     # on bare Linux also: sudo npx playwright install-deps chromium
  E2E_BASE_URL=<url> npm run e2e
  npx playwright show-report
  ```

  The base URL must serve the production nginx routing (the Vite dev server does not map
  `/api/actuator/*`). Never point the tests at prod: they write data.
- **Test data:** category names and expense notes are `e2e-<runId>-<nonce>`. The test deletes
  its expense and archives its category (categories cannot be deleted).
- **Failure artifacts:** the Playwright HTML report and traces are uploaded for 7 days. The
  repository is public, so any signed-in GitHub user can download them; they show dev data
  and the dev host name.
- **Clean up leftovers** (dev only; archived categories accumulate and cancelled runs can leave
  an active one), as one transaction:

  ```sql
  begin;
  delete from expense where category_id in (select id from category where name like 'e2e-%');
  delete from category where name like 'e2e-%';
  commit;
  ```
- `ci.yml` has a job `e2e-check` (type-check, unit tests of the wait script, `playwright test
  --list`), with no browser and no network.

## CI and Docker images

- `.github/workflows/ci.yml` runs on every PR: backend `./gradlew build`
  (compiles and runs tests), frontend lint, build and tests (`npm test`),
  `helm lint` and `helm template` of `infra/helm/pet-project` against the
  `envs/dev` and `envs/prod` values from `pet-project-deploy@main` (the render
  also runs with `--api-versions monitoring.coreos.com/v1/ServiceMonitor` and
  checks that the ServiceMonitor is rendered), a `helm template` of
  kube-prometheus-stack (version pinned in `apps.yaml`) with `infra/monitoring/values.yaml`
  plus a yq check of the monitoring Applications (`monitoring`, `monitoring-secrets`
  and `monitoring-dashboards`), the `dashboards` job (every
  `infra/monitoring/dashboards/*.json` is valid JSON with a `uid` and a `title`, has no
  grafana.com import inputs, and `kustomize` (pinned v5.6.0) renders it into a labelled ConfigMap),
  a Trivy dependency scan (see "Dependency vulnerability scanning (Trivy)" below),
  the `e2e-check` job (type-check of `e2e/`, see "E2E tests"),
  and actionlint over `.github/workflows/`. All jobs run on the self-hosted runner; see
  "CI runner" below. Run the same checks locally with
  `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color`
  and `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 lint infra/helm/pet-project --namespace dev`.
  The `helm template` check additionally needs the `envs/dev` and
  `envs/prod` values files from `pet-project-deploy@main`; download them and
  render locally with:

  ```bash
  curl -fsSL https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/dev/values.yaml -o /tmp/dev-values.yaml
  docker run --rm -v "$PWD:/apps" -v "/tmp:/vals" -w /apps alpine/helm:3.22.0 \
    template pet-project-dev infra/helm/pet-project -n dev -f /vals/dev-values.yaml > /dev/null
  ```

  The monitoring render needs no extra files:

  ```bash
  docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 template monitoring kube-prometheus-stack \
    --repo https://prometheus-community.github.io/helm-charts --version 91.9.0 \
    --namespace monitoring -f infra/monitoring/values.yaml > /dev/null
  ```

  The dashboards check runs locally with jq and kustomize (see "Dashboards"):

  ```bash
  jq -e . infra/monitoring/dashboards/*.json > /dev/null
  docker run --rm -v "$PWD:/w" -w /w registry.k8s.io/kustomize/kustomize:v5.6.0 build infra/monitoring/dashboards > /dev/null
  ```
- `.github/workflows/build-images.yml` builds, scans (Trivy) and pushes
  `ghcr.io/<owner>/<repo>/backend:<tag>` and
  `ghcr.io/<owner>/<repo>/frontend:<tag>` on push to `development`/`main`,
  tagged with the branch name and the commit SHA. Each image is scanned
  before it is pushed; a failed scan blocks the push of that image. On one
  runner the two images build one after the other, and with fail-fast a failing
  first leg cancels the queued second one; with two runners they build in
  parallel and one may already have been pushed when the other's scan fails.
- Build the images locally:

  ```bash
  docker build -t pet-backend backend
  docker build -t pet-frontend frontend
  ```

  Optionally add `--build-context gitdir=.git` to the backend build so the jar carries
  `git.properties` (the commit shown by `/actuator/info`); without it the jar has none.

- The backend image reads `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `DB_STARTUP_WAIT_TIMEOUT`, `DB_STARTUP_WAIT_INTERVAL` and `APP_TIME_ZONE`
  at runtime.
- The frontend image proxies `/api` to a host named `backend:8080`, which
  must resolve when the container starts.

### Dependency vulnerability scanning (Trivy)

- **What runs where:** a filesystem scan of `backend/gradle.lockfile`,
  `frontend/package-lock.json` and `e2e/package-lock.json` runs in `ci.yml` on every PR and on every push
  to `development`/`main`. An image scan of the built `backend` and
  `frontend` images runs in `build-images.yml` before they are pushed.
- **What fails:** fixed HIGH and CRITICAL findings fail the job. MEDIUM, LOW
  and UNKNOWN are only reported. Unfixed vulnerabilities (no patched version
  available yet) are ignored by default (`ignore-unfixed`), so they appear
  neither in the report nor in the gate. npm devDependencies are included in
  the scan (`TRIVY_INCLUDE_DEV_DEPS`); Gradle test dependencies are scanned
  too, because `gradle.lockfile` does not separate configurations.
- **How to read findings:**
  - The job summary of the run has a table with package, installed version,
    fixed version and CVE.
  - The failing gate step's log lists the blocking findings.
  - Security tab → Code scanning, filtered by tool "Trivy" and category
    `trivy-fs` / `trivy-image-backend` / `trivy-image-frontend`.
  - Fork and Dependabot PRs only get the job summary; they cannot upload
    SARIF.
- **How to fix:** bump the dependency, or rebuild on a newer base image. For
  a Gradle dependency change, run `cd backend && ./gradlew dependencies
  --write-locks` and commit `backend/gradle.lockfile`.
- **How to suppress:** add the CVE to `/.trivyignore`, following the comment
  convention at the top of that file (package, reason, added-by/date), with
  a mandatory `exp:YYYY-MM-DD` at most 90 days ahead. The finding comes back
  and fails CI again once that date passes.
- **Run locally**, with the pinned Trivy version used by CI:

  ```bash
  docker run --rm -v "$PWD:/repo" -w /repo -e TRIVY_INCLUDE_DEV_DEPS=true \
    aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e \
    fs --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 .

  docker build -t pet-backend backend && docker build -t pet-frontend frontend
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/repo" -w /repo \
    -e TRIVY_IMAGE_SRC=docker \
    aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e \
    image --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 pet-backend
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/repo" -w /repo \
    -e TRIVY_IMAGE_SRC=docker \
    aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e \
    image --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 pet-frontend
  ```

  The repo is mounted at `/repo` (Trivy's working directory) so `.trivyignore` at the
  repository root is picked up the same way it is in CI.

## CI runner

- **Where it runs:** a self-hosted GitHub Actions runner on a home machine, labels
  `self-hosted` and `home`. It runs every job of `ci.yml`, `build-images.yml`,
  `update-deploy.yml` and `e2e.yml`. Pull requests from forks, from a deleted fork and from Dependabot run
  `ci.yml` on GitHub-hosted `ubuntu-latest` instead (the `runs-on` expression). Own-branch
  PRs and pushes use the self-hosted runner. Decision and trust boundaries:
  [ADR 0021](docs/adr/0021-self-hosted-ci-runner.md).
- **Fork PRs:** the repository setting "Require approval for all external contributors"
  (Settings, Actions, General, Fork pull request workflows) is already enabled. A fork PR
  can edit the workflow files, including `runs-on`, so before approving a run, check its
  changes under `.github/`.
- **Host baseline:** Ubuntu LTS on x86_64, the runner as a systemd service under a dedicated
  non-root user, Docker Engine (rootful, the runner user in the `docker` group; Testcontainers
  needs the Docker socket), git >= 2.32, bash, coreutils, grep, curl, ca-certificates, tar,
  gzip and xz-utils, plus the shared libraries Chromium needs for `e2e.yml`, installed once by
  hand with `sudo npx playwright@1.63.0 install-deps chromium` (repeat after each Playwright
  bump). Everything else is installed by the jobs with pinned versions; the
  list of pins is in [ADR 0006](docs/adr/0006-ci-validation-and-pinning.md).
- **E2E prerequisites:** the host must resolve and reach the host in `E2E_BASE_URL` (for
  example an `/etc/hosts` entry pointing at the k3d ingress). Every push to `development`
  keeps the single runner busy for the deploy wait (up to 10 minutes) plus the tests, and
  later jobs queue behind it.
- **Restart the service:** in the runner directory, `sudo ./svc.sh status`,
  `sudo ./svc.sh stop`, `sudo ./svc.sh start`. Or use
  `sudo systemctl restart actions.runner.<owner>-<repo>.<runner-name>.service`. Jobs wait in
  "Waiting for a runner..." while the service is down. Fork and Dependabot PRs are not
  affected.
- **Caches:** Gradle and npm use the runner user's `~/.gradle` and `~/.npm`. The workflows
  use no GitHub Actions cache for them or for the Trivy scans (ADR 0021), so fork and
  Dependabot PRs on `ubuntu-latest` download their dependencies on every run. Image layers
  still use the GitHub Actions cache (`type=gha`).
- **Disk space:** the host keeps Docker images (base images, the Testcontainers `postgres:17`
  and Ryuk images, built images), the tool cache (`_work/_tool`), `~/.gradle` and `~/.npm`.
  Every "Build images" run leaves a new image tagged with its commit SHA.
  - **Weekly prune (systemd timer, installed by hand):** `docker-prune.timer` starts
    `docker-prune.service` every Sunday at 04:00. With `Persistent=true`, a run missed while
    the host was off happens at the next boot. The service runs
    `docker system prune --all --force --filter until=168h`. It removes stopped containers,
    unused networks, unused images and build cache, but only objects created more than
    7 days ago. Volumes are never pruned. The timer runs at 04:00 on Sunday, when normally no
    job runs, and an image built in a running job is normally younger than 7 days, so the
    runner service can keep running. For images, the `until` filter compares against the
    image's `Created` date (when it was built, upstream for base images), not when it was
    pulled, and a fully cached build can keep an older `Created` date. Old base images (for
    example `postgres:17`) are pulled again by the next job that needs them. Install it once:

    ```bash
    sudo tee /etc/systemd/system/docker-prune.service > /dev/null <<'EOF'
    [Unit]
    Description=Weekly docker system prune for the CI runner
    Requires=docker.service
    After=docker.service

    [Service]
    Type=oneshot
    ExecStart=/usr/bin/docker system prune --all --force --filter until=168h
    EOF
    sudo tee /etc/systemd/system/docker-prune.timer > /dev/null <<'EOF'
    [Unit]
    Description=Run docker-prune.service weekly

    [Timer]
    OnCalendar=Sun *-*-* 04:00:00
    Persistent=true

    [Install]
    WantedBy=timers.target
    EOF
    sudo systemctl daemon-reload
    sudo systemctl enable --now docker-prune.timer
    ```

    Check it with `systemctl list-timers docker-prune.timer` (last and next run) and
    `sudo journalctl -u docker-prune.service -n 50` (what the last run reclaimed).
  - **Check disk usage:**

    ```bash
    df -h / /var/lib/docker      # free space on the root and the Docker filesystem
    docker system df             # images, containers, volumes, build cache, "RECLAIMABLE"
    docker system df -v          # the same per image, container and volume
    sudo du -sh ~gh-runner/.gradle ~gh-runner/.npm ~gh-runner/.cache ~gh-runner/.local \
      <runner-dir>/_work/_tool
    ```

    Gradle deletes cache entries it has not used for 30 days on its own; `~/.npm` only grows.
    To shrink it, stop the service and run `sudo rm -rf ~gh-runner/.npm/_cacache`; the next
    `npm ci` downloads again.
  - **Pruning by hand:** `docker system prune` removes stopped containers, dangling images,
    unused networks and the build cache. `docker system prune -a` also removes cached base
    images, so the next runs pull them again from Docker Hub, which rate-limits anonymous pulls
    per IP. Without the `until` filter, only prune when no job is running: stop the service
    first, because `-a` can delete an image that "Build images" has built but not yet pushed.
- **Persistent state:** `clean: true` removes untracked and changed files but keeps
  `.git/config` and `.git/hooks` (ADR 0021), which is why "Build images" and
  `update-deploy.yml` empty the workspace before their checkout; `update-deploy.yml` also
  deletes its working copy and checkout credentials at the end. Everything else on the host
  persists between jobs: the runner user's whole home directory (`~/.gradle`, `~/.npm`,
  `~/.gitconfig`, `~/.docker/`, `~/.local/`), `_work/_tool`, and Docker images, volumes and
  build cache. Any job can change these, and later jobs use them, including "Build images"
  (GHCR push token) and "Update deploy manifests" (`DEPLOY_REPO_TOKEN`). This is an accepted
  risk with two mitigations, git config isolation in `update-deploy.yml` and Dependabot PRs on
  `ubuntu-latest`; see ADR 0021 "Trust boundaries". To reset it, stop the service, wipe the
  runner user's home caches and `_work/_tool`, and prune Docker.

## Deploy (Helm + Argo CD)

- `infra/helm/pet-project` — the chart (backend, frontend, in-cluster
  Postgres, Ingress).
- `infra/argocd/apps.yaml` — five Argo CD Applications (`pet-project-dev` ->
  namespace `dev` from branch `development`, `pet-project-prod` -> namespace
  `prod` from branch `main`, `monitoring` -> namespace `monitoring`: the
  kube-prometheus-stack chart from the prometheus-community Helm repo with its
  values from this repo's `main`, `monitoring-secrets` -> the Grafana
  SealedSecrets (admin and Postgres reader) from `infra/monitoring/sealed-secrets`
  on `main`, and `monitoring-dashboards` -> the Grafana dashboard ConfigMaps
  from `infra/monitoring/dashboards` on `main`); applied
  once by hand with `kubectl apply -f infra/argocd/apps.yaml`. Each
  pet-project app has two sources: the chart from this repo and the
  environment values from the deploy repo. See "Monitoring
  (kube-prometheus-stack)" below.
- The backend and frontend Deployments keep only 3 old ReplicaSets
  (`revisionHistoryLimit: 3`); rollbacks go through git and Argo CD.
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
- Add `dev.pet.local` / `pet.local` / `grafana.pet.local` to `/etc/hosts` for the local k3d cluster.
- Metrics: the Ingress and the frontend's nginx route `/api/` plus exactly
  `/api/actuator/health` and `/api/actuator/info` (mapped to the backend's
  `/actuator/...`, ADR 0022) to the backend; `/actuator/prometheus` is meant to be scraped from inside the
  cluster, where it is served without authentication at
  `http://backend:8080/actuator/prometheus`. The backend Service has the label
  `app: backend` and its port is named `http`, so a Prometheus Operator
  `ServiceMonitor` can select it (`matchLabels: { app: backend }`,
  `port: http`); the chart ships ServiceMonitor `backend` (port `http`, path
  `/actuator/prometheus`), rendered only when the ServiceMonitor CRD exists
  in the cluster. HTTP server timings are exported
  as a histogram (`http_server_requests_seconds_bucket`, usable with
  `histogram_quantile`), and every metric carries the tag `application="pet"`.
  Locally the endpoint is at `http://localhost:8080/actuator/prometheus`; the
  Vite dev server proxies only `/api`.
- Postgres credentials are per-namespace SealedSecrets under
  `infra/helm/pet-project/sealed-secrets/<namespace>/`, and `envs/*/values.yaml`
  never holds credentials. See "Postgres credentials (Sealed Secrets)" below.

## Postgres credentials (Sealed Secrets)

### Why Sealed Secrets

We need no external secret backend (Vault, AWS/GCP Secret Manager) for a pet
project. External Secrets would require one plus a credential to reach it.
Encrypted values live in git next to the chart and follow the same GitOps
flow. Trade-offs: re-sealing is needed if the controller key is lost (for
example after `k3d cluster delete`), and rotation is a git change.

### How it works

One strict-scope SealedSecret per namespace, at
`sealed-secrets/<namespace>/postgres-credentials.yaml`, selected by release
namespace (`dev`/`prod` from the Argo CD Application). Rendering fails for
any namespace without a file. Ciphertext is safe to commit to a public repo.
Plaintext never is.

Canonical check before merging: `helm template pet-project-<env>
infra/helm/pet-project --namespace <env> -f <env values> > /dev/null`
(fails on a missing or invalid sealed file), plus `helm lint --strict
--namespace <env> -f <env values>`. `helm lint` alone, even with
`--strict`, only logs the chart's `fail()` checks as INFO and does not
catch an invalid sealed file - only a *missing* file fails it, and only by
accident (the empty `metadata.name` that results then trips a separate
`--strict` warning-as-error). A present-but-invalid file - wrong namespace,
a cluster-wide/namespace-wide annotation, `stringData`, `spec.template.data`,
a missing `encryptedData` key - passes `helm lint --strict` but fails
`helm template`. Always run `helm template` for this chart, not just lint.
CI (`.github/workflows/ci.yml`, job `helm`) runs exactly this `helm template`
check for both `dev` and `prod` on every PR, alongside `helm lint` with
`--namespace` for the chart's other lint checks.

### Prerequisites (one-time, cluster-wide - done by hand, not by Argo CD)

Install the controller with a pinned chart version into `kube-system`, named
so `kubeseal` finds it with its defaults:

```bash
helm repo add sealed-secrets https://bitnami.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets --version 2.20.0 \
  --namespace kube-system --set-string fullnameOverride=sealed-secrets-controller
```

Install the `kubeseal` CLI with the matching version, `v0.40.0`
(chart `sealed-secrets-2.20.0`, app version `0.40.0`, from
`helm search repo sealed-secrets/sealed-secrets`). Back up the controller key
**outside any repository**: `kubectl -n kube-system get secret -l
sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > <safe place>` (this is
the private key).

The controller renews its sealing key every 30 days by default (old keys are
kept, so ciphertext sealed under an older key keeps decrypting). A one-time
backup does not cover keys created by later renewals, so **repeat this
backup after every key renewal, and always before re-sealing** (Rotate,
Seal credentials) - keep it outside any repository each time.

The controller is not managed as an Argo CD Application: it is a cluster-wide
install (a CRD plus a controller in `kube-system`), bootstrapped once like
Argo CD itself; its private key is the one thing that must be backed up; and
if `prune: true` / `selfHeal` ever removed the CRD, every SealedSecret would
be deleted, and every generated Secret with it.

### Seal credentials for dev and prod

Run from the repository root, because the output path below is relative. Run
once per environment, each time with a newly generated password.

- The plaintext exists only in a shell variable and a pipe. It is never
  written to disk and never appears on any process command line: `printf`
  is a shell builtin, and kubectl reads the value from stdin.
- `--from-file=password=/dev/stdin` sets the key name `password`. The user
  name is not secret, so it stays a `--from-literal`.

```bash
ENV=dev   # then again with ENV=prod
OUT="infra/helm/pet-project/sealed-secrets/$ENV/postgres-credentials.yaml"
PW="$(openssl rand -hex 24)"
( set -o pipefail
  printf %s "$PW" \
  | kubectl create secret generic postgres-credentials --namespace "$ENV" \
      --from-literal=user=app --from-file=password=/dev/stdin \
      --dry-run=client -o yaml \
  | kubeseal --format yaml \
  > "$OUT.tmp"
) && mv "$OUT.tmp" "$OUT" || rm -f "$OUT.tmp"
```

Write to a temp file and `mv` it into place on success, as above: if
`kubectl` or `kubeseal` fails partway (for example the controller is
unreachable), a direct `> "$OUT"` would truncate the existing, still-valid
file to empty or a partial document. `set -o pipefail` inside the subshell
makes the pipeline's exit status reflect a `kubectl` failure too, not just
the last command (`kubeseal`); without it, a failing `kubectl` piped into
`kubeseal` may not fail clearly on empty input, and the pipeline's exit
status would then depend only on `kubeseal`, which could go undetected.
`pipefail` covers this either way. The `mv` only runs after the whole
pipeline succeeds, and the failure branch removes the leftover `$OUT.tmp`
so it never lingers in the chart.

Keep `$PW` in a password manager until the rotation or first-switch steps
below are finished, because you will need it for `\password`. Then
`unset PW`.

Offline variant, when the cluster is not reachable: run `kubeseal
--fetch-cert > pub-cert.pem` once, then `kubeseal --cert pub-cert.pem
--format yaml`. The public cert may be stored anywhere.

Never commit the output of `kubectl create secret ... -o yaml` without
`kubeseal`.

**Merge blocker:** before merging to `development` or `main`, `grep -rn
PLACEHOLDER infra/helm/pet-project/sealed-secrets` must return nothing. A
placeholder file reaching a real environment makes the controller unable to
decrypt it, Argo CD prunes the old Secret, and backend and Postgres pods
fail to start.

### Rotate the credential

`POSTGRES_PASSWORD` is only read when the database is first initialised.
After that, restarting Postgres never changes the password; only changing
it inside Postgres does (`\password`, or `ALTER USER` as in "Change the
password of an already initialised database").

Every merge also triggers a new backend rollout. Any push to
`development`/`main` runs "Build images", then "Update deploy manifests"
writes a new image tag, and Argo CD rolls out new backend pods. New pods
read the password from the Secret at start.

The backend Deployment uses the default RollingUpdate strategy
(maxUnavailable 0 with 1-2 replicas). New pods that fail to authenticate end
up in `CrashLoopBackOff` and the rollout gets stuck, but the old pods keep
serving until new pods become Ready.

Steps:

1. Before merging, record the current Secret fingerprint (this prints a
   hash, never the value): `kubectl -n <env> get secret postgres-credentials
   -o jsonpath='{.data.password}' | sha256sum`. Re-seal the environment's
   file (see "Seal credentials for dev and prod") and merge it.
   - **dev:** the switch happens when the merge into `development` is synced.
   - **prod:** the re-sealed prod file goes through `development` first.
     There it only causes a harmless dev backend rollout: the dev file is
     unchanged and the dev Secret stays the same. The real switch happens
     when the change is merged into `main` and the prod Application syncs.
     **Do not run `\password` in prod before that `main` merge has synced.**
     If you do, the running prod backend loses new DB connections while the
     prod Secret still holds the old password.
2. **Right after Argo CD has synced the SealedSecret**, change the password
   inside Postgres. Do not wait for the backend rollout to finish. "Synced"
   means both of these are true:
   - the SealedSecret condition `Synced` is `True`;
   - the fingerprint from step 1 has changed.

   Timing: Argo CD usually picks up the chart change from git and syncs the
   SealedSecret a few minutes before the new image tag arrives. The tag only
   comes after "Build images" and then "Update deploy manifests". So step 2
   can often be done before any new backend pod starts. If the new pods
   start first, they fail with `CrashLoopBackOff` until step 2 is done, and
   the old pods keep serving.
   - Local socket connections in the image need no password: run `kubectl -n
     <env> exec -it postgres-0 -- psql -U app -d app`, then `\password app`,
     and paste the new value. Never put it on the command line.
3. Right after step 2, run `kubectl -n <env> rollout restart deployment/backend`,
   then `kubectl -n <env> rollout status deployment/backend`. This is needed
   in all cases:
   - A stuck rollout would otherwise only recover after the CrashLoop
     back-off (up to 5 minutes).
   - A backend pod that started before the controller updated the Secret
     still holds the old password. It fails on its next new DB connection.
   - Old pods keep their already-open pooled connections until the pool
     recycles them (Hikari `maxLifetime`, 30 minutes by default). Do not
     leave step 3 for later.
4. Verify: Argo CD shows the app `Healthy`, and `/actuator/health` returns
   `UP`.

### First switch from the old `app/app`

Follow the same order as Rotate, with these differences:

- The first sync also changes the Postgres readiness probe, so the
  `postgres-0` pod is recreated once (a short DB restart). Its data and its
  `app/app` password are kept on the PVC.
- Argo CD prunes the old Secret and the controller creates the new one.
  Pods started in between fail with `CreateContainerConfigError`; running
  pods are not affected.
- The same merge also triggers a new backend rollout. New backend pods
  crash-loop or wait until you run `\password` (Rotate step 2): they sit in
  `CreateContainerConfigError` while the Secret does not exist yet (bullet
  above), then flip to `CrashLoopBackOff` once the Secret exists but the DB
  password has not been changed yet. The old pods keep serving throughout.
  Do step 2 right after the SealedSecret is `Synced`, then step 3.
- For dev, resetting the volume instead of step 2 is acceptable (data
  loss). Delete the PVC and then the pod, otherwise PVC protection keeps the
  PVC in `Terminating` while `postgres-0` runs: `kubectl -n dev delete pvc
  data-postgres-0 --wait=false && kubectl -n dev delete pod postgres-0`. The
  StatefulSet recreates both, and Postgres re-initialises with the sealed
  credentials. Still run step 3 afterwards.
- If the SealedSecret status reports that the Secret "already exists and is
  not managed", delete the old Secret by hand and restart the controller:
  `kubectl -n <env> delete secret postgres-credentials`, then `kubectl -n
  kube-system rollout restart deployment/sealed-secrets-controller`.

### Change the password of an already initialised database

Postgres stores the password of user `app` on its data volume (the PVC
`data-postgres-0` of `statefulset/postgres`) when the database is first
initialised, and never reads `POSTGRES_PASSWORD` again after that. A change
of the sealed password therefore only changes the Secret
`postgres-credentials`: the backend picks up the new value, the database
keeps the old one, and the backend fails with `password authentication
failed for user "app"`. Restarting or recreating the `postgres-0` pod does
not help, because the volume is kept.

This is the non-interactive equivalent of Rotate steps 2 and 3; do not run
both `\password` and `ALTER USER` for the same change.

Do the step below once per namespace, every time the sealed password for
that namespace changes:

- on every rotation (see "Rotate the credential");
- on the first release of Sealed Secrets to a namespace that already has a
  database, including the first release to `prod` (see "First switch from
  the old `app/app`").

A new namespace, or a dev database whose volume was reset, is initialised
with the sealed password and needs nothing.

Run it only after the SealedSecret in that namespace is `Synced` and the
Secret fingerprint has changed (Rotate steps 1-2); in `prod` that means
after the `main` merge has synced. Before that, the Secret still holds the
old password or does not exist yet, and the commands would set the wrong
one.

Replace `<ns>` with the namespace (`dev` or `prod`). Run the lines one at
a time and check the output of each before running the next:

```bash
NEWPASS=$(kubectl -n <ns> get secret postgres-credentials -o jsonpath='{.data.password}' | base64 -d)
[ -n "$NEWPASS" ] || echo "NEWPASS is empty - stop"
kubectl -n <ns> exec statefulset/postgres -- psql -U app -d app -c "ALTER USER app PASSWORD '$NEWPASS';"
kubectl -n <ns> rollout restart deployment/backend
```

- The first line reads the new password from the Secret.
- The second line (the guard) only prints a warning if `NEWPASS` is
  empty; it does not stop the next lines from running by itself. Check
  its output before running the third line: if `NEWPASS` is empty, stop,
  because `ALTER USER ... PASSWORD ''` would clear the password instead
  of setting it, and would still print `ALTER ROLE`, so that output alone
  does not prove success.
- The third line runs `psql` over the local socket inside the Postgres
  pod, which needs no password, and changes the password stored on the
  volume. It must print `ALTER ROLE`.
- The fourth line restarts the backend so that every pod reconnects
  with the new password (Rotate step 3 explains why this is always
  needed). Follow it with `kubectl -n <ns> rollout status
  deployment/backend` and verify as in Rotate step 4.
- Then run `unset NEWPASS`.

Unlike `\password app`, running the commands above exposes the password on
the `kubectl` and `psql` command lines, so it is visible in the process
list of your machine and of the pod while the command runs. Use `\password
app` (Rotate step 2) where that matters. The quoting in the SQL statement
is safe for the hex passwords produced by "Seal credentials"; a password
containing `'` would break it.

### Grafana read-only user (grafana_reader)

The "Wallet" dashboard reads the prod database as the role `grafana_reader`.

- **What exists:** Flyway `V6__create_grafana_reader_role.sql` creates the cluster-wide role
  `grafana_reader` in every database the backend migrates: the dev container, `dev`, `prod`
  and Testcontainers. It has LOGIN, **no password**, SELECT only on `category`, `expense` and
  `budget_limit`, and read-only sessions. Without a password it cannot log in, which is the
  state everywhere except prod.
- **Where the credentials live:** the SealedSecret `grafana-postgres-reader` (namespace
  `monitoring`, keys `username` = `grafana_reader` and `password`) in
  `infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml`, applied by
  `monitoring-secrets`. Grafana reads it at pod start (`envValueFrom`, optional).
- **When to set the password:**
  - once, after V6 has run in prod (the prod backend rolled out from `main`) **and**
    `kubectl -n monitoring get sealedsecret grafana-postgres-reader` shows `Synced=True`;
  - again after every re-seal of that file.
- Check the role first: `kubectl -n prod exec statefulset/postgres -- psql -U app -d app -tAc "select rolname from pg_roles where rolname = 'grafana_reader'"`
  prints `grafana_reader`.
- **Commands (one at a time, check each output):**

  ```bash
  READER_PW=$(kubectl -n monitoring get secret grafana-postgres-reader -o jsonpath='{.data.password}' | base64 -d)
  [ -n "$READER_PW" ] || echo "READER_PW is empty - stop"
  printf "ALTER USER grafana_reader PASSWORD '%s';\n" "$READER_PW" \
    | kubectl -n prod exec -i statefulset/postgres -- psql -U app -d app -v ON_ERROR_STOP=1
  kubectl -n monitoring rollout restart deployment/monitoring-grafana
  unset READER_PW
  ```

  - The SQL goes through stdin. `printf` is a shell builtin, so unlike the `-c` variant in
    "Change the password of an already initialised database", the password does not appear on
    any command line. The third command must print `ALTER ROLE`.
  - The empty-guard note from that section applies: an empty value would *clear* the password.
  - The quoting is safe for hex passwords from `openssl rand -hex`.
  - The restart makes Grafana re-read the Secret; it is needed if Grafana started before the
    Secret existed.
- **Verify:** Grafana -> Connections -> Data sources -> "Wallet (prod Postgres)" -> "Save & test"
  succeeds (or open the Wallet dashboard). Then
  `kubectl -n monitoring get secret grafana-postgres-reader -o jsonpath='{.data.username}' | base64 -d; echo`
  prints `grafana_reader`.
- **Rotate:** re-seal with the same pattern as the Grafana admin secret, then repeat the
  commands above after the change reaches `main` and is `Synced`:

  ```bash
  OUT="infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml"
  PW="$(openssl rand -hex 24)"
  ( set -o pipefail
    printf %s "$PW" \
    | kubectl create secret generic grafana-postgres-reader --namespace monitoring \
        --from-literal=username=grafana_reader --from-file=password=/dev/stdin \
        --dry-run=client -o yaml \
    | kubeseal --format yaml \
    > "$OUT.tmp"
  ) && mv "$OUT.tmp" "$OUT" || rm -f "$OUT.tmp"
  ```
- **Never** put the password into the migration, into `values.yaml` or on a command line.

### Controller key lost / cluster recreated

Existing ciphertext can no longer be decrypted. Re-seal both envs (see "Seal
credentials for dev and prod"), or restore the key backup before installing
the controller, in this order:

1. `kubectl apply -f <backup>` into `kube-system` first, so the key Secret
   exists before the controller starts.
2. Then install the controller (or `kubectl -n kube-system rollout restart
   deployment/sealed-secrets-controller` if it is already installed) so it
   picks up the restored key instead of generating a new one.

Applying the backup after the controller has already generated a fresh key
is not enough on its own: restart the controller (step 2) so it re-reads the
key Secrets.

## Monitoring (kube-prometheus-stack)

### What is installed and what is not

The Argo CD Application `monitoring` installs the
[kube-prometheus-stack](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
chart (version pinned as `targetRevision` in `infra/argocd/apps.yaml`) into
namespace `monitoring`. Values: `infra/monitoring/values.yaml`. The Grafana
SealedSecrets (admin credentials and the Postgres reader credentials) are applied by a
separate Application, `monitoring-secrets`, and the dashboards by `monitoring-dashboards`.

- Installed: Prometheus Operator, Prometheus (retention 15d, at most 18GiB,
  20Gi PVC), Grafana, kube-state-metrics, node-exporter, the default rules and
  dashboards.
- Not installed: Alertmanager (alerting is a later card) and the scrapers of
  kube-controller-manager, kube-scheduler, kube-proxy and etcd (k3s runs them
  inside its own process, so the chart's Services would have no targets).
- Grafana datasources: Prometheus (provisioned by the chart, uid `prometheus`) and
  "Wallet (prod Postgres)" (uid `wallet-postgres`, role `grafana_reader`, see "Grafana read-only
  user (grafana_reader)"). Dashboards: see "Dashboards".
- Prometheus selects every ServiceMonitor in every namespace, not only those
  labelled `release=monitoring`. The backend's ServiceMonitor comes from the
  pet-project chart (namespaces `dev` and `prod`).
- Sizing (no CPU limits, like the pet-project chart):

  | Component | Requests | Memory limit |
  |---|---|---|
  | Prometheus | 200m / 1Gi | 2Gi |
  | Prometheus Operator | 50m / 96Mi | 192Mi |
  | Grafana | 50m / 256Mi | 512Mi |
  | Grafana sidecars (each) | 10m / 96Mi | 192Mi |
  | kube-state-metrics | 10m / 64Mi | 128Mi |
  | node-exporter | 10m / 32Mi | 64Mi |

- The app syncs with `ServerSideApply=true`: the operator CRDs are larger than
  the 256 KiB limit of the `last-applied-configuration` annotation. Argo CD
  (not Helm) applies and updates the CRDs on every sync.

### First install

1. The Sealed Secrets controller must be installed (see "Postgres credentials
   (Sealed Secrets)" above).
2. Seal the Grafana admin credentials (next subsection) and commit
   `infra/monitoring/sealed-secrets/grafana-admin.yaml`.
3. After the change has reached `main`, run
   `kubectl apply -f infra/argocd/apps.yaml`. This creates `monitoring`,
   `monitoring-secrets` and `monitoring-dashboards` and leaves the other two apps unchanged.
4. Until `main` has `infra/monitoring/`, `monitoring`, `monitoring-secrets` and
   `monitoring-dashboards` show "app path does not exist" or a values-file error. This is expected and harmless.
   Check the secret with `kubectl -n argocd get application monitoring-secrets`
   and `kubectl -n monitoring get sealedsecret grafana-admin` (`Synced=True`).
5. The pet-project apps pick up the ServiceMonitor once the CRD exists (see
   "Troubleshooting" below).
6. Set the password of the database role `grafana_reader` (see "Grafana read-only user
   (grafana_reader)"); until then only the Wallet panels fail.

### Grafana admin credentials (Sealed Secret)

The Grafana admin user and password come from the Secret `grafana-admin`
(keys `admin-user` and `admin-password`), created by a SealedSecret in
`infra/monitoring/sealed-secrets/grafana-admin.yaml`. The Argo CD Application
`monitoring-secrets` (a plain directory source on this repo's `main`) applies
it, independently of the chart sync of `monitoring`. Never put the password in `values.yaml`. Seal it with the same
pattern as the Postgres credentials (temporary file plus `mv`, `pipefail`,
the password only in a variable and on stdin):

```bash
OUT="infra/monitoring/sealed-secrets/grafana-admin.yaml"
mkdir -p "$(dirname "$OUT")"
PW="$(openssl rand -hex 24)"
( set -o pipefail
  printf %s "$PW" \
  | kubectl create secret generic grafana-admin --namespace monitoring \
      --from-literal=admin-user=admin --from-file=admin-password=/dev/stdin \
      --dry-run=client -o yaml \
  | kubeseal --format yaml \
  > "$OUT.tmp"
) && mv "$OUT.tmp" "$OUT" || rm -f "$OUT.tmp"
```

- Strict scope (the `kubeseal` default): name `grafana-admin`, namespace
  `monitoring`. The namespace does not need to exist for sealing.
- The offline `--cert` variant from "Seal credentials for dev and prod" works
  here too.
- Commit only the `kubeseal` output, never the plain Secret.
- The file must exist on `main`: without the directory `monitoring-secrets`
  shows "app path does not exist"; `monitoring` still installs, and Grafana
  waits in `CreateContainerConfigError` for the Secret `grafana-admin`. The
  same happens if the file cannot be decrypted (wrong name or namespace,
  another controller key). Do not add a `.gitkeep` there.
- Rotation:
  1. Re-seal and merge until the change reaches `main`.
  2. Wait until `kubectl -n monitoring get sealedsecret grafana-admin` shows
     `Synced=True`.
  3. Run `kubectl -n monitoring rollout restart deployment/monitoring-grafana`.
  4. The restart is enough because Grafana has no persistent volume: a new pod
     creates its database from scratch and applies the user and password from
     the Secret. The flip side is that users, preferences and dashboards edited
     in the UI are lost on every restart. The chart does not restart Grafana
     when the Secret changes, so step 3 is always required.

### Reach Grafana

Add `127.0.0.1 grafana.pet.local` to `/etc/hosts`, then open
`http://grafana.pet.local` (the same Traefik entry point as `dev.pet.local`).
The user is `admin`. The password is in your password manager, or read it with:

```bash
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

The Prometheus and Wallet (prod Postgres) datasources are provisioned automatically.

### Check the backend targets in Prometheus

```bash
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090
```

Open `http://localhost:9090/targets?search=backend`. Expect the pools
`serviceMonitor/dev/backend/0` and `serviceMonitor/prod/backend/0`, one target
per backend pod, State `UP`. In the query UI:

- `up{job="backend"}` returns one series per pod, value `1`, label
  `namespace` = `dev` or `prod`.
- `sum by (namespace) (rate(http_server_requests_seconds_count{application="pet"}[5m]))`
  shows traffic.

The same queries work in Grafana, Explore.

### Dashboards

- Where they live: `infra/monitoring/dashboards/*.json`. `kustomization.yaml` turns each file
  into a ConfigMap with the label `grafana_dashboard=1` (stable names, no hash suffix), the
  Argo CD Application `monitoring-dashboards` applies them, and the Grafana dashboard sidecar
  loads them into the folder General.
- Changes go through git: the UI cannot save provisioned dashboards, and Grafana has no
  persistence.
- CI (job `dashboards`) checks that every JSON file is valid, has a `uid` and a `title`, carries
  no `__inputs` or `${DS_...}`, that the uids are unique and that kustomize renders each file
  into a labelled ConfigMap.

#### JVM (Micrometer)

- Source: grafana.com dashboard 4701, revision 10
  (`https://grafana.com/api/dashboards/4701/revisions/10/download`); `gnetId` 4701 is kept in
  the file. The Grafana in this stack is 13.2.3 (image `grafana/grafana:13.2.3-distroless`),
  which migrates the legacy `graph`/`singlestat` panels and schema 14 rows on load.
- Edits on top of upstream, exactly six:
  1. `__inputs` is removed.
  2. Every `${DS_PROMETHEUS}` becomes the datasource `{type: prometheus, uid: prometheus}`.
  3. `uid: jvm-micrometer` is added.
  4. A `namespace` variable (default `prod`) is added after `application`, and the `instance`
     variable is filtered by it.
  5. The HTTP panels (Rate, Errors, Duration AVG/MAX/p95) exclude `uri!~"/actuator.*"`:
     Prometheus scrapes and kubelet probes are not counted, so an idle backend shows a rate of
     0 and no p95.
  6. A p95 target (`HTTP - p95`) is added to the panel Duration.
- The committed file is the output of this jq program applied to the upstream download
  (generated with jq 1.7.1, `jq --indent 2 -f jvm.jq upstream.json`):

  ```jq
  # Edits on top of grafana.com dashboard 4701 revision 10 (README "Dashboards"). jq 1.7.1.
  def prom: {"type": "prometheus", "uid": "prometheus"};
  # Label matcher of the upstream HTTP queries, and the same matcher without actuator traffic.
  def sel: "application=\"$application\", instance=\"$instance\"";
  def sel_no_actuator: sel + ", uri!~\"/actuator.*\"";
  def is_http_panel: .title == "Rate" or .title == "Errors" or .title == "Duration";
  # The HTTP target expressions of revision 10, verbatim. If an upgrade changes them, stop.
  def upstream_http_exprs: [
    "sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\"}[1m]))",
    "sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\", status=~\"5..\"}[1m]))",
    "sum(rate(http_server_requests_seconds_sum{application=\"$application\", instance=\"$instance\", status!~\"5..\"}[1m]))/sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\", status!~\"5..\"}[1m]))",
    "max(http_server_requests_seconds_max{application=\"$application\", instance=\"$instance\", status!~\"5..\"})"
  ];
  if ([.rows[].panels[] | select(is_http_panel) | .targets[].expr] | sort) != (upstream_http_exprs | sort)
  then error("4701: the Rate/Errors/Duration queries differ from revision 10; review the actuator and p95 edits before upgrading")
  else . end
  | del(.__inputs)
  | walk(if . == "${DS_PROMETHEUS}" then prom else . end)
  | .uid = "jvm-micrometer"
  | .templating.list |= (
      map(if .name == "instance"
          then .query = "label_values(jvm_memory_used_bytes{application=\"$application\", namespace=\"$namespace\"}, instance)"
          else . end)
      | .[0:1]
        + [{"name": "namespace", "label": "Namespace", "type": "query", "datasource": prom,
            "query": "label_values(jvm_memory_used_bytes{application=\"$application\"}, namespace)",
            "refresh": 2, "sort": 1, "hide": 0, "includeAll": false, "multi": false, "regex": "",
            "options": [], "current": {"selected": true, "text": "prod", "value": "prod"}}]
        + .[1:])
  | (.rows[].panels[] | select(is_http_panel) | .targets[].expr) |= (split(sel) | join(sel_no_actuator))
  | (.rows[].panels[] | select(.title == "Duration") | .targets) += [{
      "expr": "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"$application\", instance=\"$instance\", uri!~\"/actuator.*\", status!~\"5..\"}[1m])))",
      "format": "time_series", "intervalFactor": 1, "legendFormat": "HTTP - p95", "refId": "C"}]
  ```

- Upgrade: download the new revision, run the program with jq 1.7.1 and review the diff. If
  the program stops with "differ from revision 10", the upstream HTTP queries changed: review
  them, update `upstream_http_exprs` and the edits, and never just delete the guard. Update the
  revision in `kustomization.yaml` and here.
- Use the `Namespace` variable (dev/prod), then `Instance` (one per backend pod).

#### Wallet

- Panels: "Total spent in <month>" (stat), "Spend by category in <month>" (bar chart) and
  "Limit vs actual" (table). Prod only; all data, no per-user view.
- The `Month` picker lists the last 12 months in Europe/Warsaw (the default `APP_TIME_ZONE`),
  newest first, preselects the current month and accepts no custom values.
- SQL contract: the only panel query is `backend/src/main/resources/db/report/by-category.sql`
  with `:month` replaced by `${month:sqlstring}` (Grafana quotes and escapes the value, then
  the SQL casts it to `date`). The other panels reuse that result. `WalletDashboardSqlIT`
  fails if the dashboard and the SQL file drift. To change the report, change the SQL file and
  regenerate `rawSql` with:

  ```bash
  jq -Rs --arg expr '${month:sqlstring}' '
    rtrimstr("\n")
    | if (split(":month") | length) != 2 then error("by-category.sql must contain :month exactly once")
      else split(":month") | join($expr) end' backend/src/main/resources/db/report/by-category.sql
  ```

  If a Grafana version did not support the `sqlstring` format, the query would fail loudly
  (an error in the panel) instead of returning wrong data.
- **Run the test with `--rerun`:** Gradle does not track `infra/monitoring/dashboards/wallet.json`
  as an input of `test`. After a change to only that file, use `cd backend && ./gradlew test --rerun`,
  otherwise `test` can be UP-TO-DATE and skip the check. CI always runs it on a fresh checkout.

### Troubleshooting

- No pool for a namespace: run `kubectl -n <env> get servicemonitor backend`.
  If it is missing, the app's manifests were rendered before the CRD existed.
  Hard-refresh the app: `argocd app get pet-project-<env> --hard-refresh`, or
  in the UI Refresh, then Hard Refresh. The ServiceMonitor is rendered only
  when the cluster has the `monitoring.coreos.com/v1/ServiceMonitor` CRD, so
  dev and prod still sync before monitoring is installed.
- Pool with 0 targets: check that the Service has the label `app: backend` and
  the port is named `http`.
- Target DOWN: `kubectl -n <env> exec deploy/backend -- wget -qO- localhost:8080/actuator/prometheus | head`.
  Only if the image has no `wget`, use a `kubectl run` curl pod in the same
  namespace instead.

### Upgrading the chart

The chart version appears in exactly these places (check with
`grep -rn "91\.9\.0" . --exclude-dir=docs --exclude-dir=node_modules`):

- `targetRevision` of the `monitoring` Application in `infra/argocd/apps.yaml`;
- `KPS_VERSION` in `.github/workflows/ci.yml`;
- `--version` in the local render command under "CI and Docker images" above.

Bump all three together; CI fails if the first two differ. For major versions
read the upstream `UPGRADE.md` first.
