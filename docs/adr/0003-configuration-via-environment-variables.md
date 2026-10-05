# 0003. Configuration via environment variables

Date: 2026-09-18
Status: accepted

## Context
The same backend image runs in the dev container, in CI and in the `dev` and `prod`
namespaces of the cluster. `CLAUDE.md` forbids hardcoded secrets and requires environment
variables. The first Docker image (CI pipeline card) had to decide how settings and
credentials reach the container.

## Decision
- Every deployment-specific setting is a Spring property bound to an environment variable
  with a default: `${DB_URL:...}`, `${DB_USER:...}`, `${DB_PASSWORD:...}`,
  `${DB_STARTUP_WAIT_TIMEOUT:60s}`, `${DB_STARTUP_WAIT_INTERVAL:2s}`,
  `${APP_TIME_ZONE:Europe/Warsaw}`.
- Defaults are for local development only. The database defaults point at the
  dev-container Postgres with the throwaway `app/app` credential.
- The Docker image contains no `ENV` defaults and no credentials. The `Dockerfile` only
  lists the variables in a comment.
- In Kubernetes, the database credentials always come from a Secret through a
  non-optional `secretKeyRef` (see ADR 0005), so the local defaults can never be used
  there.
- Invalid values fail startup (for example a negative wait interval or an unknown time
  zone).
- Each variable is documented in the README "Configuration" table.

## Alternatives considered
- `ENV` defaults with credentials in the `Dockerfile`: rejected, no secret is baked into
  the image.
- Anything else: not recorded.

## Consequences
- A new setting needs a property with an env-var placeholder and a sensible default, a
  README row and the `Dockerfile` comment.
- A container started without the variables tries the dev-container database. Deployments
  must always set `DB_URL`, `DB_USER` and `DB_PASSWORD`.
- Tests can pass the same names as command-line properties (`--DB_URL=...`), which proves
  the env-variable wiring end to end.
