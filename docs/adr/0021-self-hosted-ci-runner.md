# 0021. Self-hosted CI runner, fork pull requests on GitHub-hosted runners

Date: 2026-10-05
Status: accepted
Amended: 2026-10-06, before acceptance: no GitHub Actions cache for Gradle, npm and the Trivy scans.

## Context
- CI moves from GitHub-hosted runners to a self-hosted runner on a home machine. Cost is not
  a reason: GitHub-hosted runners are free for public repositories. The reasons are:
  - Reliability: on 2026-10-05 GitHub-hosted runners queued for more than 15 minutes, jobs
    failed on timeouts and deployment stopped.
  - Learning goal of the project: operating an own runner, later ARC
    (actions-runner-controller) in the cluster.
  - Persistent caches for Gradle and npm.
  - Future: direct access from jobs to the home cluster for e2e tests.
- The repository is public.
- The workflows relied on tools preinstalled on `ubuntu-latest`, which a bare machine lacks
  (ADR 0006).
- `update-deploy.yml` holds `DEPLOY_REPO_TOKEN`, which writes to what Argo CD deploys to
  `prod` (ADR 0004).
- The repository has no `.github/dependabot.yml`. Dependabot PRs can still appear from
  GitHub security updates if they are enabled in the repository settings.

## Decision
- `runs-on: [self-hosted, home]` for every job.
- `ci.yml` (the only `pull_request` workflow) uses a per-job `runs-on` expression: PRs from
  forks, PRs from a deleted fork and Dependabot PRs run on `ubuntu-latest`, everything else
  (own-branch PRs, pushes) on the self-hosted runner. The fork check is fail-closed
  (`head.repo.full_name != github.repository`). Every future job triggered by `pull_request`
  must use the same expression.
- `pull_request_target` is never used.
- `workflow_run` on the self-hosted runner may only follow push-only workflows.
- "Require approval for all external contributors" (Settings, Actions, General, Fork pull
  request workflows) is required and stays enabled.
- Every checkout uses `clean: true`. It removes untracked and changed files but keeps
  `.git/config` and `.git/hooks`, and checkout reuses an existing `.git` whose
  `remote.origin.url` matches. Both token-bearing workflows (`build-images.yml` and
  `update-deploy.yml`) therefore empty the workspace before their checkout.
  `update-deploy.yml` additionally deletes its working copy and checkout's credential files
  in an `if: always()` final step.
- `update-deploy.yml` isolates git config from the persistent host: `GIT_CONFIG_GLOBAL` set
  to `/dev/null` and `GIT_CONFIG_NOSYSTEM` on the commit and push step, an empty `HOME` and
  `GIT_CONFIG_NOSYSTEM` on the checkout step. This needs git >= 2.32.
- Tools are installed per job (ADR 0006). The host provides only the documented baseline
  (README "CI runner").
- No GitHub Actions cache for dependencies: `setup-gradle` runs with `cache-disabled: true`
  (it still validates the wrapper), `setup-java` and `setup-node` get no `cache` input and
  `setup-node` has `package-manager-cache: false`, and every `trivy-action` step in `ci.yml`
  and `build-images.yml` has `cache: "false"`. Gradle and npm use the runner user's `~/.gradle`
  and `~/.npm`. The same steps run without a cache when a job lands on `ubuntu-latest`. A new
  job adds no `actions/cache` or setup-action cache option without saying why host state is
  not enough. The BuildKit layer cache (`type=gha`) in `build-images.yml` stays, because the
  `docker-container` builder is removed at the end of every job. `setup-buildx-action` keeps
  its default buildx binary cache (`cache-binary`).
- `.github/actionlint.yaml` declares the `home` label.
- Every job has a `timeout-minutes`, because a hung job blocks a single runner.

## Trust boundaries
- **Whose code runs on the runner:** more than the repository owner's code. Every own-branch
  PR and every push to `development`/`main` runs third-party code with the runner user's
  rights:
  - `npm ci` lifecycle scripts of all transitive npm dependencies;
  - Gradle plugins, dependencies and test code;
  - the setup actions, and the container images (actionlint, BuildKit, Testcontainers'
    `postgres:17` and Ryuk).

  `workflow_run` runs `main`'s workflow files. Fork PRs are excluded only as long as a
  maintainer reviews `.github/` changes before approving them. The `runs-on` expression
  lives in the PR's own copy of the workflow, so it is a routing rule, not a security
  boundary.
- **The runner user:** the runner runs as a dedicated non-root user `gh-runner`, which is
  in the `docker` group.
- **What a job can reach:** the Docker daemon (group `docker` is root-equivalent on the host)
  and the home network (k3d cluster API, other LAN devices). Code in any job can therefore
  take over the host, including the runner application itself. The host must hold no
  kubeconfig and no other long-lived credentials beyond the runner's own registration files.
- **Secrets on the runner:**
  - the per-job `GITHUB_TOKEN`;
  - in `build-images.yml`, a `GITHUB_TOKEN` with `packages: write` and
    `security-events: write`, plus the GHCR login (logged out in the post step);
  - `DEPLOY_REPO_TOKEN`, only in `update-deploy.yml`. It writes to the repository Argo CD
    deploys to `prod`, and the job deletes its working copy at the end.
- **Persistent state:**
  - the runner user's whole home directory, including:
    - `~/.gitconfig` (`url.*.insteadOf`, `http.proxy`, `http.sslVerify`, `core.hooksPath`,
      `credential.helper`);
    - `~/.gradle` (init scripts, caches);
    - `~/.npm`;
    - `~/.docker/` (`config.json`, the buildx plugin in `cli-plugins/`);
    - `~/.local/bin` (Trivy from `setup-trivy`);
  - `/etc/gitconfig` and other host files, reachable through Docker;
  - `_work/_tool` (JDK, Node, Helm);
  - `_work`: `clean: true` removes untracked and changed files but keeps `.git/config` and
    `.git/hooks` of the shared workspace, so a planted hook or remote survives into later jobs
    (`build-images.yml` and `update-deploy.yml` wipe the workspace before their checkout);
  - Docker images, volumes and build cache.

  Any job can poison this state, and later jobs consume it, including `build-images.yml` and
  `update-deploy.yml`. Third-party code that runs in any job on this runner, including an own
  push, can therefore lead to theft of `DEPLOY_REPO_TOKEN` (and with it a deploy to `prod`)
  and of the GHCR push token. Examples: a rewritten `url.insteadOf` or proxy in
  `~/.gitconfig`, a replaced `docker-buildx` or JDK, or a process left running on the host.
- **Accepted risk:** we accept this exposure because only the owner can push, dependency
  versions are pinned by lockfiles, and dependency changes are reviewed in PRs. The cost of a
  compromise is limited to this pet project's images and its local cluster. It is revisited
  if collaborators are added or if a dependency compromise affects this project. Two
  mitigations are chosen:
  - (a) git config isolation in `update-deploy.yml` (see Decision) plus the pre-checkout
    workspace wipe in both token-bearing workflows, which close the poisoned `~/.gitconfig`,
    `/etc/gitconfig` and planted `.git` (hooks, repository-local config) paths for the deploy
    token (and, for the wipe, the `packages: write` `GITHUB_TOKEN`);
  - (c) Dependabot PRs run on `ubuntu-latest`, which keeps not-yet-reviewed dependency
    versions off the runner until they are merged.

  These mitigations only narrow specific paths. They do not make the host trustworthy after a
  compromise: root-equivalent code can still replace the runner, the tool cache or the buildx
  plugin, and own pushes run third-party code too. Only GitHub-hosted (ephemeral) runners for
  the token-bearing jobs, or an ephemeral self-hosted runner, remove the exposure.
- **Root-owned files:** container actions (`docker://...`) and `container:` jobs run as root
  and can leave root-owned files in `_work` that the non-root runner's `git clean` cannot
  remove. Today's actionlint step only reads files, so this is a rule for future jobs.

## Alternatives considered
- All jobs on self-hosted, including forks: rejected, because it runs arbitrary public code
  on a home machine.
- Duplicated jobs per runner type: rejected, twice the YAML.
- The expression in a repository variable (config outside the repo), a "pick runner" job
  (extra job and latency), or YAML anchors (support not verified): rejected. The expression
  is repeated per job because `runs-on` cannot read `env`.
- Ephemeral or containerised runners (`--ephemeral`, actions-runner-controller): not now,
  planned as a learning goal.
- Isolating Gradle and npm state per job (`GRADLE_USER_HOME` and `npm_config_cache` under
  `RUNNER_TEMP`): deferred, because it makes jobs slower.
- Running the token-bearing jobs (`build-images.yml`, `update-deploy.yml`) on GitHub-hosted
  runners: rejected, because it defeats the reliability reason for the move.
- An ephemeral self-hosted runner for the token-bearing jobs only: deferred.
- Caches only for jobs on `ubuntu-latest` (a `runner.environment == 'github-hosted'`
  condition on the cache inputs): rejected. Pushes to `development` and `main` run on the
  self-hosted runner and save nothing, so a fork or Dependabot PR could only restore a cache
  saved by an earlier run of the same PR.

## Consequences
- With one runner instance, `ci.yml`'s six jobs and the two matrix legs run one after
  another, so wall-clock time grows.
- Host maintenance (OS and Docker updates) is manual. A systemd timer that runs
  `docker system prune` weekly should be installed by hand (README "CI runner"). The runner
  application updates itself.
- Fork and Dependabot PRs download every Gradle and npm dependency on each run.
- Every Trivy scan job downloads the vulnerability DB (and the Java DB in "Build images")
  fresh instead of restoring it from the GitHub Actions cache, because the default
  `cache-dir` is inside the workspace, which "Build images" empties at the start of every job.
  A failed download fails the job (ADR 0007).
- `~/.gradle` and `~/.npm` grow on the host. Gradle removes unused cache entries itself;
  `~/.npm` is cleared by hand (README "CI runner").
- When the runner is offline, own PRs and pushes queue (and fail after 24 h), while fork and
  Dependabot PRs still run.
- Changes to `update-deploy.yml` take effect only once they reach `main` (ADR 0004).
- Changing the host baseline (for example the git minimum) means updating README
  "CI runner" and ADR 0006.
