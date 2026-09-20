# Plan: Move deploy config to a separate repository

Trello card: https://trello.com/c/ZCXcJ5JM/7-move-deploy-config-to-a-separate-repository

Branch: `feature/move-deploy-config-to-separate-repo` (based on `origin/development`).

*Revision 2 - addresses the plan review: explicit `persist-credentials: true` (blocking 1),
verification for the README/chart-comment criterion (blocking 2), AC3 reported as UNVERIFIED when
the offline fallback is used (blocking 3), plus the accepted non-blocking suggestions 1-5, 7, 8.*

---

## Goal

Point the Argo CD value source and the `Update deploy manifests` workflow at the external
repository `https://github.com/Katran1990/pet-project-deploy` (branch `main`) instead of the
`deploy` branch of `pet-project`, so Argo CD no longer has to resolve a multi-source app that
references two revisions of the same repository. Documentation (README, chart comments) follows
the new layout.

## Acceptance criteria

- [x] AC1 - `infra/argocd/apps.yaml`: second source points to the `pet-project-deploy` repo, `targetRevision: main`
- [x] AC2 - `.github/workflows/update-deploy.yml`: checks out `Katran1990/pet-project-deploy` (ref `main`) with token `secrets.DEPLOY_REPO_TOKEN` and pushes there
- [x] AC3 - `helm template` still renders with values from the deploy repo
- [x] AC4 - `actionlint` passes
- [x] AC5 - README deploy section updated

## Changes

### 1. `/workspace/infra/argocd/apps.yaml`

Two edits, applied identically to both Applications (`pet-project-dev`, `pet-project-prod`).

**1.1 Second source in each Application** (current lines 26-28 and 53-55):

```yaml
    - repoURL: https://github.com/Katran1990/pet-project-deploy.git
      targetRevision: main
      ref: values
```

Everything else stays: the first source keeps `repoURL: https://github.com/Katran1990/pet-project.git`
with `targetRevision: development` (dev) / `main` (prod), `path: infra/helm/pet-project`, and
`helm.valueFiles: [$values/envs/dev/values.yaml]` / `[$values/envs/prod/values.yaml]`.
The `$values` prefix and the file paths do **not** change - the deploy repo keeps the same
`envs/dev/values.yaml` / `envs/prod/values.yaml` layout. `syncPolicy` is untouched.

**1.2 The header comment (lines 1-8) is now wrong and must be rewritten.**
The old text says *"repoURL is the real repository (.../pet-project.git); all four occurrences below
must stay byte-identical or the `$values` ref won't resolve."* After this change there are two
distinct repoURLs (two occurrences each), and byte-identity across all four is exactly what must
**not** happen. What binds the sources together is the ref name: the values source declares
`ref: values` and the chart source refers to it as `$values/...`. Keeping the dev and prod spelling
of each URL identical stays good practice (it makes the two Applications obviously the same pair),
but the comment must not claim a technical requirement it cannot back up. Proposed replacement:

```yaml
# Argo CD Applications. Apply once by hand: kubectl apply -f infra/argocd/apps.yaml
#
# Each app has two sources:
#   1. the Helm chart from this repo (branch development for dev, main for prod)
#   2. environment values from the deploy repo
#      (https://github.com/Katran1990/pet-project-deploy.git, branch main), which CI
#      pushes to (see .github/workflows/update-deploy.yml)
#
# The two sources deliberately point at different repositories: our Argo CD rejected a
# multi-source app that referenced two revisions of the same repoURL, which is why the
# environment values moved out of the `deploy` branch of this repo.
# The sources are tied together by the ref name, not by the URL - the values source
# declares `ref: values` and the chart source consumes it as
# `$values/envs/<env>/values.yaml`. Keep that name and the `$values` prefix in sync.
# Style: spell each repoURL the same way in the dev and the prod Application, so the
# two obviously refer to the same pair of repositories.
```

*(Review suggestions 1 and 2 accepted: no "byte-identical / normalised URL credential matching"
justification, and the Argo CD behaviour is phrased as what we observed rather than as a law. If
the implementer knows the cluster's Argo CD version, adding it in place of "our Argo CD" is
welcome but not required.)*

### 2. `/workspace/.github/workflows/update-deploy.yml`

**2.1 Header comment (lines 1-6):** replace "on the `deploy` branch" with the deploy repo:
"Writes the new image tag (short commit SHA) into `envs/<env>/values.yaml` in
`Katran1990/pet-project-deploy` (branch `main`). Argo CD notices the commit and rolls out the new
version." Keep the `development -> envs/dev/values.yaml` / `main -> envs/prod/values.yaml` mapping
lines. Keep the workflow `name: Update deploy manifests` and the `workflow_run` trigger unchanged
(the workflow still reacts to "Build images" on `development`/`main` of *this* repo).

**2.2 Top-level `permissions` (lines 15-16):** the workflow no longer pushes to this repository
and no longer checks it out, so `contents: write` is no longer needed. Change to:

```yaml
permissions:
  contents: read         # the deploy repo is written with DEPLOY_REPO_TOKEN, not GITHUB_TOKEN
```

Keeping the block - rather than dropping it - preserves the least-privilege convention used by
`ci.yml` and `build-images.yml`. No step consumes `GITHUB_TOKEN` any more, so nothing breaks.

**2.3 Checkout step (lines 37-39):** target the external repository.

```yaml
      - uses: actions/checkout@v6
        with:
          repository: Katran1990/pet-project-deploy
          ref: main
          token: ${{ secrets.DEPLOY_REPO_TOKEN }}
          persist-credentials: true   # keep the token in the checkout's git config so the push below authenticates
          fetch-depth: 0              # full history: the push step rebases, which is fragile on a shallow clone
```

`persist-credentials: true` is set **explicitly and must not be removed as redundant.** The whole
push path depends on it, and the failure mode is a 403 that can only surface after this lands on
`main` - a place no pre-merge check can reach (see T4). Whether v6 still defaults to `true` is not
something this plan verifies, and the insurance costs one line. With it set, `actions/checkout`
stores the token as an `http.extraheader` in the checkout's local git config, and the later
`git push` / `git pull --rebase` against `origin` (now the deploy repo) authenticate with no extra
step.

**Prohibited alternative:** the implementer must **not** authenticate by rewriting the remote, e.g.
`git remote set-url origin https://x-access-token:${TOKEN}@github.com/...`. That writes the secret
into `.git/config` and into any git error message the runner logs. The checkout-managed
`extraheader` is the only accepted mechanism. No step may `echo`, `cat` or otherwise print the
token or the git config.

Other notes: do **not** add a `path:` - the workflow's `yq`/`git` commands assume the deploy repo
is the working directory root. `fetch-depth: 0` is a deliberate deviation from the previous default
depth; the deploy repo holds two small YAML files, so the cost is negligible and it removes the
shallow-clone edge cases in `git pull --rebase`.

**2.4 "Set image tags" step:** unchanged. `FILE="envs/${ENV_NAME}/values.yaml"` is already relative
to the checkout root, which is now the deploy repo root. Keep the `strenv(TAG)` usage and the `cat`
(the files must still contain no secrets - see the note in the chart's `values.yaml`).

**2.5 "Commit and push" step (lines 51-70):** keep the structure, retarget the branch, narrow the
`git add`, and update the comment. The rebase-retry loop **must stay**: the dev run and the prod
run now both write to `main` of the deploy repo, so the collision it guards against is exactly the
same as before - only the branch name changed. It is in fact slightly more valuable now, because a
human can also push to that branch directly. The two runs still touch different files
(`envs/dev` vs `envs/prod`), so the rebase stays clean.

```bash
          git config user.name  "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          # Stage exactly the file this run rewrote: the deploy repo is shared, and a
          # layout change must fail loudly instead of turning into a silent no-op.
          git add -- "envs/${ENV_NAME}/values.yaml"
          git diff --cached --quiet && { echo "nothing changed"; exit 0; }
          git commit -m "deploy(${ENV_NAME}): ${TAG}"
          # origin is the deploy repo. The dev and prod runs write to the same branch
          # and can collide; rebase onto whatever landed first and retry. The two runs
          # touch different files (envs/dev vs envs/prod), so the rebase is clean.
          for attempt in 1 2 3; do
            git push origin HEAD:main && exit 0
            echo "push rejected (attempt ${attempt}), rebasing onto origin/main"
            git pull --rebase origin main || { git rebase --abort; exit 1; }
          done
          echo "could not push to the deploy repo after 3 attempts"
          exit 1
```

*(Review suggestions 3 and 5 accepted: `git add -- "envs/${ENV_NAME}/values.yaml"` makes the
former risk 7 a loud failure, and `|| { git rebase --abort; exit 1; }` leaves a deterministic
working tree if the rebase ever conflicts instead of dying mid-rebase under `bash -e`.)*

No `concurrency:` block is added (same reasoning as the previous card: a constant group would let a
queued dev update be cancelled by a later prod update; the retry loop is the safety net).

### 3. `/workspace/infra/helm/pet-project/values.yaml` - comments only, no value changes

Four comments still say "deploy branch" (lines 1, 8, 16, 29) and become misleading; this is a
doc-only edit that keeps the chart consistent with AC1/AC3:

- lines 1-2 header: "Environment-specific overrides live in the deploy repo
  (`Katran1990/pet-project-deploy`, branch `main`: `envs/dev/values.yaml`, `envs/prod/values.yaml`)
  and are applied by Argo CD."
- lines 8 and 16 (`tag: development`): "... the real tag comes from `envs/<env>/values.yaml` in the deploy repo".
- line 29 (postgres password note): replace "deploy branch" with "deploy repo"; keep the
  substance (prod currently comes up with `app/app`; never put a real password into `envs/*`
  because `update-deploy.yml` prints those files with `cat`). Line 32 already says `envs/*`, not
  "deploy branch" - leave it alone.

After the edit no line in this file may still read "the deploy branch" (verified in T6). Rendered
output is unchanged, so this cannot break AC3 or AC4.

### 4. `/workspace/README.md` - "Deploy (Helm + Argo CD)" section (lines 88-99)

Rewrite the third bullet and add the deploy-repo lines. Target content:

- `infra/helm/pet-project` - the chart (backend, frontend, in-cluster Postgres, Ingress). *(unchanged)*
- `infra/argocd/apps.yaml` - two Argo CD Applications ... *(unchanged, plus: each app has two sources -
  the chart from this repo and the environment values from the deploy repo)*.
- Environment values live in a **separate repository**,
  `https://github.com/Katran1990/pet-project-deploy` (branch `main`, files `envs/dev/values.yaml`
  and `envs/prod/values.yaml`). They were moved out of the `deploy` branch of this repo because
  Argo CD rejected a multi-source Application that referenced two revisions of the same repository.
- `.github/workflows/update-deploy.yml` writes the image tag into that repo after a successful
  "Build images" run, using the repository secret `DEPLOY_REPO_TOKEN` (a token with write access to
  `pet-project-deploy`). *(Name the secret only; never document its value.)*
- The `deploy` branch of this repository is superseded - nothing reads or writes it any more; it is
  kept as-is for history. *(See item 5.)*
- Add `dev.pet.local` / `pet.local` to `/etc/hosts` for the local k3d cluster. *(unchanged)*

The deploy repo is public (confirmed by the user, risk 8c), so **do not** add a sentence about
`argocd repo add` - no Argo CD repository credential is needed.

Exactly two sentences in the whole README may still mention the `deploy` branch: the bullet
explaining *why* the values moved out of it, and the "superseded" bullet. No wording of the form
"values live on the `deploy` branch" may survive (verified in T6).

### 5. The old `deploy` branch of `pet-project` - explicit decision

**Decision: leave it alone.** This task does not delete, rename, archive or modify the remote
`deploy` branch, and no `git push --delete` is planned - CLAUDE.md forbids git operations that were
not explicitly requested, and branch deletion is out of scope for this card. After this change
nothing reads it (Argo CD points at the deploy repo) and nothing writes it (the workflow pushes to
the deploy repo), so it becomes a frozen snapshot of the pre-move state. This is documented in the
README bullet above so the branch is not mistaken for a live source. Cleaning it up is a follow-up
the user can do by hand later.

## Tests

This is infrastructure work: it is proven by verification commands, not by new unit tests. No
backend/frontend/Gradle/npm/DB changes, so `./gradlew test` and `npm run lint` are unaffected (run
them only if something unexpected is touched).

Coverage map: T1 -> AC1, T2 -> AC3, T3 -> AC4, T4 -> AC2, T6 -> AC5 (plus the chart comments from
Changes item 3), T5 -> repository hygiene.

Let `SCRATCH` be the session scratchpad directory. All artefacts below stay there and must never be
committed.

### T0 - tooling check (run first, before claiming any AC)

```bash
command -v helm actionlint yq || true
helm version; actionlint -version; yq --version
```

These tools are **not** part of the dev container image (the previous card had to download them).
Fallback, in this order:

1. If a previous session left them in a scratchpad `bin`, `export PATH="$SCRATCH/bin:$PATH"` and re-check.
2. Otherwise download the static release binaries into `$SCRATCH/bin` (helm from `get.helm.sh`,
   `actionlint` and `yq` from their GitHub releases) and put that directory on `PATH`. Never install
   into the system image and never touch `frontend/node_modules`.
3. If a tool cannot be obtained (no network), **do not fake a pass**: report exactly which
   acceptance criteria are UNVERIFIED. Per CLAUDE.md a task is not done until the tests pass.

`kubectl` is not needed - no criterion requires a cluster.

### T1 - AC1: `apps.yaml` second source

```bash
F=/workspace/infra/argocd/apps.yaml
yq -N '.spec.sources | length' "$F"                 # expect: 2, 2
yq -N '.spec.sources[1].repoURL' "$F"               # expect: https://github.com/Katran1990/pet-project-deploy.git (twice)
yq -N '.spec.sources[1].targetRevision' "$F"        # expect: main, main
yq -N '.spec.sources[1].ref' "$F"                   # expect: values, values
yq -N '.spec.sources[0].repoURL' "$F"               # expect: https://github.com/Katran1990/pet-project.git (twice)
yq -N '.spec.sources[0].targetRevision' "$F"        # expect: development, main
yq -N '.spec.sources[0].helm.valueFiles[0]' "$F"    # expect: $values/envs/dev/values.yaml, $values/envs/prod/values.yaml
```

Every query must print a value; an empty result is a failure, not a pass. `yq` parsing the file at
all doubles as the YAML-validity check. Also read the rewritten header comment and confirm it no
longer claims that all four repoURLs must be byte-identical.

### T2 - AC3: `helm lint` + `helm template` with values from the deploy repo

**T2a - obtain the real values from the deploy repo.** The criterion says "values from the deploy
repo", so the proof must use that repo's current content. Preferred, read-only, into the scratchpad
only:

```bash
gh api repos/Katran1990/pet-project-deploy/contents/envs/dev/values.yaml \
  -H "Accept: application/vnd.github.raw" > "$SCRATCH/deploy-dev-values.yaml"
gh api repos/Katran1990/pet-project-deploy/contents/envs/prod/values.yaml \
  -H "Accept: application/vnd.github.raw" > "$SCRATCH/deploy-prod-values.yaml"
```

If `gh` is missing or unauthenticated, ask the user before running any network git command; with
their go-ahead, `git clone --depth 1 https://github.com/Katran1990/pet-project-deploy.git
"$SCRATCH/deploy-repo"` (a clone into the scratchpad - it does not touch the project repo, its
remotes or its branches).

**If neither works, AC3 is reported as UNVERIFIED.** The offline fallback
(`git show origin/deploy:envs/dev/values.yaml`, `...:envs/prod/values.yaml` from the local
`origin/deploy` ref) renders the chart against the **pre-migration snapshot**, not against
`Katran1990/pet-project-deploy`. Running it is still useful as a *chart smoke test* - it shows the
chart renders with values of that shape - but it does not satisfy a criterion whose whole point is
the new location. In that case the implementer must:

- report AC3 as **UNVERIFIED (offline fallback used: chart smoke test against `origin/deploy` only)**,
- not tick the AC3 checkbox,
- and hand the user the exact commands to run once network access to the deploy repo exists.

Do not soften this into "passed with a note".

**T2b - lint and render** (substitute the actual paths T2a produced - the `git clone` branch puts
them under `$SCRATCH/deploy-repo/envs/<env>/values.yaml`, not at the filenames used below):

```bash
helm lint /workspace/infra/helm/pet-project
helm template pet-project-dev /workspace/infra/helm/pet-project \
  --namespace dev  -f "$SCRATCH/deploy-dev-values.yaml"  > "$SCRATCH/render-dev.yaml"
helm template pet-project-prod /workspace/infra/helm/pet-project \
  --namespace prod -f "$SCRATCH/deploy-prod-values.yaml" > "$SCRATCH/render-prod.yaml"
```

All three must exit 0 (`helm lint` prints `1 chart(s) linted, 0 chart(s) failed`; the
`icon is recommended` INFO is expected). Then assert the overrides took effect, with `yq` over the
multi-document renders - the same table the previous card used, so a regression is visible:

| Assertion | dev render | prod render |
|---|---|---|
| backend image | `ghcr.io/katran1990/pet-project/backend:<dev tag>` | `.../backend:<prod tag>` |
| frontend image | `ghcr.io/katran1990/pet-project/frontend:<dev tag>` | `.../frontend:<prod tag>` |
| Deployment `backend` replicas | value from dev values | value from prod values |
| StatefulSet `volumeClaimTemplates[0]` storage request | value from dev values | value from prod values |
| Ingress host | `dev.pet.local` | `pet.local` |
| Services present | `backend`, `frontend`, `postgres` | same |

Example: `yq -N 'select(.kind == "StatefulSet") | .spec.volumeClaimTemplates[0].spec.resources.requests.storage' "$SCRATCH/render-prod.yaml"`.
An empty `yq` result counts as a failure.

### T3 - AC4: `actionlint`

```bash
cd /workspace
actionlint .github/workflows/update-deploy.yml    # must exit 0, no output
actionlint .github/workflows/*.yml                # informational: nothing else regressed
```

`actionlint` also shellchecks the `run:` blocks, which covers the rewritten push loop.

### T4 - AC2: workflow content (static review, the only check possible pre-merge)

```bash
W=/workspace/.github/workflows/update-deploy.yml
yq -N '.jobs.update.steps[] | select(.uses == "actions/checkout@v6") | .with' "$W"
# expect all five keys:
#   repository: Katran1990/pet-project-deploy
#   ref: main
#   token: ${{ secrets.DEPLOY_REPO_TOKEN }}
#   persist-credentials: true
#   fetch-depth: 0
yq -N '.jobs.update.steps[] | select(.uses == "actions/checkout@v6") | .with."persist-credentials"' "$W"   # expect: true
yq -N '.permissions' "$W"                          # expect: contents: read
grep -n 'HEAD:main\|origin main' "$W"              # push/pull target the deploy repo's main
grep -n 'ref: deploy\|HEAD:deploy' "$W"            # expect: no matches
grep -n 'x-access-token\|remote set-url' "$W"      # expect: no matches (prohibited auth mechanism)
```

End-to-end behaviour cannot be proven from this branch: `workflow_run` workflows always run from the
copy of the file on the **default branch** (`main`), so nothing changes until this lands on `main`.
This is precisely why `persist-credentials: true` is pinned explicitly rather than assumed.
Post-merge check to hand to the user: after a merge into `development`, "Build images" runs, then
"Update deploy manifests" must push a commit `deploy(dev): <sha7>` to
`Katran1990/pet-project-deploy@main` with `backend.tag`/`frontend.tag` updated in
`envs/dev/values.yaml`.

### T5 - repository hygiene

```bash
cd /workspace && git status --short
```

Only the four intended files (plus this plan document) may appear. Nothing under `envs/`, no
scratchpad artefacts, no changes to remotes or branches.

### T6 - AC5: README (and the chart comments from Changes item 3)

```bash
R=/workspace/README.md
grep -n 'pet-project-deploy' "$R"        # must match: the new repo is documented
grep -n 'DEPLOY_REPO_TOKEN' "$R"         # must match: the secret is named (name only, never a value)
grep -n 'envs/dev/values.yaml' "$R"      # must match: the file layout is documented
grep -n 'deploy` branch\|deploy branch' "$R"
# Exactly two acceptable hits: the bullet explaining why the values moved out of that
# branch, and the "superseded / kept for history" bullet. Any surviving claim that the
# values live on the deploy branch is a failure.
grep -n 'values live on\|live on the `deploy`' "$R"   # expect: no matches

V=/workspace/infra/helm/pet-project/values.yaml
grep -n 'deploy branch\|deploy` branch' "$V"          # expect: no matches after the edit
grep -n 'deploy repo' "$V"                            # must match on the lines that were updated (1-2, 8, 16, 29)
```

Then read the rendered "Deploy (Helm + Argo CD)" section once end to end and confirm it describes
the new layout: chart here, values in `pet-project-deploy@main`, CI pushes there with
`DEPLOY_REPO_TOKEN`, old branch superseded. A criterion that is only "looks fine" is not done -
each grep above must have the stated outcome.

## Risks and open questions

1. **`DEPLOY_REPO_TOKEN` scope.** The plan assumes the existing secret grants write access to
   contents of `Katran1990/pet-project-deploy`. If it is a fine-grained PAT scoped to the wrong
   repository or missing `Contents: read/write`, the push fails at runtime with a 403. Nothing in
   this plan creates, prints or inspects the secret; if the push fails post-merge, the user must
   re-issue it. No verification of the token is possible from this branch.
2. **Branch protection on `pet-project-deploy@main`.** If `main` there requires PRs or reviews, a
   direct push from CI is rejected and the retry loop simply fails three times. Unknown from here -
   open question 8b for the user; if protection exists, either exempt the bot or switch the workflow
   to open a PR (a different, larger design, not planned here).
3. **Argo CD repository credentials.** If `pet-project-deploy` is private, Argo CD needs its own
   credential for that URL; a public chart repo plus a private values repo is a common trip-up. The
   conditional README sentence covers the documentation side, but the actual `argocd repo add` is a
   cluster operation outside this repository.
4. **Transition window.** Until this change reaches `main`, the *old* `update-deploy.yml` from
   `main` is what `workflow_run` executes, so merges into `development` keep writing to the `deploy`
   branch while `apps.yaml` (once applied) already reads the deploy repo. During that window dev
   tags may not roll out. Applying the new `apps.yaml` to the cluster is a manual `kubectl apply`
   the user controls - recommended order: merge to `main` first, then apply. Confirm with the user
   (open question 8a).
5. **`fetch-depth: 0`** is an addition, not an explicit criterion. It is cheap insurance for
   `git pull --rebase`. If the reviewer prefers the smallest possible diff, dropping it restores the
   previous (default, shallow) behaviour, which also worked on the `deploy` branch.
   `persist-credentials: true`, by contrast, is **not** optional - see Changes 2.3.
6. **Values drift / offline verification.** If T2a has to fall back to the local `origin/deploy`
   ref, the render proves nothing about the deploy repo's current content. Per T2a this means AC3 is
   reported UNVERIFIED, not passed.
7. **Post-merge is the first real test of AC2.** Static checks (T4) cover the file's content; the
   authenticated push can only be observed after the workflow file exists on `main`. Ask the user to
   watch the first "Update deploy manifests" run and report the commit that lands in the deploy repo.
8. **Open questions - ANSWERED by the user, no longer open:**
   a. Rollout order: merge this branch into `development`, then open a `development` -> `main` PR,
      and only then `kubectl apply -f infra/argocd/apps.yaml`. The transition window described in
      risk 4 is accepted knowingly.
   b. There is **no** branch protection on `Katran1990/pet-project-deploy@main`, so the direct push
      from CI is fine and risk 2 does not apply.
   c. The deploy repo is **public**: `argocd repo add` is not needed, and the conditional README
      sentence in Changes item 4 is **omitted**. Risk 3 does not apply.

## Out of scope

- Deleting, renaming or otherwise modifying the `deploy` branch of `pet-project` (decision and
  rationale in Changes item 5), or any remote-branch operation.
- Any change inside `Katran1990/pet-project-deploy` (its `envs/*` files, README or settings).
- Creating, rotating, printing or validating `DEPLOY_REPO_TOKEN`; configuring repository secrets or
  branch protection. Authenticating by rewriting the git remote with the token in the URL is
  explicitly forbidden (Changes 2.3).
- Argo CD cluster operations: `kubectl apply -f infra/argocd/apps.yaml`, `argocd repo add`, syncing,
  any cluster verification.
- **Updating `/workspace/docs/plans/helm-argocd-manifests.md`.** It contains roughly fifteen
  mentions of the `deploy` branch, all of which were correct when it was written. It is a historical
  record of a finished card and must be left untouched - do not "refresh" it.
- Chart logic, template or default-value changes (only comments in `values.yaml` change); the
  Postgres `app/app` credential decision stays as previously recorded, with the Sealed/External
  Secrets work still a separate follow-up card.
- Backend, frontend, Gradle, npm and database migrations; `ci.yml` and `build-images.yml`.
