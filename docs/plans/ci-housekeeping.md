# Plan: CI housekeeping after the self-hosted runner

Card: https://trello.com/c/9kocV1Yx/24-ci-housekeeping-after-the-self-hosted-runner

## Goal
The runner is a persistent machine, so stop using the GitHub Actions cache in the places the card names: setup-gradle and setup-node in `ci.yml`, and the first Trivy image scan in `build-images.yml`. Gradle and npm then use the runner's local `~/.gradle` and `~/.npm`. The plan also records that decision in ADR 0021, marks ADR 0020 and 0021 accepted, and documents the weekly Docker prune timer and disk-usage checks in README "CI runner". No other behaviour changes.

## Acceptance criteria
- [ ] `build-images.yml`: `cache: "false"` on both Trivy image-scan steps (the DB stays on the host).
- [ ] `ci.yml`: no `actions/cache` for Gradle and npm. Rely on the runner's local `~/.gradle` and `~/.npm`.
- [ ] `actions/setup-java` and `actions/setup-node` do not enable their cache options.
- [ ] Keep `actions/cache` only where the job may land on `ubuntu-latest` (fork PRs). If that makes the workflow messier than it is worth, drop the caches entirely and note the trade-off.
- [ ] The PR description records the before/after wall-clock time of a full CI run and of a backend image build.
- [ ] ADR 0020 and ADR 0021 are marked accepted, in the file status and in the README index.
- [ ] README "CI runner" covers the weekly `docker system prune` timer and how to check disk usage.
- [ ] No other behaviour changes.

## Approved decisions (2026-10-06) — these override the defaults below
- **Q1:** no timer exists on the host. README documents the proposed `docker-prune.timer` / `docker-prune.service` exactly as in Change 7 (`docker system prune --all --force --filter until=168h`, Sunday 04:00). The user installs it on the host after merge. In ADR 0021 Consequences, word it as a recommendation, not a fact: "Host maintenance (OS and Docker updates) is manual. A systemd timer that runs `docker system prune` weekly should be installed by hand (README "CI runner"). The runner application updates itself."
- **Q2:** accepted that the Trivy DB does not persist on the host; no `cache-dir` change.
- **Q3:** (a) yes — add `cache: "false"` to `ci.yml` `dependency-scan` "Scan dependencies (SARIF report)" (after `version: v0.70.0`, with the same comment as in `build-images.yml`). (b) no — `setup-buildx-action` `cache-binary` stays at its default. (c) `type=gha` stays. Consequently, in the ADR 0021 Decision bullet: say "every `trivy-action` step in `ci.yml` and `build-images.yml` has `cache: \"false\"`", drop the "If Q3 is answered no" sentence, and add: "`setup-buildx-action` keeps its default buildx binary cache (`cache-binary`)."
- **Q4:** yes — Change 6 (ADR 0006 line 5) is in scope.
- **README prune wording (reviewer suggestion 1):** replace "An image built in the current job is younger than that, so "Build images" cannot lose an image it has not pushed yet, and the runner service keeps running." with "The timer runs at 04:00 on Sunday, when normally no job runs, and an image built in a running job is normally younger than 7 days, so the runner service can keep running." Keep the rest.
- **Scope check (reviewer suggestion 2):** the diff also contains this plan file, `docs/plans/ci-housekeeping.md`. Expected modified files: the 7 in "Files touched" plus this plan.
- **actionlint (reviewer suggestion 5):** if the local actionlint run reports that no workflow files were found, or the bind mount is empty, that is a failed check, not a pass — report it.

## What the repo has today (findings that shape the plan)
1. **`ci.yml` has no `actions/cache` step at all.** The caches the card means come from two places:
   - `gradle/actions/setup-gradle@v6` (line 68, no inputs). It caches through the Actions cache by default and also checks `gradle-wrapper.jar`.
   - `actions/setup-node@v6` with `cache: npm` and `cache-dependency-path: frontend/package-lock.json` (lines 99-100).
   - `actions/setup-java@v6` (lines 62-65) has no `cache:` input already, so nothing changes there.
   - `frontend/package.json` has no `packageManager` field, so setup-node's automatic caching is not on today.
2. **Trivy in `build-images.yml` has three steps, not two.** "Scan image (table report)" (line 139) and "Fail on HIGH/CRITICAL" (line 179) already have `cache: "false"`. Only "Scan image (SARIF report)" (lines 105-119) uses the action's default `cache: true`. So the actual edit is one line on the SARIF step; after it, all three steps have `cache: "false"`.
3. **Other GitHub Actions cache use the card does not name:**
   - `ci.yml` job `dependency-scan`, step "Scan dependencies (SARIF report)" (line 329): default `cache: true`. **Not in scope by the card's text; open question Q3.**
   - `build-images.yml` `cache-from/cache-to: type=gha` (BuildKit layers). **Out of scope.** The `docker-container` builder is removed at the end of every job (`cleanup: true`), so no BuildKit layers stay on the host. Dropping `type=gha` would make every image build cold. That is a behaviour change, not housekeeping.
   - `docker/setup-buildx-action@v4` with `version:` caches the buildx binary through its `cache-binary` input (default true, confirmed in V0). **Out of scope; open question Q3.**
   - trivy-action passes its `cache` input on to `setup-trivy` (confirmed in V0), which caches the Trivy binary. The SARIF-step edit therefore stops that too.
4. **No runner provisioning files in the repo.** There is no systemd unit or timer, no cron, no script; Glob and Grep for `*.service`, `*.timer`, `infra/runner`, `scripts/` and `prune` found nothing. `docs/plans/self-hosted-ci-runner.md` line 412 explicitly left "automated pruning (cron)" out of scope. Today README only covers manual `docker system prune` (lines 248-256). The README text below is therefore a **proposal** for a timer that does not exist in the repo and is probably not on the host either (Q1).
5. **actionlint is already used.** `ci.yml` job `actionlint` runs `rhysd/actionlint:1.7.12@sha256:b1934ee5...`, `.github/actionlint.yaml` declares the `home` label, and README line 131 gives a local docker command.
6. **Workflow triggers:**
   - `build-images.yml` triggers only on `push` to `development` and `main`. It has no `workflow_dispatch`, and none is added. The "after" timing of the image build can only be measured after merge.
   - `update-deploy.yml` uses no cache and is not touched.
7. **Precedent for accepted ADRs:** ADR 0019 (accepted) has no "becomes accepted once merged" line and no "branch not merged yet" sentence. ADR 0020 and 0021 drop both when they are accepted.

## Decision: fork and Dependabot PRs on ubuntu-latest
Every `ci.yml` job uses the `runs-on` expression (ADR 0021). It sends fork PRs, PRs from a deleted fork and Dependabot PRs to `ubuntu-latest`. Own-branch PRs and pushes go to `[self-hosted, home]`.

**Chosen: (b) drop the Gradle and npm caches entirely.**
- **Option (a)** would be cheap to write. It needs no extra steps, only `cache: ${{ runner.environment == 'github-hosted' && 'npm' || '' }}` and `cache-disabled: ${{ runner.environment != 'github-hosted' }}`, or the same fork/Dependabot condition repeated a second time per job.
- **It would bring almost nothing, because of GitHub's cache scoping.** A PR run can only restore caches saved on its own PR ref, its base branch or the default branch. Every push to `development` and `main` runs on the self-hosted runner, which would save nothing. So a fork or Dependabot PR could only reuse a cache from an earlier run of the same PR. These PRs are rare: they need maintainer approval, and there is no `.github/dependabot.yml`.
- **Option (a) also costs something.** It adds a second, runner-dependent code path to every setup step, and the self-hosted path could not be tested by fork PRs.
- **Trade-off we accept:** every fork or Dependabot PR run downloads all Gradle dependencies and npm packages again. That is roughly 1-3 extra minutes on GitHub-hosted bandwidth, which is free.

## Changes

### 1. `/workspace/.github/workflows/ci.yml`
- **Job `backend`, setup-java (lines 60-65):**
  - No input change.
  - Add one comment line under the existing comment: `# No cache input: Gradle uses the runner's ~/.gradle (ADR 0021).`
- **Job `backend`, setup-gradle (lines 67-68).** Replace the comment and step with:
  ```yaml
      # Validates gradle-wrapper.jar. Its GitHub Actions cache is disabled: the self-hosted
      # runner keeps ~/.gradle between jobs (ADR 0021). Fork and Dependabot PRs on
      # ubuntu-latest start without a Gradle cache.
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-disabled: true
  ```
  The step stays because it still checks the wrapper. Removing the step would also remove that check, which is a behaviour change.
- **Job `frontend`, setup-node (lines 96-100).** Replace with:
  ```yaml
      # No GitHub Actions cache: npm ci uses the self-hosted runner's ~/.npm (ADR 0021).
      # package-manager-cache: false stops setup-node from turning caching on by itself if
      # package.json ever gets a packageManager field. Fork and Dependabot PRs on
      # ubuntu-latest start without an npm cache.
      - uses: actions/setup-node@v6
        with:
          node-version: "24.21.0"       # exact pin (ADR 0006)
          package-manager-cache: false
  ```
  This removes `cache: npm` and `cache-dependency-path`.
- **Nothing else changes:** `runs-on`, jobs, `dependency-scan` (unless Q3 is answered yes), comments elsewhere.
- **If Q3 is answered yes:** in `dependency-scan` "Scan dependencies (SARIF report)", add `cache: "false"` after `version: v0.70.0`, with the same comment as in `build-images.yml` below.

### 2. `/workspace/.github/workflows/build-images.yml`
- **Step "Scan image (SARIF report)" (lines 105-119).** After `version: v0.70.0`, add:
  ```yaml
          # No GitHub Actions cache on the self-hosted runner (ADR 0021): the Trivy DB and
          # binary are neither restored from it nor saved to it.
          cache: "false"
  ```
- Nothing else changes. The table and gate steps already have `cache: "false"`. `type=gha` and setup-buildx stay as they are (Q3).

### 3. `/workspace/docs/adr/0021-self-hosted-ci-runner.md`
This is an amendment made before acceptance, because the card adds a caching rule to a still-proposed ADR. It does not deviate from any existing Decision bullet. The ADR has no caching rule today; Context line 13 lists "Persistent caches for Gradle, npm and Docker layers" only as a motivation.
- **Line 4:** `Status: accepted`. Add a new line 5: `Amended: 2026-10-06, before acceptance: no GitHub Actions cache for Gradle, npm and the Trivy image scan.`
- **Decision:** add after the "Tools are installed per job" bullet:
  ```
  - No GitHub Actions cache for dependencies: `setup-gradle` runs with `cache-disabled: true`
    (it still validates the wrapper), `setup-java` and `setup-node` get no `cache` input and
    `setup-node` has `package-manager-cache: false`, and every `trivy-action` step in
    `build-images.yml` has `cache: "false"`. Gradle and npm use the runner user's `~/.gradle`
    and `~/.npm`. The same steps run without a cache when a job lands on `ubuntu-latest`. A new
    job adds no `actions/cache` or setup-action cache option without saying why host state is
    not enough. The BuildKit layer cache (`type=gha`) in `build-images.yml` stays, because the
    `docker-container` builder is removed at the end of every job.
  ```
  If Q3 is answered no, also add: `The first Trivy scan in ci.yml dependency-scan still uses the action's default cache.`
- **Alternatives considered:** add:
  ```
  - Caches only for jobs on `ubuntu-latest` (a `runner.environment == 'github-hosted'`
    condition on the cache inputs): rejected. Pushes to `development` and `main` run on the
    self-hosted runner and save nothing, so a fork or Dependabot PR could only restore a cache
    saved by an earlier run of the same PR.
  ```
- **Consequences:**
  - Replace "Host maintenance (OS and Docker updates, disk pruning) is manual; the runner application updates itself." with: "Host maintenance (OS and Docker updates) is manual. A systemd timer installed by hand runs `docker system prune` weekly (README "CI runner"). The runner application updates itself."
  - Add: "- Fork and Dependabot PRs download every Gradle and npm dependency on each run."
  - Add: "- `~/.gradle` and `~/.npm` grow on the host. Gradle removes unused cache entries itself; `~/.npm` is cleared by hand (README "CI runner")."
  - Delete line 138, "The status becomes `accepted` once the branch is merged."
- **Trust boundaries:** V0 shows the Trivy `cache-dir` default is `${{ github.workspace }}/.cache/trivy`, inside the workspace, which `build-images.yml` empties at the start of every job. No change to the "Persistent state" list.

### 4. `/workspace/docs/adr/0020-grafana-dashboards-and-read-only-role.md`
- **Line 4:** `Status: accepted`.
- **Lines 11-12:** replace "This decision comes from the plan on branch `feature/grafana-dashboards`, which is not merged yet." with "This decision comes from the plan in `docs/plans/grafana-dashboards.md`." That plan file exists, and PR #35 is merged.
- **Line 94:** delete "The status becomes `accepted` once the branch is merged."
- No other text changes.

### 5. `/workspace/docs/adr/README.md`
- Rows 0020 (line 28) and 0021 (line 29): `proposed` becomes `accepted`. Dates stay the same.

### 6. `/workspace/docs/adr/0006-ci-validation-and-pinning.md` (recommended, see Q4)
- **Line 5:** "Amended: 2026-10-05 for the self-hosted runner (ADR 0021); the amendment is proposed until merged." becomes "Amended: 2026-10-05 for the self-hosted runner (ADR 0021)."
- Reason: that amendment came with PR #38, which is merged, and accepting ADR 0021 would otherwise contradict this line.

### 7. `/workspace/README.md`, section "CI runner"
Replace the "Disk space and `docker system prune`" bullet (lines 248-256) with the bullets below. The rest of the section stays.

````markdown
- **Caches:** Gradle and npm use the runner user's `~/.gradle` and `~/.npm`. The workflows
  use no GitHub Actions cache for them or for the Trivy image scan (ADR 0021), so fork and
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
    7 days ago. Volumes are never pruned. An image built in the current job is younger than
    that, so "Build images" cannot lose an image it has not pushed yet, and the runner
    service keeps running. Old base images (for example `postgres:17`) are pulled again by the
    next job that needs them. Install it once:

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
    `journalctl -u docker-prune.service -n 50` (what the last run reclaimed).
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
````
The runner user's name `gh-runner` comes from ADR 0021. `<runner-dir>` matches the README's existing "in the runner directory" wording.

No backend, frontend, DB migration or config changes. No new dependencies. No new ADR: the caching rule goes into ADR 0021 before it is accepted, and the timer is a host operations note in README plus one consequence line in ADR 0021.

## Tests

**V0 (done by the coordinator, 2026-10-06), upstream action inputs at the pinned versions:**
- `gradle/actions/setup-gradle@v6`: `cache-disabled` exists ("When 'true', all caching is disabled. No entries will be written to or read from the cache.", default false).
- `actions/setup-node@v6`: `package-manager-cache` exists (default true; automatic caching when `packageManager` / `devEngines.packageManager` specifies npm).
- `aquasecurity/trivy-action@ed142fd…` (v0.36.0): `cache` (default `'true'`) is passed to `aquasecurity/setup-trivy` as `cache: ${{ inputs.cache }}` (binary cache) and also gates an `actions/cache` step on `path: ${{ inputs.cache-dir }}` (DB cache). `cache-dir` default is `${{ github.workspace }}/.cache/trivy`.
- `docker/setup-buildx-action@v4`: `cache-binary` exists (default `'true'`).
- Consequence for the card's premise "the DB stays on the host" (Q2): it does not. `build-images.yml` empties the workspace at the start of every job, so with `cache: "false"` each image build downloads the Trivy DB and Java DB fresh in the SARIF step; the table and gate steps in the same job reuse it.

**Local checks (implementer).** Do not run `bootRun` and do not connect to the dev Postgres (postgres:5432). Run only one Gradle process at a time.
1. actionlint, the same image and digest as CI. It must exit 0:
   `cd /workspace && docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color`
2. Cache audit:
   - `grep -nE 'cache' /workspace/.github/workflows/ci.yml /workspace/.github/workflows/build-images.yml`
     - `ci.yml` should show only `cache-disabled: true`, `package-manager-cache: false`, the two existing `cache: "false"` lines in `dependency-scan` (three if Q3 is yes), and comments.
     - `build-images.yml` should show three `cache: "false"` lines, the two `type=gha` lines, and comments.
   - `grep -nE 'cache: npm|cache-dependency-path' /workspace/.github/workflows/*.yml` must return nothing.
3. ADR status:
   - `grep -n '^Status:' /workspace/docs/adr/0020-*.md /workspace/docs/adr/0021-*.md` shows `accepted` twice.
   - `grep -nE '^\| \[002[01]\]' /workspace/docs/adr/README.md` shows `accepted` in both rows.
   - `grep -n 'once the branch is merged\|not merged yet' /workspace/docs/adr/002[01]-*.md` returns nothing.
4. CLAUDE.md test gate (nothing here touches the code, but CLAUDE.md requires green tests):
   - `cd /workspace/backend && ./gradlew test` (Testcontainers, not the dev DB)
   - `cd /workspace/frontend && npm test`
   - `cd /workspace/frontend && npm run build && npm run lint`
   - Never delete `frontend/node_modules`.
5. Scope check, for "no other behaviour changes": only the 7 files listed in Changes are modified (6 if Q4 is no). In the workflow diffs, the only non-comment changes are the 3 input lines (`cache-disabled`, `package-manager-cache`, `cache: "false"`) and the 2 removed setup-node lines.

**CI on the PR (coordinator):**
- The CI run on the PR is green, including the `actionlint` job.
- Backend job log: setup-gradle reports caching as disabled, and nothing is restored or saved.
- Frontend job log: setup-node shows no cache restore, and there is no npm cache save in its post step.

**After merge to `development` (coordinator):** in the first "Build images" run, the "Scan image (SARIF report)" step shows no actions/cache restore or save in its log or post step, and the run is green.

**Timings for the PR description (coordinator).** Measure; nothing is added to the workflows.
- **CI before:**
  - Use the last 2-3 successful `CI` runs on `development` (`--event push`) for commits from fc8ae47 onward, the first self-hosted runs. Earlier runs were on `ubuntu-latest` and are not comparable.
  - Report the total run time (`startedAt` to `updatedAt` from `gh run view <id> --json startedAt,updatedAt,jobs`).
  - Report the job times of `Backend (Gradle)` and `Frontend (Vite)`.
  - Report the step times of `Run gradle/actions/setup-gradle@v6`, `Post Run gradle/actions/setup-gradle@v6`, `Run actions/setup-node@v6` and `Post Run actions/setup-node@v6`.
- **CI after:** the PR's `pull_request` CI run, same metrics. A re-run gives a second sample. Note that with one runner the 6 jobs run one after another, so the total includes queueing.
- **Backend image build before:**
  - Use the last 2-3 successful `Build images` runs on `development` from fc8ae47 onward.
  - Report the job time of `Build backend` and the times of "Scan image (SARIF report)" and its post step.
  - Also report the total run time.
- **Backend image build after:** only after merge, because `build-images.yml` has no `workflow_dispatch` and none is added. Take the first `Build images` run on `development` after the merge, same metrics, and add it to the PR or a PR comment afterwards.

**Mapping of criteria to proof:**
- Trivy `cache: "false"`: checks 2 and 1, plus the post-merge log.
- No Gradle/npm cache: checks 2 and 1, plus the PR CI logs.
- setup-java/setup-node have no cache options: check 2.
- Fork decision: ADR 0021 Alternatives bullet and the workflow comments. It cannot be tested without a real fork PR.
- Timings: coordinator measurement.
- ADRs accepted: check 3.
- README: review of the "CI runner" section.
- No other behaviour changes: check 5, plus green CI and checks 1 and 4.

## Risks and open questions
- **Q1, timer does not exist in the repo (decision needed).** Nothing in the repo defines a `docker system prune` timer or any provisioning, and the runner plan left automated pruning out of scope.
  - The README text above proposes `docker-prune.service` and `docker-prune.timer`, running Sunday 04:00 with `docker system prune --all --force --filter until=168h`.
  - If a timer already exists on the host, the user must give its real unit content and schedule, and the README must match it.
  - If none exists, the user installs it on the host after merge; the implementer cannot.
  - `--all` with `until=168h` over plain `docker system prune`: every build leaves an image tagged with its SHA, which plain prune never removes; the filter keeps anything created in the last 7 days, so the timer is safe while jobs run.
  - **Cost:** base images whose upstream creation date is older than 7 days are pulled again weekly (Docker Hub anonymous limit). There is also a tiny chance of removing an old image between a job's pull and its container start, at Sunday 04:00.
  - **Alternative:** plain `docker system prune --force`. It is safe too, but does not limit image growth.
- **Q2, the card's premise "the DB stays on the host" is false (confirmed by V0).** The default `cache-dir` is under `${{ github.workspace }}`, which `build-images.yml` empties before checkout. Every image build then downloads the Trivy DB and Java DB fresh.
  - That costs about the same as today's cache restore over the home uplink, and nothing is uploaded any more.
  - But there are more DB downloads, so more exposure to rate limits, and ADR 0007 says a failed download fails the job.
  - Making the DB really persist would mean setting `cache-dir` to a host path. That is a behaviour change, and it adds persistent state any job could tamper with under ADR 0021, so it is not planned.
- **Q3, other Actions-cache users not named in the card.** All are out of scope by default ("no other behaviour changes"); each one could be a one-line addition:
  - (a) `ci.yml` `dependency-scan` first Trivy step, default `cache: true`. Same reasoning as the card; recommended to include.
  - (b) `setup-buildx-action` `cache-binary` (default true, confirmed in V0).
  - (c) `type=gha` layer cache. Keep it: without it every image build is cold.
- **Q4, the ADR 0006 "Amended" line.** It still says "the amendment is proposed until merged", and that amendment (PR #38) is merged. The plan updates the line together with accepting ADR 0021. This goes slightly beyond the card's "0020 and 0021".
- **ADR deviation, stated explicitly:** ADR 0021 gets a new Decision bullet (no GitHub Actions cache for dependencies), a new Alternatives bullet and new Consequences, all before it is marked accepted. It is an amendment of a proposed ADR, not a change to an accepted one. No existing Decision bullet changes.
  - Context line 13, "Persistent caches for Gradle, npm and Docker layers", is left as the original motivation. In fact, Docker build layers do not persist on the host, because the builder is removed per job. **Changed after review at the user's request:** the line now reads "Persistent caches for Gradle and npm."
  - ADR 0007 keeps its text. Its alternatives mention DB caching as one reason for choosing the action, but its Decision does not require caching.
- **Timing comparability:**
  - "Before" CI runs are `push` events and "after" is a `pull_request` event; the jobs are the same.
  - On the self-hosted runner, setup-gradle probably skipped its cache restore already, because `~/.gradle` exists (see `docs/plans/self-hosted-ci-runner.md` risk 9). The backend gain may be small, and the image-build Trivy step may even get slower because the DB is downloaded fresh (Q2). Report the numbers as they are.
- **Disk growth:** without the Actions cache nothing changes on the host, since `~/.gradle` and `~/.npm` were already persistent. The README now explains how to check and shrink them.
- **New dependencies:** none.

## Out of scope
- Adding `workflow_dispatch` (or any trigger) to `build-images.yml`. The image-build "after" timing is measured after merge.
- Changing `type=gha`, `setup-buildx-action` `cache-binary` and the `ci.yml` Trivy fs cache (unless Q3 says yes).
- Giving Trivy a persistent `cache-dir` (Q2).
- Isolating `GRADLE_USER_HOME` or the npm cache per job.
- Installing the prune timer on the host, and any other host provisioning.
- Deleting local images after push in `build-images.yml`.
- Rewording the existing README claim that the host keeps "the BuildKit cache", beyond the edited bullet.
- Any change to `update-deploy.yml`, backend, frontend, Helm or monitoring.
- All gh commands, the PR description and the timing measurements; the coordinator does these.

## Files touched
- /workspace/.github/workflows/ci.yml
- /workspace/.github/workflows/build-images.yml
- /workspace/docs/adr/0021-self-hosted-ci-runner.md
- /workspace/docs/adr/0020-grafana-dashboards-and-read-only-role.md
- /workspace/docs/adr/README.md
- /workspace/docs/adr/0006-ci-validation-and-pinning.md (if Q4 is yes)
- /workspace/README.md
