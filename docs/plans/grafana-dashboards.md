# Plan: Grafana dashboards: JVM and Wallet

Card: "Grafana dashboards: JVM and Wallet" (https://trello.com/c/iGH4Ylkl/22-grafana-dashboards-jvm-and-wallet)
Branch: `feature/grafana-dashboards` (based on `origin/development`). All paths below are relative to the repo root.

## Goal
Keep two Grafana dashboards in git as JSON under `infra/monitoring/dashboards/`. A kustomize `configMapGenerator` turns them into ConfigMaps, and a new Argo CD Application `monitoring-dashboards` applies them so the Grafana dashboard sidecar picks them up:
- "JVM (Micrometer)", grafana.com 4701 rev 10, with a small documented set of edits: datasource wiring, a dev/prod selector, exclusion of actuator traffic from the HTTP panels, and a p95 line.
- "Wallet", which runs the same SQL as `/api/reports/by-category` against prod Postgres, for a month chosen in a `month` variable that defaults to the current month. Access goes through the read-only role `grafana_reader`, created by Flyway migration `V6`. The credentials come from the user-provided SealedSecret `grafana-postgres-reader`.

CI validates the dashboard JSON and the generated ConfigMaps.

## Acceptance criteria
- [ ] AC1: A JVM/Spring Boot dashboard (community dashboard, pinned by id and revision) shows request rate, latency p95, error rate, heap and GC for dev and prod.
- [ ] AC2: A Postgres datasource for the prod database uses a read-only DB user created by a Flyway migration. The credentials come from a SealedSecret.
- [ ] AC3: A "Wallet" dashboard shows total spent this month, spend by category and limit vs actual, using the same SQL as `/api/reports/by-category`.
- [ ] AC4: The dashboards are JSON files under `infra/monitoring/dashboards/` and are validated in CI as JSON.

Out of scope according to the card: alerting, per-user views.

### Decisions already made by the user (binding)
1. **The SealedSecret file.** `infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml` (currently untracked) is the SealedSecret for this card.
   - It is committed as-is. The implementer must not create, regenerate, edit or delete it.
   - Contents, read during planning: `kind: SealedSecret`, `metadata.name: grafana-postgres-reader`, `metadata.namespace: monitoring`, no scope annotations (strict scope), `spec.encryptedData` keys `password` and `username`.
   - The user confirmed that `username` is exactly `grafana_reader`.
2. **Confirmed scope:** a CI step that validates the dashboard JSON, a Flyway migration that creates a read-only role, and a Grafana connection to prod Postgres through the SealedSecret.
3. **Password handling.** The migration creates `grafana_reader` with LOGIN and **without a password**. It contains no password and no placeholder, and grants SELECT only on the tables the Wallet dashboard needs.
   - The user sets the password once after deploy with `ALTER USER`, using the same value as in the SealedSecret.
   - This step is documented next to README "Change the password of an already initialised database".

### Resolved questions (user answers at plan approval, 2026-10-04)
- **Q1, vendored dashboard 4701 rev 10:** approved. This also confirms the only new third-party artifact (see "New dependency").
- **Fifth Argo CD Application `monitoring-dashboards`:** approved (D1).
- **Q2, actuator traffic:** excluded. The jq program adds `uri!~"/actuator.*"` to the Rate, Errors, Duration (AVG, MAX) and p95 queries. If the upstream expressions are not exactly the expected ones, the program fails loudly (D4, edit 5).
- **Q3, Wallet month:** a `month` template variable replaces the fixed current month (D7, D9).
- **Q4:** `optional: true` on the reader Secret env stays (D6).
- **Q5:** no folder. The dashboards land in "General" (D2).
- **Q6:** confirmed, the SealedSecret's `username` is exactly `grafana_reader`.
- **Plan-reviewer suggestions 1-7, all accepted:**
  1. Table overrides match raw column names; renaming is done by `displayName` overrides, not `organize.renameByName`.
  2. The `-- Dashboard --` targets never set `withTransforms`.
  3. The month-boundary flake is removed: the main-query fixture uses a fixed owned month, and the "current month" check runs inside one DB transaction.
  4. The README documents that Gradle does not track `wallet.json` as a test input, so run `--rerun`.
  5. The README records the jq version used.
  6. The CI comment is fixed: it is the single quotes plus shellcheck's jq exemption (SC2016) that keep shellcheck quiet.
  7. The Wallet datasource sets `jsonData.postgresVersion: 1700`.

### How the criteria are interpreted
- **AC1, "pinned by id and revision":**
  - The committed JSON keeps upstream `"gnetId": 4701`.
  - The revision (10) and the download URL are recorded in a comment in `infra/monitoring/dashboards/kustomization.yaml` and in README "Dashboards".
  - The committed file is exactly the output of one jq program, kept in the README, applied to the upstream download. V1 re-runs it and diffs the result, so every edit on top of upstream is listed and reproducible.
  - The program refuses to run if the upstream HTTP queries are not the expected rev 10 strings, so a future revision upgrade cannot silently skip an edit.
- **AC1, metrics:**

  | Metric | Where it comes from |
  |---|---|
  | Request rate | upstream panel "Rate", with actuator requests excluded |
  | Error rate | upstream panel "Errors": 5xx requests/s, actuator excluded |
  | Latency p95 | **new** target "HTTP - p95" in upstream panel "Duration" (`histogram_quantile` on `_bucket`; the histogram is already enabled, `application.properties` line 33), actuator excluded like the panel's AVG/MAX targets |
  | Heap | upstream panel "JVM Heap" |
  | GC | upstream row "Garbage Collection" and panel "GC Pressure" |
- **AC1, "for dev and prod":**
  - Prometheus already scrapes both namespaces through the `backend` ServiceMonitor, and every series carries the target label `namespace="dev"|"prod"`.
  - All metrics have `application="pet"` in both environments, so a new dashboard variable `namespace` selects the environment. The `instance` variable is filtered by it.
  - Pod IPs are unique across the cluster, so the upstream panel queries, which filter by `instance`, need no namespace filter.
- **AC2, "prod database":** the in-cluster Postgres of the `prod` namespace, i.e. Service `postgres` in `infra/helm/pet-project/templates/postgres.yaml`, port 5432, database `.Values.postgres.db` (default `app`). The read-only user is enforced by the database (grants), not by Grafana.
- **AC3, "this month":** the Wallet dashboard has a single-select `month` variable.
  - It lists the last 12 months, including the current one, newest first, computed in `Europe/Warsaw`. That zone is the backend's default `APP_TIME_ZONE`, which prod uses because the chart does not set `APP_TIME_ZONE`.
  - It defaults to the first entry, the current month. So "total spent this month" is what the dashboard shows on open, and earlier months are one click away.
- **AC3, "same SQL":**
  - The Wallet dashboard has exactly **one** panel SQL query. It is the full text of `backend/src/main/resources/db/report/by-category.sql`, with its single `:month` occurrence replaced by `${month:sqlstring}` (D9).
  - The other panels reuse that panel's result through Grafana's `-- Dashboard --` datasource.
  - The test `WalletDashboardSqlIT` enforces this.
- **AC3 and per-user views (out of scope):** the schema has no `user_id` (`docs/wallet-backlog.md`: one user). The dashboard therefore shows all data, exactly like the API.
- **AC4:** a new CI job checks that:
  - every `infra/monitoring/dashboards/*.json` parses;
  - it has a `uid` and a `title`;
  - it has no unresolved `${DS_…}` import inputs and no `__inputs`;
  - the uids are unique;
  - `kubectl kustomize` renders one labelled ConfigMap entry per JSON file.

## Changes

### What was checked (context)
- **`infra/argocd/apps.yaml`:** four Applications.
  - `monitoring` = kube-prometheus-stack 91.9.0 plus this repo as a pure `ref: values` source, tracking `main`.
  - `monitoring-secrets` = directory source `infra/monitoring/sealed-secrets` on `main`, namespace `monitoring`.
  - **So the new SealedSecret is deployed automatically by `monitoring-secrets`** (a directory source applies every `*.yaml` in that directory; it is not recursive). No change is needed to deploy it, only comment updates.
- **`infra/monitoring/values.yaml`:**
  - It has no `grafana.sidecar.dashboards` settings, so the kube-prometheus-stack defaults apply: dashboard sidecar enabled, label `grafana_dashboard`, value `"1"`, `provider.allowUiUpdates: false`.
  - It has no `additionalDataSources`. The Prometheus datasource is provisioned by the chart (`grafana.sidecar.datasources`, default uid `prometheus`, name `Prometheus`).
  - Grafana has no persistence. Datasources and dashboards are provisioned again on every pod start.
- **Backend metrics** (`application.properties` lines 29-34): `/actuator/prometheus` is exposed, `percentiles-histogram.http.server.requests=true` and `management.metrics.tags.application=${spring.application.name}` (= `pet`). `PrometheusEndpointIT` already asserts `_bucket` lines and the `application` tag.
- **Scraping:**
  - ServiceMonitor `backend` in `infra/helm/pet-project/templates/backend.yaml` exists in `dev` and `prod`. The target labels include `namespace`, `pod`, `instance` (podIP:8080) and `job="backend"`.
  - Prometheus scrapes `/actuator/prometheus` every 30s, and kubelet probes `/actuator/health` every 5-10s. Both show up in `http_server_requests_*` with `uri="/actuator/…"`.
- **Report SQL:** `backend/src/main/resources/db/report/by-category.sql`.
  - It has exactly one `:month` (line 9, `cast(:month as date)`).
  - Its header already says it is the source of the Grafana panel, "which substitutes its own month expression for the month parameter".
  - Tables used: `expense`, `budget_limit`, `category`. It is loaded by `CategoryReportRepository` (JdbcClient) and returns `CategoryReportLine` (a record: categoryId, categoryName, categoryIcon, amount, share, limitAmount, remaining, totalAmount).
- **Flyway:**
  - Migrations V1-V5 exist, so the next one is **V6**. Other tables: `greetings` (V1), `quick_template` (V5) and `flyway_schema_history`.
  - The Flyway user is the Postgres superuser everywhere: `POSTGRES_USER` in the chart and in `.devcontainer/docker-compose.yml`, and `test` in Testcontainers. So `CREATE ROLE` works.
  - Default schema is `public`, because no `spring.flyway.schemas` is set.
  - Placeholder replacement (`${…}`) is on by default, so the migration must not contain `${`.
- **Prod Postgres:**
  - `postgres:17` official image. Its default `pg_hba` allows `host all all all scram-sha-256`, so a role with a password can log in over TCP, and a role without one cannot.
  - No TLS is configured, so Grafana must use `sslmode: disable`.
  - The DB name comes from `postgres.db` (default `app`; the deploy repo's `envs/prod/values.yaml` may override it, see V3).
  - There are no NetworkPolicies, so Grafana in `monitoring` can reach `postgres.prod.svc.cluster.local:5432`.
- **Shared test context:**
  - `ReportControllerIT` documents a shared Spring context and container with CategoryControllerIT, GreetingControllerIT, ApiExceptionHandlerIT and BudgetLimitControllerIT, and lists the months each class owns: ReportControllerIT 2016-01…03, 2017-01…12 and 2099-12; BudgetLimitControllerIT 2020-01, 2030-01…09, 2031-01…05 and 2039-01…06.
  - ExpenseControllerIT, ExpenseListIT and QuickTemplateControllerIT have their own contexts (`@TestBean Clock`).
  - 2018-01 is free. The implementer greps `backend/src/test` for `2018-01` and `2018, 1` to confirm.
- **CI (`.github/workflows/ci.yml`):**
  - Five jobs, no path filters, so everything runs on every PR.
  - The `helm` job's "Render kube-prometheus-stack" step yq-checks the `monitoring` and `monitoring-secrets` Applications.
  - jq, yq (mikefarah v4) and kubectl are preinstalled on GitHub-hosted `ubuntu-latest`.
- **The backend Docker build** runs only `bootJar`, not the tests. A backend test may therefore read `../infra/...` in CI and locally (Gradle's test working directory is `backend/`). `build.gradle` does not declare that file as a test input (R7).
- **Upstream JVM dashboard** (`/tmp/claude-1000/-workspace/e50fe17c-f551-48f6-a1c2-166d9ec3c66e/scratchpad/jvm-4701-rev10.json`):
  - `gnetId: 4701`, `title: "JVM (Micrometer)"`, `version: 33`, `schemaVersion: 14`, legacy `rows[]` with `graph`/`singlestat` panels, **no `uid`**.
  - `__inputs` has `DS_PROMETHEUS`, and every datasource reference is the string `"${DS_PROMETHEUS}"` (panels, the annotation "Restart Detection", all 5 template variables).
  - Row "I/O Overview" has the panels "Rate" (id 111, 1 target), "Errors" (id 112, 1 target) and "Duration" (id 113, targets A = AVG and B = MAX, both with `status!~"5.."`). There is no p95.
  - The four HTTP expressions, exactly as in rev 10 (file lines 458, 537, 613 and 621), are listed in the jq program in section 2.

### Design decisions

**D1. New Argo CD Application `monitoring-dashboards` applies the dashboards (kustomize directory source).**
- Source: this repo, `targetRevision: main`, `path: infra/monitoring/dashboards`, destination namespace `monitoring`. Argo CD detects `kustomization.yaml` and runs kustomize, so the JSON files are never applied as raw manifests.
- Why not the existing apps:
  - `monitoring` must stay a chart plus a pure `$values` ref (CI enforces "no path").
  - `monitoring-secrets` is a plain directory source for SealedSecrets.
  - Helm values cannot read files from the repo.
- This is a fifth Application, in line with Amendment 2 of `docs/plans/kube-prometheus-stack.md` (one app per concern). Approved by the user.
- The user must re-apply `apps.yaml` once after the change reaches `main` (M1).
- Sync options: `CreateNamespace=true` and `ServerSideApply=true`. Client-side apply would copy each ConfigMap into the 256 KiB `last-applied-configuration` annotation. The JVM JSON is about 100 KB today, so this leaves headroom for future dashboards.

**D2. ConfigMaps via `configMapGenerator` with stable names.**
- `generatorOptions.disableNameSuffixHash: true`, so updates happen in place and the sidecar sees a modify event, not a delete and then an add.
- `generatorOptions.labels: { grafana_dashboard: "1" }`, which equals the chart's default sidecar label and value. They are not changed in values.
- `namespace: monitoring`.
- No folder (user decision): dashboards land in "General" next to the chart's default dashboards.
- `searchNamespace` is irrelevant, because the ConfigMaps live in Grafana's own namespace.

**D3. Prometheus datasource reference: `{"type": "prometheus", "uid": "prometheus"}`.**
- The sidecar does not resolve `__inputs`, so every `"${DS_PROMETHEUS}"` is replaced by this object.
- `infra/monitoring/values.yaml` sets `grafana.sidecar.datasources.uid: prometheus` explicitly. It equals the chart default, but is spelled out because the dashboard depends on it. V3 verifies the rendered datasource ConfigMap.

**D4. Edits on top of upstream 4701 rev 10.** There are exactly six, applied by one jq program that is kept in the README:
1. `del(.__inputs)`.
2. Every `"${DS_PROMETHEUS}"` value becomes `{"type":"prometheus","uid":"prometheus"}`.
3. `"uid": "jvm-micrometer"` is added. Without it Grafana generates a random uid on every pod start (no persistence), and links break.
4. A new template variable `namespace` is inserted directly after `application`:
   - query `label_values(jvm_memory_used_bytes{application="$application"}, namespace)`, refresh on time range change (`2`), sort ascending (`1`), single-select, default `current` = `prod`;
   - the `instance` variable query becomes `label_values(jvm_memory_used_bytes{application="$application", namespace="$namespace"}, instance)`.
5. **Actuator traffic is excluded** from the four HTTP targets of the panels "Rate", "Errors" and "Duration": Rate A, Errors A, Duration A (AVG) and Duration B (MAX).
   - Every occurrence of the literal matcher `application="$application", instance="$instance"` in those four expressions becomes `application="$application", instance="$instance", uri!~"/actuator.*"`.
   - This uses jq `split`/`join` on the literal string, not a regex.
   - **Guard:** before editing, the program checks that the set of target expressions in those three panels is exactly the four rev 10 strings. Otherwise it stops with `error(...)` (jq exit code 5). This covers a changed, added or removed query, or a renamed panel.
   - Other panels (threads, logback, etc.) are unchanged.
6. Target C is appended to the panel "Duration": `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="$application", instance="$instance", uri!~"/actuator.*", status!~"5.."}[1m])))`, legend `HTTP - p95`. It uses the same `[1m]` window, actuator exclusion and 5xx exclusion as the neighbouring AVG and MAX targets.

Everything else stays byte-for-byte as jq outputs it: `gnetId`, `title`, `version`, `schemaVersion: 14`, `rows`, the `graph`/`singlestat` panel types, the other queries, and `__requires`.

**D5. Legacy panel types are accepted.**
- kube-prometheus-stack 91.9.0 bundles grafana chart 13.2.7, which ships Grafana 11 or newer. The exact image tag is read in V3 and written into the README.
- Grafana 11+ (Angular disabled) and 12+ (Angular removed) migrate `graph` to `timeseries`, `singlestat` to `stat` and schema 14 `rows` to `panels` on load. The provisioned file stays unchanged.
- Converting the panels ourselves would multiply the diff against upstream and break "pinned by revision". M3 checks visually that no panel shows "Panel plugin not found".

**D6. Postgres datasource via `grafana.additionalDataSources` and env from the Secret.**
- The grafana subchart's `envValueFrom` maps the Secret keys to the env vars `GRAFANA_POSTGRES_READER_USER` and `GRAFANA_POSTGRES_READER_PASSWORD`. The names are deliberately without the `GF_` prefix, which Grafana would treat as config overrides.
- The provisioned datasource uses `${GRAFANA_POSTGRES_READER_USER}` and `${GRAFANA_POSTGRES_READER_PASSWORD}`. Grafana expands env vars in provisioning files. kube-prometheus-stack passes `additionalDataSources` through `tpl`, which only touches `{{ }}`, so the `${…}` reaches Grafana literally (V3 checks this). No secret is ever in git in plain text.
- Fixed `uid: wallet-postgres`, `type: grafana-postgresql-datasource`, `url: postgres.prod.svc.cluster.local:5432`, `jsonData.database: app`, `jsonData.sslmode: disable`, `jsonData.postgresVersion: 1700`, `editable: false`.
- **`secretKeyRef.optional: true`** (user decision): if the reader Secret is missing or cannot be decrypted, Grafana and the JVM dashboard still start, and only the Wallet panels show an authentication error. In contrast, the admin Secret is required.
- The username comes from the Secret (key `username`). The user confirmed it is `grafana_reader`, and M2 double-checks it on the cluster.

**D7. Wallet dashboard design** (`infra/monitoring/dashboards/wallet.json`, hand-written, `schemaVersion: 39`):
- `uid: "wallet"`, `title: "Wallet"`, `tags: ["pet-project"]`, `editable: false`.
- `timepicker.hidden: true` and `time: now/M..now/M`. The panels ignore the time range; the month comes only from the `month` variable (D9).
- The `description` states the SQL contract, the `month` variable (last 12 months in Europe/Warsaw, default current) and "all data, single user".
- `templating.list` = exactly the `month` variable from D9.
- **Panel id 1, table "Limit vs actual":**
  - Datasource `{"type":"grafana-postgresql-datasource","uid":"wallet-postgres"}`, one target `refId A`, `format: "table"`, `rawQuery: true`, `editorMode: "code"`, `rawSql` = by-category.sql with `:month` replaced by `${month:sqlstring}` (section 3).
  - One `organize` transformation that **only hides** fields: `excludeByName: {category_id: true, category_icon: true, total_amount: true}`. There is no `renameByName` and no `indexByName`, so the field names stay the raw SQL column names.
  - Field overrides, each with matcher `byName` on the **raw column name**. Display names are set by the `displayName` property in the same override:

    | Column | displayName | Unit | Decimals | Extra |
    |---|---|---|---|---|
    | `category_name` | Category | | | |
    | `amount` | Spent | `currencyPLN` | 2 | |
    | `share` | Share | `percent` | 1 | |
    | `limit_amount` | Limit | `currencyPLN` | 2 | |
    | `remaining` | Remaining | `currencyPLN` | 2 | `custom.cellOptions: {"type": "color-text"}`; thresholds `{"mode": "absolute", "steps": [{"color": "red", "value": null}, {"color": "green", "value": 0}]}`: a negative remaining is red, as on the Month page; null (no limit) is not coloured |
  - Row order = SQL order: amount desc, then id.
- **Panel id 2, stat "Total spent in ${month:text}":**
  - Datasource `{"type":"datasource","uid":"-- Dashboard --"}`, target `{"refId":"A","datasource":{"type":"datasource","uid":"-- Dashboard --"},"panelId":1}`.
  - **No `withTransforms` key.** The panel gets panel 1's raw query result, before panel 1's `organize`, so `total_amount` is present.
  - `reduceOptions: {values: false, calcs: ["firstNotNull"], fields: "/^total_amount$/"}`, unit `currencyPLN`, decimals 2, `noValue: "0.00"`. A month with no rows shows 0.00, like the API's `totalAmount`.
- **Panel id 3, bar chart "Spend by category in ${month:text}":**
  - Same `-- Dashboard --` source (panelId 1), **no `withTransforms`**. Its own `filterFieldsByName` transformation keeps the raw columns `category_name` and `amount`.
  - Options: `xField: "category_name"`, `orientation: "horizontal"`. Default unit `currencyPLN`, decimals 2.
- **Layout (gridPos):** stat x0 y0 w6 h8, bar chart x6 y0 w18 h8, table x0 y8 w24 h10.
- **Why the `-- Dashboard --` sharing:** one copy of the SQL in the JSON and one DB round trip, so all three panels come from the same snapshot.

**D8. Migration V6 is idempotent across environments.**
- Roles are cluster-wide, so the role is created in a `DO` block only if it does not exist yet. This covers a role created by hand, or by another database in the same Postgres instance.
- GRANTs and `ALTER ROLE … SET` are idempotent anyway.
- It runs everywhere the backend runs Flyway: the dev-container Postgres (on the user's own `bootRun`), k8s `dev` (merge to `development`), `prod` (merge to `main`) and every Testcontainers context.
- In every database except prod the role simply has no password and cannot log in. That is harmless.
- The `month` variable query needs **no** extra grant. It uses only `generate_series`, `now`, `date_trunc` and `to_char`, which PUBLIC can execute.

**D9. Wallet `month` variable and the `:month` substitution.**
- Variable JSON (the only entry of `templating.list`):
  ```json
  {
    "name": "month", "label": "Month", "type": "query",
    "datasource": {"type": "grafana-postgresql-datasource", "uid": "wallet-postgres"},
    "query": "<VARIABLE SQL below, as a plain string>",
    "refresh": 1, "sort": 0, "multi": false, "includeAll": false, "allowCustomValue": false,
    "hide": 0, "current": {}, "options": [], "regex": ""
  }
  ```
  - `query` is stored as a **plain string**, not as an object with `rawSql`. Grafana migrates it in memory, and the panel query stays the only `rawSql` in the file.
  - `refresh: 1` reloads the list on dashboard load. `sort: 0` keeps the SQL order.
  - `current: {}` means Grafana selects the first option, the current month. Provisioned dashboards cannot be saved, so no stale saved value can override that.
- Variable SQL:
  ```sql
  select to_char(m, 'YYYY-MM') as __text, to_char(m, 'YYYY-MM-DD') as __value
  from generate_series(date_trunc('month', now() at time zone 'Europe/Warsaw') - interval '11 months',
                       date_trunc('month', now() at time zone 'Europe/Warsaw'),
                       interval '1 month') as g(m)
  order by m desc
  ```
  - `__text`/`__value` is the Grafana SQL-datasource convention for label and value: the picker shows `2026-10`, and the value is `2026-10-01`.
  - It returns 12 rows, newest first, with the current Warsaw month first.
- **What replaces `:month`:** exactly `${month:sqlstring}`. Line 9 of the panel SQL therefore reads `select cast(${month:sqlstring} as date) as first_day`, which Grafana renders as `select cast('2026-10-01' as date) as first_day`.
- **Why this is type-correct:** `sqlstring` renders a single value as a quoted SQL string literal. `cast('YYYY-MM-DD' as date)` is the same typed date the backend binds via JDBC. Any other value fails the cast instead of returning wrong data.
- **Why it is safe:**
  1. The options come only from the variable query above, i.e. digits and dashes.
  2. `allowCustomValue: false` stops values from being typed into the picker.
  3. `sqlstring` escapes `'` as `''`, so even a crafted `?var-month=` URL value stays inside one string literal and fails the date cast.
  4. Defence in depth: the query runs as `grafana_reader`, in read-only sessions, with SELECT on only the three tables the dashboard shows anyway.
  - A bare `$month` (unquoted) or `'$month'` (no escaping) is deliberately not used.
- The variable list covers only the last 12 months. The API accepts any month; older months are out of scope.

### 1. `backend/src/main/resources/db/migration/V6__create_grafana_reader_role.sql` (new)
```sql
-- Read-only login role for the Grafana "Wallet" dashboard (datasource "Wallet (prod Postgres)",
-- infra/monitoring/values.yaml). It may only read the tables used by db/report/by-category.sql.
-- No credential is set here: the role cannot log in until an operator sets it once per
-- database with ALTER USER (README "Grafana read-only user (grafana_reader)").
-- Roles are cluster-wide, not per database: create it only if it does not exist yet, so this
-- migration also succeeds where the role already exists (created by hand, or by another
-- database in the same Postgres instance).
do $$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'grafana_reader') then
        create role grafana_reader login;
    end if;
end
$$;

-- Defence in depth on top of the grants: every session of this role starts read-only.
alter role grafana_reader set default_transaction_read_only = on;

-- PUBLIC has USAGE on schema public by default; granted explicitly so the grants below do not
-- depend on that default.
grant usage on schema public to grafana_reader;
grant select on table category, expense, budget_limit to grafana_reader;
```
- `CREATE ROLE` defaults are already NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION and NOBYPASSRLS.
- No `GRANT CONNECT`: PUBLIC has CONNECT on new databases by default.
- No default privileges: new tables are **not** readable by Grafana unless a later migration grants them. This is intended.
- The file must never contain `${`, because Flyway would treat it as a placeholder and fail (this is also asserted in a test). Comments avoid quoted password literals.
- Grants on future tables or on `quick_template`, `greetings` and `flyway_schema_history` are deliberately absent.

### 2. `infra/monitoring/dashboards/jvm-micrometer.json` (new, generated)
- Upstream input: copy `/tmp/claude-1000/-workspace/e50fe17c-f551-48f6-a1c2-166d9ec3c66e/scratchpad/jvm-4701-rev10.json` to the implementer's scratchpad.
  - If that file is not readable, download `https://grafana.com/api/dashboards/4701/revisions/10/download` with `curl -fsSL` into the scratchpad.
  - Either way, check `jq -r '[.gnetId, .title, .version, .schemaVersion] | @tsv'` → `4701	JVM (Micrometer)	33	14`.
- **jq version:** generate with **jq 1.7.1**, so the output formatting is reproducible. Use the local `jq` only if `jq --version` prints `jq-1.7.1`; otherwise use `docker run --rm -i -v "$SCRATCH:/s" -w /s ghcr.io/jqlang/jq:1.7.1 …`. If neither works, stop and report it. Do not hand-edit the file. The README records the version next to the program.
- Output: `jq --indent 2 -f <program> upstream.json > infra/monitoring/dashboards/jvm-micrometer.json`. `<program>` is the program below, saved to the scratchpad (not to the repo) and pasted verbatim into the README:
```jq
# Edits on top of grafana.com dashboard 4701 revision 10 (README "Dashboards"). jq 1.7.1.
def prom: {"type": "prometheus", "uid": "prometheus"};
# Label matcher of the upstream HTTP queries, and the same matcher without actuator traffic.
def sel: "application=\"$application\", instance=\"$instance\"";
def sel_no_actuator: sel + ", uri!~\"/actuator.*\"";
def is_http_panel: .title == "Rate" or .title == "Errors" or .title == "Duration";
# The HTTP target expressions of revision 10, verbatim. If an upgrade changes them, stop.
def upstream_http_exprs: [
  "sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\"}[1m]))",
  "sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\", status=~\"5..\"}[1m]))",
  "sum(rate(http_server_requests_seconds_sum{application=\"$application\", instance=\"$instance\", status!~\"5..\"}[1m]))/sum(rate(http_server_requests_seconds_count{application=\"$application\", instance=\"$instance\", status!~\"5..\"}[1m]))",
  "max(http_server_requests_seconds_max{application=\"$application\", instance=\"$instance\", status!~\"5..\"})"
];
if ([.rows[].panels[] | select(is_http_panel) | .targets[].expr] | sort) != (upstream_http_exprs | sort)
then error("4701: the Rate/Errors/Duration queries differ from revision 10; review the actuator and p95 edits before upgrading")
else . end
| del(.__inputs)
| walk(if . == "${DS_PROMETHEUS}" then prom else . end)
| .uid = "jvm-micrometer"
| .templating.list |= (
    map(if .name == "instance"
        then .query = "label_values(jvm_memory_used_bytes{application=\"$application\", namespace=\"$namespace\"}, instance)"
        else . end)
    | .[0:1]
      + [{"name": "namespace", "label": "Namespace", "type": "query", "datasource": prom,
          "query": "label_values(jvm_memory_used_bytes{application=\"$application\"}, namespace)",
          "refresh": 2, "sort": 1, "hide": 0, "includeAll": false, "multi": false, "regex": "",
          "options": [], "current": {"selected": true, "text": "prod", "value": "prod"}}]
      + .[1:])
| (.rows[].panels[] | select(is_http_panel) | .targets[].expr) |= (split(sel) | join(sel_no_actuator))
| (.rows[].panels[] | select(.title == "Duration") | .targets) += [{
    "expr": "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"$application\", instance=\"$instance\", uri!~\"/actuator.*\", status!~\"5..\"}[1m])))",
    "format": "time_series", "intervalFactor": 1, "legendFormat": "HTTP - p95", "refId": "C"}]
```
- The `$application`, `$instance` and `$namespace` inside the jq strings are literal text: jq interpolates only `\( )`.
- The guard runs first, on the untouched upstream, and compares the **sorted list**. So a duplicated, missing, renamed or changed HTTP target all fail with jq exit code 5.
- `split`/`join` with a string argument is a literal replacement, not a regex. Duration A contains the matcher twice and gets both occurrences.

### 3. `infra/monitoring/dashboards/wallet.json` (new, hand-written)
- Content as in D7 and D9.
- `rawSql` is produced from the SQL file, not retyped. This one-liner (also in the README) prints the JSON string to embed:
  ```bash
  jq -Rs --arg expr '${month:sqlstring}' '
    rtrimstr("\n")
    | if (split(":month") | length) != 2 then error("by-category.sql must contain :month exactly once")
      else split(":month") | join($expr) end' backend/src/main/resources/db/report/by-category.sql
  ```
  - The single quotes around `${month:sqlstring}` keep the shell from expanding it.
  - `split`/`join` is a literal replacement.
- `WalletDashboardSqlIT` fails if the two ever drift.
- No `__inputs` and no `${DS_…}`. The only `${…}` references are `${month:sqlstring}` (once, in `rawSql`) and `${month:text}` (panel titles).

### 4. `infra/monitoring/dashboards/kustomization.yaml` (new)
```yaml
# Grafana dashboards as ConfigMaps, applied by the Argo CD Application "monitoring-dashboards"
# (infra/argocd/apps.yaml) and picked up by the Grafana dashboard sidecar of the "monitoring"
# app via the label grafana_dashboard=1 (kube-prometheus-stack default). Edit the JSON in git;
# the UI cannot save provisioned dashboards. See README "Dashboards".
#
# jvm-micrometer.json: grafana.com dashboard 4701 "JVM (Micrometer)", revision 10
# (https://grafana.com/api/dashboards/4701/revisions/10/download), plus the edits applied by the
# jq program in README "Dashboards" (datasource uid, dashboard uid, namespace variable, actuator
# traffic excluded from the HTTP panels, p95).
# wallet.json: hand-written; its only panel SQL is db/report/by-category.sql with :month replaced
# by the month variable (WalletDashboardSqlIT).
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: monitoring
generatorOptions:
  disableNameSuffixHash: true   # stable names: updates are in-place modifies for the sidecar
  labels:
    grafana_dashboard: "1"
configMapGenerator:
  - name: grafana-dashboard-jvm-micrometer
    files:
      - jvm-micrometer.json
  - name: grafana-dashboard-wallet
    files:
      - wallet.json
```

### 5. `infra/monitoring/values.yaml` (modify, `grafana:` block only)
- Header comment: add that the Wallet datasource credentials come from the SealedSecret `infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml` (README "Grafana read-only user (grafana_reader)").
- Add under `grafana:`:
```yaml
  # Wallet datasource credentials from the Secret grafana-postgres-reader (SealedSecret
  # infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml, applied by monitoring-secrets).
  # optional: Grafana and the JVM dashboards still start without it; only the Wallet panels fail.
  # Read at pod start: restart Grafana after a change (README).
  envValueFrom:
    GRAFANA_POSTGRES_READER_USER:
      secretKeyRef: { name: grafana-postgres-reader, key: username, optional: true }
    GRAFANA_POSTGRES_READER_PASSWORD:
      secretKeyRef: { name: grafana-postgres-reader, key: password, optional: true }
  # Read-only access to the prod database for the "Wallet" dashboard (role grafana_reader,
  # Flyway V6). Grafana expands the ${...} env references when it provisions the datasource.
  additionalDataSources:
    - name: Wallet (prod Postgres)
      uid: wallet-postgres            # referenced by infra/monitoring/dashboards/wallet.json
      type: grafana-postgresql-datasource
      access: proxy
      url: postgres.prod.svc.cluster.local:5432   # Service "postgres" of the pet-project chart
      user: ${GRAFANA_POSTGRES_READER_USER}
      jsonData:
        database: app                 # postgres.db of the pet-project chart
        sslmode: disable              # the in-cluster postgres:17 has no TLS
        postgresVersion: 1700         # postgres:17
      secureJsonData:
        password: ${GRAFANA_POSTGRES_READER_PASSWORD}
      editable: false
```
- Under the existing `grafana.sidecar.datasources`, add `uid: prometheus` with the comment "chart default, spelled out: dashboards reference the Prometheus datasource by this uid".
- `database: app` must equal the prod `postgres.db`. V3 checks the deploy repo's `envs/prod/values.yaml`. If it overrides `postgres.db`, use that value and report it.

### 6. `infra/argocd/apps.yaml` (modify)
- **Header:**
  - The `monitoring-secrets` paragraph now says the directory holds the Grafana SealedSecrets: admin credentials and the Wallet datasource's Postgres reader credentials.
  - Add a paragraph for `monitoring-dashboards`: a kustomize source on `infra/monitoring/dashboards` (branch `main`) that generates the dashboard ConfigMaps for the Grafana sidecar, kept separate for the same reason as `monitoring-secrets`.
  - Prerequisite text unchanged.
- **The `monitoring-secrets` document comment:** "Grafana admin SealedSecret" becomes "Grafana SealedSecrets (grafana-admin, grafana-postgres-reader)". Add one line: if `grafana-postgres-reader` cannot be decrypted, only the Wallet panels fail.
- **Append a fifth document:**
```yaml
---
# Grafana dashboards (JVM, Wallet) as ConfigMaps: kustomize configMapGenerator over the JSON files
# in infra/monitoring/dashboards, label grafana_dashboard=1, picked up by the Grafana dashboard
# sidecar of "monitoring". Separate from "monitoring" for the same reason as monitoring-secrets.
# See README "Dashboards".
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: monitoring-dashboards
  namespace: argocd
spec:
  project: default
  destination:
    server: https://kubernetes.default.svc
    namespace: monitoring
  source:
    repoURL: https://github.com/Katran1990/pet-project.git
    targetRevision: main
    path: infra/monitoring/dashboards
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true   # large dashboard ConfigMaps would exceed the 256 KiB last-applied annotation
```
The existing four Applications stay byte-for-byte unchanged, apart from the comment lines named above.

### 7. `infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml` (user-provided, unchanged)
- Commit it as-is. The implementer does not touch it (V5 only reads it).

### 8. `.github/workflows/ci.yml` (modify)
**8a. Header comment:** "Five independent jobs (…)" becomes "Six independent jobs (backend, frontend, the Helm chart check, the Grafana dashboards check, actionlint and the Trivy dependency scan)".

**8b. Step "Render kube-prometheus-stack with monitoring values" (job `helm`):**
- Add a fourth yq check after the `monitoring-secrets` check, in the same style:
```bash
          yq -e 'select(.metadata.name == "monitoring-dashboards") | .spec
                 | select(.destination.namespace == "monitoring" and .source.path == "infra/monitoring/dashboards" and .source.targetRevision == "main")' \
            infra/argocd/apps.yaml > /dev/null \
            || { echo "::error::apps.yaml: Application monitoring-dashboards must apply infra/monitoring/dashboards from main into namespace monitoring"; exit 1; }
```
- Extend the step comment by one clause.

**8c. New job `dashboards`, placed after `helm`:**
```yaml
  dashboards:
    name: Grafana dashboards
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v6
        with:
          persist-credentials: false

      # jq, yq (mikefarah v4) and kubectl are preinstalled on GitHub-hosted ubuntu-latest runners,
      # so no extra action. Each dashboard must be valid JSON with a uid and a title, and must not
      # carry grafana.com import inputs (__inputs / ${DS_...}): the Grafana sidecar does not resolve
      # them. The jq program is in single quotes, so the shell never expands the "${DS_" in it, and
      # shellcheck's SC2016 ("expressions don't expand in single quotes") exempts jq programs.
      - name: Validate dashboard JSON
        run: |
          shopt -s nullglob
          files=(infra/monitoring/dashboards/*.json)
          [ "${#files[@]}" -gt 0 ] || { echo "::error::no dashboards in infra/monitoring/dashboards"; exit 1; }
          for f in "${files[@]}"; do
            jq -e '(.uid | type == "string" and length > 0)
                   and (.title | type == "string" and length > 0)
                   and (has("__inputs") | not)
                   and ([.. | strings | select(contains("${DS_"))] | length == 0)' "$f" > /dev/null \
              || { echo "::error file=${f}::invalid JSON, missing uid/title, or unresolved import inputs"; exit 1; }
          done
          dup="$(jq -r .uid "${files[@]}" | sort | uniq -d)"
          [ -z "${dup}" ] || { echo "::error::duplicate dashboard uid(s): ${dup}"; exit 1; }

      # Renders the ConfigMaps exactly as Argo CD does (kustomize) and checks that every JSON file
      # ends up in a ConfigMap in namespace monitoring with the sidecar label grafana_dashboard=1.
      - name: Render dashboard ConfigMaps (kustomize)
        run: |
          out="${RUNNER_TEMP}/dashboards.yaml"
          kubectl kustomize infra/monitoring/dashboards > "${out}"
          for f in infra/monitoring/dashboards/*.json; do
            NAME="$(basename "${f}")" yq -e 'select(.kind == "ConfigMap" and .metadata.namespace == "monitoring"
                   and .metadata.labels.grafana_dashboard == "1" and (.data | has(strenv(NAME))))' "${out}" > /dev/null \
              || { echo "::error file=${f}::not rendered into a labelled ConfigMap (kustomization.yaml)"; exit 1; }
          done
```
- `${DS_` is not a GitHub Actions expression (`${{ }}`), so Actions leaves it alone.
- No new action and no new tool. actionlint must stay green (V6).
- If shellcheck still flags something, fix the quoting. Do not add `# shellcheck disable` without saying so in the report.

### 9. `backend/src/test/java/dev/katran/pet/db/GrafanaReaderRoleIT.java` (new)
- Uses the same annotations as `ReportControllerIT`: `@Import(TestcontainersConfiguration.class)` and `@SpringBootTest(webEnvironment = RANDOM_PORT)`, with no `@TestBean`, so it shares the cached context.
- Uses `JdbcTemplate` and `TransactionTemplate`. Tests:
  1. **`roleCanLogInButHasNoPassword`:**
     - `select rolcanlogin, rolpassword is null, rolsuper, rolcreaterole, rolcreatedb from pg_authid where rolname = 'grafana_reader'` → `true, true, false, false, false`. The superuser test connection can read `pg_authid`.
     - `select rolconfig from pg_roles where rolname = 'grafana_reader'` contains `default_transaction_read_only=on`.
  2. **`hasSelectOnlyOnTheReportTables`:**
     - `has_table_privilege('grafana_reader', t, p)`: SELECT true for `category`, `expense` and `budget_limit`.
     - INSERT, UPDATE, DELETE and TRUNCATE false for those three.
     - SELECT false for `quick_template`, `greetings` and `flyway_schema_history`.
     - `has_schema_privilege('grafana_reader', 'public', 'USAGE')` true, and `'CREATE'` false.
  3. **`writesAreRejectedWhenActingAsTheRole`:**
     - Each statement runs in its own `TransactionTemplate` transaction that first runs `set local role grafana_reader`.
     - `select count(*) from expense` succeeds.
     - `insert into category (name) values (…)`, `update expense set amount = amount`, `delete from budget_limit` and `select * from quick_template` each fail with SQLState `42501` (assert on the root `SQLException.getSQLState()`).
     - `SET LOCAL` ends with the transaction, so the pooled connection is clean afterwards.
  4. **`migrationRunsAgainWhenTheRoleAlreadyExists`:**
     - Load `classpath:db/migration/V6__create_grafana_reader_role.sql` and `jdbcTemplate.execute(script)`. PgJDBC accepts the multi-statement script and handles `$$` quoting.
     - It must not throw. Afterwards `select count(*) from pg_roles where rolname = 'grafana_reader'` = 1 and test 1 still holds.
  5. **`migrationContainsNoPasswordAndNoPlaceholder`:** the script text does not contain `${` and does not match `(?i)password\s*'`.

### 10. `backend/src/test/java/dev/katran/pet/report/WalletDashboardSqlIT.java` (new)
- Same shared-context annotations.
- Constants:
  - `MONTH_REF = "${month:sqlstring}"` (literal Java string);
  - `FIXTURE_MONTH = YearMonth.of(2018, 1)` (owned by this class);
  - `DASHBOARD = Path.of("../infra/monitoring/dashboards/wallet.json")`. The path is relative to Gradle's test working directory `backend/`. Assert the file exists, with a clear message (it is read from the repo, not the classpath).
- Parse with the Jackson 3 `JsonMapper` already on the test classpath (`tools.jackson.databind`). Collect every `rawSql` with `findValues("rawSql")`. Read the variable query from `templating.list[0].query`.
- Helper `grafanaSqlString(String v)` = `"'" + v.replace("'", "''") + "'"`. This is what Grafana's `sqlstring` format renders for a single value.
- Tests:
  1. **`dashboardHasOneQueryAndItIsTheReportSql`:**
     - Exactly one `rawSql`.
     - `by-category.sql` (classpath) contains `:month` exactly once.
     - `rawSql.strip()` equals `file.strip().replace(":month", MONTH_REF)`.
     - The panel holding it uses datasource uid `wallet-postgres`.
     - Every other panel's targets use datasource uid `-- Dashboard --` with `panelId` equal to that panel's id, and **have no `withTransforms` field** (missing, or `false`).
  2. **`monthVariableIsASingleSelectQueryOnWalletPostgres`:**
     - `templating.list` has exactly one entry: `name` `month`, `type` `query`, datasource uid `wallet-postgres`, `query` a string, `multi` false, `includeAll` false, `allowCustomValue` false.
     - `current` is missing or empty.
  3. **`monthVariableListsTheLastTwelveMonthsNewestFirstWhenRunAsGrafanaReader`:**
     - All in **one** `TransactionTemplate` transaction that first runs `set local role grafana_reader`. `now()` is the transaction start time, so every statement below sees the same "now" and a month boundary cannot make the test flaky.
     - Run the variable query from the JSON → `List<Map>` with `__text` and `__value`.
     - Run `select to_char(date_trunc('month', now() at time zone 'Europe/Warsaw'), 'YYYY-MM-DD')` → `current`.
     - Assert 12 rows, row 0 `__value` == `current`, and each next `__value` is exactly one month earlier (parse with `LocalDate`). Every `__value` is the 1st day of its month, and `__text` == the first 7 characters of `__value`.
     - Then, still in the same transaction, run the dashboard `rawSql` with `MONTH_REF` replaced by `grafanaSqlString(row0 __value)`. It executes without error. This proves the variable value feeds `cast(… as date)` correctly and the role needs no extra grant.
  4. **`dashboardSqlReturnsTheSameRowsAsTheReportWhenRunAsGrafanaReader`:**
     - Fixture in `FIXTURE_MONTH` (2018-01), dated 2018-01-01:
       - category A: expenses 100.00 and 50.50, limit 120.00 (over the limit);
       - category B: limit 300.00 and no expenses.
       - Unique names, inserted with `CategoryRepository` and `JdbcTemplate`, like `ReportControllerIT`.
     - Expected: `CategoryReportRepository.findByMonth(FIXTURE_MONTH)`.
     - Actual: substitute `MONTH_REF` by `grafanaSqlString("2018-01-01")`, i.e. `'2018-01-01'`, the way Grafana would for that variable value. Then, inside a `TransactionTemplate` that runs `set local role grafana_reader`, run `JdbcClient.sql(sql).query(CategoryReportLine.class).list()`.
     - Assert equal lists (records; BigDecimal scales match because the SQL is identical), and that the rows of A and B are present: A amount `150.50`, remaining `-30.50`; B amount `0.00`.
     - This proves "same SQL", "same result" and that the grants cover every table the dashboard reads.
     - The fixed month makes the test independent of the run date, which replaces reviewer suggestion 3's "read the current month from the DB" for this test. The current-month logic is covered by test 3 inside one transaction.
- Class comment: this class owns month 2018-01 in the shared database.

### 11. `backend/src/test/java/dev/katran/pet/report/ReportControllerIT.java` (comment only)
- Extend the "Shares the cached Spring Boot test context…" comment with `GrafanaReaderRoleIT` and `WalletDashboardSqlIT`.
- Add: "WalletDashboardSqlIT owns 2018-01". No code change.

### 12. `README.md` (modify)
- **"CI and Docker images" bullet:**
  - Add the new `dashboards` job: JSON validation plus the kustomize render of the dashboard ConfigMaps.
  - The yq check now also covers `monitoring-dashboards`.
  - Local commands: the jq/kustomize commands from V1/V2.
- **"Deploy (Helm + Argo CD)":**
  - "four Argo CD Applications" becomes **five**. Add `monitoring-dashboards` -> the dashboard ConfigMaps from `infra/monitoring/dashboards` on `main`.
  - `monitoring-secrets` now applies the Grafana SealedSecrets (admin and Postgres reader).
- **New subsection `### Grafana read-only user (grafana_reader)`**, directly after "Change the password of an already initialised database" (before "Controller key lost / cluster recreated"):
  - **What exists:** Flyway `V6__create_grafana_reader_role.sql` creates the cluster-wide role `grafana_reader` in every database the backend migrates: dev container, `dev`, `prod` and Testcontainers. It has LOGIN, **no password**, SELECT only on `category`, `expense` and `budget_limit`, and read-only sessions. Without a password it cannot log in, which is the state everywhere except prod.
  - **Where the credentials live:** SealedSecret `grafana-postgres-reader` (namespace `monitoring`, keys `username` = `grafana_reader` and `password`) in `infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml`, applied by `monitoring-secrets`. Grafana reads it at pod start (`envValueFrom`, optional).
  - **When to set the password:**
    - once, after V6 has run in prod (the prod backend rolled out from `main`) **and** `kubectl -n monitoring get sealedsecret grafana-postgres-reader` shows `Synced=True`;
    - again after every re-seal of that file.
    - Check the role first: `kubectl -n prod exec statefulset/postgres -- psql -U app -d app -tAc "select rolname from pg_roles where rolname = 'grafana_reader'"` prints `grafana_reader`.
  - **Commands (one at a time, check each output):**
    ```bash
    READER_PW=$(kubectl -n monitoring get secret grafana-postgres-reader -o jsonpath='{.data.password}' | base64 -d)
    [ -n "$READER_PW" ] || echo "READER_PW is empty - stop"
    printf "ALTER USER grafana_reader PASSWORD '%s';\n" "$READER_PW" \
      | kubectl -n prod exec -i statefulset/postgres -- psql -U app -d app -v ON_ERROR_STOP=1
    kubectl -n monitoring rollout restart deployment/monitoring-grafana
    unset READER_PW
    ```
    - The SQL goes through stdin. `printf` is a shell builtin, so unlike the `-c` variant above, the password does not appear on any command line. The third command must print `ALTER ROLE`.
    - The empty-guard note from the section above applies: an empty value would *clear* the password.
    - The quoting is safe for hex passwords from `openssl rand -hex`.
    - The restart makes Grafana re-read the Secret; it is needed if Grafana started before the Secret existed.
  - **Verify:** Grafana → Connections → Data sources → "Wallet (prod Postgres)" → "Save & test" succeeds (or open the Wallet dashboard). Then `kubectl -n monitoring get secret grafana-postgres-reader -o jsonpath='{.data.username}' | base64 -d; echo` prints `grafana_reader`.
  - **Rotate:** re-seal with the same pattern as the Grafana admin secret, then repeat the commands above after the change reaches `main` and is `Synced`:
    ```bash
    OUT="infra/monitoring/sealed-secrets/grafana-postgres-reader.yaml"
    PW="$(openssl rand -hex 24)"
    ( set -o pipefail
      printf %s "$PW" \
      | kubectl create secret generic grafana-postgres-reader --namespace monitoring \
          --from-literal=username=grafana_reader --from-file=password=/dev/stdin \
          --dry-run=client -o yaml \
      | kubeseal --format yaml \
      > "$OUT.tmp"
    ) && mv "$OUT.tmp" "$OUT" || rm -f "$OUT.tmp"
    ```
  - **Never** put the password into the migration, into `values.yaml` or on a command line.
- **"Monitoring (kube-prometheus-stack)":**
  - "What is installed": add the provisioned datasources (Prometheus; Wallet = prod Postgres as `grafana_reader`) and the dashboards app.
  - "First install" step 3: `kubectl apply -f infra/argocd/apps.yaml` creates `monitoring`, `monitoring-secrets` and `monitoring-dashboards`. Add a step: set the `grafana_reader` password (link).
  - "Reach Grafana": "The Prometheus datasource is provisioned automatically" becomes "the Prometheus and Wallet (prod Postgres) datasources".
- **New subsection `### Dashboards`** (after "Check the backend targets in Prometheus"):
  - Where they live (`infra/monitoring/dashboards/*.json`, ConfigMaps from `kustomization.yaml`, label `grafana_dashboard=1`, folder General).
  - Changes go through git: the UI cannot save provisioned dashboards, and Grafana has no persistence.
  - CI checks.
  - **JVM (Micrometer):**
    - Source grafana.com 4701 revision 10 (URL), `gnetId` kept in the file.
    - The six edits from D4 as a list. Say explicitly that the HTTP panels (Rate, Errors, Duration AVG/MAX/p95) exclude `uri=~"/actuator.*"`: Prometheus scrapes and kubelet probes are not counted, so an idle backend shows a rate of 0 and no p95.
    - The jq program verbatim, with "generated with jq 1.7.1" next to it.
    - How to upgrade: download the new revision, run the program with jq 1.7.1, and review the diff. If the program stops with "differ from revision 10", the upstream HTTP queries changed: review them, update `upstream_http_exprs` and the edits, and never just delete the guard. Update the revision in `kustomization.yaml` and here.
    - The Grafana image version that migrates the legacy panels (from V3).
    - Use the `Namespace` variable (dev/prod), then `Instance` (one per backend pod).
  - **Wallet:**
    - Panels.
    - The `Month` picker: last 12 months in Europe/Warsaw, newest first, default the current month, no custom values.
    - The SQL contract: the only panel query is `by-category.sql` with `:month` replaced by `${month:sqlstring}` (quoted and escaped by Grafana, then cast to `date`), and `WalletDashboardSqlIT` fails if they drift. To change the report, change the SQL file and regenerate `rawSql` with the jq one-liner from section 3 (copied verbatim).
    - **Run the test with `--rerun`:** Gradle does not track `infra/monitoring/dashboards/wallet.json` as an input of `test`. After a change to only that file, use `cd backend && ./gradlew test --rerun`, otherwise `test` can be UP-TO-DATE and skip the check. CI always runs it on a fresh checkout.
    - prod only; all data, no per-user view.

### 13. No other files
- No change to `application.properties`, the Helm chart, the frontend, `build.gradle` or `gradle.lockfile` (no dependency change, so no `--write-locks`).
- No change to the deploy repo or to `docs/wallet-backlog.md`.

### New dependency
No new library, Gradle/npm dependency, GitHub Action or CI tool. jq, yq and kubectl are preinstalled on the runners. jq 1.7.1 (image `ghcr.io/jqlang/jq:1.7.1`) is only used locally to generate the JVM file.

New third-party **content** is vendored into the repo (**confirmed by the user**, Q1):

| Artifact | Version | Why it is needed | Cost of writing it by hand |
|---|---|---|---|
| grafana.com dashboard 4701 "JVM (Micrometer)" | revision 10 (2024-11-22) | Named by the card ("community dashboard, pinned by id and revision"). It covers rate, errors, duration, heap, GC, threads, CPU and more for Micrometer metrics. | About 30 panels of PromQL and layout written and kept up to date by us, with no upstream to compare against. |

### Manual steps for the user (cluster only; not done by subagents)
- **M1:** after the change reaches `main`, run `kubectl apply -f infra/argocd/apps.yaml`. This creates `monitoring-dashboards`; the other four apps are unchanged. Wait until `monitoring`, `monitoring-secrets` and `monitoring-dashboards` are Synced/Healthy, and `kubectl -n monitoring get sealedsecret grafana-postgres-reader` shows `Synced=True`.
- **M2:** after the prod backend has rolled out with V6, set the `grafana_reader` password (README "Grafana read-only user"), restart Grafana and check that the username printout is `grafana_reader`.
- **M3 (AC1):** open "JVM (Micrometer)".
  - Select Namespace `prod`, then `dev`. Rate, Errors, Duration (AVG/MAX/p95), JVM Heap and the GC panels show data.
  - Generate some traffic on `dev.pet.local` (e.g. the Expenses page), because actuator requests are excluded and an idle backend shows a rate of 0 and no p95.
  - With no user traffic, Rate stays at 0 even though Prometheus keeps scraping. This confirms the actuator exclusion.
  - No panel says "Panel plugin not found".
- **M4 (AC2/AC3):**
  - "Save & test" on "Wallet (prod Postgres)". Open "Wallet".
  - The Month picker shows 12 months, newest first, preselected to the current month.
  - The total equals `totalAmount` of `GET /api/reports/by-category?month=<current YYYY-MM>` on prod, and the table rows equal the API rows. Switch to the previous month and compare again.
  - Query inspector on the table shows `cast('YYYY-MM-01' as date)`, so `${month:sqlstring}` is supported by the bundled Grafana (R12).
  - The table shows Category/Spent/Share/Limit/Remaining headers, `Share` as a percent with one decimal, money with 2 decimals in PLN, negative `Remaining` in red and non-negative in green.

## Tests
Rules for every subagent:
- Do not run `./gradlew bootRun` and do not connect to the dev Postgres (`postgres:5432`). DB tests run through Testcontainers only.
- One Gradle run at a time in `backend/`. Use `--rerun`, because Gradle does not see changes to `wallet.json` (R7).
- No git commands.
- Temporary files go to the session scratchpad `$SCRATCH`, never into `/workspace`.
- If a tool or image cannot be fetched, report the check as **UNVERIFIED**, never as passed.

Tools:
- `helm` and `yq` are not installed in the dev container. Use `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 …` and `docker run --rm -i -v "$PWD:/w" -w /w mikefarah/yq:4 …`.
- kustomize: `docker run --rm -v "$PWD:/w" -w /w registry.k8s.io/kustomize/kustomize:v5.6.0 build infra/monitoring/dashboards`. Any existing v5 tag is fine; say which one was used.
- jq: 1.7.1, either local (`jq --version` = `jq-1.7.1`) or `ghcr.io/jqlang/jq:1.7.1`.

### Coverage map
| AC | Automated proof | Manual |
|---|---|---|
| AC1 | V1: committed JVM JSON == jq program applied to upstream 4701 rev 10; gnetId 4701; p95 target; actuator exclusion on all five HTTP targets; namespace variable; no `${DS_`; the program fails on a mutated upstream. V2: ConfigMap rendered with the sidecar label. V3: `uid: prometheus` datasource, sidecar label. Existing `PrometheusEndpointIT`: buckets and `application` tag. | M3 |
| AC2 | `GrafanaReaderRoleIT`: role exists, LOGIN, no password, read-only grants, idempotent, no password or placeholder in the file. Every IT context runs Flyway V6 on Testcontainers. V3: datasource provisioned with `${…}` env refs only, `postgresVersion: 1700`, env from Secret `grafana-postgres-reader`. V4: `monitoring-secrets` still deploys the directory. V5: sealed file structure. | M1, M2, M4 |
| AC3 | `WalletDashboardSqlIT`: single query == by-category.sql with only `:month` → `${month:sqlstring}`; month variable structure; variable query as `grafana_reader` returns the current month first and feeds the main query; the same rows as `CategoryReportRepository` for a fixture month when run as `grafana_reader`; no `withTransforms`. V1: Wallet JSON structure. | M4 |
| AC4 | CI job `dashboards` (8c), run locally as V7 including negative cases; V6 actionlint | CI green on the PR |
| CLAUDE.md "tests pass" | V8 | |

### Verification commands (from `/workspace`)
**V1. Dashboard JSON (jq 1.7.1):**
- `jq -e . infra/monitoring/dashboards/*.json > /dev/null`.
- Re-run the jq program **copied from the README** on the upstream file and compare: `diff <(jq --indent 2 -f "$SCRATCH/jvm.jq" "$SCRATCH/jvm-4701-rev10.json") infra/monitoring/dashboards/jvm-micrometer.json` → no output.
- **Guard, negative case:**
  - Mutate the upstream: `jq '(.rows[].panels[] | select(.title=="Rate") | .targets[0].expr) |= sub("\\[1m\\]"; "[5m]")' "$SCRATCH/jvm-4701-rev10.json" > "$SCRATCH/mutated.json"`.
  - Run the program on it → non-zero exit (5) with "differ from revision 10".
  - Do the same with an extra copy of the "Errors" target appended → also fails.
- `jq -r '.gnetId, .uid, (.templating.list | map(.name) | join(","))'` on the JVM file → `4701`, `jvm-micrometer`, `application,namespace,instance,jvm_memory_pool_heap,jvm_memory_pool_nonheap,jvm_buffer_pool`.
- `jq '[.rows[].panels[] | select(.title=="Duration") | .targets[].legendFormat]'` → `["HTTP - AVG","HTTP - MAX","HTTP - p95"]`.
- `jq '[.rows[].panels[] | select(.title=="Rate" or .title=="Errors" or .title=="Duration") | .targets[].expr | contains("uri!~\"/actuator.*\"")]'` → `[true,true,true,true,true]`.
- `jq '[.rows[].panels[] | select(.title=="Rate" or .title=="Errors" or .title=="Duration") | .targets[].expr | test("instance=\"\\$instance\"(?!, uri)")] | any'` → `false`: no HTTP matcher is left without the filter.
- `grep -c 'DS_PROMETHEUS' infra/monitoring/dashboards/*.json` → `0` for both files.
- **Wallet:**
  - `jq '[.. | .rawSql? // empty] | length'` → 1, and that `rawSql` contains `${month:sqlstring}` exactly once.
  - `.uid` = `wallet`; `[.templating.list[].name]` = `["month"]`, with `allowCustomValue` false and `multi` false.
  - Panel types are `table`, `stat` and `barchart`.
  - `[.panels[].targets[]? | select(has("withTransforms"))] | length` → 0.
  - Panel 1's transformations contain no `renameByName`.

**V2. kustomize render:**
- Expect exactly two ConfigMaps, `grafana-dashboard-jvm-micrometer` and `grafana-dashboard-wallet`, namespace `monitoring`, label `grafana_dashboard: "1"`, no hash suffix.
- The data keys equal the file names, and each value parses with jq.
- Record the byte size of each ConfigMap (risk R6).

**V3. kube-prometheus-stack render** (the command from README "CI and Docker images", output to `$SCRATCH/kps.yaml`):
- Datasource ConfigMap (`monitoring-kube-prometheus-grafana-datasource`; use the rendered name if it differs):
  - contains `uid: prometheus` for the Prometheus datasource;
  - contains the Wallet entry with `uid: wallet-postgres`, `type: grafana-postgresql-datasource`, `url: postgres.prod.svc.cluster.local:5432`, `sslmode: disable`, `postgresVersion: 1700`;
  - contains `user`/`password` literally `${GRAFANA_POSTGRES_READER_USER}` / `${GRAFANA_POSTGRES_READER_PASSWORD}`.
- Grafana container env: `GRAFANA_POSTGRES_READER_USER` → `secretKeyRef grafana-postgres-reader/username optional`, and `…_PASSWORD` → `…/password optional`. `GF_SECURITY_ADMIN_*` are unchanged.
- The dashboard sidecar container env has `LABEL=grafana_dashboard` and `LABEL_VALUE=1`.
- Record the Grafana image tag (README D5 text). Flag it if it is below 11.
- Fetch `https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/prod/values.yaml` read-only. If it sets `postgres.db`, the datasource `database` must match it.

**V4. apps.yaml:**
- `yq 'select(.kind=="Application") | .metadata.name'` → `pet-project-dev`, `pet-project-prod`, `monitoring`, `monitoring-secrets`, `monitoring-dashboards`.
- The four existing documents are unchanged apart from comments: diff against a copy taken before editing.
- Run the yq block of CI step 8b locally: it passes on the real file, and fails on a scratch copy where the `monitoring-dashboards` path or revision is altered.

**V5. Sealed file (read-only):**
- `yq '[.kind, .metadata.name, .metadata.namespace] | join(" ")'` → `SealedSecret grafana-postgres-reader monitoring`.
- `.spec.encryptedData | keys` → `["password","username"]`.
- No scope annotations, no `data`/`stringData`/`spec.template.data`.
- Its mtime/checksum is unchanged from before the implementation started: record `sha256sum` first.

**V6. actionlint:** `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color` → exit 0, no output.

**V7. CI dashboards job locally:**
- Run both `run:` scripts of 8c in bash from `/workspace` (`RUNNER_TEMP=$SCRATCH`, the jq/yq/kubectl substitutes above; kustomize via the docker image if `kubectl` is missing) → exit 0.
- Negative cases, each on a scratch copy of the repo's `infra/monitoring/dashboards` plus the script run from that copy's root:
  - truncated JSON;
  - a file still containing `"${DS_PROMETHEUS}"`;
  - a duplicate uid;
  - a JSON file not listed in `kustomization.yaml`.
  - Each must fail with the matching `::error`.
- `wallet.json` (which contains `${month:sqlstring}` and `${month:text}`) must pass: only `${DS_` is rejected.

**V8. Project tests:**
- `cd /workspace/backend && ./gradlew test --rerun`. All pass, including `GrafanaReaderRoleIT` and `WalletDashboardSqlIT`. Every IT context applies V6 on Testcontainers.
- `cd /workspace/frontend && npm test` (no frontend change; CLAUDE.md requires green tests).

**V9. README:**
- `grep -n` finds:
  - `### Grafana read-only user (grafana_reader)` (once) and `### Dashboards` (once);
  - `4701`, `revisions/10/download`, `monitoring-dashboards`, `ALTER USER grafana_reader`, `five`;
  - `uri!~"/actuator.*"`, `differ from revision 10`, `jq 1.7.1`, `${month:sqlstring}`, `--rerun`.
- `grep -rn "four Argo CD Applications" README.md` → none.
- The jq program and the rawSql one-liner in the README are byte-identical to the ones used in V1 and section 3. Extract them to `$SCRATCH` and `diff`.

**V10. Changed-file set:**
- Create `$SCRATCH/impl-start.ref` before the first edit.
- `find /workspace/infra /workspace/.github /workspace/README.md /workspace/backend/src /workspace/frontend/src -type f -newer "$SCRATCH/impl-start.ref" | sort` → exactly:
  ```
  /workspace/.github/workflows/ci.yml
  /workspace/README.md
  /workspace/backend/src/main/resources/db/migration/V6__create_grafana_reader_role.sql
  /workspace/backend/src/test/java/dev/katran/pet/db/GrafanaReaderRoleIT.java
  /workspace/backend/src/test/java/dev/katran/pet/report/ReportControllerIT.java
  /workspace/backend/src/test/java/dev/katran/pet/report/WalletDashboardSqlIT.java
  /workspace/infra/argocd/apps.yaml
  /workspace/infra/monitoring/dashboards/jvm-micrometer.json
  /workspace/infra/monitoring/dashboards/kustomization.yaml
  /workspace/infra/monitoring/dashboards/wallet.json
  /workspace/infra/monitoring/values.yaml
  ```
- `grafana-postgres-reader.yaml` must **not** be in that list.
- No upstream download, jq program or render output may be left in `/workspace`.

## Risks and open questions
No open questions remain; all were resolved by the user (see "Resolved questions"). Remaining risks:
1. **R1, legacy JSON on the bundled Grafana (D5).** It relies on Grafana's automatic migration of schema 14 / `graph` / `singlestat`. V3 records the version and M3 checks the result. If a panel does not migrate, the fallback is to import 4701 rev 10 once in a scratch Grafana, export it, and vendor the export, with the README noting "exported from rev 10 via Grafana X". That is a bigger diff and would need the user's approval.
2. **R2, the `instance` variable lists dead pods.** `label_values` over the time range also returns IPs of replaced pods. The user picks the current one. Pod IPs are unique, so dev and prod never mix once Namespace is selected.
3. **R3, "error rate" is 5xx requests per second** (upstream "Errors", actuator excluded), not a percentage. The card is satisfied as written. A ratio panel would be another edit.
4. **R4, time zone hard-coded in the Wallet month variable** (`Europe/Warsaw`, the `APP_TIME_ZONE` default; the chart does not set `APP_TIME_ZONE` for prod). Only the month list and its default depend on it, because the main query receives an explicit date. If prod ever sets another zone, the default month may differ from the backend's "current month" around midnight on the 1st. Documented in the README and the dashboard description.
5. **R5, money in Grafana.** Postgres `numeric` reaches the browser as a float64 and is displayed with 2 decimals. `numeric(12,2)` values are exact to the cent at that size. The API rule "money is a string" does not apply to Grafana's display.
6. **R6, ConfigMap size.** The JVM JSON is about 100 KB (exact size recorded in V2). It is far below the 1 MiB object limit. `ServerSideApply=true` avoids the 256 KiB annotation limit for future growth.
7. **R7, a backend test reads a file outside `backend/`** (`../infra/monitoring/dashboards/wallet.json`).
   - This deliberately couples the report SQL and the dashboard. It works in CI (full checkout) and locally. The Docker build does not run tests. The test fails with a clear message if run from another working directory.
   - Gradle does not declare the file as a `test` input and `build.gradle` stays unchanged, so after a change to only `wallet.json`, `./gradlew test` can be UP-TO-DATE. The README and the subagent rules require `--rerun`.
8. **R8, the role is cluster-wide and created everywhere** (dev container, dev, Testcontainers). Without a password it cannot log in, which is harmless.
   - If someone pre-created `grafana_reader` with other attributes (e.g. NOLOGIN), V6 does not change them. The README check in M2 would show the login failure.
   - If Flyway ever runs as a non-superuser, `CREATE ROLE` needs CREATEROLE.
9. **R9, Flyway placeholders.** Any `${` in a migration fails Flyway. V6 avoids it, and `GrafanaReaderRoleIT` test 5 guards it. The dashboards' `${month:…}` are not migrations and are unaffected.
10. **R10, rollout order.**
    - Before M2, the Wallet panels fail authentication. This is expected.
    - The monitoring apps track `main`, so nothing in `infra/monitoring/` takes effect before the merge to `main`.
    - V6 reaches `dev` (on `development`) before prod. There it is harmless.
11. **R11, the Postgres datasource has no row limits or statement timeout of its own.** `grafana_reader` is read-only, but a heavy ad-hoc query in Explore could still load prod. Acceptable for a single-user pet project. A role-level `statement_timeout` could be added later.
12. **R12, `${month:sqlstring}` and `allowCustomValue` depend on the Grafana version.**
    - `sqlstring` exists in current Grafana (11+, checked in V3 by version) and is verified on the cluster in M4 (Query inspector).
    - If it were unsupported, Grafana would leave the text unreplaced and the cast would fail loudly. The fallback would be `'$month'`, which is safe only because of `allowCustomValue: false` and the read-only role, and it would need the test contract updated.
    - On a Grafana version without `allowCustomValue`, the field is ignored. `sqlstring` escaping and the read-only role still apply (D9).
13. **R13, effects of excluding actuator traffic (D4 edit 5).**
    - With no user traffic, Rate and Errors show 0 and p95/AVG show no data (0/0). This is intended and documented.
    - The guard in the jq program makes any upstream change to the HTTP queries stop the generation (exit 5) instead of silently skipping the filter.
14. **R14, `optional: true` on the reader Secret (user decision).** A missing or undecryptable Secret is less obvious: Grafana starts with an empty user and password, and the Wallet panels show "password authentication failed". The README's verify step covers it.
15. **R15, licence of the vendored dashboard.** The grafana.com page for 4701 states no licence, which is common for community dashboards. The user approved vendoring it.
16. **R16, the Wallet picker covers only the last 12 months.** Older months remain available through the API and the Month page.

## Out of scope
- From the card: alerting, and per-user views (the schema has no users; the dashboard shows all data).
- A Wallet view of the dev database. Months older than the last 12 in the Wallet picker. Dashboard folders (user decision: General).
- Converting the JVM dashboard to modern panel types, or other edits beyond the six in D4.
- Grafana persistence, TLS, SSO, a Postgres exporter, Loki.
- Changes to the backend API, the Helm chart, the frontend, Gradle dependencies, `build.gradle` (including declaring `wallet.json` as a test input) or the deploy repo.
- Creating, editing or re-sealing any SealedSecret, and running `kubectl`/`kubeseal`/`argocd` against the cluster (M1-M4 are for the user).
- Any git operation. Editing historical plans in `docs/plans/`.
