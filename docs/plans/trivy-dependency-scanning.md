# Implementation plan: Trivy dependency vulnerability scanning in CI

Trello card: https://trello.com/c/oy70Umyw/18-add-dependency-vulnerability-scanning-to-ci

## Goal
Scan backend (Gradle) and frontend (npm) dependencies with Trivy on every PR, and scan both Docker images in `build-images.yml` before they are pushed. HIGH/CRITICAL findings fail the job. All severities are reported in the job summary and in the Security tab (SARIF). A committed `.trivyignore` is the single place for suppressions.

## Acceptance criteria
- [ ] `ci.yml` has a job that scans backend (Gradle) and frontend (npm) dependencies for known CVEs using Trivy
- [ ] `build-images.yml` scans both built images with Trivy before push
- [ ] The job fails on HIGH and CRITICAL severity; MEDIUM and below are reported only
- [ ] Findings are visible in the PR (job summary or SARIF upload to the Security tab)
- [ ] README has a short section on how to read and suppress a finding

Confirmed decisions (treated as requirements):
- [ ] `security-events: write` is added only to the jobs that upload SARIF.
- [ ] The HIGH/CRITICAL gate also applies to image scans. An image that fails the scan is not pushed.
- [ ] `ignore-unfixed` is on by default, and the README says so.

## Current state (findings that shape the plan)
- **Backend has no dependency locking.** `/workspace/backend/build.gradle` has no `dependencyLocking` block and there is no `gradle.lockfile`. Versions come from the Spring Boot BOM (plugin `4.1.1`, dependency-management `1.1.7`), and the Gradle wrapper is 9.7.1. Trivy `fs` does not read `build.gradle`. For Gradle it only reads `gradle.lockfile`, so today the backend would show zero dependencies. That is a silent false negative.
- **Frontend is ready.** `/workspace/frontend/package-lock.json` exists (`lockfileVersion: 3`), and Trivy reads it directly without `npm ci`. The frontend runtime deps are only `react` and `react-dom`. Everything else (vite, typescript, oxlint, @vitejs/plugin-react) is a devDependency, and Trivy skips devDependencies by default (see Risks).
- **Backend Dockerfile.** `/workspace/backend/Dockerfile` copies only `gradlew build.gradle settings.gradle` before resolving dependencies. It has to copy the lockfile too, or the image build will ignore the locked versions.
- **Images.** The backend runtime is `eclipse-temurin:25-jre` (Ubuntu based) plus a Spring Boot fat jar. Trivy's image scan finds the nested jars in `BOOT-INF/lib`. The frontend runtime is `nginx:1.30-alpine` plus static files, so bundled npm code is **not** visible in the image scan. Only the fs scan covers npm packages.
- **How actions are pinned today.** First-party and well-known publishers use major tags (`actions/checkout@v6`, `docker/build-push-action@v7`, `gradle/actions/setup-gradle@v6`). A third-party action is pinned by full SHA with a version comment: `azure/setup-helm@9bc31f4e... # v5.0.1, pinned by SHA (third-party action)`. actionlint is pinned by image digest.
- **actionlint already runs in CI.** Job `actionlint` in `/workspace/.github/workflows/ci.yml` uses `rhysd/actionlint:1.7.12@sha256:...` with bundled shellcheck.
- **Build and deploy chain.** `build-images.yml` runs only on push to `development`/`main`. `update-deploy.yml` runs only when "Build images" ends in `success`. So a failed image scan also blocks the deploy-manifest update, which is the behavior we want.
- **Workflow permissions.** `build-images.yml` sets `contents: read` and `packages: write` at workflow level. `ci.yml` sets `contents: read`.

## Changes

### 1. `/workspace/backend/build.gradle`: turn on Gradle dependency locking
- Add:
  ```groovy
  dependencyLocking {
      lockAllConfigurations()
  }
  ```
- Why: this makes Gradle write `gradle.lockfile`, which is the only Gradle input Trivy `fs` understands. It is a built-in Gradle feature, so no plugin or new dependency is needed.
- Keep the default lock mode, not STRICT. In default mode, a resolved dependency that is missing from the lockfile, or has a different version, fails resolution. So a stale lockfile fails `./gradlew build` in the existing `backend` CI job, and the lockfile cannot silently drift from what the build really uses.

### 2. `/workspace/backend/gradle.lockfile` (new, generated, committed)
- Generate it with `cd backend && ./gradlew dependencies --write-locks`. In a single-project build this resolves every resolvable configuration.
- Commit the file as generated. Do not edit it by hand.
- **Trade-off (decision: commit the lockfile):**
  - Pros:
    - Trivy scans the exact transitive versions the build uses, with no Java setup in the scan job, so the job stays fast.
    - Builds become reproducible.
    - Every dependency change shows up as a readable diff in the PR.
  - Cons:
    - Every dependency or Spring Boot version change needs `./gradlew dependencies --write-locks` and a commit of the lockfile. Forgetting it fails the backend build with a lock-state error. The README documents the command.
    - Gradle plugin classpath (buildscript) dependencies are not locked or scanned. They are build-time only and do not ship in the image.
    - Trivy does not separate test configurations in a `gradle.lockfile`. Test-only deps such as Testcontainers are scanned too and can fail the gate. I think that is acceptable (they run in CI with Docker access), but confirm it on the first run.
  - Rejected alternatives:
    - (a) Generate the lockfile only inside the CI scan job. There is no developer friction, but the job needs JDK and Gradle, the scan is not reproducible from the repo, and the PR diff shows nothing.
    - (b) Rely only on the image scan, which sees the fat jar. It runs only after merge, so it does not meet criterion 1 ("on every PR").

### 3. `/workspace/backend/Dockerfile`
- Change `COPY gradlew build.gradle settings.gradle ./` to `COPY gradlew build.gradle settings.gradle gradle.lockfile ./`, so the image resolves the same locked versions that were scanned.
- `/workspace/backend/.dockerignore` does not exclude the file, so no change is needed there.

### 4. `/workspace/.trivyignore` (new, repository root)
- Trivy reads `.trivyignore` from the working directory by default, which is the checkout root in Actions. Every Trivy step also passes `trivyignores: .trivyignore` explicitly, so the pickup is visible and does not depend on the working directory.
- Contents: a header comment that explains the convention, and no entries at first. Convention for each entry (comment block above the ID):
  ```
  # CVE-2099-00000
  #   package:  <ecosystem>/<name>@<version> (backend lockfile | frontend lockfile | backend image | frontend image)
  #   reason:   <why it is not exploitable here / why it cannot be fixed yet>
  #   added:    YYYY-MM-DD by <who>
  CVE-2099-00000 exp:YYYY-MM-DD
  ```
- `exp:YYYY-MM-DD` is Trivy's native expiry syntax in `.trivyignore`. After that date the entry stops applying, the finding comes back and fails CI again, which forces a review. The convention is that every entry must have an `exp:` date, at most 90 days ahead (the exact limit is an open question below).
- The same file applies to both workflows and all four scans (fs report, fs gate, image report, image gate).

### 5. `/workspace/.github/workflows/ci.yml`: new job `dependency-scan`
- Update the header comment from "Four independent jobs" to five, and name the new job.
- Job `dependency-scan`, named "Dependencies (Trivy)":
  - `runs-on: ubuntu-latest`, `timeout-minutes: 10`.
  - Job-level `permissions`: `contents: read` and `security-events: write`. The workflow-level `contents: read` stays for the other jobs. If the repo is private, `actions: read` is also needed for `upload-sarif` (see open questions).
  - No JDK or Node setup. Trivy reads `backend/gradle.lockfile` and `frontend/package-lock.json` directly.
- Steps in order:
  1. `actions/checkout@v6` with `persist-credentials: false`, the same as `helm` and `actionlint`.
  2. **Report run (SARIF, all severities, never fails).** `aquasecurity/trivy-action@<full SHA> # vX.Y.Z` with:
     - `scan-type: fs`, `scan-ref: .`, `scanners: vuln`
     - `format: sarif`, `output: trivy-fs.sarif`
     - `ignore-unfixed: true`, `trivyignores: .trivyignore`
     - `exit-code: 0`
     - `version: vA.B.C` to pin the Trivy binary as well
     - Leave `severity` unset. In SARIF mode trivy-action reports all severities unless `limit-severities-for-sarif` is set, and we do not set it.
  3. **Report run (table, all severities, never fails).** Same action and inputs, but `format: table`, `output: trivy-fs.txt`, and env `TRIVY_SKIP_DB_UPDATE: true` so it reuses the DB from step 2 (plus `skip-setup-trivy: true` if the pinned version supports it).
  4. **Job summary.** A `run:` step that appends a heading plus `trivy-fs.txt` in a fenced block to `$GITHUB_STEP_SUMMARY`, or "No vulnerabilities found" if the file has no findings. This is the fallback that is visible on every PR, including forks and Dependabot, where SARIF upload is not allowed.
  5. **Upload SARIF.** `github/codeql-action/upload-sarif@v4` with `sarif_file: trivy-fs.sarif` and `category: trivy-fs`. Guard it with:
     `if: github.event_name != 'pull_request' || (github.event.pull_request.head.repo.full_name == github.repository && github.actor != 'dependabot[bot]')`
     Fork and Dependabot PRs get a read-only token, so the upload would fail there.
  6. **Gate run.** Same action with:
     - `severity: HIGH,CRITICAL`, `exit-code: 1`, `format: table`
     - `ignore-unfixed: true`, `trivyignores: .trivyignore`
     - `TRIVY_SKIP_DB_UPDATE: true`

     This is the step that fails the job, and it prints the blocking findings in the log.
- Why this ordering: all reporting happens before the gate. The report steps never fail, so they run without `if: always()`, and the job still goes red on HIGH/CRITICAL.
- Why separate report and gate runs: Trivy has one `exit-code` per run, so "fail on HIGH/CRITICAL" and "report MEDIUM and below" need two runs over the same cached DB. That costs a few seconds.

### 6. `/workspace/.github/workflows/build-images.yml`: build, then scan, then push
- Job `build` gets job-level `permissions: { contents: read, packages: write, security-events: write }`. Job-level permissions replace the workflow-level block, so all three must be listed. The workflow-level block stays, or can be reduced to `contents: read`; either is fine.
- Leave matrix `fail-fast` at its default (true), which is the current behavior. If one image fails the scan, the other matrix job is cancelled, nothing new is deployed, and `update-deploy.yml` does not run.
- Steps for each matrix service (backend, frontend), in this order:
  1. `actions/checkout@v6` (unchanged).
  2. `docker/setup-buildx-action@v4` (unchanged).
  3. `docker/metadata-action@v6` (`id: meta`, unchanged).
  4. **Build and load (no push).** `docker/build-push-action@v7`, `id: build`, with:
     - `context: ${{ matrix.service }}`
     - `push: false`, `load: true`
     - `tags: ${{ steps.meta.outputs.tags }}`, `labels: ${{ steps.meta.outputs.labels }}`
     - the same `cache-from`/`cache-to` gha scope as today

     The image ends up in the runner's Docker engine under its final GHCR tags.
  5. **Image report run (SARIF, all severities, exit 0).** trivy-action with:
     - `scan-type: image`
     - `image-ref: ${{ fromJSON(steps.meta.outputs.json).tags[0] }}`. Use the lowercased tag from metadata-action, not `env.IMAGE_PREFIX`, as the existing comment warns.
     - `format: sarif`, `output: trivy-image.sarif`
     - `ignore-unfixed: true`, `trivyignores: .trivyignore`, `version: vA.B.C`
     - env `TRIVY_IMAGE_SRC: docker`, so Trivy scans the local image and never pulls the old branch tag from GHCR
  6. **Image report run (table)**, then **job summary**, the same as steps 3–4 of the ci.yml job. The summary heading includes `${{ matrix.service }}`.
  7. **Upload SARIF.** `github/codeql-action/upload-sarif@v4` with `category: trivy-image-${{ matrix.service }}`. The category must be unique per matrix leg, or the two uploads overwrite each other. No fork guard is needed because this is a push event.
  8. **Image gate run.** `severity: HIGH,CRITICAL`, `exit-code: 1`, `ignore-unfixed: true`, `trivyignores: .trivyignore`, `TRIVY_IMAGE_SRC: docker`, `TRIVY_SKIP_DB_UPDATE: true`, `TRIVY_SKIP_JAVA_DB_UPDATE: true`.
  9. **Log in to GHCR.** Move `docker/login-action@v4` here, after the gate, so the write-capable registry login exists only once the image has passed.
  10. **Push exactly the scanned image.** A `run:` step that reads `steps.meta.outputs.tags` through an `env:` variable (never interpolated directly into the script) and runs `docker push "$tag"` for each non-empty line.
- Why `docker push` and not a second `build-push-action` with `push: true`:
  - A second build would re-resolve base image tags (`eclipse-temurin:25-jre`, `nginx:1.30-alpine`), so in principle it could push something other than what was scanned.
  - `docker push` of the loaded image guarantees scanned == pushed.
  - Side effect: build-push-action's default provenance attestation is no longer attached. Nothing in this repo uses it, but it is listed as an open question.
- Update the header comment of the file to say that images are scanned before push, and that a failed scan blocks the push and therefore the deploy-manifest update.

### 7. `/workspace/README.md`
- In "CI and Docker images":
  - Add the Trivy job to the list of what `ci.yml` runs.
  - Say that `build-images.yml` scans before pushing.
- New subsection "Dependency vulnerability scanning (Trivy)", short:
  - **What runs where:** the fs scan of `backend/gradle.lockfile` and `frontend/package-lock.json` on every PR and on pushes to development/main; the image scan in "Build images" before push.
  - **What fails:** fixed HIGH/CRITICAL findings. MEDIUM, LOW and UNKNOWN are only reported. **Unfixed vulnerabilities (no patched version available) are ignored by default (`ignore-unfixed`)**, so they appear in neither the report nor the gate.
  - **How to read findings:**
    - The job summary of the run has a table with package, installed version, fixed version and CVE.
    - The failing gate step's log lists the blocking findings.
    - Security tab → Code scanning, filtered by tool "Trivy" and category `trivy-fs` / `trivy-image-backend` / `trivy-image-frontend`.
    - Fork and Dependabot PRs only get the job summary.
  - **How to fix:** bump the dependency, or rebuild on a newer base image. For Gradle, then run `cd backend && ./gradlew dependencies --write-locks` and commit `gradle.lockfile`.
  - **How to suppress:** add the CVE to `/.trivyignore` using the comment convention (package, reason, added-by/date) with a mandatory `exp:YYYY-MM-DD`. Explain that the finding comes back after that date.
  - **Run locally** (pinned image tag, same version as CI):
    - `docker run --rm -v "$PWD:/repo" -w /repo aquasec/trivy:<A.B.C> fs --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 .`
    - `trivy image` against a locally built `pet-backend` / `pet-frontend`, which needs the Docker socket mounted.

### Not changed
- `/workspace/frontend/*`: the lockfile already exists.
- `update-deploy.yml`: it already runs only on "Build images" success.
- `.github/workflows/ci.yml` job `actionlint`: it already lints every workflow file.

## Tests
There are no unit tests for workflow YAML. Verification is layered:

| Criterion | How it is verified |
|---|---|
| Gradle deps are visible to Trivy | Locally, run `cd backend && ./gradlew dependencies --write-locks`, then `./gradlew build` (the existing backend tests must pass with locking on; CLAUDE.md: "not done until tests pass"). Then run `trivy fs --scanners vuln --list-all-pkgs --format json backend/` (docker `aquasec/trivy:<ver>`) and confirm that Maven packages from `gradle.lockfile` are listed, e.g. `org.springframework:spring-webmvc`. |
| Lockfile drift is caught | Locally, add a dependency to `build.gradle` without `--write-locks`. `./gradlew build` must fail with a lock-state error. Then revert. |
| Backend image uses the locked versions | Locally, `docker build -t pet-backend backend` must succeed. |
| ci.yml scans backend and frontend | Local `trivy fs .` output shows two targets: `backend/gradle.lockfile` (gradle) and `frontend/package-lock.json` (npm). On the PR, the "Dependencies (Trivy)" job summary shows both targets. |
| Fail on HIGH/CRITICAL, report MEDIUM and below | On a throwaway branch/PR, add a known-vulnerable pinned package with a fixed HIGH CVE to `frontend/package.json`/lockfile, e.g. `lodash@4.17.20` (CVE-2021-23337, HIGH, fixed in 4.17.21). The job must fail at the gate step, and the summary and SARIF must still be produced. If there is a MEDIUM finding (check with the local run), it must appear in the summary while the job stays green. Revert afterwards. The user performs the git and PR operations (CLAUDE.md: no git operations unless asked). |
| Suppression works | On the same throwaway branch, add `CVE-2021-23337 exp:<future date>` to `.trivyignore`. The gate must pass. With a past `exp:` date, it must fail again. |
| Findings visible in the PR | The job summary is visible on the PR run. After the first push to the base branch, the Security → Code scanning tab shows a `trivy-fs` analysis, and PR runs show the "Code scanning results" check. |
| build-images scans before push | Locally: `docker build -t pet-backend backend && trivy image --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 pet-backend`, and the same for the frontend, to establish the baseline before merging. In CI, this can only be verified by the first push to `development`: check the step order in the run (build → report → SARIF → gate → login → push), the `trivy-image-backend` and `trivy-image-frontend` analyses in the Security tab, and new tags in GHCR. A negative image test (for example temporarily using an old base image) is not planned because it would require pushing to development. It is covered by the step ordering and the identical gate config proven in the fs job. |
| Workflow syntax, expressions, shell | The existing `actionlint` CI job, and locally `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color`. It checks YAML, `${{ }}` expressions (including `fromJSON(steps.meta.outputs.json)`), `permissions` keys and the `run:` shell through shellcheck. It cannot check trivy-action input names unless its bundled action metadata covers that action. Typos there only show up at run time as "Unexpected input" warnings, so check the first run's log for them. |
| README section | Manual review. |

Only a real run can verify:
- the SARIF upload and its permissions
- the fork/Dependabot guard
- Trivy DB download and cache behavior
- the local image lookup through `TRIVY_IMAGE_SRC=docker`
- the push of the scanned image to GHCR.

## Risks and open questions
1. **New dependency: `aquasecurity/trivy-action` (open question until confirmed).**
   - Version: the latest release at implementation time, pinned by **full commit SHA** with a `# vX.Y.Z, pinned by SHA (third-party action)` comment, following the `azure/setup-helm` convention.
   - Why: it installs Trivy, caches the vulnerability DB (built-in `actions/cache`) and exposes the scan options as inputs.
   - Cost by hand: about 20 lines of `run:` shell per job, to download and verify the Trivy binary checksum and to cache `~/.cache/trivy`. That is doable, but it adds maintenance.
   - **Supply-chain note:** trivy-action and its setup action had tags hijacked in a 2026 incident (tags were force-pushed to malicious commits). So the SHA must be taken from the official GitHub release and cross-checked against Aqua's security advisory. Do not use a tag lookup alone. Also check that the pinned version's internal `aquasecurity/setup-trivy` reference is itself SHA-pinned.
   - Alternative: run the `aquasec/trivy` image by digest (like actionlint) through `docker run`. This avoids the action entirely, at the cost of manual DB caching. Please choose one.
2. **New dependency: the Trivy binary itself (open question until confirmed).**
   - Version: pinned through the action's `version:` input to a specific release, verified in the same way as item 1. The same version goes into the README's local `docker run aquasec/trivy:<ver>` command.
   - Why: it is the scanner the card requires.
   - Cost by hand: not applicable, because the card mandates Trivy.
3. **New dependency: `github/codeql-action/upload-sarif@v4` (open question until confirmed).**
   - Why: it is the only supported way to put SARIF into the Security tab.
   - Cost by hand: calling the code-scanning REST API with gzip+base64 SARIF, which is not worth it.
   - Pinning: GitHub-owned, so by major tag like `actions/*`. Confirm whether you want SHA pinning here too.
4. **Is the repository public?**
   - For a private repo, code scanning (the Security tab) needs GitHub Advanced Security, and `upload-sarif` needs `actions: read`.
   - Without GHAS, the upload fails and only the job summary satisfies "findings visible in the PR". Please confirm.
5. **Baseline on the first run.**
   - The current `package-lock.json`, the lockfile about to be generated, `eclipse-temurin:25-jre` and `nginx:1.30-alpine` may already have fixed HIGH/CRITICAL findings.
   - The implementer runs the local scans first. Each finding is either fixed (version bump, lockfile refresh) or suppressed with an expiry. Any finding that would need a suppression is reported to you for approval before it goes into `.trivyignore`.
6. **Image scans can start failing without a code change.** When a fix for a base-image package is published, the next merge fails "Build images" until the base image is rebuilt upstream or the finding is suppressed. This is intended (confirmed decision 2), but it means a merged PR may not deploy. Should images also be built and scanned (without push) on PRs to catch this earlier? That is not in the card, so not planned.
7. **npm devDependencies.** Trivy skips devDependencies in `package-lock.json` by default, so vite, typescript and oxlint (all build and dev-server tooling) would not be scanned. Only `react` and `react-dom` would be. Recommendation: pass `--include-dev-deps` (trivy-action has no dedicated input, so set it through env `TRIVY_INCLUDE_DEV_DEPS: true`) because the vite dev server has had real CVEs. Please decide. The counterpart is that Gradle test dependencies are scanned regardless (see Changes §2).
8. **Maximum suppression lifetime.** I proposed at most 90 days per `exp:` entry. Please confirm or choose another value. It is only a convention and is not enforced by tooling.
9. **Provenance attestation is dropped** by pushing with `docker push` instead of build-push-action. Nothing here consumes it. If you want to keep it, the alternative is a second `build-push-action` with `push: true` from cache. That has a tiny window in which the pushed image could differ from the scanned one.
10. **Trivy DB rate limits.** Anonymous pulls of the Trivy DB and Java DB from ghcr.io are occasionally rate-limited. The action's cache reduces pulls, but a rate-limited download fails the job (it fails closed). Mitigation if it happens: set `TRIVY_DB_REPOSITORY` / `TRIVY_JAVA_DB_REPOSITORY` to Aqua's documented mirrors. Not configured up front.
11. **Required status check.** Making "Dependencies (Trivy)" a required check in branch protection is a repo setting outside the code. You would have to do it yourself.
12. **`ignore-unfixed` also hides unfixed findings from the report**, not only from the gate. That matches "ignored by default". If you want unfixed findings reported but not gating, turn it off only for the report runs.

## Out of scope
- License scanning, SBOM generation, Dependabot configuration (per the card).
- Secret and misconfiguration scanning: `scanners: vuln` only (Dockerfiles, Helm chart and IaC are not scanned).
- Pinning base images by digest in the Dockerfiles.
- Building or scanning images on pull requests.
- Scanning or locking the Gradle plugin/buildscript classpath.
- Branch-protection or required-check settings, and GitHub Advanced Security enablement.
- Scheduled re-scans of already-pushed images.

## Relevant files
- /workspace/.github/workflows/ci.yml
- /workspace/.github/workflows/build-images.yml
- /workspace/.github/workflows/update-deploy.yml (unchanged, relies on build-images success)
- /workspace/backend/build.gradle
- /workspace/backend/gradle.lockfile (new)
- /workspace/backend/Dockerfile
- /workspace/frontend/package-lock.json (unchanged, lockfileVersion 3)
- /workspace/frontend/Dockerfile (unchanged)
- /workspace/.trivyignore (new)
- /workspace/README.md

## Decisions after plan review (approved by the user, 2026-09-24)
These override anything above that conflicts with them.

Open questions resolved:
1. Use `aquasecurity/trivy-action` pinned by full commit SHA with a version comment. Take the SHA from the official GitHub release and cross-check it against Aqua's security advisory for the 2026 tag hijack. Choose a version that includes the fix for inputs leaking between multiple invocations in one job, and whose internal `aquasecurity/setup-trivy` reference is SHA-pinned. Pin the Trivy binary through `version:`.
2. `github/codeql-action/upload-sarif@v4` (major tag, no SHA).
3. The repository is public, so no `actions: read` is needed for SARIF upload.
4. Scan npm devDependencies: set env `TRIVY_INCLUDE_DEV_DEPS: true` on every fs scan step (report and gate).
5. Maximum `.trivyignore` expiry is 90 days. Document this in `.trivyignore` and the README.
6. Provenance attestation is not needed; `docker push` of the loaded image is fine.

Reviewer suggestions, all accepted:
1. `build-images.yml`: `continue-on-error: true` on the `upload-sarif` step. The gate stays fail-closed.
2. `ci.yml` SARIF guard: use `github.event.pull_request.user.login != 'dependabot[bot]'` instead of `github.actor`.
3. `build-images.yml` image table step: also set `TRIVY_SKIP_JAVA_DB_UPDATE: true`.
4. Wording about `fail-fast`: one leg may already have pushed when the other leg's gate fails. Say so in the header comment and README; do not claim nothing is pushed.
5. Job summary: always append the whole table file. No "no findings" heuristic.
6. README local command: pin `aquasec/trivy` by digest (`aquasec/trivy:<ver>@sha256:...`), same version as CI.
7. Gradle test dependencies: during the baseline run, if findings come from test-only configurations, STOP and report the list to the user before anything goes into `.trivyignore`. If all gate findings are test-only (testImplementation / testRuntimeClasspath), propose limiting the scan to runtime dependencies instead of suppressing, and wait for the user's decision.

Baseline rule (reinforces risk 5): any finding that would need a `.trivyignore` entry is reported to the user first. Do not add entries on your own.

Additional change:
- `CLAUDE.md`, section "Commands": add a line saying that after any change to backend dependencies you must run `cd backend && ./gradlew dependencies --write-locks` and commit `backend/gradle.lockfile`, otherwise the build fails.
