# 0020. Grafana dashboards as code and a read-only database role

Date: 2026-10-04
Status: proposed

## Context
Grafana runs without persistence (ADR 0019), so dashboards edited in the UI are lost on
restart. The card asks for a pinned community JVM dashboard for dev and prod, and a
"Wallet" dashboard on the prod database with the same SQL as `/api/reports/by-category`
(ADR 0017). Grafana needs a database user that can only read. The Flyway user is the
Postgres superuser everywhere. This decision comes from the plan on branch
`feature/grafana-dashboards`, which is not merged yet.

## Decision
- **Dashboards as code:**
  - dashboards are JSON files in `infra/monitoring/dashboards/`;
  - a kustomize `configMapGenerator` turns them into ConfigMaps in namespace `monitoring`,
    with `disableNameSuffixHash: true` and the sidecar label `grafana_dashboard: "1"`;
  - a fifth Argo CD Application, `monitoring-dashboards`, applies them (branch `main`,
    `ServerSideApply=true`).
- **Community dashboards:**
  - they are vendored pinned by grafana.com id and revision, keeping `gnetId`;
  - every edit on top of upstream is made by one jq program kept in the README, so the
    file is reproducible and the diff is reviewable;
  - "JVM (Micrometer)" 4701 revision 10 gets exactly these edits: datasource wiring, a
    fixed uid, a `namespace` variable, an adjusted `instance` query, a p95 line, and the
    exclusion of `uri!~"/actuator.*"` from Rate, Errors, Duration AVG/MAX and p95;
  - the jq program fails (error, exit code 5) if the expected upstream expressions
    differ, so a revision upgrade cannot silently skip an edit;
  - legacy panel types are left to Grafana's automatic migration.
- **Identifiers:** every dashboard has a fixed `uid`, and datasources are referenced by
  fixed uid (`prometheus`, `wallet-postgres`). Import inputs (`__inputs`, `${DS_...}`) are
  not allowed.
- **CI:** a `dashboards` job checks that every JSON file parses, has a uid and a title, has
  no import inputs and no duplicate uid, and that kustomize renders a labelled ConfigMap
  per file.
- **Wallet dashboard:**
  - its only query is the text of `by-category.sql`, with `:month` replaced by
    `${month:sqlstring}`;
  - the `month` query variable on `wallet-postgres` lists the last 12 months in
    Europe/Warsaw, newest first, defaults to the current month, is single-select and has
    `allowCustomValue: false`;
  - convention for any Grafana SQL variable: interpolate it with the `sqlstring` format,
    cast it to the target type in SQL, take the options only from a query, set
    `allowCustomValue: false`, and treat the read-only role as defence in depth;
  - the other panels reuse that result through the `-- Dashboard --` datasource;
  - `WalletDashboardSqlIT` fails if the dashboard and the file drift.
- **Postgres datasource:**
  - it points at prod Postgres and is provisioned through `grafana.additionalDataSources`;
  - credentials come from the SealedSecret `grafana-postgres-reader` through
    `envValueFrom` (optional) and `${...}` env expansion, so no secret is in plain text in
    git.
- **Read-only role:**
  - Flyway `V6__create_grafana_reader_role.sql` creates `grafana_reader` with LOGIN and no
    password, idempotently, because roles are cluster-wide;
  - the role has `default_transaction_read_only = on` and SELECT only on `category`,
    `expense` and `budget_limit`;
  - an operator sets the password once in prod with `ALTER USER`, sent through stdin, using
    the value from the SealedSecret.

## Alternatives considered
- Adding the dashboards to an existing Application: `monitoring` must stay chart plus
  `$values`, `monitoring-secrets` is a plain directory source, and Helm values cannot
  read files from the repo.
- Converting the legacy JVM panels to modern types ourselves: multiplies the diff and
  breaks "pinned by revision".
- A fixed current month on the Wallet dashboard: there would be no way to look at past
  months.
- A bare `$month` or `'$month'` interpolation: the rendering depends on the variable
  format, or the value is not escaped.
- Keeping actuator traffic in the HTTP panels: scrapes and probes would dominate the rate
  and p95 at low traffic.
- A dashboard folder: deferred; dashboards land in "General".
- A required (non-optional) reader Secret: a broken secret would stop Grafana entirely
  instead of only the Wallet panels.
- A password or placeholder in the migration: forbidden.

## Consequences
- Tables added later are not readable by Grafana unless a migration grants SELECT
  explicitly.
- Dashboard changes go only through git; the UI cannot save provisioned dashboards.
- Changing the report SQL means regenerating the dashboard's `rawSql`. A backend test
  reads `../infra/monitoring/dashboards/wallet.json`, so it depends on the full checkout.
- `grafana_reader` exists without a password in every database (dev container, dev,
  Testcontainers), where it cannot log in.
- The Wallet `month` variable query (its list and default) hard-codes the zone and has to
  change together with `APP_TIME_ZONE` in prod (ADR 0015).
- `wallet.json` is not a Gradle test input: after editing only that file, run the tests
  with `--rerun`.
- `optional: true` on the reader Secret env is a deliberate exception to the non-optional
  `secretKeyRef` rule of ADR 0003, which is scoped to the backend.
- Upgrading a community dashboard means downloading the new revision, re-running the jq
  program and reviewing the diff.
- The status becomes `accepted` once the branch is merged.
