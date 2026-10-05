# 0006. Validate infrastructure in CI and pin every CI dependency

Date: 2026-09-21
Status: accepted

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
  - Tool versions are explicit: Helm `v3.22.0` (never floating to Helm 4), actionlint
    1.7.x, the Trivy binary through the action's `version:` input. This clarifies the rule:
    CLI tools preinstalled on the GitHub-hosted runner image (jq, yq, kubectl with its
    embedded kustomize) may be used in validation steps without separate pinning, because
    the runner image versions them; anything the workflow installs or downloads itself must
    be pinned.
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

## Consequences
- A PR can go red because of a change in the deploy repo alone, since it renders against
  the moving `main` of that repo (which is also what Argo CD renders).
- If the deploy repo becomes private, the checkout fails loudly instead of skipping.
- Every new action, image or tool follows the pinning rules, and a version bump updates
  every place it appears (workflows, README commands).
- Later jobs follow the same pattern: Trivy (ADR 0007), the kube-prometheus-stack render
  and version-parity check (ADR 0019), the dashboards check (ADR 0020).
- Making these checks required is a manual branch-protection setting.
