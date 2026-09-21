# Plan: Add helm lint and actionlint jobs to CI

Card: "Add helm lint and actionlint jobs to CI" (https://trello.com/c/6EGNezrY)
Branch: `feature/helm-lint-actionlint-ci` (based on `origin/development`). All paths below are relative to the repo root.

## Goal
Every PR (and every push to `development`/`main`) runs two new jobs in `.github/workflows/ci.yml`, in parallel with the existing ones:
- **Helm chart:** `helm lint`, then `helm template` against the dev and prod values from `Katran1990/pet-project-deploy@main`.
- **actionlint:** lints everything under `.github/workflows/`.

A broken chart or workflow then fails the PR, not the deploy.

## Acceptance criteria
- [ ] ci.yml runs `helm lint infra/helm/pet-project`
- [ ] ci.yml runs `helm template` against the dev and prod values ~~from the `deploy` branch~~ from `Katran1990/pet-project-deploy`, branch `main`, files `envs/dev/values.yaml` and `envs/prod/values.yaml` (user clarification: the `deploy` branch was removed from the remote; the user chose this option). A broken template fails the PR.
- [ ] ci.yml runs actionlint over `.github/workflows/`
- [ ] Anything actionlint already reports in ci.yml / build-images.yml is fixed as part of this card
- [ ] The new jobs run on pull requests and do not noticeably slow the pipeline down

Additional requirements from the user's clarifications:
- [ ] The deploy repo checkout is read-only (`actions/checkout@v6`, `repository: Katran1990/pet-project-deploy`, `ref: main`) and uses only the built-in `github.token`. `DEPLOY_REPO_TOKEN` (write access to the deploy repo) is NOT used in ci.yml (user decision, see Risk 2).
- [ ] What happens when the secret is unavailable (forks, Dependabot) is decided and documented.
- [ ] Tool versions are pinned (helm 3.x, actionlint 1.7.x). The new checks are separate parallel jobs with minimal setup.

## Changes

### What was checked
- `.github/workflows/ci.yml`:
  - Triggers: `pull_request` (all branches) and `push` to `[development, main]`.
  - `permissions: contents: read`.
  - `concurrency: ci-${{ github.ref }}`.
  - Two jobs: `backend` (Gradle build plus Testcontainers, the slowest part of the pipeline) and `frontend`.
  - Actions used: `actions/checkout@v6`, `setup-java@v6`, `gradle/actions/setup-gradle@v6`, `setup-node@v6`, `upload-artifact@v5`.
  - The header comment says "Two independent jobs (backend, frontend)" and will be out of date after this change.
- `.github/workflows/update-deploy.yml`:
  - Already checks out `Katran1990/pet-project-deploy` at `ref: main` with `token: ${{ secrets.DEPLOY_REPO_TOKEN }}`.
  - That token has **write** access to the deploy repo (it pushes image tags, and Argo CD deploys them to prod).
  - Its comments, and `infra/argocd/apps.yaml` lines 6-9, both say the deploy repo is **public**. This matters for how the new job handles a missing secret (see below).
- `infra/helm/pet-project/`:
  - `Chart.yaml` (apiVersion v2), `values.yaml`, and 4 templates.
  - No `required`/`fail` calls and no dependencies, so `helm dependency build` is not needed.
  - `templates/postgres.yaml` renders a `Secret` with `stringData.password`. The rendered output must therefore **not** be printed to the CI log. Today the value is the throwaway `app`, but the Sealed Secrets work is already planned.
- Tools in the dev container:
  - Neither `helm` nor `actionlint` is installed.
  - `docker` is available (docker-in-docker feature in `.devcontainer/devcontainer.json`), so local checks run through official images (see Tests).
- Current actionlint findings: from reading `ci.yml`, `build-images.yml` and `update-deploy.yml` (expressions, `needs`/matrix, and the shell in `run:` blocks), none are expected. actionlint could not be run while planning, so the real list comes from step T1.

### 1. `.github/workflows/ci.yml` (modify)

**1a. Header comment.** Change "Two independent jobs (backend, frontend) run in parallel." to say four independent jobs run in parallel: backend, frontend, the Helm chart check and actionlint.

**1b. Add job `helm`** after `frontend`, following the existing style (`name:`, a comment per step):

> Code review update: the checkout of this repo (the first step below) also got
> `persist-credentials: false`, and `azure/setup-helm` is pinned to the latest
> v5.x.y release (not v4.x.y as first drafted) — see the "Code review follow-ups"
> note after this section.

```yaml
  helm:
    name: Helm chart
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v6
        with:
          persist-credentials: false

      # Environment values live in the deploy repo (see infra/argocd/apps.yaml), not here.
      # Read-only checkout with the built-in token: the deploy repo is public, so no secret
      # is needed, and DEPLOY_REPO_TOKEN (write access) is deliberately kept out of PR jobs.
      # Works the same for PRs from forks and Dependabot. If the deploy repo ever becomes
      # private, this step fails with "repository not found" instead of skipping the check.
      - name: Check out environment values (pet-project-deploy)
        uses: actions/checkout@v6
        with:
          repository: Katran1990/pet-project-deploy
          ref: main
          path: deploy-values
          persist-credentials: false   # read-only: do not leave the token in .git/config
          sparse-checkout: envs

      - uses: azure/setup-helm@<commit-sha> # v5.x.y, pinned by SHA (third-party action)
        with:
          version: v3.X.Y              # pinned Helm 3.x; do not float to Helm 4

      - name: Lint chart
        run: helm lint infra/helm/pet-project

      # Same lint with each environment's values: catches warnings that only appear with them.
      - name: Lint chart with dev and prod values
        run: |
          for env in dev prod; do
            helm lint infra/helm/pet-project -f "deploy-values/envs/${env}/values.yaml"
          done

      # Renders the chart exactly as Argo CD does (chart + envs/<env>/values.yaml).
      # Output goes to /dev/null: postgres.yaml renders a Secret and must not end up in the log.
      # Errors go to stderr and fail the step.
      - name: Render chart with dev and prod values
        run: |
          for env in dev prod; do
            echo "helm template with envs/${env}/values.yaml"
            helm template "pet-project-${env}" infra/helm/pet-project \
              --namespace "${env}" \
              -f "deploy-values/envs/${env}/values.yaml" > /dev/null
          done
```

Notes:
- GitHub runs `run:` steps with `bash -e`, so the first failed `helm template` stops the loop and fails the job.
- If `envs/<env>/values.yaml` is missing (the deploy repo layout changed), helm fails with "no such file". That is the intended loud failure.
- The release names and namespaces match `infra/argocd/apps.yaml` (`pet-project-dev`/`dev`, `pet-project-prod`/`prod`).
- `sparse-checkout: envs` plus the default `fetch-depth: 1` keeps the checkout to a few KB.
- **Why `azure/setup-helm` instead of the helm already on the runner:** `ubuntu-latest` ships helm, but that version changes over time and could jump to Helm 4. The card requires helm 3.x, so it is pinned.

**1c. Add job `actionlint`:**

> Code review update: this job's checkout of the main repo also got
> `persist-credentials: false` (see "Code review follow-ups" below).

```yaml
  actionlint:
    name: Workflow lint (actionlint)
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v6
        with:
          persist-credentials: false

      # Checks every file in .github/workflows/ (actionlint's default when run from the repo root),
      # including the shell in run: blocks via shellcheck, which is bundled in the official image.
      - name: actionlint
        uses: docker://rhysd/actionlint:1.7.X@sha256:<digest>   # pin tag and digest: see Tests T0
        with:
          args: -color
```

- Running it through the Docker image means the same pinned version runs locally (T1) and in CI.
- It avoids the `curl | bash` download script, and needs no Go or shellcheck setup.
- Docker container actions mount the workspace and use it as the working directory, so no path argument is needed.
- The image is pinned by tag and digest.

Everything else in `ci.yml` stays unchanged: triggers, top-level `permissions: contents: read` (enough for both new jobs), `concurrency`, and the `backend`/`frontend` jobs. The new jobs have no `needs:`, so they run in parallel. They take roughly 20-40 s each, and the Gradle job remains the slowest part, so the pipeline is not slower overall (AC5).

**1d. Fixes for existing actionlint findings (AC4).** None are expected, but T1 is the proof:
- Anything reported in `ci.yml` or `build-images.yml` gets fixed in this card.
- The new job scans the whole directory, so anything reported in `update-deploy.yml` also fails the job. See Risk 3.

**1e. Code review follow-ups (applied after the plan above was written):**
- `azure/setup-helm` is pinned to the latest v5.x.y release, not v4.x.y. The
  `version` input is unchanged in v5, and `runs.using` moved from `node20` to
  `node24` (the only user-facing change in the v5.0.0/v5.0.1 release notes,
  plus an internal chmod fix in v5.0.1); nothing else affects our usage.
- The `helm lint`/`helm template` version pin comment reads
  `# pinned Helm 3.x; do not float to Helm 4` instead of
  `# pin: latest v3 patch (see Tests T0)`.
- The main-repo `actions/checkout@v6` step (the first step) in both the
  `helm` and `actionlint` jobs also gets `persist-credentials: false`,
  matching the existing `deploy-values` checkout. The `backend`/`frontend`
  jobs are unchanged.

### 2. `README.md` (modify, small)
In "CI and Docker images" (lines 71-72), extend the `ci.yml` bullet:
- `ci.yml` also runs `helm lint` and `helm template` of `infra/helm/pet-project` against `envs/dev` and `envs/prod` values from `pet-project-deploy@main`.
- It also runs actionlint over `.github/workflows/`.
- Add one short line with the local Docker commands from Tests T1/T2.
- Code review follow-up: also add a short `helm template` example that
  downloads `envs/dev/values.yaml` from `pet-project-deploy@main` via
  `raw.githubusercontent.com` and pipes it into `alpine/helm:3.22.0 template`
  with stdout to `/dev/null` (matching T3), so the README stands on its own.

### 3. No other files
- No `.github/actionlint.yaml`: the defaults are enough and there are no self-hosted runner labels.
- No chart changes unless T2 shows a lint failure.
- No backend, frontend, Gradle, npm or DB migration changes.
- `build-images.yml` and `update-deploy.yml` change only if actionlint reports something.

## Tests
This is CI configuration, so the proof is verification commands rather than unit tests. Local runs use Docker because helm and actionlint are not installed. `W` is the repo root and `S` a scratch directory outside the repo.

| # | Check | Command | Proves |
|---|---|---|---|
| T0 | Pinned versions and actions exist | `gh api repos/rhysd/actionlint/releases --jq '.[].tag_name' \| head` → pick the newest `v1.7.x`. `gh api repos/helm/helm/releases --jq '[.[].tag_name \| select(startswith("v3."))][0]'` → newest v3 patch. `docker manifest inspect rhysd/actionlint:1.7.X` and `docker manifest inspect alpine/helm:3.X.Y` succeed. `gh api repos/azure/setup-helm/git/matching-refs/tags/v4 --jq '.[].ref'` is non-empty. | AC5 (pinned), no "unable to resolve action" |
| T1 | actionlint is clean on all workflows (including the new jobs) | `docker run --rm -v "$W:/repo" -w /repo rhysd/actionlint:1.7.X -color` exits 0 with no output. Run it **before** editing to record the current findings for AC4, then again after. Confirm shellcheck is inside the image: `docker run --rm --entrypoint sh rhysd/actionlint:1.7.X -c 'command -v shellcheck'`. | AC3, AC4 |
| T2 | helm lint passes | `docker run --rm -v "$W:/apps" -w /apps alpine/helm:3.X.Y lint infra/helm/pet-project` → "1 chart(s) linted, 0 chart(s) failed" | AC1 |
| T3 | helm template renders with the real deploy repo values | Download (read-only, no git): `curl -fsSL https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/dev/values.yaml -o $S/dev.yaml`, and the same for prod. Then `docker run --rm -v "$W:/apps" -v "$S:/vals" -w /apps alpine/helm:3.X.Y template pet-project-dev infra/helm/pet-project -n dev -f /vals/dev.yaml > /dev/null`, and the same for prod. Both exit 0. | AC2 |
| T4 | A broken template fails (negative test, never in the repo) | `cp -r $W/infra/helm/pet-project $S/broken-chart`, add `{{ .Values.nope.missing }}` to `$S/broken-chart/templates/ingress.yaml`, run the T3 command against the broken chart → non-zero exit. For actionlint: copy `.github/workflows/ci.yml` to `$S/wf/.github/workflows/`, add an undefined `${{ steps.nope.outputs.x }}` expression, run actionlint in `$S/wf` → non-zero exit. | AC2 ("a broken template fails"), AC3 |
| T5 | The new jobs run on the PR and pass | After the PR is opened: the checks "Helm chart" and "Workflow lint (actionlint)" appear next to Backend/Frontend and are green. The "Check out environment values" log shows `Katran1990/pet-project-deploy`. Each new job takes about 1 minute or less, and the total run time is still set by the backend job. | AC2, AC3, AC5 |
| T6 | Regression | `cd backend && ./gradlew test` (CLAUDE.md rule: a task is done only when tests pass). Nothing in backend/frontend changes. | CLAUDE.md |

Locally the task is done when T0-T4 and T6 pass and no placeholder (`3.X.Y`, `1.7.X`, `<digest>`, `<commit-sha>`) is left in any file. T5 can only be checked on GitHub.

## Risks and open questions
1. **Behavior without secrets (forks, Dependabot).** No secret is used, so the check behaves identically for every PR. The deploy repo is public (`infra/argocd/apps.yaml` lines 6-9, `update-deploy.yml` line 39). If it is made private later, the checkout **fails** with "repository not found"; it never silently skips.
2. **Least privilege (decided).** `DEPLOY_REPO_TOKEN` has **write** access to the repo Argo CD deploys from, including prod. Exposing it to a job that runs on every same-repo PR would let a PR that edits `ci.yml` exfiltrate it. The user chose to use only the built-in `github.token` for the read-only checkout.
3. **actionlint scans the whole directory, not just the two files in AC4.**
   - If T1 reports anything in `update-deploy.yml`, the new job fails, so it has to be fixed in this card too. That slightly exceeds the card's wording.
   - Excluding the file with `-ignore` or a file list would hide real issues.
   - Recommendation: fix it in this card and mention it in the PR description.
4. **Exact tool versions.** Only "helm 3.x, actionlint 1.7.x" are known from earlier local runs. The plan pins the newest 3.x and 1.7.x patches found in T0. Helm 4 exists, and it is deliberately not used.
5. **Deploy values are a moving target.**
   - The PR renders against the current `main` of the deploy repo, not a fixed revision. That is also what Argo CD renders, so it is correct.
   - A PR can therefore go red because of a change in the deploy repo alone.
   - `update-deploy.yml` only rewrites `backend.tag`/`frontend.tag`, so the risk is low.
6. **The Secret must stay out of the logs.** The rendered output goes to `/dev/null`. If the rendered manifests are needed for debugging later, upload them as an artifact rather than printing them. Out of scope here.
7. **`helm lint --strict` was not added.** The AC names the plain command. `--strict` turns warnings into errors and could be added later.
8. **Remaining references to the `deploy` branch.** Listed only; none affect this card, and none are changed:
   - The card text of AC2 itself, replaced by the user clarification above.
   - `infra/argocd/apps.yaml:13`: historical explanation, correct.
   - `README.md:100` and `README.md:105-109`: historical. The "superseded once this change reaches `main`" wording will become slightly stale, which is a separate docs fix.
   - `docs/plans/helm-argocd-manifests.md` and `docs/plans/move-deploy-config-to-separate-repo.md`: historical plans, left as-is.
   - No workflow references `ref: deploy` any more.
9. **Branch protection.** Making "Helm chart" and "Workflow lint (actionlint)" required checks is a GitHub settings change made by hand. Without it, a red check does not technically block the merge.

## Out of scope
- Schema validation of rendered manifests (kubeconform/kubeval), `helm template --validate` against a cluster, and chart unit tests.
- Path filters on the new jobs. They are cheap enough to always run, and path filters complicate required checks.
- Changes to the chart, the deploy repo or Argo CD manifests, and anything on the old `deploy` branch.
- Linting `infra/argocd/apps.yaml` or other YAML (yamllint).
- Changing `update-deploy.yml` or `build-images.yml`, except for fixes actionlint requires (Risk 3).
- Branch protection / required status checks.
- README/docs cleanup of historical `deploy`-branch wording.

## Relevant files
- `.github/workflows/ci.yml` (modified)
- `README.md` (modified, lines 71-72)
- `.github/workflows/update-deploy.yml` (reference for the deploy repo checkout; changed only if actionlint requires it)
- `.github/workflows/build-images.yml` (changed only if actionlint requires it)
- `infra/helm/pet-project/` (chart under test)
- `infra/argocd/apps.yaml` (source for the release names and namespaces, and the "deploy repo is public" note)
