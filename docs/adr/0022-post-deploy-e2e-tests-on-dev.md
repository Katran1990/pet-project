# 0022. Post-deploy end-to-end tests on dev

Date: 2026-10-06
Status: proposed

## Context
- Nothing checked that a deploy works end to end: CI tests the code, Argo CD reports the
  manifests as synced, but nobody exercised the running dev stack (frontend, nginx, backend,
  database).
- The dev cluster is reachable from the home runner (ADR 0021, "Future").
- To test "what was just deployed", a run must know when dev serves the new commit. Neither
  Argo CD (credentials needed) nor the backend (no version information) told that.

## Decision
- **Tests:** Playwright with TypeScript in `e2e/` (own `package.json` and lockfile), Chromium
  only, installed by an explicit step pinned through the lockfile (ADR 0006). `npm run e2e`
  runs them against any base URL given in `E2E_BASE_URL`.
- **Trigger:** `e2e.yml` on `workflow_run` of "Update deploy manifests", for `development`
  only, on `[self-hosted, home]`. It checks out the deployed commit (`head_sha`) so the tests
  match the code that runs.
- **Wait for the deploy:** the workflow polls `/api/actuator/info` until `git.commit.id` is a
  prefix of `head_sha`, for 10 minutes at most, then fails with a clear message. The commit
  comes from `git.properties`, written by the `com.gorylenko.gradle-git-properties` plugin
  (only commit id, abbreviation and time: no branch, author or message). In the image build
  the checkout's `.git` is passed as the named build context `gitdir`
  (`backend/Dockerfile`, `build-images.yml`); it is bind-mounted read-only into the `bootJar`
  step and never written to a layer. With `REQUIRE_GIT_DIR=true` (set in CI) a missing
  context fails the image build.
- **Exposure:** nginx forwards exactly `/api/actuator/health` and `/api/actuator/info` to the
  backend (exact-match locations). This refines ADR 0019 ("nginx forwards only `/api/`"):
  `/actuator/prometheus` stays in-cluster. `application.properties` is unchanged.
- **Path-parameter traversal:** `/api/..;/actuator/prometheus` is not a dot segment for
  nginx, so it matches `location /api/` and reaches Tomcat unchanged. A probe in
  `PrometheusEndpointIT` (real Tomcat, raw URI) shows the backend answers 404 for it (and for
  `/api/%2e%2e;/...`), so no nginx or Tomcat rule is added. The probe stays as a regression
  guard: if a Tomcat or Spring upgrade changes the behaviour, the test fails and the
  decision is revisited (an nginx rule rejecting `;` in `/api/` paths was the agreed fallback).
- **Test data:** every category name and expense note is `e2e-<runId>-<nonce>`. The run id is
  `gh-<run_id>-<attempt>` in CI. Cleanup deletes the expense and archives the category
  through the UI (categories cannot be deleted, ADR 0014), with an API safety net in the test
  fixture that also runs when a test fails.
- **Base URL:** the repository variable `E2E_BASE_URL`, never hard-coded.
- **Failure artifacts:** the Playwright HTML report and traces, kept 7 days.
- **Type-check in PRs:** `ci.yml` has a job `e2e-check` (type-check, unit tests of the wait
  script with a fake fetch and clock, `playwright test --list`). It needs no browser and no
  network.
- **No gating yet:** a red run blocks neither the promotion to `main` nor a rollback. It is a
  signal, not a gate, until the tests have proven stable.
- **Why not in PRs:**
  - there is no per-PR environment, and dev runs `development`, not the PR;
  - the tests write to the shared dev database;
  - fork PRs must never reach the home network (ADR 0021).
- **Trust:** the job runs `development` code, npm dependencies and the Chromium binary on the
  runner, with a `contents: read` token and no secrets. This is within ADR 0021's accepted
  risk. `e2e.yml` follows a chain of `workflow_run` workflows that starts at the push-only
  "Build images" (ADR 0021 is amended accordingly).

## Alternatives considered
- The Playwright container image instead of a host install: runs as root (root-owned files in
  `_work`) and does not see the host's name resolution.
- Triggering on "Build images" instead. Not chosen, because "Update deploy manifests" is the
  event that says a deploy is coming. It is the fallback if GitHub does not pass
  `head_branch`/`head_sha` down the two-level `workflow_run` chain: the 10-minute wait
  absorbs the extra minute.
- Polling the Argo CD API: needs credentials.
- Spring Boot `build-info` with a build argument instead of `git.properties`.
- API-only tests: would not cover nginx routing and the UI.
- e2e in PRs against preview environments, and gating prod: out of scope for now.

## Consequences
- Archived `e2e-` categories accumulate in the dev database (accepted). Interrupted or
  cancelled runs can also leave an active `e2e-` category with its expense. Both are cleaned
  by the dev-only SQL in README "E2E tests". This is manual test-data cleanup, not app
  behaviour, so ADR 0014 stays as it is.
- Health and info are public in dev **and prod**, because it is the same image. The repository
  is public, so this reveals nothing new.
- **Known, accepted cost:** the backend jar is rebuilt on every commit, because the content of
  `.git` is part of the `bootJar` step's cache key. This includes frontend-only pushes and
  costs about 1-2 minutes.
- `e2e.yml` changes take effect only on `main` (ADR 0004).
- The runner host must resolve the `E2E_BASE_URL` host and have the Chromium libraries (ADR
  0006, README "CI runner"). Chromium is downloaded on every run.
- The repository is public, so failure artifacts (HTML report, traces, screenshots) can be
  downloaded by any signed-in GitHub user for 7 days. They show dev data visible on the pages,
  `e2e-` test data and the dev host name. This is accepted because dev holds no real data.
- With a single runner, every push to `development` keeps the runner busy during the Argo CD
  wait (up to 10 minutes) plus the tests, so the next CI or "Build images" job waits behind it.
- Only the backend commit is awaited; a short window with an old frontend pod is possible.
- The tests pin the time zone `Europe/Warsaw`, the default `APP_TIME_ZONE` (ADR 0015).
