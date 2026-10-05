# Architecture Decision Records

An ADR records one decision that shapes future work: the context, what was chosen, what was rejected and what follows from it.
Write one when a plan introduces or changes a convention that later tasks must follow (a pattern, a tool, a format, a rule), not for choices that matter to a single card.
Check this index before proposing a convention; a new ADR starts as `proposed` and becomes `accepted` when its change is merged.

| # | Title | Status | Date |
|---|---|---|---|
| [0001](0001-blocking-spring-mvc-on-virtual-threads.md) | Blocking Spring MVC on virtual threads | accepted | 2026-09-18 |
| [0002](0002-integration-tests-with-testcontainers.md) | Integration tests against real Postgres with Testcontainers | accepted | 2026-09-18 |
| [0003](0003-configuration-via-environment-variables.md) | Configuration via environment variables | accepted | 2026-09-18 |
| [0004](0004-gitops-delivery-pipeline.md) | GitOps delivery: GHCR images, Helm, Argo CD and a deploy repository | accepted | 2026-09-19 |
| [0005](0005-sealed-secrets-for-credentials.md) | Sealed Secrets for cluster credentials | accepted | 2026-09-21 |
| [0006](0006-ci-validation-and-pinning.md) | Validate infrastructure in CI and pin every CI dependency | accepted | 2026-09-21 |
| [0007](0007-dependency-locking-and-vulnerability-gate.md) | Gradle dependency locking and a Trivy vulnerability gate | accepted | 2026-09-25 |
| [0008](0008-prefer-configuration-over-custom-code.md) | Prefer configuration and existing library features over custom code | accepted | 2026-09-25 |
| [0009](0009-money-and-aggregates.md) | Money as exact decimals, aggregates computed by the database | accepted | 2026-09-25 |
| [0010](0010-problem-details-for-api-errors.md) | RFC 9457 Problem Details for all API errors | accepted | 2026-09-25 |
| [0011](0011-database-schema-conventions.md) | Database schema conventions | accepted | 2026-09-25 |
| [0012](0012-request-field-semantics.md) | Semantics of empty, null and numeric request fields | accepted | 2026-09-25 |
| [0013](0013-thin-controllers-without-service-layer.md) | Thin controllers over Spring Data repositories, no service layer | accepted | 2026-09-25 |
| [0014](0014-categories-are-archived-not-deleted.md) | Categories are archived, never deleted | accepted | 2026-09-25 |
| [0015](0015-clock-bean-and-app-time-zone.md) | "Today" and "current month" from a Clock bean in APP_TIME_ZONE | accepted | 2026-09-30 |
| [0016](0016-query-parameters-as-validated-model-attributes.md) | Query parameters bound into validated @ModelAttribute records | accepted | 2026-09-30 |
| [0017](0017-report-sql-in-shared-file.md) | Report SQL lives in one shared .sql file run through JdbcClient | accepted | 2026-10-01 |
| [0018](0018-frontend-architecture.md) | Frontend architecture: server-driven pages, native fetch, no router | accepted | 2026-10-01 |
| [0019](0019-monitoring-stack.md) | Monitoring: Micrometer metrics, kube-prometheus-stack, one Argo CD app per concern | accepted | 2026-10-04 |
| [0020](0020-grafana-dashboards-and-read-only-role.md) | Grafana dashboards as code and a read-only database role | proposed | 2026-10-04 |
