# Plan: Run CI on the self-hosted runner

Card: "Run CI on the self-hosted runner" (https://trello.com/c/DBwGALQ9)
Branch: `feature/self-hosted-ci-runner` (based on `origin/development`). All paths below are relative to the repo root.

## Goal
Every job in `ci.yml`, `build-images.yml` and `update-deploy.yml` runs on the self-hosted runner `[self-hosted, home]`. The one exception is pull requests from forks: their `ci.yml` jobs run on `ubuntu-latest`, chosen by a `runs-on` expression, so no job is duplicated. Each job installs every tool it uses with an explicit, version-pinned step and does not rely on anything preinstalled beyond a documented host baseline. ADR 0006 makes that rule mandatory, and a new proposed ADR 0021 records the runner decision and its trust boundaries.

## Acceptance criteria
From the card:
- [ ] All jobs in `ci.yml`, `build-images.yml` and `update-deploy.yml` use `runs-on: [self-hosted, home]` instead of `ubuntu-latest`. User decision 1 refines this: in `ci.yml`, fork PRs go to `ubuntu-latest` through an expression.
- [ ] Every tool a job needs (card list: Java 25, Node 22, Helm, yq, actionlint, Trivy, kubeconform) is installed by an explicit, version-pinned step (ADR 0006). Nothing relies on tools being preinstalled. See Risk 5 (Node 22 vs 24) and Risk 6 (kubeconform, jq, kustomize).
- [ ] Gradle and npm caches use `actions/cache` as before.
- [ ] The Docker build uses the local daemon. No docker/setup-buildx-action network driver is needed, but buildx is kept for the cache (interpretation in Risk 7).
- [ ] Each job starts with a clean workspace (`actions/checkout` with `clean: true`), because the runner reuses `_work`.
- [ ] README has a short "CI runner" section: where it runs, how to restart the service, and a `docker system prune` note.
- [ ] The repo contains no secrets and no hostnames beyond the runner labels.

Binding user decisions:
- [ ] D1: The repository is public. PRs from forks (final form: decision 3 above, `head.repo.full_name != github.repository`, plus Dependabot PRs) run on `ubuntu-latest`. Everything else runs on `[self-hosted, home]`: own-branch PRs, pushes and `workflow_run`. This is done with a `runs-on` expression and no job duplication. Push and `workflow_run` events evaluate to the self-hosted labels. The label array goes through `fromJSON`. Jobs that can run on `ubuntu-latest` work on both runner types.
- [ ] D2: README says that "Require approval for all external contributors" (Actions → Fork pull request workflows) is already enabled.
- [ ] D3: `update-deploy.yml` moves to self-hosted. A final `if: always()` step deletes the job's working copy in `_work`, including the checked-out deploy repo.
- [ ] D4: New ADR (status `proposed`) on the self-hosted runner, covering the reasons, the fork trade-off and the trust boundaries. The ADR index is updated.
- [ ] D5: ADR 0006 is updated: explicit, version-pinned installation of every tool is mandatory, not just preferred.

## Decisions at plan approval (2026-10-05, binding, override "Risks and open questions")
The user approved the plan with these answers. Where they differ from the sections below, these win.

1. **ADR 0021 Context — reasons for the self-hosted runner** (cost is explicitly *not* a reason):
   - Reliability: on 2026-10-05 GitHub-hosted runners queued for more than 15 minutes, jobs failed on timeouts and deployment stopped.
   - Learning goal of the project: operating an own runner, later ARC (actions-runner-controller) in the cluster.
   - Persistent caches for Gradle, npm and Docker layers.
   - Future: direct access from jobs to the home cluster for e2e tests.
2. **Risk 2: options (a) + (c).**
   - (a) In `update-deploy.yml`: `GIT_CONFIG_GLOBAL: /dev/null` and `GIT_CONFIG_NOSYSTEM: "1"` on the commit/push step; step-level `HOME: ${{ runner.temp }}/empty-home` and `GIT_CONFIG_NOSYSTEM: "1"` on the deploy-repo checkout step (create the directory first if checkout needs it to exist). Host baseline git minimum becomes **2.32** everywhere it appears (plan "Host baseline", README "CI runner", ADR 0006 amendment).
   - (c) Dependabot PRs also go to `ubuntu-latest` (see decision 3 for the final expression).
   - The remaining exposure is recorded in ADR 0021 as an **accepted risk** (write the conditional paragraph in §6 as accepted, listing (a) and (c) as the chosen mitigations and stating they only narrow specific paths). Not chosen: (b), (d), (e) — list them in Alternatives considered as rejected/deferred.
   - Add to ADR 0021 trust boundaries: the runner runs as a dedicated non-root user `gh-runner`.
3. **Fork detection:** use the fail-closed form based on `head.repo.full_name`. Combined with decision 2(c), every `ci.yml` job uses exactly:
   ```yaml
       runs-on: ${{ github.event_name == 'pull_request' && (github.event.pull_request.head.repo.full_name != github.repository || github.event.pull_request.user.login == 'dependabot[bot]') && 'ubuntu-latest' || fromJSON('["self-hosted","home"]') }}
   ```
   Update the §1b truth table, the 1a header comment, README and ADR 0021 accordingly (fork PRs, PRs from a deleted fork and Dependabot PRs → `ubuntu-latest`; own-branch PRs and pushes → self-hosted).
4. Node stays on **24**, pinned to an exact 24.x.y.
5. kubeconform is not installed; jq and standalone kustomize v5.6.0 are installed — confirmed.
6. buildx interpretation (Risk 7) confirmed: keep `setup-buildx-action`, default `docker-container` driver on the local daemon.
7. Exact runtime pins and mandatory SHA-256 verification of downloaded binaries (ADR 0006 amendment) confirmed.
8. Host baseline list (item 8a) accepted, with git >= 2.32.
9. Host is **amd64**, **one** runner instance.
10. Add `timeout-minutes` to every job in all three workflows. Defaults: `backend` 30, `frontend` 15, `build` 30, `update` 10; for jobs that do not have one yet (`helm`, `dashboards`, `dependency-scan`, `actionlint`) use 10 unless a job already sets a value — keep existing values.

Reviewer suggestions — all nine accepted:
- R1. ADR 0002, Consequences: replace "in CI (`ubuntu-latest` provides it)" with the runner's Docker daemon (host baseline, ADR 0021; GitHub-hosted runners provide it for fork/Dependabot PRs).
- R2. `build-images.yml` header and README (the "run in parallel" sentence about the matrix legs): on one runner the legs run one after the other, and with fail-fast a failing first leg cancels the queued second one.
- R3. The comment on the D3 delete step must match where checkout v6 keeps the credential (a file under `$RUNNER_TEMP` included from `.git/config`), consistent with the notes in §3.
- R4. T3: `ubuntu-latest` is expected only in `runs-on:` expressions and comments/README text, not as a literal `runs-on: ubuntu-latest`.
- R5. ADR 0021 states that the repo has no `.github/dependabot.yml`; Dependabot PRs can still come from GitHub security updates if enabled in settings, and they are routed to `ubuntu-latest` anyway (decision 3).
- R6. ADR 0021 (Consequences or Trust boundaries): container actions (`docker://…`) and `container:` jobs run as root and can leave root-owned files in `_work` that the non-root runner's `git clean` cannot remove; today's actionlint step only reads files, so this is a rule for future jobs.
- R7. README "CI runner" stays short: the full list of where pins live moves to ADR 0006; README only links there.
- R8. README "Persistent state" bullet: call it an accepted risk with the chosen mitigations (a) and (c), consistent with ADR 0021.
- R9. git minimum 2.32 everywhere the host baseline appears (same as decision 2).

## Changes

### What was checked (tool inventory)
Every job in the three workflows was read. The table shows what each job runs today, what `ubuntu-latest` currently provides implicitly, and what this plan does about it.

| Workflow / job | Tools it actually uses | Implicit on `ubuntu-latest` today | After this change |
|---|---|---|---|
| `ci.yml` `backend` | git (checkout), JDK 25 (`setup-java`, `"25"` = floating major), Gradle 9.7.1 (wrapper, validated by `setup-gradle`), Docker Engine (Testcontainers: `postgres:17` plus Ryuk), bash | git, Docker Engine, bash | JDK pinned to an exact Temurin 25 patch. Docker and git come from the host baseline. |
| `ci.yml` `frontend` | git, Node 24 (`setup-node`, `"24"` = floating major), npm (bundled with Node), oxlint/tsc/vite/vitest (locked npm deps) | git | Node pinned to an exact 24.x.y. |
| `ci.yml` `helm` | git (two checkouts, one with `sparse-checkout`), Helm v3.22.0 (`azure/setup-helm`, pinned), **yq** (mikefarah v4), grep, bash, network to prometheus-community | git, **yq**, grep | yq installed by a pinned, checksum-verified step. |
| `ci.yml` `dashboards` | git, **jq**, **yq**, **`kubectl kustomize`**, coreutils (`sort`, `uniq`, `basename`), bash `shopt` | git, **jq**, **yq**, **kubectl** | jq, yq and kustomize installed by pinned, checksum-verified steps. |
| `ci.yml` `dependency-scan` | git, Trivy v0.70.0 (via `trivy-action` → `setup-trivy`, which downloads with curl/tar), `actions/cache`, cat | git, curl, tar | Unchanged. Trivy is already pinned through `version:`. curl and tar come from the host baseline. |
| `ci.yml` `actionlint` | Docker Engine (container action `rhysd/actionlint:1.7.12@sha256:…`, shellcheck bundled) | Docker Engine | Unchanged. Docker comes from the host baseline. |
| `build-images.yml` `build` | git, Docker Engine and CLI, **buildx plugin** plus the **BuildKit image** (`setup-buildx-action`, default `docker-container` driver, both floating), Trivy (`TRIVY_IMAGE_SRC=docker`), `docker login`/`docker push`, bash | git, Docker Engine, **buildx plugin** | buildx CLI and BuildKit image pinned through `setup-buildx-action` inputs. |
| `update-deploy.yml` `update` | bash, **git** (config, commit, `pull --rebase`, push; really required), **yq**, cat | git, **yq** | yq installed by a pinned, checksum-verified step. git comes from the host baseline. |

No job uses kubeconform, unzip, or Java/Node outside `backend`/`frontend`. JavaScript actions run on the runner's bundled Node, not on `setup-node`.

**Host baseline.** The runner host must provide the items below. The workflows do not install or pin them, and README "CI runner" and the amended ADR 0006 document them:
- Ubuntu LTS on x86_64 (amd64). Images are single-arch amd64 (ADR 0004), and all downloaded binaries are `linux_amd64`.
- The GitHub Actions runner application, registered at repository level with the custom label `home` (plus the default `self-hosted`, `Linux`, `X64`), and running as a systemd service under a dedicated non-root user.
- Docker Engine (rootful daemon on `/var/run/docker.sock`) and the docker CLI, with the runner user in the `docker` group. This is needed by:
  - Testcontainers in `backend` (ADR 0002);
  - the actionlint container action;
  - the BuildKit builder;
  - `docker push`;
  - Trivy's image scan.
- git >= 2.32 (`GIT_CONFIG_GLOBAL`, decision 2(a)). Without git, `actions/checkout` falls back to a REST tarball. That breaks `sparse-checkout` in `helm` and every git command in `update-deploy.yml`.
- bash, coreutils (`sha256sum`, `find`, `sort`, `uniq`, `basename`), grep, curl, ca-certificates, tar, gzip, xz-utils.
- Outbound HTTPS to GitHub (including `objects.githubusercontent.com` and the cache service), Docker Hub, ghcr.io, Maven Central/Gradle, the npm registry, get.helm.sh, nodejs.org/Adoptium mirrors (via the setup actions) and prometheus-community.github.io. No inbound access is needed.

Everything else is installed by the job itself.

**Where `runs-on` can read values from.** According to GitHub's context-availability table, `jobs.<job_id>.runs-on` can read only `github`, `needs`, `strategy`, `matrix`, `vars` and `inputs`. It cannot read `env`, so a workflow-level `env` cannot hold the expression. The options for a shared definition were:
- (a) Repeat the expression per job. **Chosen.** The user allowed it explicitly, it is plain YAML, and actionlint checks it.
- (b) A YAML anchor on the first job's `runs-on`. GitHub Actions added anchor support in 2025, but neither that support nor actionlint 1.7.12's handling of anchors could be verified while planning, and anchors would be a new construct in this repo. Listed in Risk 3.
- (c) A repository variable (`vars.*`). Rejected: the configuration would live outside the repo.
- (d) A "pick runner" job that the other jobs `needs`. Rejected: it adds a job, latency and its own `runs-on`.

### 1. `.github/workflows/ci.yml` (modify)

**1a. Header comment.** Add a paragraph on runner selection: self-hosted `[self-hosted, home]` for everything except PRs from forks, which go to `ubuntu-latest`. Then explain:
- why the expression is repeated per job (`runs-on` cannot read `env`);
- that on push events `github.event.pull_request` is absent, so the condition is false and the self-hosted labels are used;
- **that this expression is not a security boundary.** A fork PR runs its own copy of this file and can change `runs-on`. The boundary is the required approval for external contributors (ADR 0021).

**1b. `runs-on` of all six jobs** (`backend`, `frontend`, `helm`, `dashboards`, `dependency-scan`, `actionlint`) becomes, verbatim:
```yaml
    runs-on: ${{ github.event_name == 'pull_request' && (github.event.pull_request.head.repo.full_name != github.repository || github.event.pull_request.user.login == 'dependabot[bot]') && 'ubuntu-latest' || fromJSON('["self-hosted","home"]') }}
```
Truth table (`cond && a || b` is safe here because `'ubuntu-latest'` is truthy):

| Event | Condition | Result |
|---|---|---|
| `pull_request` from a fork | `head.repo.full_name != github.repository` | `ubuntu-latest` |
| `pull_request` from a deleted fork | `head.repo` is null, so `null != github.repository` is true (fail-closed) | `ubuntu-latest` |
| `pull_request` from Dependabot | `user.login == 'dependabot[bot]'` | `ubuntu-latest` |
| `pull_request` from a branch of this repo | both false | `["self-hosted","home"]` |
| `push` to development/main | `github.event_name != 'pull_request'` | `["self-hosted","home"]` |

Remove the old `# GitHub runners have Docker, so Testcontainers works` comment on `backend`. Keep the existing comment that Testcontainers needs the runner's Docker daemon, and add "(host baseline, README "CI runner")".

**1c. Workflow-level `env` with the tool pins used by more than one step or job.** The pins live here so each one exists once in this file, and steps can read workflow-level `env`:
```yaml
env:
  # Pinned CLI tools (ADR 0006): version plus SHA-256 of the linux amd64 release asset,
  # taken from the publisher's checksum file. Bump version and checksum together.
  YQ_VERSION: v4.<x>.<y>          # mikefarah/yq, newest v4 at implementation time
  YQ_SHA256: <sha256 of yq_linux_amd64>
  JQ_VERSION: 1.<x>.<y>           # jqlang/jq, newest release at implementation time
  JQ_SHA256: <sha256 of jq-linux-amd64>
  KUSTOMIZE_VERSION: v5.6.0       # same version as the README's local kustomize command
  KUSTOMIZE_SHA256: <sha256 of kustomize_v5.6.0_linux_amd64.tar.gz>
```

**1d. Checkouts.** Every `actions/checkout@v6` step gets `clean: true`. That is the action's default, but it is set explicitly as the card requires, with one comment per job: "the self-hosted runner reuses `_work`". This applies to `backend`, `frontend`, both checkouts in `helm` (the `deploy-values` one included), `dashboards`, `dependency-scan` and `actionlint`.
- `checkout` runs `git clean -ffdx` plus `git reset --hard` on an existing repo, and empties the directory otherwise. The double `-f` also removes a stale nested `deploy-values/` repo before the second checkout recreates it.
- `backend` and `frontend` also get `persist-credentials: false`. ADR 0006 already requires it, these two checkouts predate that rule, and on a persistent host the token should not sit in `.git/config` while Gradle/npm code runs. Neither job uses git credentials later.

**1e. `backend`.** `actions/setup-java@v6` gets `java-version` set to an exact Temurin 25 version (for example `"25.0.<n>"`, the newest GA patch, verified in T0) instead of `"25"`. `gradle/actions/setup-gradle@v6` is unchanged and keeps caching through the GitHub Actions cache (AC "as before"; see Risk 9 for how it behaves when `~/.gradle` already exists on the persistent host).

**1f. `frontend`.** `actions/setup-node@v6` gets `node-version` set to an exact `"24.<x>.<y>"` (newest 24 LTS patch, verified in T0). Node stays on 24; see Risk 5. `cache: npm` and `cache-dependency-path` are unchanged.

**1g. `helm`.** After the checkouts and before `setup-helm`, add an "Install yq (pinned)" step. The same snippet pattern is used for every direct download in this plan:
```yaml
      # The self-hosted runner is a bare machine (ADR 0006): install yq explicitly. RUNNER_TEMP is
      # emptied by the runner at the start and end of every job, so nothing persists on the host.
      # GITHUB_PATH prepends, so on ubuntu-latest this binary wins over the preinstalled yq.
      - name: Install yq (pinned)
        run: |
          bin="${RUNNER_TEMP}/bin"
          mkdir -p "${bin}"
          curl -fsSL --retry 3 -o "${bin}/yq" \
            "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64"
          echo "${YQ_SHA256}  ${bin}/yq" | sha256sum -c -
          chmod +x "${bin}/yq"
          "${bin}/yq" --version
          echo "${bin}" >> "$GITHUB_PATH"
```
Rewrite the "Render kube-prometheus-stack" comment: it no longer says yq is "preinstalled on GitHub-hosted ubuntu-latest runners", but that yq comes from the step above. The `yq`/`helm` commands are unchanged.

**1h. `dashboards`.** Add three install steps, in the same pattern and before the validation steps:
- **"Install jq (pinned)"**: `https://github.com/jqlang/jq/releases/download/jq-${JQ_VERSION}/jq-linux-amd64`, checked with `JQ_SHA256`, saved as `${bin}/jq`.
- **"Install yq (pinned)"**: the same step as in 1g.
- **"Install kustomize (pinned)"**: `https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${KUSTOMIZE_VERSION}/kustomize_${KUSTOMIZE_VERSION}_linux_amd64.tar.gz`. Verify the tarball's SHA-256, then run `tar -xzf … -C "${bin}" kustomize`.

In "Render dashboard ConfigMaps (kustomize)", change `kubectl kustomize infra/monitoring/dashboards` to `kustomize build infra/monitoring/dashboards`. This installs one small, pinned tool instead of all of kubectl, and it is the same version as the README's local command (`registry.k8s.io/kustomize/kustomize:v5.6.0`). The alternative of installing pinned kubectl and keeping the command is in Risk 6. Update the comment "jq, yq (mikefarah v4) and kubectl are preinstalled…" to describe the install steps. The jq/yq logic is unchanged.

**1i. `dependency-scan`, `actionlint`.** Only `runs-on` and `clean: true` change. Trivy is already pinned (`version: v0.70.0`), and actionlint by tag and digest.

Unchanged: triggers, `permissions`, `concurrency`, the step logic, `upload-artifact@v5`, and the SARIF guard.

### 2. `.github/workflows/build-images.yml` (modify)
- `runs-on: [self-hosted, home]`, a literal value. This workflow only runs on `push` to `development`/`main`, so it never runs fork code. Add a short comment saying so.
- `actions/checkout@v6`: add `clean: true` and `persist-credentials: false`. No later step uses git credentials; `docker/login-action` uses `GITHUB_TOKEN` itself.
- Added after code review (user decision B): a step "Empty the workspace before checkout" (`find "${GITHUB_WORKSPACE:?}" -mindepth 1 -delete`) runs before the checkout, as in `update-deploy.yml`. `clean: true` keeps `.git/config` and `.git/hooks`, so a `.git` planted by an earlier job would otherwise run during checkout with the `packages: write` `GITHUB_TOKEN`.
- `docker/setup-buildx-action@v4`: keep it, with the default `docker-container` driver. That driver runs BuildKit as a container **on the local Docker daemon**, and it is needed for `cache-from/cache-to: type=gha`, which the plain `docker` driver cannot export. `load: true` puts the image into the local daemon, where Trivy (`TRIVY_IMAGE_SRC=docker`) and `docker push` use it. No remote, kubernetes or network driver options are added. Under the amended ADR 0006, add:
  - `version: v0.<x>.<y>`: pins the buildx CLI plugin that the action installs. The host does not need the docker-buildx package.
  - `driver-opts: image=moby/buildkit:v0.<x>.<y>@sha256:<digest>`: pins the BuildKit image by tag and digest, as ADR 0006 requires for tool images.
  - Keep the action's default `cleanup: true`, which removes the builder container at the end of the job. Add a comment explaining all three.
- `docker/login-action@v4`: keep the default `logout: true` and add a comment that it removes the GHCR credential from the runner user's `~/.docker/config.json` at the end of the job (persistent host).
- Header comment: one line saying the build runs on the self-hosted runner's Docker daemon, and that the two matrix legs run one after the other if only one runner instance is online.

### 3. `.github/workflows/update-deploy.yml` (modify)
- `runs-on: [self-hosted, home]`, a literal value. `workflow_run` only fires after "Build images", which is push-only and filtered to `development`/`main`, so no fork code runs here. `DEPLOY_REPO_TOKEN` is not available to forks anyway. Add a comment.
- Deploy repo checkout: add `clean: true`. Everything else stays as ADR 0004 defines it: `persist-credentials: true`, `fetch-depth: 0`, the token.
- New step "Install yq (pinned)" before "Set image tags", using the snippet from 1g with step-level `env` `YQ_VERSION`/`YQ_SHA256`. These must equal the values in `ci.yml`; a comment says so, and README lists both places. Remove the comment "yq v4 (mikefarah) is preinstalled on ubuntu-latest runners".
- New final step (D3):
```yaml
      # The self-hosted runner is persistent: delete this job's working copy (the deploy repo
      # checkout and its git config, which held DEPLOY_REPO_TOKEN as an extra header) so no
      # token-bearing state survives in _work. always(): also after a failure or cancellation.
      # The workspace directory itself stays; the runner expects it to exist.
      - name: Delete the working copy
        if: always()
        run: find "${GITHUB_WORKSPACE:?}" -mindepth 1 -delete
```
- Added in code review round 1: the same `find … -delete` also runs as a step **before** the deploy-repo checkout. `clean: true` keeps `.git/config` and `.git/hooks`, and checkout reuses an existing `.git` whose `remote.origin.url` matches, so a `.git` planted by an earlier job (hooks, repo-local `url.*.insteadOf`/`http.proxy`) would otherwise see `DEPLOY_REPO_TOKEN`.
  Notes:
  - `:?` aborts if the variable were ever empty, so the step cannot delete from `/`.
  - `find -delete` removes read-only git object files, because only directory permissions matter.
  - The step runs before `actions/checkout`'s post step. Upstream, that post step returns early when `<path>/.git/config` does not exist, so it should not fail. Confirm this on the first real run (T9).
  - `checkout` v6 keeps the persisted credential in a file under `$RUNNER_TEMP`. Because this step deletes `.git/config` first, checkout's post step returns early and does not remove that file; the runner empties `$RUNNER_TEMP` at the end of every job. Added after code review (user decision): the step also deletes checkout's credential files from `$RUNNER_TEMP` explicitly, so the file is gone at the end of the job's own steps instead of relying on the runner's later `_temp` cleanup.

### 4. `.github/actionlint.yaml` (new)
```yaml
# Custom label of the self-hosted runner (ADR 0021); without this actionlint reports
# 'label "home" is unknown' for runs-on: [self-hosted, home].
self-hosted-runner:
  labels:
    - home
```
actionlint picks this file up automatically, both in the CI container action (repo mounted as the workspace) and with the README's local `docker run` command. This deliberately reverses a note in `docs/plans/helm-lint-actionlint-ci.md` ("No `.github/actionlint.yaml`… there are no self-hosted runner labels"). That plan is historical and stays unchanged.

### 5. `README.md` (modify)
- **"CI and Docker images"**: in the dashboards description, change "`kubectl kustomize` renders it" to "`kustomize` (pinned v5.6.0) renders it". Add one sentence: "All jobs run on the self-hosted runner; see "CI runner"."
- **New section "CI runner"** after "CI and Docker images", short, with no hostnames, IPs or secrets:
  - **Where it runs:** a self-hosted GitHub Actions runner on a home machine, labels `self-hosted` and `home`. It runs every job of `ci.yml`, `build-images.yml` and `update-deploy.yml`. Pull requests from forks run `ci.yml` on GitHub-hosted `ubuntu-latest` instead (the `runs-on` expression).
  - **Fork PRs:** the repository setting "Require approval for all external contributors" (Settings → Actions → General → Fork pull request workflows) is enabled. A fork PR can edit the workflow files, including `runs-on`, so before approving a run, check its changes under `.github/`.
  - **Host baseline:** the list from "What was checked" (Ubuntu x86_64, runner as a systemd service under a dedicated user, Docker Engine with that user in the `docker` group, git >= 2.32, bash, coreutils, curl, ca-certificates, tar, gzip, xz-utils). Testcontainers needs the rootful Docker socket. Everything else is installed by the jobs with pinned versions. The pins live in:
    - `ci.yml` (`setup-java`/`setup-node` versions, the workflow `env` block, `setup-helm`, Trivy, actionlint);
    - `build-images.yml` (buildx and BuildKit);
    - `update-deploy.yml` (yq, same values as `ci.yml`);
    - the README's local commands.
  - **Restart the service:** in the runner directory, `sudo ./svc.sh status`, `sudo ./svc.sh stop`, `sudo ./svc.sh start`. Or use `sudo systemctl restart actions.runner.<owner>-<repo>.<runner-name>.service`; the placeholders stay placeholders. Jobs wait in "Waiting for a runner…" while the service is down. Fork PRs are not affected.
  - **Disk space and `docker system prune`:**
    - The host keeps Docker images (base images, the Testcontainers `postgres:17` and Ryuk images, built images), the BuildKit cache, the tool cache (`_work/_tool`), `~/.gradle` and `~/.npm`.
    - `docker system prune` removes stopped containers, dangling images, unused networks and the build cache.
    - `docker system prune -a` also removes cached base images, so the next runs pull them again from Docker Hub, which rate-limits anonymous pulls per IP.
    - Only prune when no job is running: stop the service first. `-a` can delete an image that "Build images" has built but not yet pushed.
  - **Persistent state:** jobs start with a clean checkout (`clean: true`), and `update-deploy.yml` deletes its working copy at the end. Everything else on the host persists between jobs: the runner user's whole home directory (`~/.gradle`, `~/.npm`, `~/.gitconfig`, `~/.docker/`, `~/.local/`), `_work/_tool`, and Docker images, volumes and build cache. Any job can change these, and later jobs use them. Those later jobs include "Build images" (GHCR push token) and "Update deploy manifests" (`DEPLOY_REPO_TOKEN`). This is an accepted risk; see ADR 0021 "Trust boundaries". To reset it, stop the service, wipe the runner user's home caches and `_work/_tool`, and prune Docker.

### 6. `docs/adr/0021-self-hosted-ci-runner.md` (new, status `proposed`, date 2026-10-05)
Title: "Self-hosted CI runner, fork pull requests on GitHub-hosted runners". It uses the ADR template sections:
- **Context:** the reasons for moving CI to a home machine (**the user has to supply them, see Risk 1**). The repository is public. The workflows relied on tools preinstalled on `ubuntu-latest`. The job that holds `DEPLOY_REPO_TOKEN` (ADR 0004).
- **Decision:**
  - `runs-on: [self-hosted, home]` for every job.
  - `ci.yml` (the only `pull_request` workflow) uses the per-job expression that sends fork PRs to `ubuntu-latest`. Every future job triggered by `pull_request` must use the same expression.
  - `pull_request_target` is never used.
  - `workflow_run` on the self-hosted runner may only follow push-only workflows.
  - Approval for all external contributors is required and stays enabled.
  - Every checkout uses `clean: true`. Jobs that handle a write token delete their working copy in an `if: always()` final step.
  - Tools are installed per job (ADR 0006). The host provides only the documented baseline.
  - `.github/actionlint.yaml` declares the `home` label.
- **Trust boundaries:**
  - **Whose code runs on the runner:** more than the repository owner's code. Every own-branch PR and every push to `development`/`main` runs third-party code with the runner user's rights:
    - `npm ci` lifecycle scripts of all transitive npm dependencies;
    - Gradle plugins, dependencies and test code;
    - the setup actions, and the container images (actionlint, BuildKit, Testcontainers' `postgres:17` and Ryuk).

    Dependabot PRs add dependency versions that nobody has reviewed yet. `workflow_run` runs `main`'s workflow files. Fork PRs are excluded only as long as a maintainer reviews `.github/` changes before approving them. The `runs-on` expression lives in the PR's own copy of the workflow, so it is a routing rule, not a security boundary.
  - **What a job can reach:** the Docker daemon (group `docker` = root-equivalent on the host) and the home network (k3d cluster API, other LAN devices). Code in any job can therefore take over the host, including the runner application itself. The host must hold no kubeconfig and no other long-lived credentials beyond the runner's own registration files.
  - **Secrets on the runner:**
    - the per-job `GITHUB_TOKEN`;
    - in `build-images.yml`, a `GITHUB_TOKEN` with `packages: write` and `security-events: write`, plus the GHCR login (logged out in the post step);
    - `DEPLOY_REPO_TOKEN`, only in `update-deploy.yml`. It writes to the repository Argo CD deploys to `prod`, and the job deletes its working copy at the end.
  - **Persistent state:**
    - the runner user's whole home directory, including:
      - `~/.gitconfig` (`url.*.insteadOf`, `http.proxy`, `http.sslVerify`, `core.hooksPath`, `credential.helper`);
      - `~/.gradle` (init scripts, caches);
      - `~/.npm`;
      - `~/.docker/` (`config.json`, the buildx plugin in `cli-plugins/`);
      - `~/.local/bin` (Trivy from `setup-trivy`);
    - `/etc/gitconfig` and other host files, reachable through Docker;
    - `_work/_tool` (JDK, Node, Helm);
    - `_work` (cleaned at job start);
    - Docker images, volumes and build cache.

    Any job can poison this state, and later jobs consume it, including `build-images.yml` and `update-deploy.yml`. Third-party code that runs in **any** job on this runner, including an own push, can therefore lead to theft of `DEPLOY_REPO_TOKEN` (and with it a deploy to `prod`) and of the GHCR push token. Examples: a rewritten `url.insteadOf` or proxy in `~/.gitconfig`, a replaced `docker-buildx` or JDK, or a process left running on the host.
  - **Accepted risk (conditional on the user's decision, plan Risk 2):** _[The implementer writes this paragraph only after the user decides. If the user accepts the risk, it reads roughly:]_ "We accept this exposure because only the owner can push, dependency versions are pinned by lockfiles, and dependency changes are reviewed in PRs. The cost of a compromise is limited to this pet project's images and its local cluster. It is revisited if collaborators are added or if a dependency compromise affects this project." Any mitigations the user chooses in Risk 2 are listed here as part of the decision. They narrow specific paths, but they do not make the host trustworthy after a compromise. Only GitHub-hosted (ephemeral) runners for the token-bearing jobs, or an ephemeral self-hosted runner, remove the exposure.
- **Alternatives considered:**
  - All jobs on self-hosted, including forks: rejected, because it runs arbitrary public code on a home machine.
  - Duplicated jobs per runner type: rejected, twice the YAML.
  - The expression in a repository variable, a "pick runner" job, or YAML anchors (see "Where `runs-on` can read values from").
  - Ephemeral or containerised runners (`--ephemeral`, actions-runner-controller): not now.
  - Routing Dependabot PRs to `ubuntu-latest`: pending, plan Risk 2. It reduces but does not remove the supply-chain exposure, because own pushes run third-party code too.
  - Running the token-bearing jobs (`build-images.yml`, `update-deploy.yml`) on GitHub-hosted runners: pending, plan Risk 2. It conflicts with user decision D3 as it stands.
  - Isolating git config, Gradle and npm state per job: pending, plan Risk 2.
- **Consequences:**
  - With one runner instance, `ci.yml`'s six jobs and the two matrix legs run one after another, so wall-clock time grows.
  - Host maintenance (OS and Docker updates, disk pruning) is manual; the runner application updates itself.
  - When the runner is offline, own PRs and pushes queue (and fail after 24 h), while fork PRs still run.
  - Changes to `update-deploy.yml` take effect only once they reach `main` (ADR 0004).
  - The status becomes `accepted` once the branch is merged.

### 7. `docs/adr/0006-ci-validation-and-pinning.md` (modify, D5)
The ADR is edited in place, as earlier ADRs were. Add under `Status: accepted` the line `Amended: 2026-10-05 for the self-hosted runner (ADR 0021); the amendment is proposed until merged.`
- **Decision → Pinning:** replace the "This clarifies the rule: CLI tools preinstalled on the GitHub-hosted runner image (jq, yq, kubectl…) may be used… without separate pinning" sentence with a mandatory rule:
  - Every tool a job runs is installed by an explicit, version-pinned step in that job. The step is one of:
    - a setup action with a pinned version input (`setup-java`, `setup-node`, `azure/setup-helm`, `trivy-action` `version:`, `setup-buildx-action` `version:`);
    - a container image pinned by tag and digest (actionlint, BuildKit);
    - a direct download of a release binary pinned by version and verified against a SHA-256 committed in the workflow, taken from the publisher's checksum file (yq, jq, kustomize).
  - Language runtimes are pinned to an exact patch.
  - Nothing preinstalled on a runner may be relied on, except the host baseline in README "CI runner" (bash, coreutils, grep, git, curl, ca-certificates, tar/gzip/xz, Docker Engine). The same rule applies on GitHub-hosted and self-hosted runners.
- **Tool versions list:** add the exact Java and Node versions, yq, jq, kustomize v5.6.0, buildx and BuildKit.
- **Alternatives considered:** add "Relying on tools preinstalled on `ubuntu-latest`: they are missing on the bare self-hosted runner and their versions float."
- **Consequences:** add "Adding a tool means adding an install step with version and checksum. A bump updates the version and the checksum together, in every workflow that installs it."

### 8. `docs/adr/README.md` (modify)
Add the row `| [0021](0021-self-hosted-ci-runner.md) | Self-hosted CI runner, fork pull requests on GitHub-hosted runners | proposed | 2026-10-05 |`. The 0006 row is unchanged; it stays `accepted`.

### Not changed
- No backend, frontend, Gradle, npm, Dockerfile, Helm chart or DB migration changes.
- No changes to ADR 0002, 0004 or 0007 (no deviation: Testcontainers still uses the runner's Docker, the deploy flow is the same, Trivy is the same).
- No secrets, hostnames or IPs anywhere.

## Tests
This is CI configuration, so there are no unit tests. The proof is local verification commands, run in the dev container with Docker, plus post-push checks on GitHub. Do not run `bootRun`, and do not touch the dev Postgres. Run Gradle one invocation at a time.

| # | Check | How | Proves |
|---|---|---|---|
| T0 | Every pin exists and every checksum is real | Read-only lookups (no git):<br>- `gh api repos/mikefarah/yq/releases/latest`, `gh api repos/jqlang/jq/releases/latest`, `gh api repos/kubernetes-sigs/kustomize/releases` (find `kustomize/v5.6.0`), `gh api repos/docker/buildx/releases/latest`, `gh api repos/moby/buildkit/releases/latest`.<br>- Temurin 25 GA patch from `https://api.adoptium.net/v3/assets/latest/25/hotspot`. Node 24 patch from `https://nodejs.org/dist/index.json`.<br>- Download each linux amd64 asset into a scratch dir, run `sha256sum`, and compare with the publisher's checksum file (`checksums` + `checksums_hashes_order` for yq, `sha256sum.txt` for jq, `checksums.txt` for kustomize).<br>- BuildKit digest: `docker buildx imagetools inspect moby/buildkit:v0.<x>.<y>`. | Pinning (ADR 0006), no "unable to resolve" or checksum failures |
| T1 | actionlint is clean, including the new expression and labels | `docker run --rm -v /workspace:/repo -w /repo rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667 -color` exits 0. This also proves YAML validity of all three workflows and shellchecks the new `run:` blocks. | runs-on criteria, D1, D3 |
| T2 | `.github/actionlint.yaml` is effective | In a scratch copy (`cp -r /workspace/.github $S/wf/`), delete `actionlint.yaml` and run T1 there: it must report the unknown label `home`. With the file present: clean. | §4 |
| T3 | Expression shape | Read-through against the truth table in §1b. `grep -n 'runs-on' .github/workflows/*.yml` shows six identical expressions in `ci.yml` and the literal `[self-hosted, home]` in the other two. `grep -n ubuntu-latest .github/workflows/*.yml` matches only inside those expressions. | D1, card AC1 |
| T4 | Install steps work on a bare Ubuntu and the tools do their job | In `ubuntu:24.04` (no curl, no jq), install only the baseline and run the real snippets and checks: `docker run --rm -v /workspace:/repo:ro -w /repo ubuntu:24.04 bash -c '…'`. Inside: `apt-get install -y curl ca-certificates`; set `RUNNER_TEMP=$(mktemp -d)`, `GITHUB_PATH=$(mktemp)` and the `*_VERSION`/`*_SHA256` values; paste the yq, jq and kustomize install steps verbatim; prepend `$(cat $GITHUB_PATH)` to `PATH`; then run the `dashboards` job's two `run:` blocks and the `yq -e` checks from `helm` against the mounted repo. Everything exits 0. Negative test: one wrong hex digit in a `*_SHA256` makes `sha256sum -c` fail. | Card AC2 (bare machine), D1 consequence (works on both runner types) |
| T5 | update-deploy yq logic with the pinned yq | In the same container: download `envs/dev/values.yaml` from `raw.githubusercontent.com/Katran1990/pet-project-deploy/main/` to a scratch path (read-only, no git). Run the `yq -i … strenv(TAG)` line and the password check against it, both exit 0. Never push or commit to the deploy repo. | §3 |
| T6 | Helm and dashboards are unaffected | The README commands: `alpine/helm:3.22.0 lint … --namespace dev`, `helm template` for dev and prod with the downloaded values, and the kube-prometheus-stack render. Plus `docker run --rm -v /workspace:/w -w /w registry.k8s.io/kustomize/kustomize:v5.6.0 build infra/monitoring/dashboards > /dev/null`. All exit 0. | No regression (chart and dashboards untouched) |
| T7 | No secrets or hostnames | `grep -nE '([0-9]{1,3}\.){3}[0-9]{1,3}' .github/ README.md docs/adr/0021-*` has no hits in new text. Review the diff for hostnames, user names and paths of the real machine. No placeholder (`<x>`, `<sha256 …>`, `<digest>`) is left in any workflow. | Card AC7 |
| T8 | Backend and frontend suites | Not affected: no code, dependency or build-script change. CLAUDE.md requires passing tests before a task is done, so run `cd backend && ./gradlew test` and `cd frontend && npm test` once each as a regression gate. Testcontainers uses the dev container's Docker. Do not run `npm ci`. | CLAUDE.md |
| T9 | Real runs (only possible after push; the user does the git and PR steps) | The runner must be online with label `home` **before** the PR is opened, because the PR's own `ci.yml` already runs on it. Then:<br>(1) Own-branch PR: all six jobs show the home runner in "Set up job". The install steps print the pinned versions. `backend` passes with Testcontainers. A second run's checkout log shows the workspace being cleaned.<br>(2) After merge to `development`: "Build images" runs on the runner, the scan and the push succeed, and the BuildKit image is the pinned digest.<br>(3) `update-deploy.yml` only changes after it reaches `main` (ADR 0004). Before that, `main`'s old copy runs on `ubuntu-latest`. After the first run from `main`: the delete step ran, the checkout post step did not fail, and `ls -A` of the job's `_work/<repo>/<repo>` on the host is empty.<br>(4) Fork PR (for example from a second account): it waits for approval, then runs on `ubuntu-latest`, and all six jobs pass there. | D1, D3, card AC1/3/4/5 |

Locally the task is done when T0–T8 pass. T9 can only be checked on GitHub.

## Risks and open questions

### Decisions needed before implementation
1. **Reasons for ADR 0021 (blocks its Context section).** The card and the decisions do not say why CI moves to a home machine. Note that GitHub-hosted runners are free and unlimited for public repositories, so cost is not a reason. Candidate reasons for you to confirm or replace: persistent caches (Gradle, npm, Docker layers, Testcontainers images), faster hardware, or a later step toward jobs that reach the home cluster. I will not invent the reasons.
2. **Third-party code on the persistent runner can steal `DEPLOY_REPO_TOKEN` and the GHCR push token (decision needed).**
   - **Scope.** This is not only about Dependabot. Every own-branch PR and every push to `development`/`main` runs third-party code on the runner: `npm ci` lifecycle scripts of all transitive dependencies, Gradle plugins and dependencies, test code, and the setup actions and container images. Dependabot PRs (branches in this repo, `fork == false`, so self-hosted under D1) add dependency versions that nobody has reviewed yet.
   - **Path to the tokens.** The runner user is in the `docker` group, which is root-equivalent, so code in any job can change persistent host state that later jobs consume:
     - `~/.gitconfig` and `/etc/gitconfig`: `url.*.insteadOf`, `http.proxy` plus `http.sslVerify=false`, `core.hooksPath`, `credential.helper`;
     - `~/.gradle` init scripts, `~/.npm`;
     - `_work/_tool` (JDK, Node, Helm), `~/.docker/cli-plugins/docker-buildx`, the Trivy binary in `~/.local/bin`;
     - Docker images;
     - a process or container left running on the host.

     The later jobs include `build-images.yml` (`packages: write`, GHCR login) and `update-deploy.yml` (`DEPLOY_REPO_TOKEN`, write access to what Argo CD deploys to `prod`). A compromised dependency in an ordinary own push can therefore end with a malicious image in GHCR or a malicious commit in the deploy repo, and so in `prod`.
   - **The plan does not claim that only trusted code runs there.** ADR 0021 states the exposure and records the acceptance as conditional on your decision (§6 "Trust boundaries").
   - **Your options.** You can choose more than one. The first three are cheap.
     - (a) **Isolate git config in `update-deploy.yml`.**
       - On the `Commit and push` step, set `GIT_CONFIG_GLOBAL: /dev/null` and `GIT_CONFIG_NOSYSTEM: "1"`. A poisoned global or system git config then cannot redirect the push or capture the token. `actions/checkout` keeps the token in the repository-local config: an extra header in `.git/config`, or in checkout v6 a file under `$RUNNER_TEMP` included from `.git/config`. That config is still read, and `git config user.name/user.email` without `--global` writes locally, so the step keeps working.
       - Do **not** set `GIT_CONFIG_GLOBAL` on the checkout step itself. Checkout writes a temporary global config (for example `safe.directory`), and pointing it at `/dev/null` would fail. For the checkout step, set step-level `HOME: ${{ runner.temp }}/empty-home` and `GIT_CONFIG_NOSYSTEM: "1"` instead. Checkout copies `~/.gitconfig` from `HOME` into its temporary config, so an empty `HOME` keeps a poisoned one out.
       - This needs git >= 2.32 for `GIT_CONFIG_GLOBAL`. The host baseline goes from 2.25 to 2.32 (Ubuntu 22.04 ships 2.34, 24.04 ships 2.43).
       - Verify locally, without the real deploy repo: in an `ubuntu:24.04` container, write a poisoned `~/.gitconfig` (`url."https://example.invalid/".insteadOf "https://github.com/"`). Run `git config --show-origin --list` and `git ls-remote` against a local bare repo, with and without the env. The poisoned entry must disappear with the env set. Confirm checkout's behaviour with `HOME` overridden on the first real run (T9 (3)).
     - (b) **Isolate Gradle and npm state per job** (`GRADLE_USER_HOME` and `npm_config_cache` under `$RUNNER_TEMP`, restored by `actions/cache`). This closes the init-script and npm-cache poisoning paths, at the cost of slower jobs (Risk 9).
     - (c) **Route Dependabot PRs to `ubuntu-latest`** by adding `|| github.event.pull_request.user.login == 'dependabot[bot]'` to the expression, the same check the SARIF guard uses. This narrows the exposure but does not cover own pushes.
     - (d) **Strongest: run `build-images.yml` and `update-deploy.yml` on `ubuntu-latest`.** They are ephemeral and get a fresh VM every time, so no host state reaches the tokens. This contradicts D3 and the card for those two workflows, so it needs your explicit decision.
     - (e) An ephemeral self-hosted runner (`--ephemeral` with re-registration, or a container per job). This is a larger change, out of scope here.
   - **Default if you choose none:** the plan stays as written. The ADR records the exposure as an accepted risk with your reasoning. Note that (a) and (b) only narrow specific paths: root-equivalent code can still replace the runner, the tool cache or the buildx plugin. Only (d) or (e) removes the exposure for the token-bearing jobs.
3. **Fork detection edge case.** If a fork is deleted, `head.repo` becomes null, `fork == true` is false, and a re-run of that PR would land on the self-hosted runner. A fail-closed alternative with the same meaning, matching the existing SARIF guard, is `github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository && 'ubuntu-latest' || fromJSON('["self-hosted","home"]')`. The plan uses your expression as specified. I recommend the alternative; please choose. The YAML-anchor variant (one definition instead of six) is also possible if you want it. It must first be verified against GitHub and actionlint 1.7.12.
4. **The expression is not a security boundary.** A fork PR runs its own `ci.yml`, so it can change `runs-on` to `[self-hosted, home]`. The real control is "Require approval for all external contributors" plus reviewing `.github/` changes before approving. README and ADR 0021 say this explicitly. Repository-level runners on a personal account have no runner-group setting that blocks public-repo or fork jobs.

### Card vs repository conflicts (called out, not silently resolved)
5. **Node 22 vs 24.** The card says Node 22, but the repo uses 24 everywhere: `ci.yml` `node-version: "24"`, `frontend/Dockerfile` `node:24-alpine`, the dev container Node LTS, and `@types/node ^24`. The plan pins an exact **24.x.y**. Moving to 22 would be a runtime downgrade, out of scope for a runner card. Please confirm.
6. **The card's tool list does not match what the jobs use.**
   - **kubeconform**: no job uses it. Earlier plans left manifest schema validation out of scope. Installing an unused tool adds nothing, so it is not installed. If you want kubeconform validation of rendered manifests, that is a separate card.
   - **jq and kubectl/kustomize**: missing from the card, but the `dashboards` job needs them. They are installed. I chose standalone kustomize v5.6.0, the same version as the README command, over pinned kubectl, which would leave the step's command unchanged. Please confirm.
   - **The buildx plugin and BuildKit image**: these are tools too under the amended ADR 0006, so they are pinned through action inputs. This slightly exceeds the card.
7. **"No setup-buildx network driver needed, but keep buildx for cache" is ambiguous.** My interpretation: keep `docker/setup-buildx-action` with its default `docker-container` driver, which runs on the local daemon. `type=gha` cache export needs that driver, and the plain `docker` driver cannot do it. Do not add remote, kubernetes or network driver options. If you meant dropping `setup-buildx-action` and building with the plain `docker` driver, the GHA layer cache would stop working, which contradicts "keep buildx for cache".
8. **Exact runtime pins.** The amended ADR 0006 requires them, so Java and Node change from major-only (`"25"`, `"24"`) to exact patches. The trade-off: there is no Renovate or Dependabot config, so security patches for the CI JDK and Node become manual bumps. The Docker images (`eclipse-temurin:25-jdk`, `node:24-alpine`) still float, which is out of scope. Please confirm the exact pins, or allow major-only pins for runtimes through setup actions.
8a. **Host baseline vs "do not rely on tools being preinstalled".** The card says every tool is installed by an explicit, pinned step and nothing preinstalled is relied on. The plan still relies on a documented host baseline that the workflows neither install nor pin:
   - Docker Engine and the docker CLI (rootful, runner user in group `docker`);
   - git >= 2.32 (Risk 2 option (a) was chosen);
   - bash, coreutils (`sha256sum`, `find`, `sort`, `uniq`, `basename`), grep;
   - curl, ca-certificates, tar, gzip, xz-utils.

   Reasons:
   - A job cannot install a Docker daemon for itself.
   - `actions/checkout` needs git for sparse checkout and for `update-deploy.yml`'s commit and push.
   - Every pinned install step needs curl, `sha256sum` and tar to download and verify the pinned binaries.
   - The setup actions extract tarballs.

   ubuntu-latest provides all of these too, so the same jobs run on both runner types. This is a conscious deviation from the card's literal wording, and it is written into the amended ADR 0006 as the only exception. **Please confirm the list, or name items that must also be installed per job.** Only the non-daemon tools could be installed per job, for example a pinned static git or curl, and it would cost the same download-and-verify step each time.

### Other risks
9. **Caches "as before" behave differently on a persistent host.**
   - `setup-gradle` skips the cache restore when `~/.gradle` already exists ("Gradle User Home already exists"), so after the first run the persistent home directory is used. It works and is faster, but it is persistent state, recorded in ADR 0021.
   - `setup-node`'s npm cache restores over the existing `~/.npm`.
   - `actions/cache` traffic goes over the home uplink.
   - Isolating both per job (`GRADLE_USER_HOME`/`npm_config_cache` under `$RUNNER_TEMP`) is possible but not planned unless you choose Risk 2 option (b). The persistent directories are also a poisoning path towards the token-bearing jobs (Risk 2).
10. **One runner instance is assumed.** Jobs and matrix legs then serialize. If several runner instances share one host and user, they share `~/.docker/config.json`, so one `build-images` leg's post-step `docker logout` could log out the other mid-push. Fix if that happens: a per-job `DOCKER_CONFIG` under `$RUNNER_TEMP`. Not planned. How many instances will run?
11. **amd64 is assumed.** All downloaded assets are `linux_amd64`. On an arm64 host, the binaries fail with "exec format error" and the images would be arm64 (ADR 0004 assumes amd64). Please confirm the host architecture.
12. **No `timeout-minutes` on `backend`, `frontend`, `build` or `update`.** The default is 360 minutes, and on a single runner a hung job blocks everything. Recommendation: add timeouts (for example 30/15/30/10 minutes). Not in the card, so not planned unless you agree.
13. **Docker Hub anonymous rate limit per home IP.** Images cached on the host help. `docker system prune -a` resets that cache. Trivy DB rate limiting is unchanged (ADR 0007, fails closed).
14. **Host timezone and locale.** GitHub-hosted runners use UTC. Frontend tests pin `TZ=Europe/Warsaw` in `vite.config.ts`, and the backend uses a `Clock` from `APP_TIME_ZONE` (ADR 0015), so the risk is low. If a backend test turns out to depend on the JVM default zone, set `TZ: UTC` on the job.
15. **The delete step runs before `checkout`'s post step.** Upstream `checkout` returns early when `.git/config` is missing. Confirm this on the first real run from `main` (T9 (3)), which can only happen after merge.

### ADR deviations
- **ADR 0006 is changed (D5):** the "preinstalled tools may be used without separate pinning" clause is replaced by a mandatory explicit install rule, and runtimes are pinned to exact patches (Risk 8). **New in this plan beyond D5:** SHA-256 verification for directly downloaded binaries, the analogue of image digests. ADR 0006 did not require it before. Please confirm.
- **ADR 0006 "checkouts use `persist-credentials: false`":** the `backend`, `frontend` and `build-images` checkouts did not follow it, and this plan fixes them. This brings the code in line with the ADR; it does not deviate from it.
- **ADR 0004:** no deviation. `update-deploy.yml` keeps `persist-credentials: true` with `DEPLOY_REPO_TOKEN`, and only adds the cleanup step.
- **New convention → ADR 0021 (`proposed`).** It covers the self-hosted runner, the `runs-on` expression rule for `pull_request` jobs, `clean: true`, the cleanup of token-bearing working copies, `.github/actionlint.yaml`, and the trust boundaries.

### New dependencies (open until you confirm)
No new libraries. These CI tools were used implicitly before and are now explicitly downloaded and pinned:

| Artifact | Version | Why | Cost by hand |
|---|---|---|---|
| mikefarah/yq binary | newest v4.x.y (T0) | `helm` checks and `update-deploy` tag write | Rewriting the YAML queries in another tool; not sensible |
| jqlang/jq binary | newest 1.x.y (T0); the README's dashboard generation stays documented with jq 1.7.1 | `dashboards` JSON checks | Same |
| kustomize binary | v5.6.0 | `dashboards` render (replaces `kubectl kustomize`) | Same |
| docker/buildx CLI via `setup-buildx-action` `version:` | newest v0.x.y (T0) | buildx was preinstalled on `ubuntu-latest`, not on the host | n/a |
| moby/buildkit image | newest v0.x.y by digest (T0) | the builder image was floating | n/a |

Third-party installer actions (for example a `setup-yq` or `install-jq` action) were rejected: they would add SHA-pinned third-party code, while a ten-line `curl` + `sha256sum` step is simpler.

## Out of scope
- kubeconform or any other new validation of rendered manifests.
- Provisioning the runner host (installing Docker or the runner, registering it, systemd setup), OS hardening, network isolation of the runner, and automated pruning (cron).
- Ephemeral or containerised runners, multiple runner instances, runner groups.
- Changing Node to 22, bumping Java/Node majors, and pinning the Dockerfile base images.
- Routing Dependabot PRs to `ubuntu-latest` (Risk 2), adding `timeout-minutes` (Risk 12), and per-job Gradle/npm/Docker config isolation (Risks 9 and 10), unless you decide otherwise.
- Branch protection and required checks. The "Require approval for all external contributors" setting is already enabled and is only documented here.
- Any git operation (commit, push, PR) and anything that touches the dev database or runs `bootRun`.
- Editing historical plans in `docs/plans/`.

## Relevant files
- `/workspace/.github/workflows/ci.yml` (modified)
- `/workspace/.github/workflows/build-images.yml` (modified)
- `/workspace/.github/workflows/update-deploy.yml` (modified)
- `/workspace/.github/actionlint.yaml` (new)
- `/workspace/README.md` (modified: "CI and Docker images", new "CI runner")
- `/workspace/docs/adr/0021-self-hosted-ci-runner.md` (new, proposed)
- `/workspace/docs/adr/0006-ci-validation-and-pinning.md` (modified)
- `/workspace/docs/adr/README.md` (modified)
- `/workspace/docs/adr/0004-gitops-delivery-pipeline.md`, `/workspace/docs/adr/0007-dependency-locking-and-vulnerability-gate.md`, `/workspace/docs/adr/0002-integration-tests-with-testcontainers.md` (read, unchanged)
- `/workspace/frontend/vite.config.ts` (read: tests pin `TZ=Europe/Warsaw`), `/workspace/frontend/Dockerfile` (read: `node:24-alpine`)
