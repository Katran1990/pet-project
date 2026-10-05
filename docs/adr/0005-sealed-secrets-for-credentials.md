# 0005. Sealed Secrets for cluster credentials

Date: 2026-09-21
Status: accepted

## Context
The Helm chart stored the Postgres password as plain text (`app/app`) in `values.yaml`,
and `prod` came up with that credential because the deploy repo did not override it.
`dev` and `prod` needed different credentials, nothing secret may appear in
`envs/*/values.yaml` (CI prints those files), and the repository is public.

## Decision
- Cluster credentials are Sealed Secrets: `kubeseal` output committed to git and decrypted
  by the controller in the cluster.
- Postgres: one strict-scope SealedSecret per namespace at
  `infra/helm/pet-project/sealed-secrets/<namespace>/postgres-credentials.yaml`, chosen by
  `.Release.Namespace`. Both `user` and `password` are sealed; the resulting Secret keeps
  the name `postgres-credentials`.
- The template validates the file (kind, name, namespace, no `namespace-wide`/
  `cluster-wide` annotation, both `encryptedData` keys) and calls `fail()` otherwise. A
  namespace without a file never renders, and there is no fallback to `app/app`.
- The controller is a manual, one-time cluster install in `kube-system` with a pinned
  chart version. Its private key is backed up outside every repository.
- Sealing commands keep the plaintext in a shell variable and on stdin
  (`--from-file=password=/dev/stdin`), never on a command line or on disk.
- The local dev credential (`app/app` defaults in `application.properties` and
  `.devcontainer/docker-compose.yml`) stays. It never applies in the cluster, because the
  `secretKeyRef` is not optional.
- The same pattern is used for Grafana: `infra/monitoring/sealed-secrets/` holds
  `grafana-admin` and `grafana-postgres-reader`, applied by the `monitoring-secrets`
  Argo CD Application (ADR 0019).

## Alternatives considered
- External Secrets Operator: needs an external secret backend (Vault, cloud secret
  manager) and a credential to reach it; too much for a pet project.
- `encryptedData` inside `envs/*/values.yaml` in the deploy repo: puts secret material into
  files CI prints, and the deploy repo was not to be changed.
- A values selector such as `postgres.sealedSecretEnv`: the namespace already is the real
  key, a second selector could only disagree with it.
- The controller as an Argo CD Application: `prune`/`selfHeal` removing the CRD would
  delete every SealedSecret and every generated Secret.
- Rendering nothing for an unknown namespace: quieter and less safe.
- A render-time `fail` on `PLACEHOLDER`: would make the chart impossible to verify before
  real ciphertext exists.
- An Argo CD sync wave `-1` on the SealedSecret: an undecryptable one would block the whole
  sync, including unrelated image updates.
- Automatic backend restart on Secret change (checksum annotations): out of scope; rotation
  restarts by hand.

## Consequences
- `helm template` without `--namespace dev|prod` fails by design. `helm template`, not
  `helm lint`, is the canonical check (lint only logs `fail()` as INFO).
- A new namespace needs its own sealed file before it can render.
- Losing the controller key (for example by recreating the k3d cluster without the backup)
  makes all ciphertext useless until everything is re-sealed.
- `POSTGRES_PASSWORD` is read only when the database is first initialised; changing the
  password needs `ALTER USER`/`\password` and a backend restart (README procedures).
- Sealed files applied raw by Argo CD (Grafana) are not validated by any chart; they are
  checked by hand before merge.
