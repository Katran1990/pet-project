# Plan: Add Helm chart and Argo CD deploy manifests

Trello card: https://trello.com/c/t61B5es0/4-add-helm-chart-and-argo-cd-deploy-manifests

*Revision 2 - addresses the plan review (concurrency rewrite, deploy-branch freshness check, honest tooling fallback, postgres-password decision). Tooling section updated by the coordinator with what is actually installed.*

## Acceptance criteria

- [ ] `infra/helm/pet-project` passes `helm lint`
- [ ] `helm template` renders successfully with dev and prod values from the `deploy` branch
- [ ] `.github/workflows/update-deploy.yml` passes `actionlint`
- [ ] `infra/argocd/apps.yaml` is valid YAML with two Applications (dev, prod)

Additional constraints from the card (authoritative):
- [ ] `imageRegistry` in `values.yaml` and `repoURL` in `apps.yaml` match the real GitHub repository (`https://github.com/Katran1990/pet-project.git`)
- [ ] Files are moved out of `docs/stage5/`, and `docs/stage5/` is deleted completely (`docs/plans/` stays)
- [ ] The `deploy` branch is **not** modified by this PR

## Goal

Move the draft Helm chart, Argo CD `Application` manifests and the `update-deploy` workflow from `docs/stage5/` into their real locations (`infra/` and `.github/workflows/`), correct the repository-specific values so they match the real GitHub repo and what the existing "Build images" pipeline actually publishes, then delete `docs/stage5/`.

---

## What was verified in the repository first

**Existing image pipeline** - `.github/workflows/build-images.yml`:
- `IMAGE_PREFIX: ghcr.io/${{ github.repository }}`, and `docker/metadata-action@v6` is given `images: ${{ env.IMAGE_PREFIX }}/${{ matrix.service }}` with `matrix.service: [backend, frontend]`.
- metadata-action lowercases the image name, so the published images are exactly:
  - `ghcr.io/katran1990/pet-project/backend:<tag>`
  - `ghcr.io/katran1990/pet-project/frontend:<tag>`
- Tags produced: `type=ref,event=branch` -> `development` / `main`, and `type=sha,prefix=,format=short` -> the **7-character** short SHA.

**Conclusion for `imageRegistry`:** the draft value `ghcr.io/katran1990/pet-project` combined with `backend.image: backend` already yields exactly the published name. **It is correct and must stay lowercase** (GHCR rejects uppercase). Only its comment needs fixing. Do *not* "capitalise" it to `Katran1990`.

**Conclusion for the workflow's tag:** `cut -c1-7` matches metadata-action's short-SHA format. Consistent, no change needed.

**Chart/app contracts that constrain resource names:**
- `frontend/nginx.conf` line 16 hardcodes `proxy_pass http://backend:8080;` -> the backend Service **must** be named exactly `backend`.
- `docs/stage5/infra/helm/pet-project/templates/backend.yaml` line 22 builds `DB_URL` as `jdbc:postgresql://postgres:5432/<db>` -> the Postgres Service **must** be named exactly `postgres`.
- Therefore the templates must **not** be changed to `{{ .Release.Name }}-backend` style names. Keep the fixed names.
- `backend/src/main/resources/application.properties` has `management.endpoints.web.exposure.include=health,info` (line 15) and reads `DB_URL` / `DB_USER` / `DB_PASSWORD` (lines 8-10) -> the `/actuator/health` probes and the env wiring in `backend.yaml` are correct.
- `frontend/Dockerfile` line 17 exposes port 80 -> the frontend container port/Service are correct.

**Repo layout:** `README.md` already advertises `infra/    infrastructure files` (line 19) and already has a "CI and Docker images" section (line 69), but `infra/` does not exist yet. This task creates it.

**Env values on the `deploy` branch** (read-only; must not be modified):
- `dev`: `backend.tag`, `frontend.tag`, `ingress.host: dev.pet.local`
- `prod`: `backend.tag`, `backend.replicas: 2`, `frontend.tag`, `ingress.host: pet.local`, `postgres.storage: 5Gi`

---

## Changes

### 1. Move - `docs/stage5/` -> final locations

Use plain filesystem moves (`mv`), **not** `git mv` (CLAUDE.md: no git operations).

| Source | Destination |
|---|---|
| `docs/stage5/infra/helm/pet-project/Chart.yaml` | `infra/helm/pet-project/Chart.yaml` |
| `docs/stage5/infra/helm/pet-project/values.yaml` | `infra/helm/pet-project/values.yaml` |
| `docs/stage5/infra/helm/pet-project/templates/backend.yaml` | `infra/helm/pet-project/templates/backend.yaml` |
| `docs/stage5/infra/helm/pet-project/templates/frontend.yaml` | `infra/helm/pet-project/templates/frontend.yaml` |
| `docs/stage5/infra/helm/pet-project/templates/postgres.yaml` | `infra/helm/pet-project/templates/postgres.yaml` |
| `docs/stage5/infra/helm/pet-project/templates/ingress.yaml` | `infra/helm/pet-project/templates/ingress.yaml` |
| `docs/stage5/infra/argocd/apps.yaml` | `infra/argocd/apps.yaml` |
| `docs/stage5/.github/workflows/update-deploy.yml` | `.github/workflows/update-deploy.yml` |

The chart directory name **must** stay `pet-project` - `helm lint` errors if the directory name differs from `Chart.yaml: name`.

### 2. `infra/helm/pet-project/Chart.yaml` - no functional change

Keep as-is: `apiVersion: v2`, `name: pet-project`, `type: application`, `version: 0.1.0`, `appVersion: "0.1.0"`.
- `name` matches the directory -> `helm lint` passes.
- Do not bump the version; this is the chart's first landing.
- `helm lint` will print `[INFO] Chart.yaml: icon is recommended`. INFO does not fail the lint; no icon is added.

### 3. `infra/helm/pet-project/values.yaml` - comment fixes only

Keep every key and value exactly as drafted. Three comment edits:

- **Line 4**, replace `# check: must match your GitHub login` with the real contract:
  `# Must equal IMAGE_PREFIX from .github/workflows/build-images.yml (ghcr.io/<owner>/<repo>), lowercase - GHCR rejects uppercase names.`
  Value stays `ghcr.io/katran1990/pet-project`.
- **Lines 8 / 16** (`tag: development`): keep the value; fix the comment on line 8 and add the same comment on line 16 (frontend has none today) - CI does not rewrite this file; it rewrites `envs/<env>/values.yaml` on the `deploy` branch. Suggested: `# default only; the real tag comes from envs/<env>/values.yaml on the deploy branch`.
- **Lines 27-29** (`postgres.password`): keep `password: app` (decision, see below) and extend the existing comment so the current state is not misread:
  ```yaml
  # Dev credential only - the same throwaway app/app pair that backend's
  # application.properties already defaults to. envs/prod/values.yaml on the
  # deploy branch does NOT override it, so "prod" currently comes up with app/app.
  # Replace with Sealed Secrets / External Secrets before prod is reachable
  # from outside (follow-up card). Never put a real password into envs/* -
  # update-deploy.yml prints those files with `cat`.
  ```

**Decision on the Postgres password (settled, not an open question):** keep `app/app` as drafted and do not block the card. It is the identical throwaway dev credential already visible in `backend/src/main/resources/application.properties` lines 8-10 and in the README Configuration table, so nothing new is leaked. Three conditions attached, all covered by this plan: (i) the comment above plus an explicit line in the implementation report, (ii) a follow-up card for Sealed/External Secrets before "prod" is exposed, (iii) the password must never move into `envs/*` on the `deploy` branch, because the `Set image tags` step does `cat "$FILE"` into the build log - safe today because that file holds only tags and hosts.

Everything else stays: `backend.image: backend`, `frontend.image: frontend`, `backend.replicas: 1`, `frontend.replicas: 1`, the three `resources` blocks, `postgres.image: postgres:17`, `postgres.storage: 1Gi`, `postgres.db/user: app`, `ingress.host: dev.pet.local`.

Rationale for keeping all defaults: `helm lint` renders the templates with defaults only, so every key referenced by a template must exist here - it does.

### 4. Consistency check - chart vs. the `deploy` branch env values

Every key the env files override exists in `values.yaml` and is consumed by a template. Helm merges maps deeply, so `backend: {tag, replicas}` in `envs/prod` keeps the chart's `backend.image` and `backend.resources`.

| Env key | Set in | Present in chart `values.yaml` | Consumed by |
|---|---|---|---|
| `backend.tag` | dev, prod | yes (`development`) | `templates/backend.yaml` image string |
| `backend.replicas` | prod (`2`) | yes (`1`) | `templates/backend.yaml` `spec.replicas` |
| `frontend.tag` | dev, prod | yes (`development`) | `templates/frontend.yaml` image string |
| `ingress.host` | dev (`dev.pet.local`), prod (`pet.local`) | yes (`dev.pet.local`) | `templates/ingress.yaml` `spec.rules[0].host` |
| `postgres.storage` | prod (`5Gi`) | yes (`1Gi`) | `templates/postgres.yaml` `volumeClaimTemplates` |

**No template changes are required.** The mechanics that most often break here were checked:
- `resources: {{- toYaml .Values.X.resources | nindent 12 }}` - container keys sit at 10 spaces in all three templates, so `nindent 12` is correct.
- `volumeClaimTemplates` sits at `spec` level (2 spaces) in the StatefulSet - correct.
- `pg_isready -U {{ .Values.postgres.user | quote }}` renders to a valid flow sequence.

So templates `backend.yaml`, `frontend.yaml`, `postgres.yaml` and `ingress.yaml` are moved **byte-identical**.

### 5. `infra/argocd/apps.yaml` - repoURL + header comment

- Replace all **four** occurrences of `https://github.com/katran1990/pet-project.git` with `https://github.com/Katran1990/pet-project.git`. The four occurrences are lines **19** (dev chart source), **25** (dev `$values` ref source), **46** (prod chart source) and **52** (prod `$values` ref source). All four must be byte-identical strings, otherwise the `$values` ref will not resolve.
- Drop the now-stale line 7 `# Replace REPO_URL with your repository if it differs.` and replace it with a one-liner noting the URL is the real repo and that all four `repoURL` values must stay identical.

Everything else stays exactly as drafted, and this is what satisfies AC4:

| Field | `pet-project-dev` | `pet-project-prod` |
|---|---|---|
| `metadata.name` | `pet-project-dev` | `pet-project-prod` |
| `metadata.namespace` | `argocd` | `argocd` |
| `spec.project` | `default` | `default` |
| `destination.server` | `https://kubernetes.default.svc` | same |
| `destination.namespace` | `dev` | `prod` |
| source 1 `targetRevision` | `development` | `main` |
| source 1 `path` | `infra/helm/pet-project` | `infra/helm/pet-project` |
| source 1 `helm.valueFiles` | `$values/envs/dev/values.yaml` | `$values/envs/prod/values.yaml` |
| source 2 | `targetRevision: deploy`, `ref: values` (no `path`) | same |
| `syncPolicy.automated` | `prune: true`, `selfHeal: true` | same |
| `syncOptions` | `CreateNamespace=true` | same |

Two documents separated by `---`, both `apiVersion: argoproj.io/v1alpha1`, `kind: Application`.

### 6. `.github/workflows/update-deploy.yml` - hardening, keep the logic

Keep: the name `Update deploy manifests`, the `workflow_run` trigger on `workflows: ["Build images"]` / `types: [completed]` / `branches: [development, main]`, the `if: success` guard, `actions/checkout@v6` with `ref: deploy`, the `yq -i` tag rewrite, and the commit/push step. The branch->env mapping (`development`->`dev`, `main`->`prod`) and the 7-char SHA are already consistent with `build-images.yml`.

**6.1 `permissions: contents: write` stays.** Required to push to the `deploy` branch. Already approved by the user - not reopened here. It is the reason for the injection hardening in 6.2.

**6.2 Stop interpolating `${{ ... }}` directly into `run:` scripts.** `github.event.workflow_run.head_branch` is attacker-controllable (a branch name) and this workflow runs with `contents: write` on the base repo. Move both expressions into step-level `env:` and reference shell variables. Also rename the step id `env` -> `pick` (an id literally called `env` is legal but shadows the `env` context in every later reference):

```yaml
- name: Pick environment
  id: pick
  env:
    HEAD_BRANCH: ${{ github.event.workflow_run.head_branch }}
    HEAD_SHA: ${{ github.event.workflow_run.head_sha }}
  run: |
    case "$HEAD_BRANCH" in
      development) echo "name=dev"  >> "$GITHUB_OUTPUT" ;;
      main)        echo "name=prod" >> "$GITHUB_OUTPUT" ;;
      *) echo "unexpected branch: $HEAD_BRANCH"; exit 1 ;;
    esac
    # metadata-action's short SHA tag is the first 7 characters
    echo "tag=${HEAD_SHA:0:7}" >> "$GITHUB_OUTPUT"
```
Update the two later references to `steps.pick.outputs.name` / `steps.pick.outputs.tag`. This also keeps actionlint's shellcheck integration clean (no unquoted expansions).

**6.3 Same treatment for `Set image tags`:**
```yaml
- name: Set image tags
  env:
    TAG: ${{ steps.pick.outputs.tag }}
    ENV_NAME: ${{ steps.pick.outputs.name }}
  run: |
    # yq v4 (mikefarah) is preinstalled on ubuntu-latest runners
    FILE="envs/${ENV_NAME}/values.yaml"
    yq -i ".backend.tag = strenv(TAG) | .frontend.tag = strenv(TAG)" "$FILE"
    cat "$FILE"
```
`strenv(TAG)` is yq's native env injection and forces a string. This matters because of YAML type coercion in `envs/<env>/values.yaml`, not because of the rendered image: both `backend.yaml` and `frontend.yaml` already wrap the whole image in double quotes, so an integer tag would still render a valid reference. The real hazard is the values file itself - a short SHA such as `1234e56` would be parsed as a float and rewritten as `1.234e+59`, and leading-zero SHAs can be mangled the same way.

**6.4 `concurrency`: NOT added. Decision: drop it from this card, and handle the real race at the push.**

Reasoning, stated plainly because revision 1 of this plan got it wrong:
- GitHub keeps at most **one pending run per concurrency group** and cancels the older pending run when a newer one arrives. So `cancel-in-progress: false` does **not** mean "everything queues and lands"; it means "the newest pending run wins and the middle one is dropped". Writing the opposite into a comment would put a false statement in the repo.
- A **per-branch** group (`update-deploy-${{ ... head_branch }}`) puts dev and prod in different groups and therefore does not prevent the race it was supposed to prevent: a `development` run and a `main` run both push to the same `deploy` branch and can still collide.
- A **constant** group (`update-deploy`) does serialise dev and prod, but at the cost of the cancellation behaviour above: a queued dev update can be cancelled by a later prod update and the dev tag is then never written at all. For this workflow every update must land, so cancellation is the worse failure mode.
- Conclusion: `concurrency` is the wrong tool here. No `concurrency` block is added. The actual race is handled where it occurs - at the push - with a rebase-and-retry, which is correct regardless of how runs are scheduled:

```yaml
- name: Commit and push
  env:
    ENV_NAME: ${{ steps.pick.outputs.name }}
    TAG: ${{ steps.pick.outputs.tag }}
  run: |
    git config user.name  "github-actions[bot]"
    git config user.email "github-actions[bot]@users.noreply.github.com"
    git add envs
    git diff --cached --quiet && { echo "nothing changed"; exit 0; }
    git commit -m "deploy(${ENV_NAME}): ${TAG}"
    # The dev and prod runs write to the same branch and can collide.
    # Rebase onto whatever landed first and retry; the two runs touch
    # different files (envs/dev vs envs/prod), so the rebase is clean.
    for attempt in 1 2 3; do
      git push origin HEAD:deploy && exit 0
      echo "push rejected (attempt ${attempt}), rebasing onto origin/deploy"
      git pull --rebase origin deploy
    done
    echo "could not push to deploy after 3 attempts"
    exit 1
```
Notes: GitHub runs `run:` blocks with `bash -e`, and a command on the left of `&&` does not trip `errexit`, so both the "nothing changed" early exit and the retry loop behave as written. If a push is ultimately impossible the step fails loudly (red run) instead of silently skipping the deploy.

### 7. Delete `docs/stage5/`

After the moves, remove the whole tree with `rm -r docs/stage5`. This includes the emptied `docs/stage5/infra/...` and `docs/stage5/.github/workflows`. **Do not touch `docs/plans/`.**

### 8. `README.md` - one short section (~10 lines, no more)

The Layout block (line 19) already lists `infra/`, which until now pointed at nothing. Add a "Deploy (Helm + Argo CD)" section after the existing "CI and Docker images" section (line 69), held to roughly ten lines:
- `infra/helm/pet-project` - the chart (backend, frontend, in-cluster Postgres, Ingress).
- `infra/argocd/apps.yaml` - two Argo CD Applications (`pet-project-dev` -> namespace `dev` from branch `development`, `pet-project-prod` -> namespace `prod` from branch `main`); applied once by hand with `kubectl apply -f infra/argocd/apps.yaml`.
- Environment values live on the `deploy` branch (`envs/dev/values.yaml`, `envs/prod/values.yaml`); `.github/workflows/update-deploy.yml` writes the image tag there after a successful "Build images" run.
- Add `dev.pet.local` / `pet.local` to `/etc/hosts` for the local k3d cluster.

Do not expand beyond this; no new Layout entries, no tables.

### Full file list

| Action | Path |
|---|---|
| Create (moved, unchanged) | `infra/helm/pet-project/Chart.yaml` |
| Create (moved, comments edited) | `infra/helm/pet-project/values.yaml` |
| Create (moved, unchanged) | `infra/helm/pet-project/templates/backend.yaml` |
| Create (moved, unchanged) | `infra/helm/pet-project/templates/frontend.yaml` |
| Create (moved, unchanged) | `infra/helm/pet-project/templates/postgres.yaml` |
| Create (moved, unchanged) | `infra/helm/pet-project/templates/ingress.yaml` |
| Create (moved, repoURL + comment edited) | `infra/argocd/apps.yaml` |
| Create (moved, hardened) | `.github/workflows/update-deploy.yml` |
| Modify | `README.md` |
| Delete (whole directory) | `docs/stage5/` |

No backend, frontend, Gradle, npm or DB migration changes. No changes to the `deploy` branch.

---

## Tests

This is infrastructure work: it is proven by verification commands, not by new unit tests.

Let `SCRATCH=/tmp/claude-1000/-workspace/e81cfe37-b286-4cfc-8dc3-6596025f001e/scratchpad`.

### T0 - tooling (already done by the coordinator)

`helm`, `actionlint`, `yq` and `kubectl` are **not** installed in the dev container. The coordinator has already downloaded the first three into `$SCRATCH/bin`; put that directory on PATH before running anything below:

```bash
export PATH="$SCRATCH/bin:$PATH"
helm version        # v3.22.0
actionlint -version # 1.7.12
yq --version        # v4.53.6
```

`kubectl` is **not** available and is not needed - no acceptance criterion requires a cluster.

**PyYAML is not available and cannot be installed** (`python3 -m pip` is absent from the image). Therefore every YAML assertion below uses `yq` rather than Python. `yq` is the same tool the workflow uses on the runner, so this is a faithful check, not a downgrade.

If a tool is somehow missing at execution time, do **not** fake a pass: AC1, AC2 and AC3 each require a real tool, and per CLAUDE.md ("a task is not done until the tests pass") the implementer must stop and report the unverified criteria to the user rather than substitute a weaker check.

### T1 - AC1: `helm lint` passes

```bash
helm lint infra/helm/pet-project
```
Must exit 0 and print `1 chart(s) linted, 0 chart(s) failed`. `[INFO] Chart.yaml: icon is recommended` is expected and does not affect the exit code. The AC is plain `helm lint`; `--strict` is not part of it and is not run.

### T2 - AC2: `helm template` renders with dev and prod values from the `deploy` branch

**T2a - prove the values are the real ones from `origin/deploy`.** The AC names the `deploy` branch, so the proof must not rest on a scratchpad copy of unknown freshness. These commands are read-only and do not modify, check out or push the branch:
```bash
cd /workspace
# No extra fetch: `git fetch origin` already ran during branch setup for this card,
# so refs/remotes/origin/deploy is current as of the start of this task. If the diffs
# below are non-empty, report it - do NOT fetch again without asking the user
# (CLAUDE.md: no git operations unless explicitly asked).
git show origin/deploy:envs/dev/values.yaml  > "$SCRATCH/deploy-dev-values.yaml"
git show origin/deploy:envs/prod/values.yaml > "$SCRATCH/deploy-prod-values.yaml"
diff -u "$SCRATCH/deploy-envs/dev/values.yaml"  "$SCRATCH/deploy-dev-values.yaml"
diff -u "$SCRATCH/deploy-envs/prod/values.yaml" "$SCRATCH/deploy-prod-values.yaml"
```
Both diffs must be empty. If they are not, use the `git show` output (the branch is authoritative) and report the drift. Use the `$SCRATCH/deploy-*-values.yaml` files for T2b. These files stay in the scratchpad and must never be added to this branch.

**T2b - render both environments:**
```bash
helm template pet-project-dev infra/helm/pet-project \
  --namespace dev  -f "$SCRATCH/deploy-dev-values.yaml"  > "$SCRATCH/render-dev.yaml"
helm template pet-project-prod infra/helm/pet-project \
  --namespace prod -f "$SCRATCH/deploy-prod-values.yaml" > "$SCRATCH/render-prod.yaml"
```
Both must exit 0. Then assert the overrides actually took effect - this is the concrete proof for the consistency check in Changes section 4:

| Assertion | dev render | prod render |
|---|---|---|
| backend image | `ghcr.io/katran1990/pet-project/backend:development` | `...backend:main` |
| frontend image | `ghcr.io/katran1990/pet-project/frontend:development` | `...frontend:main` |
| Deployment `backend` replicas | `1` | `2` |
| StatefulSet `volumeClaimTemplates[0]` storage request | `1Gi` | `5Gi` |
| Ingress host | `dev.pet.local` | `pet.local` |
| Service names present | `backend`, `frontend`, `postgres` | same |

Check the assertions with `yq` over the multi-document render, e.g.
`yq 'select(.kind == "Deployment" and .metadata.name == "backend") | .spec.replicas' "$SCRATCH/render-prod.yaml"`.
The storage value lives in the StatefulSet, not in a separate PVC object, so query it as
`yq -N 'select(.kind == "StatefulSet") | .spec.volumeClaimTemplates[0].spec.resources.requests.storage' "$SCRATCH/render-prod.yaml"` -
a `select(.kind == "PersistentVolumeClaim")` query would return nothing and exit 0, i.e. pass silently.
**An empty `yq` result counts as a failure, never as a pass** - every assertion must print a value.
`yq` failing to parse a render file is itself a failure - that doubles as the "every rendered document is valid YAML" check.

Keep the render files in `$SCRATCH`; they must not land in the repo.

### T3 - AC3: `actionlint` passes on `update-deploy.yml`

The acceptance criterion covers **only** the new file:
```bash
actionlint .github/workflows/update-deploy.yml
```
Must exit 0 with no output.

Informational only:
```bash
actionlint .github/workflows/*.yml
```
`ci.yml` and `build-images.yml` are already merged and **out of scope for this card**. If actionlint flags anything in them, **report it to the user and leave them untouched** - that belongs in its own card.

If `shellcheck` is present, actionlint runs it automatically on `run:` blocks - desirable, and the reason for the `env:`-variable refactor in Changes sections 6.2/6.3. There is no fallback for this AC: without actionlint, AC3 is not proven.

### T4 - AC4: `apps.yaml` is valid YAML with two Applications

Using `yq` (PyYAML is unavailable, see T0). `yq` parses the file, which is itself the validity check; then assert the structure:

```bash
cd /workspace
F=infra/argocd/apps.yaml
# exactly two documents, both Argo CD Applications
test "$(yq -N 'documentIndex' "$F" | wc -l)" -eq 2   # -N is required: without it yq prints a --- separator between documents
test "$(yq -N '[.apiVersion] | join("")' "$F" | sort -u)" = "argoproj.io/v1alpha1"
test "$(yq -N '.kind' "$F" | sort -u)" = "Application"
# names, namespaces, project, destinations
test "$(yq -N '.metadata.name' "$F" | paste -sd, -)" = "pet-project-dev,pet-project-prod"  # AC4: exactly these two apps
yq -N '.metadata.namespace' "$F"            # argocd, argocd
yq -N '.spec.project' "$F"                  # default, default
yq -N '.spec.destination.server' "$F"       # https://kubernetes.default.svc x2
yq -N '.spec.destination.namespace' "$F"    # dev, prod
# sources: two per app, chart source + $values ref source
yq -N '.spec.sources | length' "$F"                       # 2, 2
yq -N '.spec.sources[0].targetRevision' "$F"              # development, main
yq -N '.spec.sources[0].path' "$F"                        # infra/helm/pet-project x2
yq -N '.spec.sources[0].helm.valueFiles[0]' "$F"          # $values/envs/dev/..., $values/envs/prod/...
yq -N '.spec.sources[1].targetRevision' "$F"              # deploy, deploy
yq -N '.spec.sources[1].ref' "$F"                         # values, values
# every repoURL identical and pointing at the real repository
test "$(yq -N '.spec.sources[].repoURL' "$F" | sort -u)" = "https://github.com/Katran1990/pet-project.git"
# sync policy
yq -N '.spec.syncPolicy.automated.prune' "$F"             # true, true
yq -N '.spec.syncPolicy.automated.selfHeal' "$F"          # true, true
yq -N '.spec.syncPolicy.syncOptions[]' "$F"               # CreateNamespace=true x2
```
Every value printed must match the comment next to it; the `test` assertions must exit 0. Report the actual output, not just "OK".

### T5 - repository hygiene (card requirement, not an AC)

- `test ! -e docs/stage5` - the directory is gone.
- `test -d docs/plans` - plans are untouched.
- `test -d frontend/node_modules` - the Docker volume was never touched.
- `git status --short` shows only the intended additions/deletions, and **no** files under `envs/` (the `deploy` branch values must never be copied into this branch).

### T6 - smoke/regression run (not evidence for any AC)

This card changes no application code, so this is a safety net only, run because CLAUDE.md requires a green tree:
- `cd backend && ./gradlew test`
- `cd frontend && npm run lint && npm run build` (do **not** run `npm ci` - it wipes the `node_modules` volume contents)

Both must be green exactly as before. Neither proves AC1-AC4.

### T7 - post-merge checks (cannot run here; report to the user)

- **`workflow_run` only fires from the default branch.** GitHub runs a `workflow_run`-triggered workflow from the copy of the file on the **default branch** (`main` here); the `branches: [development, main]` filter applies to the *triggering* run's branch, not to where `update-deploy.yml` lives. So merging this PR into `development` alone will **not** make "Update deploy manifests" run - nothing happens until the file has also landed on `main`. Do not report that silence as a bug.
- Once the file is on `main`: after a merge into `development`, "Build images" runs, then "Update deploy manifests" must run and push a commit `deploy(dev): <sha7>` to the `deploy` branch, with `backend.tag`/`frontend.tag` updated in `envs/dev/values.yaml` (and quoted as strings - see Changes section 6.3).
- Applying `infra/argocd/apps.yaml` to a real Argo CD and seeing both Applications reach `Synced/Healthy` is the only true end-to-end proof; there is no cluster in this container.

**Done locally when T1-T6 pass.** T7 is a post-merge check.

---

## Risks and decisions

There are no blocking open questions left. Items 1 and 2 are decisions already taken; the rest are accepted risks the user should be aware of.

1. **`permissions: contents: write` on `update-deploy.yml` - settled, approved by the user.** Recorded here only because it is the reason for the script-injection hardening in Changes sections 6.2/6.3.

2. **Postgres password `app/app` - settled, kept (Changes section 3).** Conditions: documented in `values.yaml` and in the implementation report; `envs/prod/values.yaml` does not override it, so "prod" currently comes up with `app/app`; a follow-up card must introduce Sealed/External Secrets before that environment is reachable from outside; the password must never be written into `envs/*`, which `update-deploy.yml` prints with `cat`.

3. **GHCR packages are private by default; the chart has no `imagePullSecrets`.** A real cluster would fail with `ImagePullBackOff` until the packages are made public or a pull secret is added. Not an acceptance criterion and invisible to `helm lint` / `helm template`. Out of scope here, but the user should know.

4. **`repoURL` casing.** Argo CD normalises repository URLs (including case) before comparing, so `katran1990` vs `Katran1990` is cosmetic *provided all four occurrences are identical* - which they are either way. The upstream casing is used because the card explicitly asks for a match with the real repository.

5. **`postgres.storage` is immutable after first sync.** `volumeClaimTemplates` on a StatefulSet cannot be changed in place; the prod `5Gi` is fine on a fresh install, but a later change would make Argo CD's sync fail.

6. **No `ingressClassName` on the Ingress.** Relies on k3d's Traefik being the default IngressClass. True for the intended local cluster; any other cluster would need an explicit class.

7. **`yq` availability on the runner.** mikefarah `yq` v4 is preinstalled on GitHub's `ubuntu-latest` image, so the workflow needs no install step; a future runner-image change would break it. A comment marks the dependency.

8. **No `concurrency` on `update-deploy.yml` (Changes section 6.4).** Two runs can still start simultaneously; the rebase-and-retry push is what makes that safe. If a push still cannot land after three attempts the run fails visibly and the tag update must be replayed by the next merge.

9. **Resource names are intentionally not release-scoped.** `backend`, `frontend` and `postgres` are hardcoded because `frontend/nginx.conf` and the backend's `DB_URL` depend on those exact Service names. Two releases of this chart in the *same* namespace would collide; dev and prod live in separate namespaces, so this is fine. Do not "improve" it without also changing `nginx.conf`.

10. **The `deploy` branch must already exist with `envs/dev` and `envs/prod`.** Confirmed. This PR must not add, move or edit anything under `envs/`.

---

## Out of scope

- Any change to the `deploy` branch (`envs/dev/values.yaml`, `envs/prod/values.yaml`, its README). T2a only *reads* it via `git show`.
- Creating or configuring a k3d cluster, installing Argo CD, running `kubectl apply -f infra/argocd/apps.yaml`, or any live sync.
- Secret management (Sealed Secrets / External Secrets), `imagePullSecrets`, GHCR package visibility - **follow-up card** (see Risks 2 and 3).
- Adding `helm lint` and `actionlint` jobs to `ci.yml` - worth doing, but it changes an already-merged workflow and **deserves its own card**.
- Fixing anything actionlint reports in the existing `ci.yml` / `build-images.yml` (T3): report only.
- `.helmignore`, chart `icon`, `templates/NOTES.txt`, `values.schema.json`, `helm-unittest`, chart packaging/publishing to a chart registry.
- TLS/cert-manager on the Ingress, HPA, PodDisruptionBudgets, `securityContext` hardening, network policies, a managed database instead of the in-cluster StatefulSet.
- Argo CD `finalizers`, `AppProject` definitions, ApplicationSets, notifications, sync waves.
- Changes to `ci.yml`, `build-images.yml`, the Dockerfiles, `nginx.conf` or any application code.
- Expanding the README beyond the ~10-line section in Changes section 8.
- Any git operation that writes (commit, branch, `git mv`, push) - the coordinator handles those after approval. The only git commands in this plan are the read-only `git fetch` / `git show` / `git status` in T2a and T5.
