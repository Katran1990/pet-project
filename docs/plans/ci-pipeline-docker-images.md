# Plan: Add CI pipeline and Docker images

Trello card: "Add CI pipeline and Docker images" (https://trello.com/c/tsets7SN/2-add-ci-pipeline-and-docker-images)

## Goal
Move the draft CI workflows, Dockerfiles and nginx config from `docs/stage4/` to where they belong, and fix them so they work in this repo. Then add `.dockerignore` files and delete `docs/stage4/`. When this is done, every PR runs backend tests and the frontend build, and every push to `development`/`main` publishes both images to GHCR.

## Acceptance criteria
- [ ] `.github/workflows/ci.yml` runs backend tests and the frontend build on every PR
- [ ] `.github/workflows/build-images.yml` pushes backend and frontend images to GHCR on push to development/main
- [ ] `backend/Dockerfile` and `frontend/Dockerfile` build successfully (`docker build`)
- [ ] Frontend nginx proxies `/api` to the backend

Additional user requirements (authoritative):
- [ ] CI may use `permissions: packages: write` and push to GHCR with `GITHUB_TOKEN` (approved by the user)
- [ ] The files are moved from `docs/stage4/` to their real locations, and `docs/stage4/` is deleted completely. `docs/plans/` stays.
- [ ] `backend/.dockerignore` and `frontend/.dockerignore` exist
- [ ] The false "lowercased below" claim in `build-images.yml` is fixed

## Changes

### What I checked in the project

**Backend**
- It uses Groovy DSL: `backend/build.gradle` and `backend/settings.gradle` (`rootProject.name = 'pet'`). There is no `.kts`, so the names in the draft `COPY gradlew build.gradle settings.gradle ./` are correct.
- The wrapper is complete: `backend/gradlew`, `backend/gradle/wrapper/gradle-wrapper.jar` and `backend/gradle/wrapper/gradle-wrapper.properties` (Gradle 9.7.1). `backend/.gitattributes` forces LF line endings for `gradlew`.
- `gradle-wrapper.jar` is not ignored (`!gradle/wrapper/gradle-wrapper.jar`), so `gradle/actions/setup-gradle` can validate it.
- `gradlew` is stored in git as mode 100755 (verified by the coordinator with `git ls-files -s`), so `./gradlew` works on CI runners.
- The Java toolchain is 25 (`JavaLanguageVersion.of(25)`) and there is no foojay auto-provisioning. This works in both places because the running JDK is 25: `eclipse-temurin:25-jdk` in Docker and `setup-java` 25 in CI.
- The server port is the default 8080. The application has no `server.port`.
- Runtime env vars are `DB_URL`, `DB_USER` and `DB_PASSWORD`. Their defaults in `backend/src/main/resources/application.properties` point at the dev-container Postgres. The image needs no build-time secrets; the DB settings must be provided at `docker run`.
- `management.endpoints.web.exposure.include=health,info`, so `/actuator/health` is available.
- Every test uses `@Import(TestcontainersConfiguration.class)` (Postgres 17). This includes `PetApplicationTests.contextLoads` and `GreetingControllerIT`, so **every backend test run needs Docker**. GitHub `ubuntu-latest` runners have Docker, so the tests can run in CI without a service container.
- The Testcontainers artifacts are 2.x (`org.testcontainers.postgresql.PostgreSQLContainer`), managed by Spring Boot 4.1. These versions work with Docker Engine 29.

**Frontend**
- `package.json` scripts: `dev`, `build` (`tsc -b && vite build`), `lint` (`oxlint`), `preview`. **There is no `test` script.**
- `package-lock.json` exists, so `npm ci` works.
- Node: the lock file's engine ranges are `^20.19.0 || >=22.12.0`, so Node 24 is compatible. The dev container uses Node "lts", which is 24.
- `package-lock.json` includes the musl native bindings that `node:24-alpine` needs: `@rolldown/binding-linux-x64-musl`, `lightningcss-linux-x64-musl` and `@oxlint/binding-linux-x64-musl`.
- The local `node_modules` has the **glibc** bindings (`*-linux-x64-gnu`). If it gets into the Docker build context, `COPY . .` would overwrite the musl modules and break the Alpine build. This is the main reason for `frontend/.dockerignore`.
- Vite output: `vite.config.ts` does not set `build.outDir`, so the output goes to `dist/`, which matches `COPY --from=build /app/dist`. Static assets are in `frontend/public/` (favicon.svg).
- API calls: `frontend/src/App.tsx` calls `fetch('/api/greeting')`, a relative path with the `/api` prefix. The Vite dev proxy sends `/api` to `http://localhost:8080`. The backend controller is `@RequestMapping("/api")`.
- So nginx must forward the **full URI including `/api`**. `proxy_pass http://backend:8080;` (no URI part) does exactly that, so the draft's proxy logic is correct.

### 1. Create `.github/workflows/ci.yml` (moved from `docs/stage4/.github/workflows/ci.yml`)
Move it with a plain `mv`, not `git mv`, then make these corrections:
- **Remove the step `Unit tests` (`npm test --if-present -- --run`).** There is no `test` script, so `--if-present` makes it a silent no-op that shows as a green "Unit tests" step while testing nothing. The `-- --run` argument also assumes a Vitest runner that does not exist. Add the step back in the same card that adds a frontend test runner.
- **Keep `Lint` but drop `--if-present`:** use `npm run lint`. The script exists, so the step should fail loudly if someone removes it by mistake. oxlint must pass locally first (see Tests).
- **Backend step:** keep `./gradlew build --no-daemon`. It runs `test` (the AC) and also compiles and assembles the jar. Testcontainers uses the runner's Docker daemon, so no extra setup is needed.
  - Keep the comment on `runs-on`. Also add a short comment that the backend tests need Docker (Testcontainers).
- **Action versions:** check that every major tag exists before committing (see Tests, T1). Candidates to check: `actions/checkout@v6`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/setup-node@v6`, `actions/upload-artifact@v4`.
  - If a tag does not exist (for example `setup-java@v6` or `setup-gradle@v6`), use the newest existing major. Do not guess.
  - `upload-artifact@v4` works but is not the latest. Bump it to the newest major only if checking tags is cheap. This is optional.
- Keep the rest as it is:
  - `permissions: contents: read`.
  - `concurrency` on `github.ref`.
  - The `upload-artifact` path `backend/build/reports/tests/test`. This is correct because `defaults.run.working-directory` does not apply to `uses:` steps.
  - Node `"24"` with the npm cache keyed on `frontend/package-lock.json`.
  - Triggers: `pull_request` (all branches) and push to `development`/`main`.

### 2. Create `.github/workflows/build-images.yml` (moved from `docs/stage4/.github/workflows/build-images.yml`)
Corrections:
- **Lowercase decision: rely on `docker/metadata-action` and fix the comment. Do not add a separate lowercasing step.**
  - `IMAGE_PREFIX` is used in exactly one place, the `images:` input of `docker/metadata-action`.
  - That action normalizes image names to lowercase, and its `tags` output is the only thing passed to `build-push-action`. So `Katran1990` becomes `katran1990`, and GHCR's lowercase rule is met without extra code.
  - Replace the header comment with something like: `# <owner>/<repo>. GHCR requires lowercase names; docker/metadata-action lowercases the image name, so do not use IMAGE_PREFIX directly as a tag elsewhere.`
  - Also change the file header comment `ghcr.io/<owner>/pet-project/...` to `ghcr.io/<owner>/<repo>/...` (lowercased), because the repository name comes from `github.repository`.
  - Before relying on this, confirm from the README or release notes of the chosen `docker/metadata-action` major (e.g. via `gh api repos/docker/metadata-action/readme` or the release notes) that it lowercases image names. Record the source in the implementation report. If this cannot be confirmed, add an explicit lowercasing step instead (see Risk 6).
  - Verification: the "Image metadata" step log on the first run must show `ghcr.io/katran1990/...` (T6).
- **GHA cache scope per matrix service.** Without a scope, both matrix jobs write to the same `type=gha` cache key and evict each other. Change to:
  - `cache-from: type=gha,scope=${{ matrix.service }}`
  - `cache-to: type=gha,mode=max,scope=${{ matrix.service }}`
- **Action versions:** check that the tags exist, the same way as in ci.yml: `docker/setup-buildx-action@v3`, `docker/login-action@v3`, `docker/metadata-action@v5`, `docker/build-push-action@v6`. If newer majors exist, prefer them. Before switching, check that the inputs used here (`images`, `tags`, `context`, `push`, `labels`, `cache-from`, `cache-to`) are unchanged.
- Keep as it is (the user approved it):
  - `permissions: contents: read, packages: write`
  - Login with `${{ secrets.GITHUB_TOKEN }}` and `github.actor`. No custom secrets.
  - Tags `type=ref,event=branch` and `type=sha,prefix=,format=short`.
  - The metadata-action labels, which include `org.opencontainers.image.source` and so link the package to the repo.
- **Concurrency group, added after code review at the user's request:** add a top-level `concurrency: group: build-images-${{ github.ref }}, cancel-in-progress: true` block (after `permissions`, matching `ci.yml`'s style), so two quick pushes to the same branch cannot let an older build finish last and overwrite the `development`/`main` image tag.

### 3. Create `backend/Dockerfile` (moved from `docs/stage4/backend/Dockerfile`)
Corrections:
- Add `RUN chmod +x gradlew` after `COPY gradlew ...`. This protects against a missing executable bit in the build context, for example when building from a Windows or Mac checkout.
- Keep `COPY gradle ./gradle`. The wrapper is there, and `.dockerignore` must not exclude `gradle/`.
- Keep the dependency pre-fetch layer and `bootJar`. Drop `-x test`: `bootJar` does not depend on `test`, so the flag is noise. The existing comment already explains that tests run in CI.
- Only `bootJar` runs in a clean stage, so `build/libs/` contains exactly one jar (no `-plain.jar`), and `COPY --from=build /app/build/libs/*.jar app.jar` is unambiguous. `.dockerignore` excludes the host's `build/`, so a stale plain jar cannot leak in.
- Runtime stage:
  - Keep `useradd --system --uid 1001 app`.
  - Change `USER app` to `USER 1001` (numeric), so Kubernetes `runAsNonRoot` can check it without resolving `/etc/passwd`.
  - Keep `EXPOSE 8080` and the `ENTRYPOINT`.
- Add a comment listing the runtime env vars `DB_URL`, `DB_USER` and `DB_PASSWORD`. Do **not** add `ENV` defaults with credentials: no secrets are baked into the image.

### 4. Create `frontend/Dockerfile` (moved from `docs/stage4/frontend/Dockerfile`)
Corrections:
- Keep the `node:24-alpine` build stage. The musl bindings are in the lock file, and `.dockerignore` keeps the host's glibc `node_modules` out.
- **Bump `nginx:1.27-alpine`.** 1.27 is an old mainline branch and gets no more updates. Use the current stable line as `nginx:<stable>-alpine`, for example `1.30-alpine` if `docker manifest inspect nginx:1.30-alpine` succeeds, otherwise `1.28-alpine`. Pin to major.minor, not `latest`.
- Keep `COPY nginx.conf /etc/nginx/conf.d/default.conf` and `COPY --from=build /app/dist /usr/share/nginx/html`. `nginx.conf` must stay in the build context, so it must not be listed in `.dockerignore`.

### 5. Create `frontend/nginx.conf` (moved from `docs/stage4/frontend/nginx.conf`)
- Keep `location /api/ { proxy_pass http://backend:8080; }`. There is no URI after the host, so `/api/greeting` reaches the backend unchanged. This meets AC4.
- Change `proxy_set_header Host $host;` to `proxy_set_header Host $http_host;`.
  - `$host` drops the port.
  - The backend builds the `Location` header of `POST /api/greetings` from the incoming Host (`ServletUriComponentsBuilder.fromCurrentRequest()`). With `$host`, a frontend published on a non-80 port would return `Location` URLs without the port.
- Add `proxy_set_header X-Forwarded-Proto $scheme;` next to `X-Forwarded-For`. This is informational; Spring does not use it until `server.forward-headers-strategy` is set (out of scope).
- **Startup DNS decision: keep the static upstream host `backend`.**
  - nginx resolves `backend` when it loads the config. If the name does not resolve, nginx exits ("host not found in upstream").
  - This is acceptable because the image is meant to run next to a service named `backend` (docker compose now, a Kubernetes Service later). In both cases the name resolves even when the backend is not ready yet.
  - The alternative, `resolver` plus a variable in `proxy_pass`, needs a resolver IP that differs between Docker (127.0.0.11) and Kubernetes. That is extra complexity with no current consumer.
  - Document this in the existing comment above `location /api/`, and use `--add-host backend:...` for isolated checks (T4).
- Keep the SPA fallback `location /` and the `/assets/` long-cache block as they are.

### 6. Create `backend/.dockerignore`
Patterns are relative to the root of the build context (`backend/`):
```
/build
/.gradle
/bin
/out
/.idea
*.iml
/.vscode
HELP.md
.env
.env.*
Dockerfile
.dockerignore
```
Must **not** exclude: `gradlew`, `gradle/`, `build.gradle`, `settings.gradle`, `src/`.

### 7. Create `frontend/.dockerignore`
```
node_modules
dist
dist-ssr
*.local
*.log
*.tsbuildinfo
.vscode
.idea
.DS_Store
.env
.env.*
Dockerfile
.dockerignore
```
Must **not** exclude: `nginx.conf`, `package.json`, `package-lock.json`, `index.html`, `public/`, `src/`, `tsconfig*.json`, `vite.config.ts`, `.oxlintrc.json`.

This file only filters what is sent to the Docker daemon. It never touches `frontend/node_modules`, which is a Docker volume and must not be deleted.

### 8. Delete `docs/stage4/`
After the five files are moved, remove the whole directory with plain filesystem commands (no git), for example `rm -r /workspace/docs/stage4`. This includes the now-empty `docs/stage4/.github/workflows`, `docs/stage4/backend` and `docs/stage4/frontend`.

Do not touch `docs/plans/`.

### 9. Modify `README.md` (small docs update)
- Add a "CI and Docker images" section:
  - `ci.yml` runs on every PR: backend `./gradlew build`, frontend lint and build.
  - `build-images.yml` pushes `ghcr.io/<owner>/<repo>/backend|frontend:<branch>|<sha>` on push to development/main.
  - Local commands: `docker build -t pet-backend backend` and `docker build -t pet-frontend frontend`.
  - The backend image reads `DB_URL`, `DB_USER` and `DB_PASSWORD`.
  - The frontend image proxies `/api` to host `backend:8080`, which must resolve when the container starts.
- Add `.github/` to the Layout block.

### Full file list
| Action | Path |
|---|---|
| Create (moved + edited) | `/workspace/.github/workflows/ci.yml` |
| Create (moved + edited) | `/workspace/.github/workflows/build-images.yml` |
| Create (moved + edited) | `/workspace/backend/Dockerfile` |
| Create (moved + edited) | `/workspace/frontend/Dockerfile` |
| Create (moved + edited) | `/workspace/frontend/nginx.conf` |
| Create | `/workspace/backend/.dockerignore` |
| Create | `/workspace/frontend/.dockerignore` |
| Modify | `/workspace/README.md` |
| Delete (whole directory) | `/workspace/docs/stage4/` (the 5 files listed above and their subdirectories) |

No DB migrations, no application code changes, no Gradle or npm dependency changes.

## Tests
This is infrastructure work, so it is proven by verification commands, not new unit tests. Run all of them inside the dev container, from absolute paths.

| # | Check | Command / method | Proves |
|---|---|---|---|
| T0 | Existing backend tests still pass | `cd /workspace/backend && ./gradlew test` | Regression for the backend step in CI (AC1). Required by CLAUDE.md. |
| T0b | Frontend lint and build pass locally, exactly as CI runs them | `cd /workspace/frontend && npm run lint && npm run build` (do **not** run `npm ci` here: it deletes the contents of `node_modules`, which is a volume; `npm install` is fine if needed) | AC1 (frontend job will be green) |
| T1 | Every referenced action tag exists | For each `owner/repo@vN`: `gh api repos/<owner>/<repo>/git/matching-refs/tags/vN --jq '.[].ref'` must return at least one ref. This is read-only GitHub API use, not a git operation. | AC1, AC2 (workflows do not fail on "unable to resolve action") |
| T2 | Workflow syntax is valid | Preferred: `actionlint .github/workflows/*.yml`, if available or runnable via `docker run --rm -v /workspace:/repo -w /repo rhysd/actionlint:latest`. Fallback: YAML parse check, e.g. `python3 -c "import yaml,sys;[yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/*.yml`. Also check by reading the files: ci triggers on `pull_request`; build-images triggers on `push` to `[development, main]`, has `packages: write`, `push: true`, and a per-service cache scope. | AC1, AC2 |
| T3 | Both images build | `docker build -t pet-backend:local /workspace/backend` and `docker build -t pet-frontend:local /workspace/frontend` both exit 0 | AC3 |
| T3b | `.dockerignore` is effective | The frontend build succeeds on Alpine, even though the host `node_modules` has glibc bindings. `docker run --rm pet-frontend:local ls /usr/share/nginx/html` shows `index.html`, `assets/` and `favicon.svg`. `docker run --rm --entrypoint sh pet-backend:local -c 'ls /app && id -u'` shows only `app.jar` and uid `1001`. | Requirement 3, AC3 |
| T4 | nginx config is valid | `docker run --rm --add-host backend:127.0.0.1 pet-frontend:local nginx -t` prints "syntax is ok / test is successful". Without `--add-host` this fails with "host not found in upstream". That is expected and documented (Risk 3). | AC4 |
| T5 | End-to-end `/api` proxy smoke test | `docker network create pet-smoke`; run all three containers with `--network pet-smoke`: `postgres:17` as `--name db` with `POSTGRES_DB/USER/PASSWORD=app`; `pet-backend:local` as `--name backend` with `DB_URL=jdbc:postgresql://db:5432/app`, `DB_USER=app`, `DB_PASSWORD=app` (do not publish it on host port 8080; a local `bootRun` may use it); poll health with `docker run --rm --network pet-smoke curlimages/curl -s http://backend:8080/actuator/health` until it reports `UP`; then run `pet-frontend:local` with `--network pet-smoke -p 8081:80`. Then `curl -s localhost:8081/api/greeting` returns JSON whose `message` field is `"Hello from Postgres"` (compare only `message`; `id` may vary), and `curl -s localhost:8081/` returns the SPA `index.html`. Clean up the containers and the network afterwards. These credentials are local throwaway values passed on the command line, not stored in any file. | AC4 (nginx really proxies `/api` to the backend), plus a sanity check that the backend image starts with env-provided DB settings |
| T6 | GHCR push works end to end | Only possible after merge to `development`/`main`, outside this environment. Check that the "Build images" run is green, that the metadata step log shows lowercase `ghcr.io/katran1990/...`, and that the packages appear under the repo. Report this as a post-merge check to the user. | AC2 (final proof) |
| T7 | CI runs on a PR | Proven when the PR for this card is opened: both the `Backend (Gradle)` and `Frontend (Vite)` jobs run and pass. Post-PR check. | AC1 (final proof) |
| T8 | Cleanup done | `/workspace/docs/stage4` does not exist. `/workspace/docs/plans/` still exists. `/workspace/frontend/node_modules` still exists. | Requirement 2 and the node_modules rule |

In short, the task counts as done locally when T0 to T5 and T8 pass. T6 and T7 can only be checked on GitHub.

## Risks and open questions
1. **`gradlew` executable bit in git.** Resolved: the index mode is 100755, so no change is needed.
2. **Action major versions.** The drafts use tags such as `actions/setup-java@v6` and `gradle/actions/setup-gradle@v6`. I could not confirm them offline. T1 is required, and any tag that does not exist is replaced by the newest existing major.
3. **nginx refuses to start if `backend` does not resolve.** Accepted by the user; standalone start is not needed. See Changes §5.
4. **`development` branch.** Resolved: `origin/development` exists and is the PR base.
5. **Images are pushed even if CI fails on the pushed commit.** The two workflows are independent. On `main` and `development`, merges are expected to go through PRs where CI already ran. If stricter gating is wanted, build-images could use `workflow_run` on CI success, or both could be in one workflow. This is not in this plan.
6. **Relying on metadata-action for lowercasing.** This is safe while `IMAGE_PREFIX` is used only as metadata-action input. The new comment warns about this. If it is ever used directly (for example as `cache-to: type=registry,ref=...`), it must be lowercased explicitly, for example `echo "IMAGE_PREFIX=${IMAGE_PREFIX,,}" >> "$GITHUB_ENV"`.
7. **Backend DB defaults.** `application.properties` defaults to the dev-container credentials (`app/app@postgres`). If a container starts without the env vars, it will try those. This setup already exists and is not a secret, but deployments must always set `DB_URL`, `DB_USER` and `DB_PASSWORD`.
8. **GHCR package visibility.** Packages are private the first time they are pushed. Making them public, or granting pull access to a cluster, is a manual step in GitHub settings.
9. **Image architecture.** The images are single-arch (linux/amd64 on GitHub runners). Hosts that need arm64 would need multi-platform builds, which are not planned here.

## Out of scope
- A frontend test runner (Vitest) and the matching CI step.
- docker-compose or Kubernetes manifests for running the images together (only the throwaway smoke test in T5).
- Deployment, image signing, SBOM, vulnerability scanning, multi-arch builds, a `latest` tag, release/semver tags.
- Running nginx as non-root or unprivileged, gzip, security headers, cache headers for `index.html`, TLS.
- `server.forward-headers-strategy` or other backend changes; a Docker `HEALTHCHECK`; JVM memory tuning; layered jars.
- Branch protection rules and required status checks (GitHub settings).
- Changing `concurrency.cancel-in-progress` in ci.yml (the user decided to keep it as is).
- Keep the README section short (plan review note).
- Any git operations (commit, branch, `git mv`, changing file modes in the index) unless the user asks for them.
