# 0006. Validate infrastructure in CI and pin every CI dependency

Date: 2026-09-21
Status: accepted
Amended: 2026-10-05 for the self-hosted runner (ADR 0021); the amendment is proposed until merged.

## Context
A broken Helm chart or workflow used to surface only at deploy time. The draft workflows
referenced action tags that nobody had checked, and in 2026 the tags of
`aquasecurity/trivy-action` were hijacked (force-pushed to malicious commits). The deploy
repo token has write access to what Argo CD deploys to `prod`.

## Decision
- **Pinning:**
  - GitHub-owned and well-known publishers are pinned by major tag (`actions/checkout@v6`,
    `docker/build-push-action@v7`, `github/codeql-action/upload-sarif@v4`).
  - Third-party actions are pinned by full commit SHA with a comment
    `# vX.Y.Z, pinned by SHA (third-party action)` (`azure/setup-helm`,
    `aquasecurity/trivy-action`). The SHA is taken from the official release, not from a
    tag lookup alone.
  - Tool images are pinned by tag and digest (`rhysd/actionlint:1.7.12@sha256:...`,
    `aquasec/trivy`).
  - Every tool a job runs is installed by an explicit, version-pinned step in that job
    (mandatory since the amendment of 2026-10-05). The step is one of:
    - a setup action with a pinned version input (`setup-java`, `setup-node`,
      `azure/setup-helm`, `trivy-action` `version:`, `setup-buildx-action` `version:`);
    - a container image pinned by tag and digest (actionlint, BuildKit);
    - a direct download of a release binary pinned by version and verified against a SHA-256
      committed in the workflow, taken from the publisher's checksum file (yq, jq, kustomize).
  - Language runtimes are pinned to an exact patch (Java: the patch line, which resolves to the
    newest build of it).
  - Nothing preinstalled on a runner may be relied on, except the host baseline in README
    "CI runner" (bash, coreutils, grep, git >= 2.32, curl, ca-certificates, tar/gzip/xz,
    Docker Engine). The same rule applies on GitHub-hosted and self-hosted runners.
  - Tool versions (as of 2026-10-05): Helm `v3.22.0` (never floating to Helm 4), actionlint
    1.7.12, Trivy v0.70.0 through the action's `version:` input, Java Temurin `25.0.4`
    (resolves to jdk-25.0.4.1+1), Node `24.21.0`, yq `v4.54.1`, jq `1.8.2`, kustomize
    `v5.6.0`, docker/buildx `v0.37.2`, moby/buildkit `v0.33.1`
    (`sha256:cec9f139f45e93c5c69c60f8b07cfad9f43f4ef6b6a6cd917527fea5ff2e3dea`).
  - Where the pins live: `ci.yml` (`setup-java`/`setup-node` versions, the workflow `env`
    block with yq, jq and kustomize versions and checksums, `setup-helm`, Trivy, actionlint),
    `build-images.yml` (buildx and BuildKit), `update-deploy.yml` (yq, same values as
    `ci.yml`) and the README's local commands.
  - Every tag is checked to exist before it is committed.
- **Infrastructure checks in `ci.yml`** (separate parallel jobs, on every PR and push):
  - `helm`: `helm lint`, then lint and `helm template` for `dev` and `prod` with
    `--namespace` and the real `envs/<env>/values.yaml` from `pet-project-deploy@main`.
    Rendered output goes to a file or `/dev/null`, never to the log.
  - `actionlint` over all of `.github/workflows/`, including shellcheck on `run:` blocks.
- **Least privilege:**
  - top-level `permissions: contents: read`, with extra permissions only on the job that
    needs them;
  - checkouts use `persist-credentials: false`;
  - the deploy repo is checked out read-only with the built-in token, and
    `DEPLOY_REPO_TOKEN` is never used in PR jobs;
  - untrusted `${{ }}` values reach scripts only through `env:`.

## Alternatives considered
- The Helm already on the runner: its version changes over time and could jump to Helm 4.
- `DEPLOY_REPO_TOKEN` for the read-only checkout: a PR that edits `ci.yml` could exfiltrate
  a token with write access to `prod`.
- Installing actionlint with the `curl | bash` script: replaced by the pinned image.
- Path filters on the new jobs: they complicate required checks, and the jobs are cheap.
- `helm lint --strict`: not added; it does not catch an invalid sealed file anyway
  (ADR 0005).
- Excluding files from actionlint: would hide real issues.
- Relying on tools preinstalled on `ubuntu-latest`: they are missing on the bare self-hosted
  runner and their versions float.

## Consequences
- A PR can go red because of a change in the deploy repo alone, since it renders against
  the moving `main` of that repo (which is also what Argo CD renders).
- If the deploy repo becomes private, the checkout fails loudly instead of skipping.
- Every new action, image or tool follows the pinning rules, and a version bump updates
  every place it appears (workflows, README commands).
- Later jobs follow the same pattern: Trivy (ADR 0007), the kube-prometheus-stack render
  and version-parity check (ADR 0019), the dashboards check (ADR 0020).
- Adding a tool means adding an install step with version and checksum. A bump updates the
  version and the checksum together, in every workflow that installs it.
- Making these checks required is a manual branch-protection setting.
