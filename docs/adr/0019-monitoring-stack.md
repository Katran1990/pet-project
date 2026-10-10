# 0019. Monitoring: Micrometer metrics, kube-prometheus-stack, one Argo CD app per concern

Date: 2026-10-04
Status: accepted
Amended: 2026-10-07: nginx also forwards exactly `/api/actuator/health` and `/api/actuator/info` (ADR 0022).

## Context
The backend had only `health` and `info` actuator endpoints, and the cluster had no
monitoring. The cluster is a local k3d (k3s) installation on a 14 GB server shared with
the app. Monitoring had to be installed through Argo CD like everything else, and the
Grafana admin credentials had to come from a SealedSecret.

## Decision
- **Backend metrics:**
  - `runtimeOnly io.micrometer:micrometer-registry-prometheus`, version from the Boot BOM;
  - actuator exposure `health,info,prometheus`;
  - `/actuator/prometheus` is unauthenticated and meant for in-cluster scraping only: the
    Ingress routes to the frontend, and its nginx forwards only `/api/`
    (refined by ADR 0022: nginx also forwards exactly `/api/actuator/health` and
    `/api/actuator/info`; `/actuator/prometheus` stays in-cluster);
  - HTTP server timings are published as a percentile histogram, and every meter carries
    the tag `application=${spring.application.name}`;
  - the backend Service has the label `app: backend` and a port named `http`.
- **kube-prometheus-stack 91.9.0** through the Argo CD Application `monitoring`:
  - release and namespace `monitoring`;
  - `ServerSideApply=true`, because the operator CRDs exceed the annotation size limit;
  - values in `infra/monitoring/values.yaml`, taken from this repo as a pure
    `ref: values` source tracking `main`;
  - the version is pinned in `apps.yaml` and in CI (`KPS_VERSION`); CI checks that both
    match and renders the chart.
- **Lean profile:**
  - Alertmanager off, and its Grafana datasource off too;
  - the k3s control-plane scrapers off;
  - retention `15d`, `retentionSize: 18GiB`, 20Gi PVC;
  - every component has requests and a memory limit, and no CPU limit;
  - Grafana has no persistence, an Ingress at `grafana.pet.local`, and admin credentials
    from the SealedSecret `grafana-admin`.
- **Discovery:**
  - Prometheus selects every ServiceMonitor in every namespace
    (`serviceMonitorSelectorNilUsesHelmValues: false`).
  - The pet-project chart ships ServiceMonitor `backend` (port `http`, path
    `/actuator/prometheus`), rendered only when
    `.Capabilities.APIVersions.Has "monitoring.coreos.com/v1/ServiceMonitor"`.
  - CI renders that path with `--api-versions` and asserts it.
- **One Argo CD Application per concern:**
  - `monitoring` (chart plus `$values`, no `path`; CI enforces this);
  - `monitoring-secrets` (a directory source for `infra/monitoring/sealed-secrets`);
  - later `monitoring-dashboards` (ADR 0020).
- The backend and frontend Deployments keep `revisionHistoryLimit: 3`, because rollbacks
  go through git and Argo CD.

## Alternatives considered
- A separate management port for actuator: out of scope.
- The legacy `micrometer-registry-prometheus-simpleclient`: not used.
- A `release: monitoring` label on the ServiceMonitor: would tie the app chart to the
  monitoring release name.
- Always rendering the ServiceMonitor, or behind a values toggle: pet-project syncs would
  fail whenever the CRD is missing (for example at bootstrap), and automated sync does not
  retry the same commit.
- Applying the SealedSecret through `path` on the `ref: values` source of `monitoring`:
  replaced by the separate `monitoring-secrets` Application.
- A third source with the same repoURL: risks the "same repoURL" rejection seen earlier
  (ADR 0004).
- Tracking `development` instead of `main`: untested changes would hit the only
  monitoring instance, which also watches prod.
- CPU limits: not set, following the chart convention and avoiding throttling.
- Grafana persistence: not enabled.
- A CI check of the sealed file's structure: not added.

## Consequences
- Nothing in `infra/monitoring/` takes effect before it reaches `main`.
- A missing CRD silently means no ServiceMonitor; an app whose manifests were cached
  before the CRD existed needs an Argo CD hard refresh.
- Every future ServiceMonitor in any namespace is scraped.
- `http_server_requests_*` includes actuator traffic (scrapes and probes).
- Deleting the `monitoring` Application removes the CRDs and with them all
  ServiceMonitors.
- Grafana users, preferences and UI-edited dashboards are lost on every restart;
  dashboards and datasources must be provisioned from git.
- Upgrading the chart means changing every place that names the version; no alerting
  exists yet.
- A new monitoring concern (secrets, dashboards, rules) gets its own Application rather
  than extending `monitoring`.
