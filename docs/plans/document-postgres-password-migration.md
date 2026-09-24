# Plan: document the Postgres password migration for existing databases

Trello card: https://trello.com/c/30XB3iir/8-document-postgres-password-migration-for-existing-databases

## Goal
Add a subsection to the README section "Postgres credentials (Sealed Secrets)". It explains that Postgres keeps the password of user `app` on its data volume, and gives the three commands that bring that password in line with the sealed Secret. The step runs once per namespace whenever the sealed password changes. This is a documentation change only: `README.md` is the only file edited.

## Decisions after plan review (approved by the user on 2026-09-24)
These decisions override the draft and the "No other README lines change" statement below.

1. **Empty-password guard.** Keep the three card commands verbatim, each on its own unindented line, but add a separate guard line between the first and the second command inside the same `bash` block:
   `[ -n "$NEWPASS" ] || echo "NEWPASS is empty - stop"`
   Update the bullet about the first command accordingly (the guard line prints a warning; do not run the next commands if it does). Keep the explicit warning that an empty password would be cleared and that `ALTER ROLE` is printed even then, so `ALTER ROLE` alone does not prove success.
2. **Framing first.** Move the sentence "This is the non-interactive equivalent of Rotate steps 2 and 3" (reworded as needed) to the top of the subsection, right after the explanation paragraph and before the "Do the step below once per namespace" list, so operators do not run both `\password` and `ALTER USER`. The remaining caution about the password on the command line stays at the end.
3. **Rotate intro cross-reference.** Reword the existing sentence in the "Rotate the credential" intro (currently README line 263, "... only `\password` does ...") so it no longer claims `\password` is the only method, e.g. "only changing it inside Postgres does (`\password`, or `ALTER USER` as in "Change the password of an already initialised database")". Change only that sentence; the rest of Rotate and First switch stays as is.
4. **Risk 4 resolved.** The user confirmed that `pet-project-deploy` does not override `postgres.db`; `-d app` is correct.
5. Minor: in the name table, `kind: StatefulSet` is on `postgres.yaml` line 31 (name on line 33).

Verification additions: check that the guard line is present in the new subsection's code block, that the three card commands still match verbatim (exact-line check), and that the Rotate intro no longer contains the phrase "only `\password` does".

## Acceptance criteria
- [ ] README section "Postgres credentials (Sealed Secrets)" has a subsection about rotating or changing the password for an already initialised database
- [ ] It explains why the old password survives a secret change (data volume)
- [ ] It contains the three commands above with `<ns>` as a placeholder
- [ ] It states that the step is needed once per namespace when the sealed password changes, including the first release of Sealed Secrets to prod

## Verification of resource names against the chart

| Name in the card | Where it is defined | Result |
|---|---|---|
| Secret `postgres-credentials` (key `password`) | `/workspace/infra/helm/pet-project/templates/postgres.yaml` (lines 17, 53-56), `/workspace/infra/helm/pet-project/templates/backend.yaml` (lines 25, 28), `/workspace/infra/helm/pet-project/sealed-secrets/{dev,prod}/postgres-credentials.yaml` (`metadata.name` and `spec.template.metadata.name`) | Matches. The chart's `fail()` check also enforces the name. |
| `statefulset/postgres` | `postgres.yaml` line 33 (`kind: StatefulSet`, `name: postgres`, `replicas: 1`). There is no release-name prefix, so the name is literal. | Matches. `kubectl exec statefulset/postgres` resolves to pod `postgres-0`, the same pod the README already uses. |
| `deployment/backend` | `backend.yaml` line 4 (`kind: Deployment`, `name: backend`), no prefix | Matches. |
| database `app` | `/workspace/infra/helm/pet-project/values.yaml` line 26 `postgres.db: app` feeds `POSTGRES_DB` and `DB_URL` | Matches the chart default. The per-env overrides live in the external repo `pet-project-deploy` (`envs/*/values.yaml`), which cannot be seen from here. If either file overrides `postgres.db`, `-d app` would be wrong. This is not expected, and the earlier plan `docs/plans/postgres-sealed-secrets.md` says `postgres.db: app` is kept. |
| user `app` | Not in the chart. `POSTGRES_USER` comes from the sealed key `user`, which is ciphertext. The only source is the README seal command (`--from-literal=user=app`, README line 223). | This matches the documented procedure but cannot be checked from the chart. It would only differ if someone had sealed a different `user`. |

None of the names conflicts with the chart. The two points to note are that the user name `app` is set by the sealing procedure, not the chart, and that the `db` override in the deploy repo could not be checked.

## Changes

### `/workspace/README.md` (the only file changed)

**Placement:** insert a new level-3 subsection inside `## Postgres credentials (Sealed Secrets)`:
- after `### First switch from the old \`app/app\``, whose last bullet ends at current line 342 (`kube-system rollout restart deployment/sealed-secrets-controller\`.`);
- before `### Controller key lost / cluster recreated` (current line 344).

Why here: both preceding subsections (Rotate, First switch) lead to this step, so it follows them in the order an operator works. It is also still inside the `##` section, which runs to the end of the file. The subsection uses `###`, like its siblings, so it does not break the section-boundary rule that earlier plans depend on ("the next `## ` heading ends the section").

No other README lines change. The `## Configuration` and `## Deploy` sections, and the existing Rotate and First switch text, stay as they are (cross-references are covered under Risks and open questions).

**Draft subsection text** (paste as-is; the three commands are verbatim from the card, with `<ns>` kept):

````markdown
### Change the password of an already initialised database

Postgres stores the password of user `app` on its data volume (the PVC
`data-postgres-0` of `statefulset/postgres`) when the database is first
initialised, and never reads `POSTGRES_PASSWORD` again after that. A change
of the sealed password therefore only changes the Secret
`postgres-credentials`: the backend picks up the new value, the database
keeps the old one, and the backend fails with `password authentication
failed for user "app"`. Restarting or recreating the `postgres-0` pod does
not help, because the volume is kept.

Do the step below once per namespace, every time the sealed password for
that namespace changes:

- on every rotation (see "Rotate the credential");
- on the first release of Sealed Secrets to a namespace that already has a
  database, including the first release to `prod` (see "First switch from
  the old `app/app`").

A new namespace, or a dev database whose volume was reset, is initialised
with the sealed password and needs nothing.

Run it only after the SealedSecret in that namespace is `Synced` and the
Secret fingerprint has changed (Rotate steps 1-2); in `prod` that means
after the `main` merge has synced. Before that, the Secret still holds the
old password or does not exist yet, and the commands would set the wrong
one.

Replace `<ns>` with the namespace (`dev` or `prod`):

```bash
NEWPASS=$(kubectl -n <ns> get secret postgres-credentials -o jsonpath='{.data.password}' | base64 -d)
kubectl -n <ns> exec statefulset/postgres -- psql -U app -d app -c "ALTER USER app PASSWORD '$NEWPASS';"
kubectl -n <ns> rollout restart deployment/backend
```

- The first command reads the new password from the Secret. If it prints
  an error or `NEWPASS` is empty, stop: `ALTER USER ... PASSWORD ''` would
  clear the password instead of setting it.
- The second runs `psql` over the local socket inside the Postgres pod,
  which needs no password, and changes the password stored on the volume.
  It must print `ALTER ROLE`.
- The third restarts the backend so that every pod reconnects with the new
  password (Rotate step 3 explains why this is always needed). Follow it
  with `kubectl -n <ns> rollout status deployment/backend` and verify as in
  Rotate step 4.
- Then run `unset NEWPASS`.

This is the non-interactive equivalent of Rotate steps 2 and 3. Unlike
`\password app`, it puts the password on the `kubectl` and `psql` command
lines, so it is visible in the process list of your machine and of the pod
while the command runs. Use `\password app` (Rotate step 2) where that
matters. The quoting in the SQL statement is safe for the hex passwords
produced by "Seal credentials"; a password containing `'` would break it.
````

### No other files
There are no chart, code, config, migration or frontend changes. This plan file is not part of the README change.

## Tests
There are no automated tests for README content, so the change is checked by scripted checks plus the existing test suite.

Let `SCRATCH` be a scratch directory outside the repository. Nothing is written inside `/workspace`.

1. **AC1: the subsection exists inside the right section.** Extract the section and check for the heading:
   ```bash
   awk '/^## Postgres credentials \(Sealed Secrets\)$/{f=1;next} f&&/^## /{f=0} f' /workspace/README.md > "$SCRATCH/section.md"
   grep -nx '### Change the password of an already initialised database' "$SCRATCH/section.md"
   ```
   Expect one match. Also check the order: the heading's line number in README must be greater than that of `### First switch from the old` and smaller than that of `### Controller key lost / cluster recreated` (`grep -n '^### ' /workspace/README.md`).
2. **AC3: the three commands appear verbatim with `<ns>`.** Write the three expected lines into `"$SCRATCH/expected.txt"` using a quoted heredoc (`<<'EOF'`, so nothing expands), then:
   ```bash
   while IFS= read -r l; do grep -qxF -- "$l" "$SCRATCH/section.md" || echo "MISSING: $l"; done < "$SCRATCH/expected.txt"
   ```
   Expect no output. Also check `grep -c '<ns>' "$SCRATCH/section.md"` is at least 3, and that there is no `<env>` inside the new subsection's code block.
3. **AC2: the explanation mentions the data volume.** `grep -n 'data volume' "$SCRATCH/section.md"` matches in the new subsection, together with `POSTGRES_PASSWORD`.
4. **AC4: once per namespace, including prod.** `grep -n 'once per namespace' "$SCRATCH/section.md"` and `grep -n 'first release to `prod`' "$SCRATCH/section.md"` both match.
5. **Rendering and style:** the fenced blocks are balanced (`grep -c '^```' /workspace/README.md` is even), the text is in English, and there are no emojis.
6. **Nothing else changed:** only `README.md` was edited (plus this plan file).
7. **Regression (CLAUDE.md: a task is done only when the tests pass):** `cd /workspace/backend && ./gradlew test` must pass. Docker is needed for Testcontainers. The README is not part of the build, so this only confirms nothing else changed.
8. **Optional manual check (user, on the cluster, not from the dev container):** after the next rotation or the first prod release, run the commands and confirm `ALTER ROLE`, `rollout status` success, and `/actuator/health` returning `UP`.

## Risks and open questions
1. **Clash with the existing "never on the command line" rule (decision needed).** README line 303 says to paste the password into `\password` and "Never put it on the command line". The card's second command puts `$NEWPASS` on the `kubectl exec` and `psql` command lines (and, if the statement fails, in the Postgres server log through `log_min_error_statement`). The acceptance criteria require the commands verbatim, so the draft keeps them and states the trade-off, pointing to `\password app` as the safer option. Alternatives for the user to choose from:
   - (a) keep the draft as is (the default);
   - (b) also show a stdin variant: `printf "ALTER USER app PASSWORD '%s';\n" "$NEWPASS" | kubectl -n <ns> exec -i statefulset/postgres -- psql -U app -d app -v ON_ERROR_STOP=1`;
   - (c) make the card's commands the only documented method and drop the caution.
2. **Empty `NEWPASS`.** If the Secret does not exist yet (the gap between Argo CD pruning the old Secret and the controller creating the new one on a first switch), the first command yields an empty string. PostgreSQL then clears the password instead of setting it. The draft covers this in prose ("stop if empty") so the three commands stay verbatim. A code guard such as `[ -n "$NEWPASS" ] &&` would change the card's commands, so it is left out unless the user asks for it.
3. **Two methods for the same step.** Rotate step 2 and First switch describe the interactive `\password`. The new subsection is framed as the "non-interactive equivalent of Rotate steps 2 and 3", so the two do not contradict each other. Open question: add a one-line pointer from Rotate step 2 and First switch to the new subsection? This is not needed for the acceptance criteria and is left out to keep the change minimal.
4. **Names outside the chart.** User `app` depends on the sealing procedure (README line 223), not the chart. Database `app` could be overridden in `pet-project-deploy` `envs/*/values.yaml`, which could not be checked. The user should confirm neither file sets `postgres.db`.
5. **Timing in prod.** Running the commands before the prod `main` merge has synced sets the password that is already current (the Secret still holds the old value). The draft repeats the existing "only after `Synced` + changed fingerprint" rule to prevent that.
6. **Wording "once per namespace".** On a first switch, the dev option of resetting the volume replaces this step. The draft says a reset or new database "needs nothing" so it does not conflict with "once per namespace".

## Out of scope
- Any change to the Helm chart, the SealedSecrets, the Argo CD manifests, CI workflows, backend or frontend code.
- Automating the password sync (for example a Job or init container that runs `ALTER USER` on Secret change).
- Rewriting or merging the existing "Rotate the credential" and "First switch" subsections.
- Running the commands against the dev or prod clusters.

## Relevant files
- `/workspace/README.md` (the only file to edit; insert between current lines 342 and 344)
- `/workspace/infra/helm/pet-project/templates/postgres.yaml`
- `/workspace/infra/helm/pet-project/templates/backend.yaml`
- `/workspace/infra/helm/pet-project/values.yaml`
- `/workspace/infra/helm/pet-project/sealed-secrets/dev/postgres-credentials.yaml`
- `/workspace/infra/helm/pet-project/sealed-secrets/prod/postgres-credentials.yaml`
- `/workspace/docs/plans/postgres-sealed-secrets.md` (earlier plan, for context)
