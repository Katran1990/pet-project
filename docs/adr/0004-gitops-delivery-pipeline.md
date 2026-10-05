# 0004. GitOps delivery: GHCR images, Helm, Argo CD and a deploy repository

Date: 2026-09-19
Status: accepted

## Context
The project needed a path from a merged commit to running pods in a local k3d cluster
with two environments, `dev` and `prod`. Draft CI workflows, Dockerfiles, a Helm chart
and Argo CD manifests existed under `docs/stage4/` and `docs/stage5/`. The first design
kept environment values on a `deploy` branch of this repository, and our Argo CD then
rejected a multi-source Application that referenced two revisions of the same repoURL.

## Decision
- **Branches:** feature branches merge into `development`, which is promoted to `main`.
- **Images:** `.github/workflows/build-images.yml` builds `backend` and `frontend` on
  every push to `development`/`main` and pushes them to
  `ghcr.io/<owner>/<repo>/<service>` with `GITHUB_TOKEN` (`packages: write`).
  - Tags: the branch name and the 7-character short SHA.
  - Names are lowercased by `docker/metadata-action`; `IMAGE_PREFIX` is only used as its
    input.
  - `ci.yml` (tests, lint, build) runs independently on every PR.
- **Chart:** `infra/helm/pet-project` (backend, frontend, in-cluster Postgres, Ingress).
- **Argo CD:** `infra/argocd/apps.yaml`, applied once by hand. `pet-project-dev` deploys
  branch `development` into namespace `dev`, `pet-project-prod` deploys `main` into `prod`.
  Automated sync with `prune` and `selfHeal`, `CreateNamespace=true`.
- **Environment values** (image tags, hosts, replicas, storage) live in the separate
  repository `Katran1990/pet-project-deploy`, branch `main`, files
  `envs/dev/values.yaml` and `envs/prod/values.yaml`. Each Application has two sources:
  the chart from this repo and `ref: values` from the deploy repo.
- **Tag update:** `.github/workflows/update-deploy.yml` runs on `workflow_run` after a
  successful "Build images". It writes the short SHA with `yq` (`strenv`, so the tag
  stays a string) into `envs/<env>/values.yaml` and pushes to the deploy repo with
  `DEPLOY_REPO_TOKEN`.
  - The checkout keeps the token as an extra header (`persist-credentials: true`).
  - Concurrent dev and prod runs are handled by rebase-and-retry (three attempts), not by
    a `concurrency` group.
  - Untrusted event values reach `run:` scripts only through `env:`.

## Alternatives considered
- Environment values on a `deploy` branch of this repository: rejected, our Argo CD
  refused a multi-source Application with two revisions of the same repoURL. The branch
  is frozen and no longer read or written.
- A `concurrency` group on `update-deploy.yml`: a per-branch group does not prevent the
  dev/prod race on the shared branch, and a constant group lets a later run cancel a
  queued one, so a tag update could be lost.
- An explicit lowercasing step for image names: unnecessary while `IMAGE_PREFIX` is only
  passed to `docker/metadata-action`.
- Gating the image build on CI success (`workflow_run` or one combined workflow): noted,
  not planned.
- Opening PRs against the deploy repo instead of pushing: only needed with branch
  protection there, which does not exist; not planned.
- Authenticating by rewriting the remote URL with the token: prohibited, it writes the
  secret into `.git/config` and error messages.

## Consequences
- `workflow_run` always runs the workflow file from the default branch `main`, so changes
  to `update-deploy.yml` take effect only after they reach `main`.
- In each Application a repoURL appears only once, and sources are tied together by the
  ref name `values` / `$values`.
- Nothing secret may ever go into `envs/*`: `update-deploy.yml` prints those files into
  the build log, and the workflow fails on a password-like key.
- Images are pushed even if CI failed on the same commit; merges are expected to go
  through PRs where CI already ran.
- GHCR packages are private by default and the chart has no `imagePullSecrets`; images are
  single-arch (amd64).
- Promotion to `prod` is a merge into `main`; there is no manual deploy step.
