# Implementation plan: Patch OS packages in the frontend and backend images

Trello card: https://trello.com/c/VsiQZTrW

## Goal
Both Docker images upgrade their base-image OS packages on every build, so distro security fixes reach the images without waiting for an upstream base-image rebuild. The upgrade runs in a final stage named `runtime`, which `build-images.yml` excludes from the `type=gha` layer cache. `.trivyignore` entries that a local Trivy scan confirms as fixed are removed.

## Acceptance criteria
- [ ] `frontend/Dockerfile`: the final nginx stage runs `RUN apk upgrade --no-cache`.
- [ ] `backend/Dockerfile`: the final `eclipse-temurin:25-jre` stage runs `apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*` (as root, before switching to the non-root user).
- [ ] `.github/workflows/build-images.yml`: the build step sets `no-cache-filters` on the final stage of both images, so the OS upgrade runs on every CI build instead of being reused from the `type=gha` layer cache. The build stages keep using the cache.
- [ ] `.trivyignore`: remove CVE-2026-93990 and CVE-2026-103111 once a local scan of the frontend image confirms they are fixed; remove CVE-2026-84782 once a local scan of the backend image confirms it is fixed. An entry whose fix is not confirmed stays.
- [ ] A local Trivy image scan of both images (pinned Trivy version, same flags as CI, see README) reports no HIGH/CRITICAL findings.
- [ ] ADR 0007 is amended ("Amended:" line): one sentence that both images patch OS packages at build time on every build (final stage excluded from the layer cache); one sentence that the unpinned patch-level OS upgrade is a deliberate exception to ADR 0006; two Consequences bullets (builds of the same commit are no longer reproducible over time; a package-mirror outage or a bad distro update can fail or alter the build), and the existing "Build images can fail without a code change" bullet revised so it stays accurate.
- [ ] No other changes.

## Current state (findings that shape the plan)
- **`/workspace/frontend/Dockerfile`**
  - It has two unnamed-to-named stages: `node:24-alpine AS build`, then the final stage `FROM nginx:1.30-alpine` (no name), which has two `COPY` lines and `EXPOSE 80`.
  - The final stage has no `USER`, so it runs as root and `apk upgrade` works as is.
  - The official nginx Alpine image installs nginx from a repository it does not keep in `/etc/apk/repositories`. So `apk upgrade` only touches Alpine main/community packages, and nginx itself is not upgraded.
- **`/workspace/backend/Dockerfile`**
  - Stages: `scratch AS gitdir` (replaced in CI by the named context), `eclipse-temurin:25-jdk AS build`, then the final stage `FROM eclipse-temurin:25-jre` (no name).
  - The final stage runs `WORKDIR /app`, `RUN useradd --system --uid 1001 app`, `USER 1001`, then `COPY` of the jar. Everything before `USER 1001` runs as root.
  - The Temurin JRE comes from a tarball in `/opt/java/openjdk`, not from apt, so `apt-get upgrade` does not change the Java runtime.
- **`/workspace/.github/workflows/build-images.yml`**
  - It has one matrix job, `service: [backend, frontend]`. The step "Build image" uses `docker/build-push-action@v7` (major tag, ADR 0006) with:
    - `context: ${{ matrix.service }}`;
    - backend-only `build-contexts` / `build-args` through matrix expressions;
    - `push: false`, `load: true`;
    - `cache-from: type=gha,scope=${{ matrix.service }}` and `cache-to: type=gha,mode=max,scope=${{ matrix.service }}`.
  - There is no `target:` input, so the last stage of each Dockerfile is built.
  - BuildKit runs on the `docker-container` driver: `setup-buildx-action@v4` with buildx `v0.37.2` and BuildKit `v0.33.1` pinned by digest.
- **Workflow validation in CI**
  - `ci.yml` job `actionlint` runs `docker://rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667` with `-color` over `.github/workflows/`, including shellcheck.
  - `.github/actionlint.yaml` declares the `home` label.
  - README line 171 gives the local equivalent.
- **`/workspace/.trivyignore`** has exactly three entries:
  - CVE-2026-93990: libexpat 2.8.4-r0, fixed in 2.8.5-r0, exp 2026-10-24.
  - CVE-2026-84782: libssl3t64, openssl and openssl-provider-legacy 3.5.5-1ubuntu3.5, fixed in 3.5.5-1ubuntu3.6, exp 2026-10-30.
  - CVE-2026-103111: pcre2 10.48-r0, fixed in 10.49-r0, exp 2026-10-31.

  CVE-2026-4775 (libtiff) is not suppressed, so the frontend image gate fails today.
- **ADR 0006 (pinning)** covers CI dependencies, not the contents of the runtime images. The base images already float by tag. The user agreed with this reading. ADR 0007 records the unpinned patch-level OS upgrade as a deliberate exception to ADR 0006 (criterion 6). No ADR 0006 file change, and no new action or version (`build-push-action@v7` stays).
- **ADR 0021 (layer cache)**
  - What it says: "The BuildKit layer cache (`type=gha`) in `build-images.yml` stays, because the `docker-container` builder is removed at the end of every job."
  - This plan keeps that cache: same `cache-from` / `cache-to`, and the build stages still come from it. It only stops reuse of the final stage.
  - The ADR decides that the cache exists and why. It says nothing about which stages use it, and its reason is unaffected.
  - **This is therefore not a deviation from ADR 0021, and ADR 0021 is not changed.** The exclusion is recorded in ADR 0007, with a reference to ADR 0021 (see Changes 5).
- **README**
  - The "Run locally" section (lines 248-267) builds with `docker build -t pet-backend backend && docker build -t pet-frontend frontend`. It scans with `aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e` using `image --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1`, `TRIVY_IMAGE_SRC=docker`, the Docker socket mounted and the repo at `/repo`.
  - Line 299-300 ("Image layers still use the GitHub Actions cache (`type=gha`)") stays true.
  - "How to fix: bump the dependency, or rebuild on a newer base image" stays true.
  - Nothing becomes false, so **README is not changed**.

## Changes

### 1. `/workspace/frontend/Dockerfile`
- Name the final stage `runtime` and insert the upgrade directly after `FROM`, before the `COPY` lines.
- Only the stage line, its comment and the new `RUN` change:
  ```dockerfile
  # Stage 2: serve the static files with nginx. build-images.yml excludes the stage "runtime"
  # from the layer cache (no-cache-filters), so the upgrade below runs on every CI build.
  FROM nginx:1.30-alpine AS runtime

  # Pick up Alpine security fixes published since the base image was built
  RUN apk upgrade --no-cache
  ```
- `--no-cache` keeps no apk index in the layer, so no cleanup is needed.
- Naming the last stage does not change what a plain `docker build` produces: the last stage is still the default target.

### 2. `/workspace/backend/Dockerfile`
- Name the final stage `runtime`, the same name as in the frontend. Then one constant workflow value works for both matrix legs, with no matrix expression.
- `runtime` collides with no existing stage (`gitdir`, `build`).
- Insert the upgrade after `WORKDIR /app` and before `# Run as a non-root user` / `RUN useradd ...`:
  ```dockerfile
  # Stage 2: minimal runtime image (JRE only, no Gradle, no sources). build-images.yml excludes
  # the stage "runtime" from the layer cache (no-cache-filters), so the upgrade runs on every CI build.
  FROM eclipse-temurin:25-jre AS runtime
  WORKDIR /app

  # Pick up Ubuntu security fixes published since the base image was built (as root, before USER)
  RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
  ```
- **One `RUN` instruction:** the apt lists are removed in the same layer and never persist in the image.
- **Final stage only:** the `eclipse-temurin:25-jdk` build stage is not shipped and is not touched.
- **No `DEBIAN_FRONTEND=noninteractive`:**
  - A Docker build has no TTY. debconf fails over from Dialog and Readline to its Teletype frontend, which reads EOF from stdin and takes the defaults. At worst it prints "unable to initialize frontend" warnings.
  - A fresh base image has no locally modified conffiles, so dpkg has nothing to ask about.
  - This keeps the exact command from criterion 2.
- **Fallback, only if the build fails on a dpkg conffile prompt:** add the option inline, as `apt-get upgrade -y -o Dpkg::Options::=--force-confold`. Never use `ENV`, which would leak into the runtime image.
- **No `--no-install-recommends`:** `apt-get upgrade` never installs new packages (that needs `--with-new-pkgs` or `dist-upgrade`), so the flag would have no effect.
- **`upgrade`, not `dist-upgrade`:** this is the minimal, conventional form. A fix that needs a new dependency would be held back, and the version check in Tests catches that.

### 3. `/workspace/.github/workflows/build-images.yml`
In step "Build image" (`docker/build-push-action@v7`), add after the `cache-to` line:
```yaml
          # The final stage "runtime" of both Dockerfiles upgrades the base image's OS packages.
          # Never reuse it from the layer cache, so every build installs the distro fixes published
          # so far (ADR 0007). The build stages (npm ci, Gradle) still come from the cache (ADR 0021).
          no-cache-filters: runtime
```
- **The input exists:** `no-cache-filters` is a documented input of `docker/build-push-action` ("Do not cache specified stages") and maps to buildx `--no-cache-filter`. Tests Step 0 confirms it in the v7 `action.yml`.
- **Scope of the filter:**
  - BuildKit sets ignore-cache only on the instructions of the named stage, including its `FROM` resolution and `COPY --from=build`.
  - The stages it depends on (`build`, and the `gitdir` named context) keep their cache, so `npm ci`, `npm run build`, `./gradlew dependencies` and `bootJar` are still cache hits when their inputs are unchanged.
  - Re-running `COPY --from=build` is a cheap file copy.
- **Interaction with `cache-from` / `cache-to mode=max`:**
  - The filter only stops cache *reuse* (import) for the `runtime` stage. `cache-from` still serves the build stages.
  - With `mode=max`, the freshly built `runtime` layers are still exported to the gha cache. Nothing ever reads them back, which costs a few MB of upload per build within the per-service scope. This is harmless, and no change to `cache-to` is needed.
- **Pinning:** no new action, and no version change (ADR 0006 is satisfied).
- **Scanned == pushed:** unchanged. The loaded image is scanned and then pushed with `docker push`.
- **No other edits** to the file. The header comment does not mention caching, so it stays correct.

### 4. `/workspace/.trivyignore`
- Remove each entry together with its whole comment block, **only after** the proof steps in Tests confirm the fix. The rule per entry:
  - CVE-2026-93990: frontend image has `libexpat >= 2.8.5-r0`, and the CVE is absent from an unsuppressed scan of `pet-frontend`.
  - CVE-2026-103111: frontend image has `pcre2 >= 10.49-r0`, and the CVE is absent from an unsuppressed scan of `pet-frontend`.
  - CVE-2026-84782: backend image has `libssl3t64`, `openssl` and `openssl-provider-legacy` at `>= 3.5.5-1ubuntu3.6`, and the CVE is absent from an unsuppressed scan of `pet-backend`.
- An entry that fails either check stays unchanged, and the result goes back to the user.
- The header comment (convention and `exp:` rule) stays. If all three entries go, the file is header-only, which was its original "empty" state.
- The user has confirmed this change. CI's `ci.yml` and `build-images.yml` Trivy steps read this file.

### 5. `/workspace/docs/adr/0007-dependency-locking-and-vulnerability-gate.md`
- **Amended line:** add it directly under the existing `Amended:` line, in the same `Amended: YYYY-MM-DD: <text>.` form, using the implementation date:
  ```
  Amended: 2026-10-09: both images patch their OS packages on every build (a deliberate exception to ADR 0006).
  ```
- **Decision:** add two sub-bullets under the existing **Image scan** bullet. Its first two lines stay unchanged.
  ```
    - Both images patch their OS packages at build time on every build: the final stage
      `runtime` of each Dockerfile runs `apk upgrade --no-cache` or `apt-get upgrade -y`, and the
      build step excludes that stage from the `type=gha` layer cache
      (`no-cache-filters: runtime`, ADR 0021), while the build stages stay cached.
    - This unpinned, patch-level OS upgrade is a deliberate exception to the pinning rules of
      ADR 0006: the base images already float by tag, and the upgrade exists to pull in fixes
      without waiting for a new base-image tag.
  ```
- **Consequences:** replace the existing bullet `"Build images" can fail without a code change when a fix for a base-image package is published, ...` with three bullets:
  ```
  - "Build images" can still fail without a code change when Trivy knows a fix that the build
    cannot install: the distro mirror does not carry it yet, `apt-get upgrade` holds it back,
    or the package does not come from the distro repositories (for example the Temurin JRE in
    `/opt/java/openjdk`). A distro fix that is already published no longer causes this,
    because the next build installs it. A merged PR may then not deploy, and one matrix leg
    may already have pushed when the other fails.
  - Builds of the same commit are no longer reproducible over time: each build installs the
    OS package versions current at that moment. The pushed image is still exactly the scanned
    one.
  - A package-mirror outage (Alpine CDN, Ubuntu archive) fails the build, and a bad distro
    update can fail or alter the build without a code change.
  ```
- Nothing else in the ADR changes: Status, Context, Alternatives and the other Consequences stay as they are.

### Not changed
- `/workspace/README.md`: nothing it states becomes false (see Current state).
- `/workspace/docs/adr/0006-ci-validation-and-pinning.md`: the exception is recorded in ADR 0007 (criterion 6), and no ADR 0006 rule is changed.
- `/workspace/docs/adr/0021-self-hosted-ci-runner.md`: not a deviation (see Current state).
- `/workspace/.github/workflows/ci.yml` and the other workflows.

## Tests
Verification is mainly `docker build`, actionlint and Trivy, not unit tests. Run the steps in exactly this order.

Ground rules for the executor:
- Never run `bootRun`, and never connect to the dev Postgres (`postgres:5432`).
- Run only one Gradle process at a time in `backend/`.
- Run the Docker builds one at a time.
- **No git commands at all.** The coordinator does the scope check.
- Run every command from `/workspace` unless stated otherwise.
- Save long build logs to the session scratchpad, not into the repo.

**Step 0: confirm the action input.** Fetch v7's metadata read-only:
```bash
curl -fsSL https://raw.githubusercontent.com/docker/build-push-action/v7/action.yml | grep -n -A2 'no-cache-filters'
```
- Expected: an input `no-cache-filters` ("Do not cache specified stages").
- If it is missing, stop and report.
- If there is no network, note it and rely on Step 2 and the first CI run.

**Step 1: edit both Dockerfiles and the workflow** (Changes 1-3). Do not touch `.trivyignore` or the ADR yet.

**Step 2: workflow lint**, the same check as the `actionlint` job in `ci.yml` and README line 171, using the image pinned in `ci.yml`:
```bash
docker run --rm -v "$PWD:/repo" -w /repo \
  rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color
```
- It must exit 0.
- actionlint checks YAML and expression syntax. It only flags unknown action inputs if its bundled metadata covers `docker/build-push-action@v7`. Step 0 covers the input name either way.

**Step 3: CI-equivalent builds.** `--pull` re-resolves the base tags, as the CI builder does. `--no-cache-filter runtime` is the local equivalent of the workflow's `no-cache-filters: runtime`.
```bash
docker build --pull --no-cache-filter runtime -t pet-frontend frontend
docker build --pull --no-cache-filter runtime -t pet-backend backend
```
- Both must succeed. This also covers the frontend's `npm run build` inside the image.
- In the backend log, the upgrade step shows no debconf or dpkg error, and the "upgraded" count is printed. For a conffile prompt failure, see the fallback in Changes 2.

**Step 4: simulate the CI cache behaviour.** Run each build twice more, with plain progress output.
- a) **With the filter**, the same as CI:
  ```bash
  docker build --pull --no-cache-filter runtime --progress=plain -t pet-frontend frontend 2>&1 | tee <scratchpad>/frontend-filter.log
  docker build --pull --no-cache-filter runtime --progress=plain -t pet-backend backend 2>&1 | tee <scratchpad>/backend-filter.log
  ```
  Expected:
  - The build-stage steps (`RUN npm ci`, `RUN npm run build`, `RUN ./gradlew dependencies ...`, the `bootJar` `RUN`) show `CACHED`.
  - `RUN apk upgrade --no-cache` / `RUN apt-get update && apt-get upgrade -y ...` do **not** show `CACHED`, and they print apk/apt output. This proves criterion 3 locally.
- b) **Negative control, without the filter:** `docker build --progress=plain -t pet-frontend frontend` (and the same for the backend). Expected: the upgrade `RUN` now shows `CACHED`. This shows that the filter is what forces the re-run.
- c) Rebuild once more as in 4a, so the images scanned below come from a filtered, fresh upgrade.

**Step 5: installed package versions (direct proof per CVE).**
```bash
docker run --rm --entrypoint sh pet-frontend -c 'apk list -I 2>/dev/null | grep -E "^(libexpat|pcre2|tiff)-"'
docker run --rm --entrypoint dpkg-query pet-backend -W libssl3t64 openssl openssl-provider-legacy
```
Expected:
- `libexpat >= 2.8.5-r0`
- `pcre2 >= 10.49-r0`
- `libssl3t64`, `openssl` and `openssl-provider-legacy` at `>= 3.5.5-1ubuntu3.6` (where present in the image)
- `tiff` at a version that fixes CVE-2026-4775. **Record the observed `tiff` version in the report.**

**Step 6: scans with all three entries ignored-out (proof scans).** These use the same pinned image and flags as the README, plus `--ignorefile /dev/null`, so the repo `.trivyignore` is **not** applied. All three entries are candidates for removal, so an empty ignore file is exactly ".trivyignore without these entries".
- a) **Gate without suppressions:**
  ```bash
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/repo" -w /repo \
    -e TRIVY_IMAGE_SRC=docker \
    aquasec/trivy:0.70.0@sha256:be1190afcb28352bfddc4ddeb71470835d16462af68d310f9f4bca710961a41e \
    image --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 \
    --ignorefile /dev/null pet-frontend
  ```
  Run the same command with `pet-backend`. How to read the result:
  - Exit 0: no HIGH/CRITICAL even without suppressions.
  - Non-zero exit caused **only** by one of the three suppressed CVEs (CVE-2026-93990, CVE-2026-103111, CVE-2026-84782): this is the expected "entry stays" outcome for that CVE, not a stop condition. That entry is kept, and the rest continues.
  - Non-zero exit caused by any **other** finding (for example CVE-2026-4775 still present, or a new CVE): stop and report to the user. Do not add `.trivyignore` entries (ADR 0007: new suppressions need the user's approval).
- b) **All-severity report:** run the same commands without `--severity` and `--exit-code`, and pipe each through `grep -E 'CVE-2026-(93990|103111|4775|84782)'`. Expect no match. Any match decides that CVE's entry per the rules above.
- **Fallback:** if `--ignorefile /dev/null` misbehaves, drop `-v "$PWD:/repo" -w /repo` so no `.trivyignore` is visible to Trivy.
- **Decision rule:** an entry is removed only if both Step 5 and Step 6 pass for its CVE.

**Step 7: edit `.trivyignore`** (Change 4), then **ADR 0007** (Change 5).

**Step 8: final gating scans with the repo `.trivyignore`.**
- Run exactly the README "Run locally" `docker run ... image ... pet-backend` and `... pet-frontend` commands, against the images from Step 4c. Both must exit 0, which proves criterion 5.
- Also run the README `fs` gate command once. `.trivyignore` is shared with the fs scan, and removing image-only entries must not change its result (it must exit 0).

**Step 9: existing test suites** (project rule: not done until tests pass). The changes do not affect them.
```bash
cd /workspace/backend && ./gradlew test
cd /workspace/frontend && npm test
```

**Step 10: report for the scope check (criterion 7).** The executor runs no git commands. It lists the files it edited, which must be exactly:
- `frontend/Dockerfile`
- `backend/Dockerfile`
- `.github/workflows/build-images.yml`
- `.trivyignore`
- `docs/adr/0007-dependency-locking-and-vulnerability-gate.md`

The plan file `docs/plans/patch-os-packages-in-images.md` is saved by the coordinator. The coordinator does the scope check.

The report also includes:
- the Step 0 result;
- the actionlint result;
- the CACHED / not-CACHED evidence from Step 4;
- the package versions from Step 5, including `tiff`;
- the per-entry decisions from Step 6;
- the exit codes from Step 8;
- the test results.

| Criterion | Proof |
|---|---|
| 1 frontend `apk upgrade` | Dockerfile diff (`AS runtime`, `RUN` right after `FROM`); Step 3 build succeeds; Step 5 shows fixed `tiff`, `libexpat`, `pcre2` |
| 2 backend `apt-get upgrade` | Dockerfile diff (one `RUN`, before `useradd`/`USER 1001`); Step 3 build log; Step 5 shows fixed OpenSSL packages |
| 3 `no-cache-filters` in CI | Step 0 (input exists in v7), Step 2 (actionlint), Step 4a/4b (upgrade re-runs with the filter, build stages `CACHED`; upgrade `CACHED` without the filter). Final proof is the first "Build images" run after merge: the upgrade step is not `CACHED` there, and the build stages are |
| 4 `.trivyignore` removals | Steps 5 and 6 per entry, before the edit |
| 5 no HIGH/CRITICAL | Step 8 (README commands, exit code 0); Step 6a shows the result without suppressions |
| 6 ADR 0007 amended | Diff review: Amended line in the existing format, the two Decision sentences, the revised and the two new Consequences bullets |
| 7 no other changes | Step 10 (coordinator) |

## Risks and open questions
1. **`no-cache-filters` at `docker/build-push-action@v7`.** It is a documented input, but the v7 `action.yml` is not checked into this repo. Step 0 confirms it. actionlint may not know v7's inputs, and an unknown input would only show up as an "Unexpected input" warning in the run log, so also check the first CI run's log.
2. **Longer CI builds.** Every "Build images" run now re-runs the upgrade:
   - apt update plus upgrade takes roughly 20-60 s;
   - apk takes a few seconds;
   - plus the `COPY --from=build` of the runtime stage.

   The build stages stay cached. The `mode=max` cache export also uploads the never-reused runtime layers, which is a few MB per build and stays within the per-service scope.
3. **ADR 0021 is not changed (decision stated, no action needed).** The `type=gha` cache stays, with the same reason and the same inputs. Only reuse of the final stage is turned off, and ADR 0007 records that with a reference to ADR 0021. If you read this as a deviation from ADR 0021, the change would be an extra `Amended:` line in ADR 0021, which criterion 7 currently excludes.
4. **Distro behaviour of `apt-get upgrade`.**
   - The command has no `DEBIAN_FRONTEND`: debconf's Teletype frontend reads EOF and takes the defaults.
   - The only fallback is `-o Dpkg::Options::=--force-confold`, used if a conffile prompt fails the build.
   - `upgrade` is used, not `dist-upgrade`. If the OpenSSL fix is held back, Step 5 shows it, CVE-2026-84782 stays, and the case is reported instead of switching to `dist-upgrade` silently.
5. **The scan result depends on the day's Trivy DB and package repositories.** New CVEs may appear between planning and execution. Any new gating finding is a stop-and-report point (Step 6). No new suppressions without the user's approval.
6. **Local builds differ from CI.** The README's plain `docker build` reuses a cached upgrade layer, so a local scan can show findings that CI no longer has. README is not changed (criterion 7, nothing false). Whether to add `--no-cache-filter runtime` to the README build commands could be a follow-up.
7. **Image size** grows by the size of the upgraded packages, which are duplicated in the upgrade layer. This is minor.
8. **`/dev/null` as an ignore file.** If Trivy 0.70.0 rejects it, use the fallback in Step 6 (no repo mount).
9. **No new dependencies.** `apk`, `apt-get` and the `no-cache-filters` input of the already used action are all existing.

## Out of scope
- Workflow changes beyond `no-cache-filters` in `build-images.yml`, for example a scheduled rebuild that picks up distro fixes without a push.
- README changes (nothing it states becomes false).
- Pinning base images by digest, or changing base image tags.
- Upgrading the Java runtime or nginx beyond what the distro repositories provide.
- New `.trivyignore` entries, and changing the `exp:` dates of entries that stay.
- ADR 0006 and ADR 0021 file changes, and new ADRs.
- Building or scanning images on pull requests.

## Decisions after plan review (approved by the user, 2026-10-09)
These are already incorporated above.
1. The cache busting is part of this card: `no-cache-filters` on the final stage of both images in `build-images.yml`. The final stages are named `runtime`.
2. ADR 0007 gets the two new Consequences bullets, and the existing "Build images can fail without a code change" bullet is revised.
3. The ADR 0006 reading is confirmed. ADR 0007 states the unpinned patch-level OS upgrade as a deliberate exception to ADR 0006.
4. Plan-reviewer suggestions accepted:
   - In Step 6a, a non-zero exit caused only by a retained entry's CVE means "entry stays". Only new findings stop the work.
   - The backend fallback is `-o Dpkg::Options::=--force-confold`, and the debconf wording is corrected (Teletype frontend reading EOF).
   - The executor runs no git commands; the coordinator does the scope check.
   - The observed `tiff` version is recorded in the report.

## Clarifications after the second plan review
These override the text above where they differ.
1. **Step 4a exit status:** run the `docker build ... | tee ...` commands with `set -o pipefail` (or check `${PIPESTATUS[0]}`), so a failed build is never read as evidence.
2. **ADR 0007 revised Consequences bullet:** replace the example "(for example the Temurin JRE in `/opt/java/openjdk`)" with "(for example the frontend's `nginx` package, which comes from the nginx.org repository and is not touched by `apk upgrade`)". Trivy does not report a tarball-installed JRE as a package, so that example was inaccurate.
3. **Changes 3 wording:** in CI the backend `bootJar` `RUN` never hits the cache, because the `gitdir` context (the checkout's `.git`) changes on every commit. That was already the case before this change. Only the local Step 4a (no `gitdir` context) expects it `CACHED`.
4. **Step 4a with `--pull`:** if `node:24-alpine` or `eclipse-temurin:25-jdk` get a new upstream digest between Step 3 and Step 4a, the build-stage steps re-run. Read that as a base-image change, not as a failure of the filter; repeat 4a once to get the `CACHED` evidence.

## Decision during implementation (user, 2026-10-09)
The backend proof scan (Step 6a) found 3 HIGH findings in `/usr/bin/pebble` (Go `stdlib` v1.26.7: CVE-2026-78667, CVE-2026-78669, CVE-2026-97031). The binary ships with the `eclipse-temurin:25-jre` base image (Ubuntu 26.04), belongs to no dpkg package, so `apt-get upgrade` cannot fix it, and the application does not use it (the entrypoint is `java`). The user decided:
1. **`backend/Dockerfile`:** in the same `RUN` line, after `apt-get upgrade`, add `rm -f /usr/bin/pebble`, with a comment: the binary comes from the Ubuntu 26.04 base image, the application does not use it (entrypoint is `java`), it belongs to no dpkg package, so apt does not update it; it is removed so that dead code is not scanned.
2. Re-run the backend proof scan without suppressions (Step 6); expected result: 0 HIGH/CRITICAL.
3. Then continue with the plan: remove all three entries from `.trivyignore` (the file keeps only the convention header), and amend ADR 0007.
4. **ADR 0007:** add one sentence to the Decision (in the Image scan sub-bullets): unused base-image binaries are removed from the image, not suppressed.

## Relevant files
- /workspace/frontend/Dockerfile
- /workspace/backend/Dockerfile
- /workspace/.github/workflows/build-images.yml
- /workspace/.trivyignore
- /workspace/docs/adr/0007-dependency-locking-and-vulnerability-gate.md
- /workspace/docs/adr/0006-ci-validation-and-pinning.md (read, unchanged)
- /workspace/docs/adr/0021-self-hosted-ci-runner.md (read, unchanged; layer cache)
- /workspace/.github/workflows/ci.yml (read, unchanged; actionlint job and pinned image)
- /workspace/README.md (read, unchanged; "Run locally" commands, lines 248-267; actionlint command, line 171)
