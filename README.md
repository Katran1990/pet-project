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

| Variable                   | Default                                    |
| -------------------------- | ------------------------------------------ |
| `DB_URL`                   | `jdbc:postgresql://postgres:5432/app`      |
| `DB_USER`                  | `app`                                      |
| `DB_PASSWORD`              | `app`                                      |
| `DB_STARTUP_WAIT_TIMEOUT`  | `60s`                                      |
| `DB_STARTUP_WAIT_INTERVAL` | `2s`                                       |

On startup the backend waits up to `DB_STARTUP_WAIT_TIMEOUT` for Postgres to
accept connections, trying again every `DB_STARTUP_WAIT_INTERVAL`.
Authentication or unknown-database errors fail immediately instead of
waiting out the timeout. Setting `DB_STARTUP_WAIT_TIMEOUT=0` restores
fail-fast (a single attempt).

The `DB_USER`/`DB_PASSWORD` defaults apply only to local runs. In Kubernetes,
they always come from the Secret `postgres-credentials` created by Sealed
Secrets, and there is no fallback.

## API

| Method | Path            | Description                     |
| ------ | --------------- | ------------------------------- |
| `GET`  | `/api/greeting` | Returns the stored greeting     |
| `POST` | `/api/greetings` | Creates a greeting ({"message": "..."}, 1-200 chars) |
| `POST` | `/api/categories` | Creates a category ({"name": "...", "icon": "..."}); name is stripped, empty icon stored as null; 409 on duplicate name, case-insensitive |
| `GET`  | `/api/categories` | Lists active categories; ?includeArchived=true includes archived ones |
| `GET`  | `/api/categories/{id}` | Returns one category (archived ones too); 404 if unknown |
| `PATCH` | `/api/categories/{id}` | Updates name, icon and/or archived; omitted or null fields are unchanged, "icon": "" clears the icon |
| `GET`  | `/actuator/health` | Application health           |

Errors are returned as RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`, `detail` and `instance`. Validation errors (400) additionally contain `errors: [{"field": "...", "message": "..."}]`.

## CI and Docker images

- `.github/workflows/ci.yml` runs on every PR: backend `./gradlew build`
  (compiles and runs tests), frontend lint and build, `helm lint` and
  `helm template` of `infra/helm/pet-project` against the `envs/dev` and
  `envs/prod` values from `pet-project-deploy@main`, a Trivy dependency scan
  (see "Dependency vulnerability scanning (Trivy)" below), and actionlint over
  `.github/workflows/`. Run the same checks locally with
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
- `.github/workflows/build-images.yml` builds, scans (Trivy) and pushes
  `ghcr.io/<owner>/<repo>/backend:<tag>` and
  `ghcr.io/<owner>/<repo>/frontend:<tag>` on push to `development`/`main`,
  tagged with the branch name and the commit SHA. Each image is scanned
  before it is pushed; a failed scan blocks the push of that image. The two
  images build in parallel with fail-fast, so one may already have been
  pushed by the time the other's scan fails.
- Build the images locally:

  ```bash
  docker build -t pet-backend backend
  docker build -t pet-frontend frontend
  ```

- The backend image reads `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `DB_STARTUP_WAIT_TIMEOUT` and `DB_STARTUP_WAIT_INTERVAL` at runtime.
- The frontend image proxies `/api` to a host named `backend:8080`, which
  must resolve when the container starts.

### Dependency vulnerability scanning (Trivy)

- **What runs where:** a filesystem scan of `backend/gradle.lockfile` and
  `frontend/package-lock.json` runs in `ci.yml` on every PR and on every push
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
