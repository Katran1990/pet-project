# Plan: Manage Postgres credentials with Sealed Secrets

Branch: `feature/postgres-sealed-secrets` (based on `origin/development`).
Card: https://trello.com/c/iqQaU78W

---

## Goal

Stop storing the Postgres password as plain text in the Helm chart. Each environment gets its own credentials as a Sealed Secrets `SealedSecret`. The ciphertext is committed inside the chart, one file per namespace, and the Sealed Secrets controller in the cluster decrypts it. This way `dev` and `prod` get different credentials, nothing secret ever appears in `envs/*/values.yaml`, and the deploy repo needs no changes.

## Acceptance criteria

- [ ] AC1 - Postgres credentials are no longer stored in plain text in the repository
- [ ] AC2 - The chart consumes them through Sealed Secrets or External Secrets (pick one, document the choice) -> **Sealed Secrets**
- [ ] AC3 - dev and prod get different credentials
- [ ] AC4 - The password never appears in `envs/*/values.yaml` (`update-deploy.yml` prints those files with `cat` into the build log)
- [ ] AC5 - `helm lint` and `helm template` still pass for dev and prod
- [ ] AC6 - README documents how to rotate the credential

## Design decisions (read before Changes)

**D1 - Where the per-env ciphertext lives: chart files, picked by `.Release.Namespace`.**
New files `infra/helm/pet-project/sealed-secrets/<namespace>/postgres-credentials.yaml` hold the unmodified `kubeseal --format yaml` output. The template loads the file with `.Files.Get`, keyed on `.Release.Namespace`. Argo CD passes the Application's destination namespace (`dev` / `prod`, see `infra/argocd/apps.yaml`) to Helm as the release namespace.
- Why namespace and not a new value such as `postgres.sealedSecretEnv`: a strict-scope SealedSecret only decrypts in the namespace it was sealed for. The namespace is already the real key, so a second selector could only disagree with it. It also needs **no change in `Katran1990/pet-project-deploy`**, which this task must not touch.
- Why not `encryptedData` inside `envs/*/values.yaml`: it breaks AC4 in spirit (secret material in files that CI `cat`s), and we cannot edit the deploy repo.
- Ciphertext in this public repo is safe by design. Only the controller's private key can decrypt it, and strict scope binds it to name `postgres-credentials` plus namespace `dev`/`prod`.
- Promotion: both files live on both branches. The dev file takes effect when merged to `development` (dev app). The prod file takes effect when it reaches `main` (prod app).

**D2 - Scope: strict (the kubeseal default).** The template rejects files that carry the `sealedsecrets.bitnami.com/namespace-wide` or `.../cluster-wide` annotations. As a result a dev ciphertext cannot be replayed into `prod`, and the reverse is also true.

**D3 - Missing or invalid file means the render fails loudly.** A namespace with no sealed file, such as a plain `helm lint` (namespace `default`) or a future `staging`, fails `helm template` with a clear message. The same is true for a namespace whose file exists but is invalid (wrong namespace, non-strict scope, `stringData`/`data`, a missing `encryptedData` key). It does not render a Postgres without credentials, and it never falls back to `app/app`. When Argo CD hits this, the sync fails and nothing is applied. `helm lint` itself, even with `--strict`, only logs the `fail()` message as INFO and does not turn it into a lint failure for a *present* invalid file (verified on Helm v4.3.0); `--strict` only happens to catch the *missing*-file case, and only by accident, via the empty `metadata.name` that the broken render then produces. The canonical check is therefore `helm template` (which fails on both a missing and an invalid file), with `helm lint --strict --namespace dev|prod -f <env values>` run alongside for the chart's other lint checks (AC5 is scoped "for dev and prod"). See Risk 3.

**D4 - Both `user` and `password` are sealed.** The resulting Secret has the same name (`postgres-credentials`) and keys (`user`, `password`) as today. Because of that, `backend.yaml` and the `secretKeyRef`s in `postgres.yaml` stay as they are. `postgres.user` is removed from `values.yaml`. Its only other consumer is the readiness probe, which will read `$POSTGRES_USER` from the container env instead. The username then exists only inside the sealed data.

**D5 - Controller installation: a documented manual prerequisite, not an Argo CD Application.** Reasons:
- It is a cluster-wide install (a CRD plus a controller in `kube-system`), bootstrapped once like Argo CD itself.
- Its private key is the one thing that must be backed up.
- If `prune: true` / `selfHeal` ever removed the CRD, every SealedSecret would be deleted, and every generated Secret with it.

The README pins a chart version and documents key backup. No cluster-wide resource is added to this repo.

**D6 - Backend `application.properties` defaults (`${DB_PASSWORD:app}`) and `.devcontainer/docker-compose.yml` (`POSTGRES_PASSWORD: app`) stay as they are.**
- In the chart path, `DB_USER` / `DB_PASSWORD` always come from a non-optional `secretKeyRef`. If the Secret is missing, the pod fails with `CreateContainerConfigError` before Spring starts, so the `:app` default can never be used in Kubernetes.
- These values are the local dev-container credential documented in README "Prerequisites". They are not a deployed-environment credential.
- AC1 is read as "the deployed environments' credentials". The grep check (T5) is scoped to `infra/` and `.github/`. This is confirmed as Open question Q1.

---

## Changes

### 1. `infra/helm/pet-project/templates/postgres.yaml` - replace the Secret document

Delete the `kind: Secret` document (current lines 3-11) and put in its place a block that loads, validates and emits the SealedSecret. Outline (the exact text may be polished, the behaviour may not):

```yaml
{{- /*
Postgres credentials: a SealedSecret per namespace, committed as kubeseal output under
sealed-secrets/<namespace>/postgres-credentials.yaml (strict scope). The Sealed Secrets
controller decrypts it into the Secret "postgres-credentials" (keys: user, password).
See README, "Postgres credentials (Sealed Secrets)".
*/}}
{{- $path := printf "sealed-secrets/%s/postgres-credentials.yaml" .Release.Namespace }}
{{- $raw := .Files.Get $path }}
{{- if not $raw }}
{{- fail (printf "no sealed Postgres credentials for namespace %q: %s is missing (README, \"Postgres credentials\")" .Release.Namespace $path) }}
{{- end }}
{{- $ss := fromYaml $raw }}
{{- if hasKey $ss "Error" }}{{ fail (printf "%s is not valid YAML: %s" $path $ss.Error) }}{{ end }}
{{- if ne (default "" $ss.kind) "SealedSecret" }}{{ fail (printf "%s must be a SealedSecret" $path) }}{{ end }}
{{- if ne (dig "metadata" "name" "" $ss) "postgres-credentials" }}{{ fail (printf "%s: metadata.name must be postgres-credentials" $path) }}{{ end }}
{{- if ne (dig "metadata" "namespace" "" $ss) .Release.Namespace }}{{ fail (printf "%s: sealed for another namespace" $path) }}{{ end }}
{{- $ann := (dig "metadata" "annotations" (dict) $ss) | default (dict) }}
{{- if or (hasKey $ann "sealedsecrets.bitnami.com/cluster-wide") (hasKey $ann "sealedsecrets.bitnami.com/namespace-wide") }}{{ fail (printf "%s must use strict scope" $path) }}{{ end }}
{{- range $k := list "user" "password" }}
{{- if not (dig "spec" "encryptedData" $k "" $ss) }}{{ fail (printf "%s: spec.encryptedData.%s is missing" $path $k) }}{{ end }}
{{- end }}
{{ toYaml $ss }}
---
```

Notes:
- `toYaml $ss` re-emits a single normalized document. It also avoids a doubled `---` if kubeseal output starts with one.
- The `fromYaml`/`dig`/`hasKey`/`fail` functions are standard Helm/Sprig functions.

**Readiness probe** (current line 43): stop depending on `.Values.postgres.user`:
```yaml
            exec:
              command: ["sh", "-c", 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
```
`POSTGRES_USER` and `POSTGRES_DB` are already in the container env. `pg_isready` does not authenticate, so this adds no secret exposure.

The rest of the file is unchanged. That includes the `secretKeyRef: { name: postgres-credentials, key: user|password }` entries, the StatefulSet and the Service names (`postgres` is a hard contract with `backend.yaml` `DB_URL`).

### 2. `infra/helm/pet-project/templates/backend.yaml` - no change

It already reads `DB_USER`/`DB_PASSWORD` from Secret `postgres-credentials`. The SealedSecret controller produces a Secret with that same name and those keys.

### 3. `infra/helm/pet-project/values.yaml` - remove the credential

- Delete `postgres.user: app` and `postgres.password: app`, along with the whole "Dev credential only ..." comment (lines 28-34).
- Keep `postgres.db: app`. The database name is not a credential and `backend.yaml` builds `DB_URL` from it.
- Add a short comment in the `postgres:` block: "Credentials are not values: they come from `sealed-secrets/<namespace>/postgres-credentials.yaml` (Sealed Secrets). Never put a password into this file or into `envs/*` in the deploy repo - `update-deploy.yml` prints those files with `cat`."

After this edit, no key named `password` or `user` may remain in `values.yaml`.

### 4. New `infra/helm/pet-project/sealed-secrets/dev/postgres-credentials.yaml` and `.../prod/postgres-credentials.yaml` - placeholders

These are structurally valid SealedSecrets, so lint and template pass. The ciphertext is a placeholder that is obviously fake and different per environment, and the controller cannot decrypt it. Content for dev (prod is identical with `prod` substituted):

```yaml
# PLACEHOLDER - replace this whole file with kubeseal output before merging
# (README, "Postgres credentials (Sealed Secrets)"). The controller cannot decrypt
# these values, so the dev namespace gets no database credentials until then.
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: postgres-credentials
  namespace: dev
spec:
  encryptedData:
    user: PLACEHOLDER-run-kubeseal-for-dev
    password: PLACEHOLDER-run-kubeseal-for-dev
  template:
    metadata:
      name: postgres-credentials
      namespace: dev
    type: Opaque
```

The files sit outside `templates/`, so Helm does not render them directly. They are packaged with the chart and readable via `.Files.Get`. There is no `.helmignore` in the chart, so nothing excludes them. The user overwrites them with real ciphertext (manual step M2). The implementer cannot do that because there is no cluster, `kubectl` or `kubeseal`.

### 5. `infra/helm/pet-project/Chart.yaml`

`version: 0.1.0` -> `0.2.0`. The file itself says to bump when templates change. `appVersion` stays unchanged.

### 6. `.github/workflows/update-deploy.yml` - guard before `cat` (defence in depth for AC4)

In the "Set image tags" step, insert this between `yq -i ...` and `cat "$FILE"`:

```bash
          # Credentials come from SealedSecrets in the chart, never from env values.
          # Refuse to print a values file that carries a password-like key.
          if grep -qiE '^[[:space:]]*[a-z_]*password[a-z_]*[[:space:]]*:' "$FILE"; then
            echo "::error::${FILE} contains a password key - remove it from the deploy repo"; exit 1
          fi
```

The error message names only the file, never its content. The header comment and other steps are unchanged. Reminder: `workflow_run` runs the copy of this file from `main`, so the guard only becomes active after it reaches `main`.

### 7. `infra/argocd/apps.yaml` - comment only

Add one line to the header: "Prerequisite: the Sealed Secrets controller must be installed in the cluster (README, "Postgres credentials (Sealed Secrets)"); without its CRD the sync fails and nothing is applied." The Applications themselves do not change. Destination namespaces `dev`/`prod` are exactly the namespaces the sealed files are bound to.

### 8. `README.md`

- **"Configuration"**: add one sentence under the table. The defaults apply only to local runs. In Kubernetes, `DB_USER`/`DB_PASSWORD` always come from the Secret `postgres-credentials` created by Sealed Secrets, and there is no fallback.
- **"Deploy (Helm + Argo CD)"**: add one bullet pointing to the new section. Postgres credentials are per-namespace SealedSecrets under `infra/helm/pet-project/sealed-secrets/<namespace>/`, and `envs/*/values.yaml` never holds credentials.
- **New section, heading exactly `## Postgres credentials (Sealed Secrets)`** (level 2, placed directly after the "Deploy (Helm + Argo CD)" section). Its subsections use level-3 headings (`### ...`), so the next `## ` heading ends the section; the T7 order check relies on this. Subsections:
  1. **Why Sealed Secrets.** We need no external secret backend (Vault, AWS/GCP Secret Manager) for a pet project. External Secrets would require one plus a credential to reach it. Encrypted values live in git next to the chart and follow the same GitOps flow. Trade-offs: re-sealing is needed if the controller key is lost (for example after `k3d cluster delete`), and rotation is a git change.
  2. **How it works.** One strict-scope SealedSecret per namespace, at `sealed-secrets/<namespace>/postgres-credentials.yaml`, selected by release namespace (`dev`/`prod` from the Argo CD Application). Rendering fails for any namespace without a file. Ciphertext is safe to commit to a public repo. Plaintext never is.
  3. **Prerequisites (one-time, cluster-wide - done by hand, not by Argo CD).** Install the controller with a pinned chart version into `kube-system`, named so `kubeseal` finds it with its defaults:
     ```bash
     helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
     helm install sealed-secrets sealed-secrets/sealed-secrets --version <pinned> \
       --namespace kube-system --set-string fullnameOverride=sealed-secrets-controller
     ```
     Install the `kubeseal` CLI with the matching version. Back up the controller key **outside any repository**: `kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > <safe place>` (this is the private key). The implementer fills `<pinned>` with the current chart version found via `helm search repo sealed-secrets/sealed-secrets` and states it in the README. Do not invent a number. Also explain why the controller is not an Argo CD Application (D5).
  4. **Seal credentials for dev and prod.** Run from the repository root, because the output path below is relative. Run once per environment, each time with a newly generated password.
     - The plaintext exists only in a shell variable and a pipe. It is never written to disk and never appears on any process command line: `printf` is a shell builtin, and kubectl reads the value from stdin.
     - `--from-file=password=/dev/stdin` sets the key name `password`. The user name is not secret, so it stays a `--from-literal`.
     ```bash
     ENV=dev   # then again with ENV=prod
     PW="$(openssl rand -hex 24)"
     printf %s "$PW" \
     | kubectl create secret generic postgres-credentials --namespace "$ENV" \
         --from-literal=user=app --from-file=password=/dev/stdin \
         --dry-run=client -o yaml \
     | kubeseal --format yaml \
     > infra/helm/pet-project/sealed-secrets/$ENV/postgres-credentials.yaml
     ```
     Keep `$PW` in a password manager until the rotation or first-switch steps below are finished, because you will need it for `\password`. Then `unset PW`.

     Offline variant, when the cluster is not reachable: run `kubeseal --fetch-cert > pub-cert.pem` once, then `kubeseal --cert pub-cert.pem --format yaml`. The public cert may be stored anywhere.

     Never commit the output of `kubectl create secret ... -o yaml` without `kubeseal`.
  5. **Rotate the credential.** The README text must include the quoted phrases below literally, because T7 greps for them. Background the README must state:
     - "`POSTGRES_PASSWORD` is only read when the database is first initialised". After that, restarting Postgres never changes the password; only `\password` does.
     - "Every merge also triggers a new backend rollout". Any push to `development`/`main` runs "Build images", then "Update deploy manifests" writes a new image tag, and Argo CD rolls out new backend pods. New pods read the password from the Secret at start.
     - The backend Deployment uses the default RollingUpdate strategy (maxUnavailable 0 with 1-2 replicas). New pods that fail to authenticate end up in `CrashLoopBackOff` and the rollout gets stuck, but the "old pods keep serving" until new pods become Ready.

     Steps:
     1. Before merging, record the current Secret fingerprint (this prints a hash, never the value): `kubectl -n <env> get secret postgres-credentials -o jsonpath='{.data.password}' | sha256sum`. Re-seal the environment's file (subsection 4) and merge it.
        - **dev:** the switch happens when the merge into `development` is synced.
        - **prod:** the re-sealed prod file goes through `development` first. There it only causes a harmless dev backend rollout: the dev file is unchanged and the dev Secret stays the same. The real switch happens when the change is merged into `main` and the prod Application syncs. **Do not run `\password` in prod before that `main` merge has synced.** If you do, the running prod backend loses new DB connections while the prod Secret still holds the old password.
     2. **Right after Argo CD has synced the SealedSecret**, change the password inside Postgres. Do not wait for the backend rollout to finish. "Synced" means both of these are true:
        - the SealedSecret condition `Synced` is `True`;
        - the fingerprint from step 1 has changed.

        Timing: Argo CD usually picks up the chart change from git and syncs the SealedSecret a few minutes before the new image tag arrives. The tag only comes after "Build images" and then "Update deploy manifests". So step 2 can often be done before any new backend pod starts. If the new pods start first, they fail with `CrashLoopBackOff` until step 2 is done, and the old pods keep serving.
        - Local socket connections in the image need no password: run `kubectl -n <env> exec -it postgres-0 -- psql -U app -d app`, then `\password app`, and paste the new value. Never put it on the command line.
     3. Right after step 2, run `kubectl -n <env> rollout restart deployment/backend`, then `kubectl -n <env> rollout status deployment/backend`. This is needed in all cases:
        - A stuck rollout would otherwise only recover after the CrashLoop back-off (up to 5 minutes).
        - A backend pod that started before the controller updated the Secret still holds the old password. It fails on its next new DB connection.
        - Old pods keep their already-open pooled connections until the pool recycles them (Hikari `maxLifetime`, 30 minutes by default). Do not leave step 3 for later.
     4. Verify: Argo CD shows the app `Healthy`, and `/actuator/health` returns `UP`.
  6. **First switch from the old `app/app`.** Follow the same order as Rotate, with these differences:
     - The first sync also changes the Postgres readiness probe, so the `postgres-0` pod is recreated once (a short DB restart). Its data and its `app/app` password are kept on the PVC.
     - Argo CD prunes the old Secret and the controller creates the new one. Pods started in between fail with `CreateContainerConfigError`; running pods are not affected.
     - The same merge also triggers a new backend rollout. New backend pods crash-loop until you run `\password` (Rotate step 2), while the old pods keep serving. Do step 2 right after the SealedSecret is `Synced`, then step 3.
     - For dev, resetting the volume instead of step 2 is acceptable (data loss). Delete the PVC and then the pod, otherwise PVC protection keeps the PVC in `Terminating` while `postgres-0` runs: `kubectl -n dev delete pvc data-postgres-0 --wait=false && kubectl -n dev delete pod postgres-0`. The StatefulSet recreates both, and Postgres re-initialises with the sealed credentials. Still run step 3 afterwards.
     - If the SealedSecret status reports that the Secret "already exists and is not managed", see manual step M4 first.
  7. **Controller key lost / cluster recreated.** Existing ciphertext can no longer be decrypted. Re-seal both envs (subsection 4), or restore the key backup before installing the controller.

### 9. `.github/workflows/ci.yml` - follow-up: pass `--namespace` to the `helm` job's lint steps

Added independently by PR #10 (a `helm` job running `helm lint`/`helm template` plus an `actionlint` job) after this plan's original Changes 1-8 landed. Its two `helm lint` steps ran without `--namespace`, i.e. against namespace `default`, for which the chart has no sealed file (D3). On Helm 3.x this still exits 0, but only after logging the chart's `fail()` checks as 6 separate `[INFO] Fail: ...` lines plus a `[WARNING] ... object name does not conform ...` for the resulting empty `metadata.name` - noise that never actually lints the real per-env sealed files. Fix: add `--namespace dev` to the unnamespaced "Lint chart" step, and `--namespace "${env}"` to the per-environment "Lint chart with dev and prod values" loop. The "Render chart with dev and prod values" step already passes `--namespace "${env}"` and is unchanged; its stale comment (referring to a plaintext `Secret`, from before Change 1) is corrected to say the chart now renders a `SealedSecret` (ciphertext only) and that `helm template` - not `helm lint` - is what actually enforces the sealed-file validation (see D3/T2). Consequence for this plan's own checks: T2's `helm lint --strict` commands and CI's `helm lint` steps now agree on always passing `--namespace`, and `helm template` remains the canonical check in both places.

---

## Manual steps for the user (cannot be done from the dev container)

- **M1** - Install the Sealed Secrets controller and back up its key (README, Prerequisites). This must happen **before** the change reaches `development`, otherwise the Argo CD sync fails on the unknown `SealedSecret` kind. That failure is safe: nothing is applied and the old Secret stays.
- **M2** - On this branch, replace both placeholder files with real `kubeseal` output, using a separately generated password per env (README "Seal credentials"). **Merge blocker:** `grep -rn PLACEHOLDER infra/helm/pet-project/sealed-secrets` must return nothing before merging to `development`. If placeholders reach dev, the sync prunes the old Secret and the controller cannot create a new one, so backend and Postgres pods fail to start.
- **M3** - The first sync of each environment is the first switch (README subsection 6).
  - Merging to `development` (dev) or to `main` (prod) also rolls out new backend pods, which crash-loop until the database password is changed. Old pods keep serving meanwhile, so act promptly:
    1. Wait until `kubectl -n <env> get sealedsecret postgres-credentials -o jsonpath='{.status.conditions[?(@.type=="Synced")].status}'` prints `True`. On a first switch this is enough, because no SealedSecret existed before; if these steps are ever reused for a rotation, also require the changed fingerprint (README Rotate steps 1-2).
    2. Immediately run `\password app` in `postgres-0` (for dev, the alternative is the volume reset from README subsection 6: `kubectl -n dev delete pvc data-postgres-0 --wait=false && kubectl -n dev delete pod postgres-0`).
    3. Then `kubectl -n <env> rollout restart deployment/backend` and `rollout status`.
  - Do M4 first if the SealedSecret is not `Synced`.
- **M4** - Check `kubectl -n <env> get sealedsecret postgres-credentials -o jsonpath='{.status.conditions}'` is `Synced=True`, and that Secret `postgres-credentials` has an ownerReference of kind `SealedSecret`. If the controller refused to overwrite the Argo-managed Secret from the old chart:
  - run `kubectl -n <env> delete secret postgres-credentials`
  - then trigger a reconcile with `kubectl -n kube-system rollout restart deployment/sealed-secrets-controller`
- **M5 (AC3 cluster proof)** - Compare the passwords without printing them. The two hashes must differ:
  ```bash
  for ns in dev prod; do kubectl -n $ns get secret postgres-credentials -o jsonpath='{.data.password}' | sha256sum; done
  ```
- **M6 (deploy repo, only if T2 finds one)** - If `envs/dev|prod/values.yaml` in `Katran1990/pet-project-deploy` contains any `postgres.password`/`postgres.user` key, delete it there by hand. The chart now ignores it, and the new workflow guard would fail on `password`. The current content is expected not to contain one, per the card.

---

## Tests

This is infrastructure work, proven by verification commands. The backend and frontend code are unchanged.

Let `SCRATCH` be the session scratchpad directory. Everything below is created there and never inside `/workspace`.

**Coverage map:**

| Test | Proves |
|---|---|
| T1 | tooling |
| T2 | AC5 (and AC2 render) |
| T3 | AC2 |
| T4 | AC3 (repo side; cluster side is M5) |
| T5 | AC1 |
| T6 | AC4 |
| T7 | AC6 |
| T8 | hygiene / CLAUDE.md "tests pass" |

### T1 - tooling in the scratchpad

```bash
SCRATCH=<session scratchpad>; mkdir -p "$SCRATCH/bin" "$SCRATCH/values"; export PATH="$SCRATCH/bin:$PATH"
ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')
HELM_VERSION=<latest stable tag from https://github.com/helm/helm/releases>   # record it in the report
curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz" | tar -xz -C "$SCRATCH"
mv "$SCRATCH/linux-${ARCH}/helm" "$SCRATCH/bin/"
curl -fsSL -o "$SCRATCH/bin/yq" "https://github.com/mikefarah/yq/releases/latest/download/yq_linux_${ARCH}" && chmod +x "$SCRATCH/bin/yq"
(cd "$SCRATCH/bin" && bash <(curl -fsSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash))
helm version && yq --version && actionlint -version
```

If a download fails (no network), report the affected ACs as **UNVERIFIED**. Never report a pass without running the check.

### T2 - AC5: lint and template for dev and prod

**Values:** fetch the real env values read-only from the public deploy repo:

```bash
for e in dev prod; do
  curl -fsSL "https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/$e/values.yaml" > "$SCRATCH/values/$e.yaml"
done
```

Fallback, only if that fails: local fixtures in `$SCRATCH/values/` with the known shape.
- dev: `backend.tag`, `frontend.tag`, `ingress.host: dev.pet.local`
- prod: the same plus `backend.replicas: 2`, `postgres.storage: 5Gi`, `ingress.host: pet.local`

If fixtures are used, report AC5 as "verified against fixtures, not against the deploy repo".

```bash
C=/workspace/infra/helm/pet-project
helm lint --strict "$C" --namespace dev  -f "$SCRATCH/values/dev.yaml"
helm lint --strict "$C" --namespace prod -f "$SCRATCH/values/prod.yaml"
helm template pet-project-dev  "$C" --namespace dev  -f "$SCRATCH/values/dev.yaml"  > "$SCRATCH/render-dev.yaml"
helm template pet-project-prod "$C" --namespace prod -f "$SCRATCH/values/prod.yaml" > "$SCRATCH/render-prod.yaml"
```

`helm template` is the canonical check for this chart, because it is the only one of the two that actually fails on an invalid (not just missing) sealed file - see D3. `helm lint --strict` is still run for the chart's other lint checks, but plain `helm lint` (without `--strict`) only logs a rendering `fail()` as INFO and never turns it into a lint failure (verified on Helm v4.3.0), and even `--strict` only happens to fail for a *missing* sealed file (via the resulting empty `metadata.name`); a *present but invalid* file (wrong namespace, non-strict scope, `stringData`, a missing `encryptedData` key, ...) passes `helm lint --strict` and must be caught by `helm template` instead.

- All four commands must exit 0. `helm lint --strict` prints `1 chart(s) linted, 0 chart(s) failed`. The `icon is recommended` INFO is expected.
- The regression table from the previous card still holds: image tags from the env values, prod backend replicas `2`, prod storage `5Gi`, Ingress hosts `dev.pet.local` / `pet.local`, Services `backend`, `frontend`, `postgres`.

**Negative test (D3):**

```bash
! helm template x "$C" --namespace staging > /dev/null 2> "$SCRATCH/neg.txt"
grep -q 'no sealed Postgres credentials for namespace "staging"' "$SCRATCH/neg.txt"
```

A plain `helm lint "$C"` (namespace `default`, no `--strict`) exits 0: the `fail()` message is only logged as INFO, not raised as a lint failure. `helm lint --strict "$C"` (namespace `default`) does fail, because the resulting object then also has an empty `metadata.name`, which `--strict` turns from a WARNING into an error - this is specific to the *missing*-file case. Record this in the report rather than treating it as a regression (Risk 3).

**Invalid-but-present-file test (D3):** on a chart copy, make the dev sealed file invalid without removing it (for example set `metadata.namespace: prod` while keeping the file at `sealed-secrets/dev/...`, or add a `stringData` key). `helm lint --strict` on that copy with `--namespace dev` must still report `0 chart(s) failed` (it does not catch this), while `helm template ... --namespace dev` must fail with the corresponding message from templates/postgres.yaml. This is why `helm lint --strict` alone is not sufficient and `helm template` is the canonical check.

**CI (Change 9):** `.github/workflows/ci.yml`'s `helm` job (Helm 3.22.0, added by PR #10) runs the same shape of checks automatically on every PR: `helm lint infra/helm/pet-project --namespace dev`, then `helm lint ... --namespace "${env}" -f deploy-values/envs/${env}/values.yaml` and `helm template ... --namespace "${env}" -f deploy-values/envs/${env}/values.yaml > /dev/null` for `env in dev prod`. All three must exit 0 with no `[INFO] Fail: ...` lines and no `object name does not conform` WARNING - both would appear if `--namespace` were missing (verified on Helm 3.22.0 and 4.3.0 by replaying the exact steps against the real env values).

### T3 - AC2: the chart consumes a SealedSecret, not a Secret

For each of `render-dev.yaml` / `render-prod.yaml` (with `<env>` = `dev` / `prod`):

```bash
R="$SCRATCH/render-<env>.yaml"
yq -N 'select(.kind == "Secret") | .metadata.name' "$R"                       # expect: empty
yq -N 'select(.kind == "SealedSecret") | .apiVersion' "$R"                    # expect: bitnami.com/v1alpha1
yq -N 'select(.kind == "SealedSecret") | .metadata.name' "$R"                 # expect: postgres-credentials
yq -N 'select(.kind == "SealedSecret") | .metadata.namespace' "$R"            # expect: <env>
yq -N -o=json -I=0 'select(.kind == "SealedSecret") | .spec.encryptedData | keys | sort' "$R"   # expect exactly: ["password","user"]
yq -N 'select(.kind == "Deployment" and .metadata.name == "backend") | .spec.strategy' "$R"   # expect: null (default RollingUpdate 25%/25% -> maxUnavailable 0 for 1-2 replicas; old pods survive a failing rollout, README Rotate relies on it)
yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[0].env[] | select(.name == "DB_PASSWORD") | .valueFrom.secretKeyRef.name' "$R"   # expect: postgres-credentials
yq -N 'select(.kind == "StatefulSet") | .spec.template.spec.containers[0].readinessProbe.exec.command[2]' "$R"   # expect: contains $POSTGRES_USER
```

An empty result where a value is expected counts as a failure.

**Guard tests:** copy the chart to `$SCRATCH/chart-copy` and render it with `--namespace dev` after each of these mutations. Each must fail with its message:
- `metadata.namespace: prod` in the dev file
- a `sealedsecrets.bitnami.com/cluster-wide: "true"` annotation
- `encryptedData.password` removed

Never mutate `/workspace`.

### T4 - AC3: distinct per environment (repo side)

```bash
test "$(yq -N 'select(.kind=="SealedSecret") | .metadata.namespace' "$SCRATCH/render-dev.yaml")" = dev
test "$(yq -N 'select(.kind=="SealedSecret") | .metadata.namespace' "$SCRATCH/render-prod.yaml")" = prod
! diff -q <(yq -N 'select(.kind=="SealedSecret") | .spec.encryptedData' "$SCRATCH/render-dev.yaml") \
          <(yq -N 'select(.kind=="SealedSecret") | .spec.encryptedData' "$SCRATCH/render-prod.yaml")
```

This proves structure only: two separate, namespace-bound objects. Distinct *plaintext* cannot be proven from ciphertext, since kubeseal output differs even for identical input. It is guaranteed by the procedure (an independent `openssl rand` per env) and confirmed on the cluster by M5. Report AC3 as "structure verified; plaintext distinctness pending M5".

### T5 - AC1: no plaintext credential in the repository

```bash
cd /workspace
grep -rniE 'password[[:space:]]*:' infra/ .github/ --exclude-dir=sealed-secrets
# expected: only .github/workflows/build-images.yml "password: ${{ secrets.GITHUB_TOKEN }}"
grep -rniE '^[[:space:]]*stringData[[:space:]]*:|POSTGRES_PASSWORD:|password: *app' infra/  # expect: no matches
yq -N '.postgres | keys' infra/helm/pet-project/values.yaml                     # expect: no user, no password
for f in infra/helm/pet-project/sealed-secrets/*/postgres-credentials.yaml; do
  yq -N '(.data // "none"), (.stringData // "none"), (.spec.template.data // "none")' "$f"   # expect: none, none, none
done
```

- Only `encryptedData` may carry values in the sealed files.
- The `stringData` check is anchored to a line that is actually a YAML key (`^[[:space:]]*stringData[[:space:]]*:`), not just the word anywhere on a line: `templates/postgres.yaml`'s own guard checks (Change 1/2) contain the words `stringData` and `data` inside their `fail()` messages as prose, and an unanchored grep would flag that prose as a false positive.
- Out-of-scope hits reviewed per D6: `backend/src/main/resources/application.properties`, `.devcontainer/docker-compose.yml` and the README dev-container lines (local dev credential only).
- Before merge (after M2): `grep -rn PLACEHOLDER infra/helm/pet-project/sealed-secrets` must be empty. This is the user's merge blocker. On the implementer's branch it is expected to match.

### T6 - AC4: nothing secret in `envs/*/values.yaml`

```bash
grep -niE 'password|secret' "$SCRATCH/values/dev.yaml" "$SCRATCH/values/prod.yaml"   # expect: no matches (else -> M6)
yq -N '.jobs.update.steps[] | select(.name == "Set image tags") | .run' .github/workflows/update-deploy.yml | grep -n 'password'   # guard present, before `cat`
actionlint .github/workflows/update-deploy.yml                                        # exit 0, no output
```

Guard behaviour, simulated in the scratchpad:
- a file with `postgres:\n  password: x` makes the extracted guard snippet exit 1
- the fetched real files make it exit 0

The chart no longer reads any credential key from values, so a password has no legitimate reason to be added there.

### T7 - AC6: README

```bash
R=/workspace/README.md
grep -n 'Postgres credentials (Sealed Secrets)' "$R"                 # section exists
grep -n 'External Secrets' "$R"                                      # choice rationale
grep -n 'kubeseal' "$R"; grep -n 'fullnameOverride=sealed-secrets-controller' "$R"; grep -n -- '--version' "$R"
grep -niE 'back ?up' "$R"                                            # controller key backup
grep -n -- '--from-file=password=/dev/stdin' "$R"                    # password never on a command line
grep -nE -- '--from-literal=password' "$R"                           # expect: no matches
grep -niE 'rotate|rotation' "$R"
# Rollout-aware wording - every phrase must match:
grep -n 'POSTGRES_PASSWORD` is only read when the database is first initialised' "$R"
grep -n 'Every merge also triggers a new backend rollout' "$R"
grep -n 'CrashLoopBackOff' "$R"
grep -n 'old pods keep serving' "$R"
grep -n 'Right after Argo CD has synced the SealedSecret' "$R"
grep -n 'First switch from the old' "$R"
# Order inside the section (from its "## " heading to the next "## " heading):
# \password must come before the restart, and both must exist
awk '/^## /{s=($0 ~ /^## Postgres credentials \(Sealed Secrets\)[[:space:]]*$/)} s&&/\\password app/&&!p{p=NR} s&&/rollout restart deployment\/backend/&&!r{r=NR} END{exit !(p && r && p<r)}' "$R" && echo "order OK"
grep -c '^## Postgres credentials (Sealed Secrets)$' "$R"             # expect: 1 (exact level-2 heading, exactly once)
grep -n 'Do not restart the backend before' "$R"                     # expect: no matches (the old, impossible instruction)
```

Then read the whole section once and check that:
- Rotate and First switch both say the merge also triggers a backend rollout, that new pods crash-loop until `\password`, and that old pods keep serving.
- Step 3 is "restart and wait for recovery", not the first restart.
- No plaintext password example appears, only `$PW` / `openssl rand`.

**T7b - seal command shape (offline).** Download `kubectl` into `$SCRATCH/bin` (`https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/${ARCH}/kubectl`). Run the README pipeline without `kubeseal`, using a dummy value:

```bash
printf %s 'dummy-not-a-secret' \
| kubectl create secret generic postgres-credentials --namespace dev \
    --from-literal=user=app --from-file=password=/dev/stdin --dry-run=client -o yaml > "$SCRATCH/t7b.yaml"
yq -N '.data | keys | sort' -o=json -I=0 "$SCRATCH/t7b.yaml"               # expect: ["password","user"]
yq -N '.data.password' "$SCRATCH/t7b.yaml" | base64 -d                    # expect: dummy-not-a-secret (no trailing newline)
yq -N '.metadata.namespace' "$SCRATCH/t7b.yaml"                            # expect: dev
```

`--dry-run=client` needs no cluster. If kubectl still refuses without a kubeconfig, retry with `KUBECONFIG=/dev/null`. If it still fails, report T7b as UNVERIFIED and do not change the command.

### T8 - hygiene (git-free) and project tests

No git commands, not even read-only ones: CLAUDE.md forbids git operations without explicit consent.

**Reference marker.** It must be created as the very first action of the implementation, before any file is edited:

```bash
touch "$SCRATCH/impl-start.ref"
```

If it was not created in time, report this and use `-mmin -<minutes since the implementation started>` instead of `-newer`.

**Changed or new files since the marker (strict scope).** An exact-set check covers only the project areas: `infra/`, `.github/`, `README.md`, `backend/src/`, `frontend/src/` and `.devcontainer/`.

```bash
find /workspace/infra /workspace/.github /workspace/README.md /workspace/backend/src /workspace/frontend/src /workspace/.devcontainer \
  -type f -newer "$SCRATCH/impl-start.ref" -print | sort
```

The output must be exactly this set. Any other path here fails the check; in particular `templates/backend.yaml`, `application.properties` and anything under `.devcontainer/` must not appear:

```
/workspace/.github/workflows/ci.yml            (Change 9 follow-up)
/workspace/.github/workflows/update-deploy.yml
/workspace/README.md
/workspace/infra/argocd/apps.yaml
/workspace/infra/helm/pet-project/Chart.yaml
/workspace/infra/helm/pet-project/sealed-secrets/dev/postgres-credentials.yaml
/workspace/infra/helm/pet-project/sealed-secrets/prod/postgres-credentials.yaml
/workspace/infra/helm/pet-project/templates/postgres.yaml
/workspace/infra/helm/pet-project/values.yaml
```

**Changed or new files elsewhere (report and review, not an automatic fail):**

```bash
find /workspace \( -path /workspace/.git -o -path /workspace/frontend/node_modules -o -name .gradle -o -name build -o -name node_modules \
     -o -path /workspace/infra -o -path /workspace/.github -o -path /workspace/backend/src -o -path /workspace/frontend/src -o -path /workspace/.devcontainer \) -prune \
  -o -type f -newer "$SCRATCH/impl-start.ref" ! -path /workspace/README.md -print | sort
```

List every hit in the report with a one-line explanation. Examples:
- `/workspace/docs/plans/postgres-sealed-secrets.md` edited by the coordinator;
- another plan in `docs/plans/` (for example `retry-postgres-connection.md`) written by a parallel task;
- tool state such as `/workspace/.claude/`.

The reviewer decides whether a hit matters. The implementer must not revert or delete anything found here.

**Deleted files.** `find -newer` cannot detect deletions, so check the expected files explicitly:

```bash
for f in infra/helm/pet-project/Chart.yaml infra/helm/pet-project/values.yaml \
         infra/helm/pet-project/templates/backend.yaml infra/helm/pet-project/templates/frontend.yaml \
         infra/helm/pet-project/templates/ingress.yaml infra/helm/pet-project/templates/postgres.yaml \
         infra/helm/pet-project/sealed-secrets/dev/postgres-credentials.yaml \
         infra/helm/pet-project/sealed-secrets/prod/postgres-credentials.yaml \
         infra/argocd/apps.yaml .github/workflows/ci.yml .github/workflows/build-images.yml \
         .github/workflows/update-deploy.yml README.md backend/src/main/resources/application.properties \
         .devcontainer/docker-compose.yml; do
  test -f "/workspace/$f" || echo "MISSING /workspace/$f"
done
# expect: no output
```

**No tooling or artefacts inside the repository.** Tool and artefact names are matched as regular files only, and scratch directory names as directories only.

`/workspace/infra/helm` (the chart parent directory) is **expected to exist**. It must not match because it is a directory, not a file named `helm`.

```bash
find /workspace -path /workspace/.git -prune -o -path /workspace/frontend/node_modules -prune -o \
  \( -type f \( -name helm -o -name yq -o -name actionlint -o -name kubectl -o -name '*.tar.gz' \
                -o -name 'render-*.yaml' -o -name 'neg.txt' -o -name 't7b.yaml' -o -name 'pub-cert.pem' -o -name 'impl-start.ref' \) \
     -o -type d \( -name 'linux-amd64' -o -name 'linux-arm64' -o -name 'chart-copy' \) \) -print
# expect: no output
test -d /workspace/infra/helm/pet-project && echo "chart dir present (expected)"
find /workspace/infra /workspace/docs -name 'values' -type d                        # expect: no output (fetched env values stay in $SCRATCH/values)
find /workspace/infra -path '*/envs/*' -print                                       # expect: no output
```

`/workspace/frontend/node_modules` is a Docker volume and is only ever pruned from the search, never touched.

**Project tests:**

```bash
cd /workspace/backend && ./gradlew test      # unaffected, run because CLAUDE.md requires passing tests
```

`backend/build` and `.gradle` produced by this run are excluded from the `find` above on purpose.

---

## Risks and open questions

1. **Q1 (resolved: the user confirmed the local credentials stay untouched): scope of AC1.** The plan keeps the local dev-container credential `app/app` in `application.properties`, `.devcontainer/docker-compose.yml` and the README (D6). It is never used in the cluster, because the non-optional `secretKeyRef` makes the pod fail instead of falling back. If the user wants zero `app` passwords repo-wide, removing the defaults would break `./gradlew bootRun` and the dev container setup. That would be a separate change.
2. **Placeholders merged by mistake.** If the placeholder files reach `development`, Argo CD prunes the old Secret and the controller cannot decrypt the new one, so dev goes down. It fails closed and never falls back to `app/app`. Mitigation: merge blocker M2/T5. A render-time `fail` on `PLACEHOLDER` was rejected because it would make AC5 impossible to verify before the user seals.
3. **`helm template` without `--namespace` now fails (D3).** This is deliberate: a namespace without sealed credentials must not render. Plain `helm lint` (no `--strict`) does not fail even without `--namespace`: it only logs the `fail()` message as INFO (verified on Helm v4.3.0). `helm lint --strict` does fail for that specific case, but only by accident (the resulting empty `metadata.name`) - it does not fail for a namespace whose sealed file is *present but invalid* (wrong namespace, non-strict scope, `stringData`, a missing `encryptedData` key). `helm template` is therefore the canonical check, catching both the missing- and the invalid-file case - see the canonical check in T2. At the time this risk was first written, CI did not run Helm at all, so nothing automated could break; PR #10 later added a `helm` job (Change 9), whose steps now always pass `--namespace` for exactly this reason. Alternative if the user prefers: render nothing for unknown namespaces. That is quieter and less safe.
4. **Takeover of the existing Secret.** Today Argo CD manages a plain Secret with the same name. On the first sync, the controller may refuse to overwrite it ("not managed by SealedSecret") until Argo CD prunes it. Whether it retries on its own depends on the controller version and cannot be tested here. M4 gives the manual fix.
5. **Sync ordering.** Argo CD applies custom kinds after built-ins, so the Deployment/StatefulSet may start before the Secret exists. Kubelet retries `CreateContainerConfigError` until it does. An `argocd.argoproj.io/sync-wave: "-1"` annotation was considered and left out: with an undecryptable SealedSecret, the Degraded health would block the whole sync, including unrelated image updates.
6. **Existing volumes and automatic backend rollouts.** `POSTGRES_PASSWORD` only applies when the database is first initialised, and a Postgres pod restart never changes it.
   - Every merge that carries a re-sealed file also triggers a new backend rollout: build-images, then update-deploy, then Argo CD. New pods fail authentication until the manual `\password` step.
   - Availability depends on the default RollingUpdate keeping old pods (maxUnavailable 0 for 1-2 replicas; T3 asserts that no `strategy` is set). If someone later sets `strategy: Recreate` or raises `maxUnavailable`, rotation causes an outage.
   - Old pods also start failing on new DB connections once `\password` has run, so the restart must follow immediately. This is documented as a strict step order in the README (Rotate, First switch) and in M3.
   - The Postgres readiness-probe change restarts `postgres-0` once on the first sync. If the Secret is missing or undecryptable at that moment (placeholders, M4), Postgres stays in `CreateContainerConfigError` until it is fixed.
7. **Controller key lifecycle.** The controller renews its sealing key every 30 days by default. Old keys are kept, so old ciphertext still decrypts. Losing all keys (for example by recreating the k3d cluster without the backup) makes every committed ciphertext useless until re-sealed. This is documented. The key backup must never go into any repository.
8. **Username `app` unchanged across envs.** AC3 is satisfied by distinct passwords. The procedure lets the user pick a different username per env (`--from-literal=user=...`). The probe and backend follow automatically (D4), but an existing database then needs `CREATE ROLE` or a fresh volume.
9. **Workflow guard (the user approved this CI change) only active after merge to `main`** (`workflow_run` semantics). Its only effect is to fail loudly. It never prints file content.
10. **Chart and CLI versions.** The pinned controller chart version and the helm version used for verification are looked up at implementation time and recorded. The plan does not hardcode numbers it cannot confirm.

## Out of scope

- Any change to `Katran1990/pet-project-deploy` (listed only as manual step M6 if needed), and any git operation.
- Installing the Sealed Secrets controller, running `kubeseal`, generating real ciphertext, any `kubectl` or Argo CD operation, or cluster verification (M1-M5 are for the user).
- External Secrets Operator or any external secret backend.
- Managing the controller as an Argo CD Application, or any cluster-wide manifest in this repo (D5).
- Automatic backend restart on credential change (checksum annotations). Rotation restarts by hand.
- Backend/frontend code, `application.properties`, `.devcontainer/`, `ci.yml` (except the `--namespace` follow-up in Change 9), `build-images.yml`, DB migrations, `imagePullSecrets` / GHCR visibility.
- Editing historical plans in `docs/plans/` (other than saving this one).
