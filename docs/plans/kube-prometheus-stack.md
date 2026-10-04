# Plan: Deploy kube-prometheus-stack via Argo CD

Card: "Deploy kube-prometheus-stack via Argo CD" (https://trello.com/c/q7CfJcOo/21-deploy-kube-prometheus-stack-via-argo-cd)
Branch: `feature/kube-prometheus-stack` (based on `origin/development`). All paths below are relative to the repo root.

## Goal
Add a third Argo CD Application, `monitoring`, that installs `prometheus-community/kube-prometheus-stack` **91.9.0** into namespace `monitoring`. Its values live in `infra/monitoring/values.yaml` and are sized for a 14 GB server shared with the app. Grafana gets an Ingress at `grafana.pet.local`, and its admin credentials come from a SealedSecret that the user seals. The pet-project chart ships a `ServiceMonitor`, so this Prometheus scrapes the backend's `/actuator/prometheus` in `dev` and `prod`. The backend and frontend Deployments keep only 3 old ReplicaSets. CI also renders the monitoring chart with our values.

## Acceptance criteria
- [ ] AC1: `infra/argocd/apps.yaml` has an Application `monitoring`: chart version pinned, automated sync, `CreateNamespace`.
- [ ] AC2: `infra/monitoring/values.yaml` has:
  - Prometheus retention `15d`, storage `20Gi`, requests and limits set;
  - Alertmanager disabled;
  - Grafana enabled, with an Ingress at `grafana.pet.local`.
- [ ] AC3: The Grafana admin password comes from a SealedSecret, not from values.
- [ ] AC4: A ServiceMonitor in the pet-project chart scrapes backend `/actuator/prometheus` in dev and prod.
- [ ] AC5: `helm template` renders, and the actionlint and helm lint checks in CI are green.
- [ ] AC6: README explains how to reach Grafana and how to check the targets in Prometheus.
- [ ] AC7: pet-project chart: the backend and frontend Deployments set `revisionHistoryLimit: 3`.
- [ ] AC8: Latency histograms: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` in application.properties.
- [ ] AC9: Common tag: `management.metrics.tags.application=${spring.application.name}` in application.properties.

Out of scope according to the card: alerting rules, Loki/logs, dashboards (next card).

### Decisions already made by the user
1. **Grafana SealedSecret.** The user runs `kubeseal` (strict scope, namespace `monitoring`) and supplies the ciphertext file. This plan defines the Secret name, keys, file path, the README command, and how Argo CD applies the file. The implementer must **not** write any ciphertext or placeholder.
2. **CI.** Add one step to the existing `helm` job that runs `helm template` on kube-prometheus-stack 91.9.0 with `infra/monitoring/values.yaml`. Keep the pinning style: Helm v3.22.0 and SHA-pinned third-party actions. actionlint must stay green.
3. **AC8 and AC9 are already on `origin/development`** (PR #32, `backend/src/main/resources/application.properties` lines 33-34, confirmed while planning). They are only verified here. No backend code changes.
4. `revisionHistoryLimit: 3` (AC7) is in scope.

### How the criteria are interpreted
- **AC1:**
  - "Pinned" means an exact `targetRevision: 91.9.0`, not a range.
  - "Automated sync" means `prune: true` and `selfHeal: true`, the same as the existing two apps.
- **AC2, "requests/limits set":**
  - Every component gets CPU and memory requests plus a memory limit: Prometheus, Prometheus Operator, Grafana and its sidecars, kube-state-metrics and node-exporter.
  - There are no CPU limits. This matches the chart's existing convention in `infra/helm/pet-project/values.yaml` (see Risk 8).
- **AC4:**
  - The repo side is proven by rendering the chart in namespaces `dev` and `prod`.
  - That the targets are actually UP can only be checked on the cluster (manual step M4).
- **AC5:** the existing helm lint and helm template steps for pet-project, the new kube-prometheus-stack render step, and the actionlint job must all pass.

## Changes

### What was checked (context)
- **`infra/argocd/apps.yaml`:**
  - Two multi-source apps, `pet-project-dev` and `pet-project-prod`. Each has the chart from this repo and `ref: values` from `Katran1990/pet-project-deploy`.
  - The header warns: "our Argo CD rejected a multi-source app that referenced two revisions of the same repoURL".
  - The header also says the sources are tied together by the `ref` name `values` / `$values`.
- **`infra/helm/pet-project/templates/backend.yaml`:**
  - Service `backend` already has `labels: { app: backend }` and a port named `http` (8080 → 8080). The comments say both exist for a ServiceMonitor.
  - Deployment `backend` has no `revisionHistoryLimit`. The same is true for `frontend.yaml`.
- **Backend actuator:**
  - `application.properties` has no `management.server.port`, so `/actuator/prometheus` is served on the main port 8080, the same port as the Service port `http`.
  - Exposure is `health,info,prometheus`.
- **`infra/helm/pet-project/Chart.yaml`:** `version: 0.3.0  # chart version, bump when templates change`.
- **`.github/workflows/ci.yml`:**
  - Job `helm` uses `azure/setup-helm@9bc31f4e… # v5.0.1` with Helm `v3.22.0`.
  - It lints with `--namespace dev|prod`, then renders pet-project for dev and prod into `/dev/null`.
  - Job `actionlint` uses `rhysd/actionlint:1.7.12@sha256:…`.
- **The cluster is k3d (k3s):**
  - The README says "local k3d cluster", and `ingress.yaml` says "k3d ships with Traefik".
  - The existing Ingress has no `ingressClassName`, so it relies on Traefik being the default class.
- **Upstream chart 91.9.0 (unpacked copy checked):**
  - `Chart.yaml`: appVersion v0.94.1, `kubeVersion: '>=1.25.0-0'`.
  - Dependencies: `crds` (condition `crds.enabled`), kube-state-metrics 8.6.0, prometheus-node-exporter 4.59.0, grafana 13.2.7 (from `oci://ghcr.io/grafana-community/helm-charts`) and windows-exporter. The packaged chart vendors all subcharts under `charts/`.
  - **Value keys used by this plan (all verified in `values.yaml` / subchart values):**

    | Key | Default |
    |---|---|
    | `alertmanager.enabled` | true |
    | `prometheus.prometheusSpec.retention` | 10d |
    | `.retentionSize` | `""`, format like `50GiB` |
    | `.storageSpec.volumeClaimTemplate` | not set |
    | `.resources` | `{}` |
    | `.serviceMonitorSelectorNilUsesHelmValues` | true |
    | `.serviceMonitorSelector` | `{}` |
    | `.serviceMonitorNamespaceSelector` | `{}` |
    | `prometheusOperator.resources` | `{}` |
    | `grafana.enabled` | true |
    | `grafana.admin.existingSecret` / `.userKey` / `.passwordKey` | `""` / `admin-user` / `admin-password` |
    | `grafana.ingress.enabled` / `.hosts` / `.path` | false / `[]` / `/` |
    | `grafana.sidecar.resources` | subchart `sidecar.resources: {}` |
    | `grafana.sidecar.datasources.alertmanager.enabled` | true |
    | `kube-state-metrics.resources` | `{}` |
    | `prometheus-node-exporter.resources` | `{}` |
    | `kubeControllerManager.enabled` | true |
    | `kubeScheduler.enabled` | true |
    | `kubeProxy.enabled` | true |
    | `kubeEtcd.enabled` | true |
  - **Selector rendering** (`templates/prometheus/prometheus.yaml` lines 203-218):
    - If `serviceMonitorSelector` is empty and `NilUsesHelmValues` is true, the Prometheus CR gets `serviceMonitorSelector.matchLabels.release: <release name>`. If false, it gets `serviceMonitorSelector: {}`, which selects all.
    - `serviceMonitorNamespaceSelector` is always rendered as `{}` (all namespaces) unless it is set.
    - The Prometheus ClusterRole grants cluster-wide get/list/watch on services, endpoints, endpointslices and pods. So ServiceMonitors in `dev` and `prod` work with no extra RBAC.
  - **Alertmanager references:**
    - The Prometheus `alerting:` block is rendered only if `alertmanager.enabled` is true or `alertingEndpoints` is set.
    - The Grafana **Alertmanager datasource** is rendered whenever `grafana.sidecar.datasources.alertmanager.enabled` is true (`templates/grafana/configmaps-datasources.yaml` line 79), **regardless of `alertmanager.enabled`**. It must be turned off explicitly, otherwise Grafana gets a datasource that points at a Service that does not exist.
  - Default rules for etcd, scheduler, controller-manager and kube-proxy are gated on the matching `*.enabled` flag, so disabling a component also removes its rules.
  - **Grafana admin credentials:**
    - With `admin.existingSecret` set, the subchart creates no admin Secret of its own (`charts/grafana/templates/secret.yaml` line 1).
    - It reads **both** `GF_SECURITY_ADMIN_USER` (key `userKey`) and `GF_SECURITY_ADMIN_PASSWORD` (key `passwordKey`) from that Secret (`_pod.tpl`). So the SealedSecret must contain both keys.
  - **Chart README, "Argo CD" section:** "The operator CRDs are larger than the 262144 byte limit on the `kubectl.kubernetes.io/last-applied-configuration` annotation … Sync the application with `ServerSideApply=true`."
  - **Names with release name `monitoring`:**
    - `kube-prometheus-stack.fullname` is `printf "%s-%s" .Release.Name "kube-prometheus-stack" | trunc 26` = `monitoring-kube-prometheus`.
    - Prometheus Service: `monitoring-kube-prometheus-prometheus` (port 9090).
    - Grafana Deployment and Service: `monitoring-grafana`.
    - The implementer confirms these from the render (V3) before writing them into the README.

### Design decisions

**D1. One Application, two sources: the Helm repo for the chart and this repo for the values and the SealedSecret.**
- Source 1: `repoURL: https://prometheus-community.github.io/helm-charts`, `chart: kube-prometheus-stack`, `targetRevision: 91.9.0`, `helm.valueFiles: [$values/infra/monitoring/values.yaml]`.
- Source 2: `repoURL: https://github.com/Katran1990/pet-project.git`, `ref: values`, **and** `path: infra/monitoring/sealed-secrets`.
  - Argo CD's multi-source docs say that when a `$values` source also sets `path`, Argo CD generates manifests from that path too. One source therefore provides the values file and applies the SealedSecret.
  - `$values` still resolves to the **repo root**, so the value file is `$values/infra/monitoring/values.yaml`.
- Why this shape:
  - It keeps the existing rule from the apps.yaml header: each repoURL appears **once** per Application. The earlier rejection was about two revisions of the same repoURL.
  - It reuses the `ref: values` / `$values` name convention.
  - It needs no fourth Application (the card says "a third Application"), and the user can commit raw `kubeseal` output without wrapping it into values (`extraManifests`).
- `path` must be the `sealed-secrets` **subdirectory**. If it pointed at `infra/monitoring`, Argo CD would try to apply `values.yaml` as a Kubernetes object and the sync would fail. A directory source only reads `*.yaml|*.yml|*.json`, and it is not recursive by default.
- Fallback if the cluster's Argo CD version does not deploy from a `ref` source that also has `path`: see Risk 2.

**D2. The repo source tracks `targetRevision: main`.**
- Monitoring is one cluster-wide instance and mostly watches prod, so it changes when prod changes (the same branch as `pet-project-prod`).
- Consequence: nothing in `infra/monitoring/` takes effect until it reaches `main`. Before that, an applied `monitoring` Application shows a ComparisonError ("app path does not exist"). That is harmless.
- Open question Q2 offers `development` instead.

**D3. Grafana admin Secret contract** (the user-provided file):
- Path: `infra/monitoring/sealed-secrets/grafana-admin.yaml`. This is the only file in that directory.
- Kind `SealedSecret`, `metadata.name: grafana-admin`, `metadata.namespace: monitoring`, strict scope (the kubeseal default, so no scope annotations).
- `spec.encryptedData` keys are exactly `admin-user` and `admin-password`.
- Values file: `grafana.admin.existingSecret: grafana-admin`, `userKey: admin-user`, `passwordKey: admin-password`.
- **What happens until the file exists:**
  - **PR / CI:** nothing needs it. The kube-prometheus-stack render only references the Secret by name.
  - **The change reaches `main` without the file:** git does not track empty directories, so `infra/monitoring/sealed-secrets` does not exist. Argo CD reports "app path does not exist" for the `monitoring` Application, generates no manifests, and installs or changes nothing. That is a loud, fail-closed state. The pet-project apps are not affected (D4).
    - The implementer therefore must **not** add a `.gitkeep`. With one, the stack would install half-working, with Grafana stuck on a missing Secret.
  - **The file exists but cannot be decrypted** (wrong name or namespace, or a different controller key): the SealedSecret reports an error, Secret `grafana-admin` is missing, and the Grafana pod sits in `CreateContainerConfigError`. Prometheus, the operator and the exporters run normally.
  - **Merge blocker:** `infra/monitoring/sealed-secrets/grafana-admin.yaml` must exist and pass V5 before this PR is merged.

**D4. ServiceMonitor guard: `.Capabilities.APIVersions.Has "monitoring.coreos.com/v1/ServiceMonitor"`, with no values toggle.**
- Argo CD's repo-server runs `helm template` with `--api-versions` for every API of the destination cluster. So the ServiceMonitor renders exactly when the CRD exists.
- **Rejected alternative: always render it, or render it behind a values toggle that defaults to on.**
  - Then pet-project-dev/prod fail to sync whenever the CRD is missing, for example at bootstrap, because all three apps start in parallel.
  - Argo CD's automated sync does not retry a failed sync of the same commit unless `syncPolicy.retry` is set. That would block image rollouts until the next commit.
- **Downside of the guard:** a missing CRD silently means no ServiceMonitor. Mitigations:
  - CI renders the guarded path explicitly with `--api-versions monitoring.coreos.com/v1/ServiceMonitor` and fails if no ServiceMonitor appears.
  - The README documents the Argo CD hard refresh in case an app's cached manifests predate the CRD (Risk 4).

**D5. Discovery: `prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues: false`.**
- Prometheus then selects every ServiceMonitor, and the namespace selector `{}` covers all namespaces. The upstream README recommends this for custom ServiceMonitors.
- Rejected alternative: a `release: monitoring` label on the pet-project ServiceMonitor. It would tie the app chart to the monitoring release name.
- The ServiceMonitor sets no `namespaceSelector`, so each one only matches the `backend` Service in its own namespace (`dev` or `prod`).

**D6. Lean profile.**
- Off: Alertmanager (AC2) and its Grafana datasource.
- Off: the four control-plane scrapers that k3s runs in-process on localhost: kube-controller-manager, kube-scheduler, kube-proxy and etcd. With k3s, the chart's Services for them select no pods. They would only add empty targets, extra objects in `kube-system`, and permanently true `*Down` rules.
- Kept at upstream defaults: CRDs, the operator with its admission webhooks, kube-state-metrics, node-exporter, default rules, default dashboards, and kubelet/apiserver/coredns monitoring.
- No Grafana persistence (the chart default). Grafana state lives in an `emptyDir`, and dashboards come from ConfigMaps.
- `retentionSize: 18GiB` sits under the 20Gi PVC. k3d's local-path volumes do not enforce their size, so this is what actually caps disk use (Risk 7).

**D7. `revisionHistoryLimit: 3` is a literal in the templates, not a value.** It is a cluster-wide policy ("rollbacks go through git and Argo CD"), not something that differs per environment. This is the smallest change.

### 1. `infra/argocd/apps.yaml` (modify)

**Header comment.** Rewrite the "Each app has two sources" paragraph so it covers both shapes, keeping the existing facts:
- **pet-project-dev/prod:** the chart from this repo plus env values from the deploy repo (unchanged text).
- **monitoring:** the chart from the prometheus-community Helm repo, plus this repo used as **one** source. `ref: values` provides `infra/monitoring/values.yaml`, and `path: infra/monitoring/sealed-secrets` applies the Grafana admin SealedSecret. It follows `main`.
- The "different repositories" rule and the `ref: values` / `$values` naming rule apply to all three apps.
- Extend the Sealed Secrets prerequisite line so it also covers the `monitoring` app.

**Append the third Application:**
```yaml
---
# Cluster monitoring: kube-prometheus-stack (Prometheus Operator, Prometheus, Grafana,
# kube-state-metrics, node-exporter) in namespace "monitoring". One instance for the whole
# cluster; it scrapes the backend in dev and prod through the ServiceMonitor in the
# pet-project chart. See README "Monitoring (kube-prometheus-stack)".
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: monitoring
  namespace: argocd
spec:
  project: default
  destination:
    server: https://kubernetes.default.svc
    namespace: monitoring
  sources:
    - repoURL: https://prometheus-community.github.io/helm-charts
      chart: kube-prometheus-stack
      targetRevision: 91.9.0     # pinned; CI renders the same version (.github/workflows/ci.yml, job "Helm chart")
      helm:
        releaseName: monitoring  # resource names derive from it; CI renders with the same name
        valueFiles:
          - $values/infra/monitoring/values.yaml
    # This repo, once: `ref: values` provides the values file above, and `path` makes Argo CD
    # also apply the Grafana admin SealedSecret (kubeseal output) from that directory.
    # Only that subdirectory: values.yaml is not a Kubernetes object.
    - repoURL: https://github.com/Katran1990/pet-project.git
      targetRevision: main
      ref: values
      path: infra/monitoring/sealed-secrets
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true   # the operator CRDs exceed the 256 KiB last-applied-configuration annotation limit
```
- `releaseName: monitoring` is Argo CD's default (the app name) anyway. It is set explicitly so that renaming the Application cannot silently rename every resource, and so CI parity is visible.
- The CRDs and the custom resources that use them are in the same sync. Argo CD applies CRDs first and skips the dry run for resources whose CRD is part of the sync, so no sync waves are needed.

### 2. `infra/monitoring/values.yaml` (new)
Content (comments may be polished; keys and values may not):
```yaml
# Values for the "monitoring" Argo CD Application (infra/argocd/apps.yaml):
# prometheus-community/kube-prometheus-stack 91.9.0, release "monitoring", namespace "monitoring".
# Rendered in CI by .github/workflows/ci.yml (job "Helm chart").
# The server has 14 GB RAM shared with the app: every component gets requests and a memory
# limit (no CPU limits, like infra/helm/pet-project/values.yaml), and unused parts are off.
# Never put a password here. Grafana's admin credentials come from the SealedSecret
# infra/monitoring/sealed-secrets/grafana-admin.yaml (README "Monitoring (kube-prometheus-stack)").

# Alerting is a separate card. Without Alertmanager, Prometheus gets no alerting section.
alertmanager:
  enabled: false

# k3s (k3d) runs these inside the k3s process, bound to localhost: the chart's Services would
# select no pods, leaving empty targets and permanently true "...Down" rules.
kubeControllerManager:
  enabled: false
kubeScheduler:
  enabled: false
kubeProxy:
  enabled: false
kubeEtcd:
  enabled: false

prometheus:
  prometheusSpec:
    retention: 15d
    # Safety net under the 20Gi volume: k3d's local-path volumes do not enforce their size,
    # so this is what caps disk usage (oldest blocks are deleted first).
    retentionSize: 18GiB
    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 20Gi
    resources:
      requests: { cpu: 200m, memory: 1Gi }
      limits:   { memory: 2Gi }
    # Select every ServiceMonitor (serviceMonitorNamespaceSelector stays {} = all namespaces),
    # not only those labelled release=monitoring: the backend's ServiceMonitor lives in the
    # pet-project chart (namespaces dev and prod) and must not depend on this release name.
    serviceMonitorSelectorNilUsesHelmValues: false

prometheusOperator:
  resources:
    requests: { cpu: 50m, memory: 96Mi }
    limits:   { memory: 192Mi }

grafana:
  enabled: true
  admin:
    existingSecret: grafana-admin   # SealedSecret, see the header; keys below
    userKey: admin-user
    passwordKey: admin-password
  ingress:
    enabled: true
    hosts:
      - grafana.pet.local           # add to /etc/hosts like dev.pet.local (README)
    path: /
  resources:
    requests: { cpu: 50m, memory: 192Mi }
    limits:   { memory: 384Mi }
  sidecar:
    # Applies to each sidecar container (dashboards and datasources)
    resources:
      requests: { cpu: 10m, memory: 96Mi }
      limits:   { memory: 192Mi }
    datasources:
      alertmanager:
        enabled: false              # rendered even with alertmanager.enabled=false otherwise

kube-state-metrics:
  resources:
    requests: { cpu: 10m, memory: 64Mi }
    limits:   { memory: 128Mi }

prometheus-node-exporter:
  resources:
    requests: { cpu: 10m, memory: 32Mi }
    limits:   { memory: 64Mi }
```
- **Totals:** requests ≈ 1.6 GiB, memory limits ≈ 3.1 GiB, plus the operator-defaulted config-reloader (about 50Mi) and short-lived admission webhook Jobs. That fits next to the app, whose current memory limits are about 3.3 GiB across dev and prod.
- No `ingressClassName`, so this Ingress uses the default class exactly like `infra/helm/pet-project/templates/ingress.yaml` (Traefik on k3d).
- `grafana.admin.userKey/passwordKey` equal the chart defaults. They are spelled out because they are the contract with the sealed file (D3).

### 3. `infra/monitoring/sealed-secrets/grafana-admin.yaml` (user-provided; NOT created by the implementer)
- The user produces this file with the README command (section 7). It is the unmodified `kubeseal --format yaml` output.
- The implementer creates neither this file nor a `.gitkeep` in its directory (D3), and reports V5 as PENDING-USER.

### 4. `infra/helm/pet-project/templates/backend.yaml` (modify)
- **Deployment:** add `revisionHistoryLimit: 3` right after `replicas:`, with a short comment: "rollbacks go through git and Argo CD; old ReplicaSets only clutter the Argo CD tree".
- **Append after the Service:**
```yaml
{{- if .Capabilities.APIVersions.Has "monitoring.coreos.com/v1/ServiceMonitor" }}
---
# Scraped by the Prometheus of the "monitoring" Argo CD Application (kube-prometheus-stack),
# which selects every ServiceMonitor in every namespace (infra/monitoring/values.yaml).
# Rendered only when the ServiceMonitor CRD exists, so dev/prod still sync before monitoring
# is installed (README "Monitoring (kube-prometheus-stack)").
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: backend
  labels: { app: backend }
spec:
  selector:
    matchLabels: { app: backend }   # the backend Service's label
  endpoints:
    - port: http                    # the backend Service's named port (8080)
      path: /actuator/prometheus
{{- end }}
```
- No `namespaceSelector`, so the ServiceMonitor matches only its own namespace. No `interval`, so the Prometheus default (30s) applies.
- The resulting target labels include `namespace="dev"|"prod"`, `job="backend"`, `pod` and `service`. Micrometer's `application="pet"` tag does not collide with any of them.
- Update the two existing Service comments ("selected by a Prometheus ServiceMonitor") to say "(the ServiceMonitor below)".
- The implementer checks in V2 that the rendered output has no doubled `---` and no stray empty document.

### 5. `infra/helm/pet-project/templates/frontend.yaml` (modify)
Add `revisionHistoryLimit: 3` right after `replicas:` in the Deployment, with the same comment. Nothing else changes.

### 6. `infra/helm/pet-project/Chart.yaml` (modify)
`version: 0.3.0` → `0.4.0`, because the templates change. `appVersion` stays the same.

### 7. `README.md` (modify)
- **"CI and Docker images":** extend the `ci.yml` bullet:
  - The pet-project render also runs with `--api-versions monitoring.coreos.com/v1/ServiceMonitor` and checks that the ServiceMonitor is rendered.
  - CI renders kube-prometheus-stack 91.9.0 with `infra/monitoring/values.yaml`.
  - Add the local Docker command from V3.
- **"Deploy (Helm + Argo CD)":**
  - `apps.yaml` now holds **three** Applications. Add `monitoring` → namespace `monitoring`, chart kube-prometheus-stack 91.9.0 from the prometheus-community Helm repo, values and the Grafana SealedSecret from this repo's `main`.
  - Replace "the chart does not ship one" in the Metrics bullet with: the chart ships ServiceMonitor `backend` (port `http`, path `/actuator/prometheus`), rendered only when the ServiceMonitor CRD exists.
  - Mention `revisionHistoryLimit: 3` in one sentence.
  - Extend the `/etc/hosts` bullet with `grafana.pet.local`.
- **New section `## Monitoring (kube-prometheus-stack)`**, after "Postgres credentials (Sealed Secrets)". It uses `###` subsections:
  1. **What is installed and what is not.**
     - Installed: operator, Prometheus (15d, at most 18GiB, 20Gi PVC), Grafana, kube-state-metrics, node-exporter, default rules and dashboards.
     - Not installed: Alertmanager (alerting is a later card); the control-plane scrapers (k3s, D6).
     - All ServiceMonitors in all namespaces are selected (D5).
     - A sizing table copied from values.yaml.
     - `ServerSideApply=true` is needed because of the CRD size.
     - Argo CD (not Helm) applies and updates the CRDs on every sync.
  2. **First install.**
     - The Sealed Secrets controller is a prerequisite (link the existing section).
     - Seal the Grafana admin credentials (subsection 3).
     - After the change reaches `main`, run `kubectl apply -f infra/argocd/apps.yaml`. This creates `monitoring` and leaves the other two apps unchanged.
     - Until `main` has `infra/monitoring/`, the app shows "app path does not exist". This is expected.
     - The pet-project apps pick up the ServiceMonitor once the CRD exists (subsection 6, troubleshooting).
  3. **Grafana admin credentials (Sealed Secret).** The same pattern as the Postgres section (temp file plus `mv`, `pipefail`, the password only in a variable and on stdin):
     ```bash
     OUT="infra/monitoring/sealed-secrets/grafana-admin.yaml"
     mkdir -p "$(dirname "$OUT")"
     PW="$(openssl rand -hex 24)"
     ( set -o pipefail
       printf %s "$PW" \
       | kubectl create secret generic grafana-admin --namespace monitoring \
           --from-literal=admin-user=admin --from-file=admin-password=/dev/stdin \
           --dry-run=client -o yaml \
       | kubeseal --format yaml \
       > "$OUT.tmp"
     ) && mv "$OUT.tmp" "$OUT" || rm -f "$OUT.tmp"
     ```
     - Strict scope (the default): name `grafana-admin`, namespace `monitoring`, keys `admin-user` and `admin-password`. The namespace does not need to exist for sealing.
     - The offline `--cert` variant from the Postgres section also works.
     - Never commit the output without `kubeseal`.
     - **Merge blocker:** the file must exist (D3, "What happens until the file exists").
     - **Rotation:**
       1. Re-seal and merge until the change reaches `main`.
       2. Wait until `kubectl -n monitoring get sealedsecret grafana-admin` shows `Synced=True`.
       3. Run `kubectl -n monitoring rollout restart deployment/monitoring-grafana`.
       4. Why the restart is enough: Grafana has no persistent volume, so each new pod creates its database from scratch and applies the admin user and password from the Secret. The flip side is that users, preferences and dashboards edited in the UI are lost on every restart.
       5. The chart does not restart Grafana automatically when the Secret changes, so the restart in step 3 is always required.
  4. **Reach Grafana.**
     - Add `127.0.0.1 grafana.pet.local` to `/etc/hosts`, then open `http://grafana.pet.local` (same Traefik entry point as `dev.pet.local`).
     - User `admin`. The password is in your password manager, or read it with `kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d; echo`.
     - The Prometheus datasource is provisioned automatically.
  5. **Check the backend targets in Prometheus.**
     - `kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090`, then open `http://localhost:9090/targets?search=backend`.
     - Expect the pools `serviceMonitor/dev/backend/0` and `serviceMonitor/prod/backend/0`, one target per backend pod, State `UP`.
     - Query `up{job="backend"}`: one series per pod, value `1`, label `namespace` = `dev` or `prod`.
     - `sum by (namespace) (rate(http_server_requests_seconds_count{application="pet"}[5m]))` shows traffic. The same queries work in Grafana → Explore.
  6. **Troubleshooting.**
     - **No pool for a namespace:** run `kubectl -n <env> get servicemonitor backend`. If it is missing, the app's manifests were rendered before the CRD existed. Hard-refresh the app (`argocd app get pet-project-<env> --hard-refresh`, or UI → Refresh → Hard Refresh).
     - **Pool with 0 targets:** check the Service label `app: backend` and the port name `http`.
     - **Target DOWN:** `kubectl -n <env> exec deploy/backend -- wget -qO- localhost:8080/actuator/prometheus | head`. Only if the image has no `wget`, use a `kubectl run` curl pod in the same namespace instead.
  7. **Upgrading the chart.**
     - Bump `targetRevision` in `apps.yaml` **and** `KPS_VERSION` in `ci.yml` together. CI fails if they differ.
     - Read upstream `UPGRADE.md` for major versions.

### 8. `.github/workflows/ci.yml` (modify, job `helm` only)

**8a. Modify the existing step "Render chart with dev and prod values".**
- Pass `--api-versions monitoring.coreos.com/v1/ServiceMonitor`, so the render matches a cluster with monitoring installed and the guarded ServiceMonitor is actually rendered and checked.
- Write to a file in `RUNNER_TEMP` instead of `/dev/null`, and assert that the ServiceMonitor is present.
- Keep the existing comment about the sealed-file `fail()` checks, and add one line on why `--api-versions` is passed.
```yaml
        run: |
          for env in dev prod; do
            echo "helm template with envs/${env}/values.yaml"
            out="${RUNNER_TEMP}/pet-project-${env}.yaml"
            helm template "pet-project-${env}" infra/helm/pet-project \
              --namespace "${env}" \
              --api-versions monitoring.coreos.com/v1/ServiceMonitor \
              -f "deploy-values/envs/${env}/values.yaml" > "${out}"
            grep -qx 'kind: ServiceMonitor' "${out}" \
              || { echo "::error::no ServiceMonitor rendered for ${env}"; exit 1; }
          done
```
The output is never printed. It contains only SealedSecret ciphertext and `secretKeyRef` references.

**8b. New step after it** (decision 2):
```yaml
      # Renders kube-prometheus-stack as the "monitoring" Argo CD Application does: same chart
      # version, release name and namespace as infra/argocd/apps.yaml, plus
      # infra/monitoring/values.yaml. --repo fetches the packaged chart (subcharts included),
      # so no `helm repo add` is needed. The grep fails the step if apps.yaml pins another
      # version. Output goes to /dev/null: it is several thousand lines.
      - name: Render kube-prometheus-stack with monitoring values
        env:
          KPS_VERSION: 91.9.0   # keep in sync with the "monitoring" Application in infra/argocd/apps.yaml
        run: |
          grep -qF "targetRevision: ${KPS_VERSION}" infra/argocd/apps.yaml \
            || { echo "::error::infra/argocd/apps.yaml does not pin kube-prometheus-stack ${KPS_VERSION}"; exit 1; }
          helm template monitoring kube-prometheus-stack \
            --repo https://prometheus-community.github.io/helm-charts \
            --version "${KPS_VERSION}" \
            --namespace monitoring \
            -f infra/monitoring/values.yaml > /dev/null
```
- No new action, so the pinning style is unchanged: Helm v3.22.0 from the existing `azure/setup-helm` step.
- The job stays within `timeout-minutes: 5`. The chart archive is a few MB.
- The workflow header comment ("Five independent jobs …") stays correct, because no job is added.

### 9. No other files
- No backend or frontend code, no Flyway migration, no Gradle or npm dependency.
- `application.properties` is only verified (AC8 and AC9, decision 3).
- `infra/helm/pet-project/values.yaml`, `ingress.yaml` and `postgres.yaml` do not change. Neither does the deploy repo.

### New dependency

| Artifact | Version | Why it is needed | Cost of writing it by hand |
|---|---|---|---|
| Helm chart `prometheus-community/kube-prometheus-stack` (repo `https://prometheus-community.github.io/helm-charts`) | 91.9.0 (appVersion v0.94.1). Bundled subcharts: kube-state-metrics 8.6.0, prometheus-node-exporter 4.59.0, grafana 13.2.7, `crds`. Container images are pulled at the chart's default tags. | Named by the card. It provides the Prometheus Operator and CRDs (`ServiceMonitor`, `Prometheus`), a configured Prometheus with RBAC and storage, Grafana with a provisioned Prometheus datasource and sidecars, kube-state-metrics, node-exporter, and the default rules and dashboards. | Thousands of lines of manifests: operator, CRDs, RBAC, StatefulSet, scrape configs for kubelet/cAdvisor/apiserver, Grafana provisioning, exporters. Plus maintaining their upgrades. Not reasonable for this project. |

No other new dependency: no new GitHub Action and no new CLI in CI.

### Manual steps for the user (cannot be done from the dev container)
- **M1:** on this branch, before merging, seal the Grafana admin credentials with the README command (section 7, subsection 3) and commit `infra/monitoring/sealed-secrets/grafana-admin.yaml`. Then run V5.
- **M2:** after the change reaches `main`, run `kubectl apply -f infra/argocd/apps.yaml`. Wait until `monitoring` is Synced/Healthy and `kubectl -n monitoring get sealedsecret grafana-admin` shows `Synced=True`.
- **M3:** add `grafana.pet.local` to `/etc/hosts` and log in to Grafana (AC6).
- **M4 (cluster proof of AC4):** follow README "Check the backend targets in Prometheus". Both the `dev` and `prod` pools must be UP, and `up{job="backend"}` must return one series per backend pod. If a pool is missing, hard-refresh that pet-project app (Risk 4).
- **M5:** watch `kubectl top pods -n monitoring` for a day and adjust the sizing if needed (Risk 7).

## Tests
This is infrastructure and CI work. The proof is verification commands. Backend and frontend tests only have to stay green. No test code changes.

Rules for every subagent:
- Do not run `./gradlew bootRun`, and do not connect to the dev Postgres (`postgres:5432`).
- Only one Gradle run at a time in `backend/`.
- No git commands.
- Everything temporary goes to the session scratchpad `$SCRATCH`, never into `/workspace`.

Tools:
- `helm` and `yq` are not installed in the dev container.
- Use `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 …` (as in the README).
- For `yq`, download it into `$SCRATCH/bin` as in `docs/plans/postgres-sealed-secrets.md` T1, or use `docker run --rm -i mikefarah/yq:4`.
- If a download or the network fails, report the check as **UNVERIFIED**, never as passed.

### Coverage map

| AC | Proof |
|---|---|
| AC1 | V1 (yq on apps.yaml) |
| AC2 | V3 (assertions on the rendered kube-prometheus-stack) |
| AC3 | V3 (Grafana reads the `grafana-admin` Secret; no chart-generated admin Secret; no password in values); V5 after M1 |
| AC4 | V2 (ServiceMonitor rendered for dev and prod with the right selector, port and path; absent without the CRD); M4 on the cluster |
| AC5 | V2, V3, V4 locally; CI job "Helm chart" and "Workflow lint (actionlint)" green on the PR |
| AC6 | V6 (README content) and M3/M4 |
| AC7 | V2 (`revisionHistoryLimit` on both Deployments) |
| AC8, AC9 | V7 (grep application.properties) |
| CLAUDE.md "tests pass" | V8 |

### Verification commands (run from `/workspace`)

**V1. Application `monitoring`:**
```bash
yq 'select(.metadata.name == "monitoring") | [.spec.destination.namespace, .spec.sources[0].chart, .spec.sources[0].targetRevision, .spec.sources[0].helm.releaseName, .spec.sources[0].helm.valueFiles[0], .spec.sources[1].ref, .spec.sources[1].path, .spec.sources[1].targetRevision, .spec.syncPolicy.automated.prune, .spec.syncPolicy.automated.selfHeal] | join(" ")' infra/argocd/apps.yaml
# expect: monitoring kube-prometheus-stack 91.9.0 monitoring $values/infra/monitoring/values.yaml values infra/monitoring/sealed-secrets main true true
yq 'select(.metadata.name == "monitoring") | .spec.syncPolicy.syncOptions' infra/argocd/apps.yaml   # contains CreateNamespace=true and ServerSideApply=true
yq 'select(.kind == "Application") | .metadata.name' infra/argocd/apps.yaml                       # pet-project-dev, pet-project-prod, monitoring
```
The two existing Applications must be byte-for-byte unchanged. Compare them against a copy of `apps.yaml` taken into `$SCRATCH` before editing.

**V2. pet-project lint and render (dev and prod).**
- Fetch the env values read-only: `curl -fsSL https://raw.githubusercontent.com/Katran1990/pet-project-deploy/main/envs/$e/values.yaml -o $SCRATCH/values/$e.yaml` for `e` in `dev` and `prod`.
- Commands:
```bash
for e in dev prod; do
  docker run --rm -v "$PWD:/apps" -v "$SCRATCH/values:/vals" -w /apps alpine/helm:3.22.0 lint infra/helm/pet-project --namespace $e -f /vals/$e.yaml
  docker run --rm -v "$PWD:/apps" -v "$SCRATCH/values:/vals" -w /apps alpine/helm:3.22.0 template pet-project-$e infra/helm/pet-project \
    --namespace $e --api-versions monitoring.coreos.com/v1/ServiceMonitor -f /vals/$e.yaml > "$SCRATCH/pp-$e.yaml"
  docker run --rm -v "$PWD:/apps" -v "$SCRATCH/values:/vals" -w /apps alpine/helm:3.22.0 template pet-project-$e infra/helm/pet-project \
    --namespace $e -f /vals/$e.yaml > "$SCRATCH/pp-$e-nocrd.yaml"
done
```
- Expected for each env:
  - lint prints `1 chart(s) linted, 0 chart(s) failed`;
  - `grep -cx 'kind: ServiceMonitor' "$SCRATCH/pp-$e.yaml"` → `1`;
  - `grep -cx 'kind: ServiceMonitor' "$SCRATCH/pp-$e-nocrd.yaml"` → `0` (the guard works);
  - `yq 'select(.kind=="ServiceMonitor") | [.metadata.name, .spec.selector.matchLabels.app, .spec.endpoints[0].port, .spec.endpoints[0].path] | join(" ")'` → `backend backend http /actuator/prometheus`;
  - `yq 'select(.kind=="Service" and .metadata.name=="backend") | [.metadata.labels.app, .spec.ports[0].name] | join(" ")'` → `backend http`. The selector and port really match the Service;
  - `yq 'select(.kind=="Deployment") | .metadata.name + "=" + (.spec.revisionHistoryLimit | tostring)'` → `backend=3`, `frontend=3` (AC7);
  - `yq 'select(. == null)' …` and a visual check: no empty documents and no doubled `---` around the ServiceMonitor.
- Optional: re-run the exact CI loop from 8a in a shell (with `RUNNER_TEMP=$SCRATCH`) and check that it exits 0.

**V3. kube-prometheus-stack render:**
```bash
docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 template monitoring kube-prometheus-stack \
  --repo https://prometheus-community.github.io/helm-charts --version 91.9.0 \
  --namespace monitoring -f infra/monitoring/values.yaml > "$SCRATCH/kps.yaml"
```
- If there is no network, `helm pull` the chart elsewhere, or use an unpacked 91.9.0 copy mounted at `/kps`, and run `template monitoring /kps/kube-prometheus-stack …`. Report that as "verified against a local copy".
- The command must exit 0. Assertions (an empty result where a value is expected counts as a failure):
```bash
K="$SCRATCH/kps.yaml"
yq 'select(.kind=="Prometheus") | .spec.retention' "$K"                                                    # 15d
yq 'select(.kind=="Prometheus") | .spec.retentionSize' "$K"                                                # 18GiB
yq 'select(.kind=="Prometheus") | .spec.storage.volumeClaimTemplate.spec.resources.requests.storage' "$K"  # 20Gi
yq -o=json -I=0 'select(.kind=="Prometheus") | .spec.resources' "$K"                                       # requests cpu+memory, limits memory
yq -o=json -I=0 'select(.kind=="Prometheus") | [.spec.serviceMonitorSelector, .spec.serviceMonitorNamespaceSelector]' "$K"  # [{},{}]
yq 'select(.kind=="Prometheus") | .spec.alerting' "$K"                                                     # null
yq 'select(.kind=="Alertmanager") | .metadata.name' "$K"                                                    # empty
yq 'select(.kind=="Ingress") | .spec.rules[].host' "$K"                                                     # exactly grafana.pet.local
yq 'select(.kind=="Deployment" and .metadata.name=="monitoring-grafana") | .spec.template.spec.containers[] | select(.name=="grafana") | .env[] | select(.name=="GF_SECURITY_ADMIN_USER" or .name=="GF_SECURITY_ADMIN_PASSWORD") | .name + " " + .valueFrom.secretKeyRef.name + " " + .valueFrom.secretKeyRef.key' "$K"
#   GF_SECURITY_ADMIN_USER grafana-admin admin-user
#   GF_SECURITY_ADMIN_PASSWORD grafana-admin admin-password
yq 'select(.kind=="Secret") | .metadata.name' "$K"                                                          # must NOT contain monitoring-grafana
grep -c 'admin-password:' "$K"                                                                              # 0 (no Secret/ConfigMap carries an admin password)
grep -n -iE 'adminPassword|admin-password:' infra/monitoring/values.yaml                                    # no matches
yq 'select(.kind=="ServiceMonitor") | .metadata.name' "$K" | grep -E 'scheduler|controller-manager|kube-proxy|etcd'   # no matches
yq 'select(.kind=="ConfigMap" and .metadata.name=="monitoring-kube-prometheus-grafana-datasource") | .data."datasource.yaml"' "$K" | grep -c 'type: alertmanager'   # 0
yq 'select(.kind=="Deployment" or .kind=="DaemonSet") | .metadata.name as $n | .spec.template.spec.containers[] | $n + "/" + .name + " " + (.resources | tojson)' "$K"
#   every line has non-empty requests and limits.memory (operator, grafana + its sidecars, kube-state-metrics, node-exporter)
yq 'select(.kind=="Service") | .metadata.name' "$K"   # contains monitoring-kube-prometheus-prometheus and monitoring-grafana -> the exact names used in README
```
If a rendered name differs from the one assumed in this plan (datasource ConfigMap, Grafana Deployment, Prometheus Service), use the rendered name in the README and report the difference.

**V4. actionlint:** `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color` must exit 0 with no output.

**V5. Grafana SealedSecret (after M1; report PENDING-USER while the file is absent):**
```bash
F=infra/monitoring/sealed-secrets/grafana-admin.yaml
test -f "$F"
yq '[.kind, .metadata.name, .metadata.namespace] | join(" ")' "$F"                          # SealedSecret grafana-admin monitoring
yq -o=json -I=0 '.spec.encryptedData | keys | sort' "$F"                                    # ["admin-password","admin-user"]
yq -o=json -I=0 '.metadata.annotations // {} | keys' "$F" | grep -E 'cluster-wide|namespace-wide'   # no matches (strict scope)
yq '(.data // "none"), (.stringData // "none"), (.spec.template.data // "none")' "$F"       # none none none
ls infra/monitoring/sealed-secrets                                                          # only grafana-admin.yaml
```
The implementer must not create this file. If it exists before M1, report it as unexpected.

**V6. README:**
- `grep -n '^## Monitoring (kube-prometheus-stack)$' README.md` → exactly 1 match.
- `grep -n` matches for each of: `grafana.pet.local`, `port-forward svc/monitoring-kube-prometheus-prometheus 9090`, `/targets`, `up{job="backend"}`, `--from-file=admin-password=/dev/stdin`, `hard-refresh`, `ServerSideApply`, `three`.
- `grep -nE -- '--from-literal=admin-password' README.md` → no matches.
- Read the section once and check the facts against D1-D7.

**V7. AC8 and AC9 already present:**
```bash
grep -nx 'management.metrics.distribution.percentiles-histogram.http.server.requests=true' backend/src/main/resources/application.properties   # line 33
grep -nx 'management.metrics.tags.application=${spring.application.name}' backend/src/main/resources/application.properties                 # line 34
```
`application.properties` must not be modified by this task.

**V8. Project tests (no code changed, required by CLAUDE.md):**
- `cd /workspace/backend && ./gradlew test --rerun`. One Gradle run at a time. Testcontainers only.
- `cd /workspace/frontend && npm test`.

**V9. Changed-file set.**
- Create `$SCRATCH/impl-start.ref` before the first edit.
- Then run `find /workspace/infra /workspace/.github /workspace/README.md /workspace/backend/src /workspace/frontend/src -type f -newer "$SCRATCH/impl-start.ref" | sort`.
- The output must be exactly:
  ```
  /workspace/.github/workflows/ci.yml
  /workspace/README.md
  /workspace/infra/argocd/apps.yaml
  /workspace/infra/helm/pet-project/Chart.yaml
  /workspace/infra/helm/pet-project/templates/backend.yaml
  /workspace/infra/helm/pet-project/templates/frontend.yaml
  /workspace/infra/monitoring/values.yaml
  ```
- After M1, `/workspace/infra/monitoring/sealed-secrets/grafana-admin.yaml` is added by the user.
- No tool binaries, rendered files or values copies may be left inside `/workspace`.

## Risks and open questions
1. **Q1: new dependency (needs confirmation).** The `kube-prometheus-stack` chart 91.9.0 with its bundled subcharts and default images (see the dependency table). The card names it, so confirming should be a formality. It is listed per the planning rules.
2. **Argo CD support for a `ref` source that also has `path` (D1).**
   - Argo CD's multi-source docs describe this ("if the `path` field is set in the `$values` source, Argo CD will attempt to generate resources from the git repository at that URL"). It could not be tested against the user's Argo CD version here.
   - Check after M2: the `monitoring` app tree must show `SealedSecret/grafana-admin`.
   - Fallback A: keep the `ref: values` source without `path` and add a third source with the same repoURL and revision plus `path`. This risks the "same repoURL" rejection noted in apps.yaml.
   - Fallback B (safer): a small fourth Application `monitoring-secrets` (directory source `infra/monitoring/sealed-secrets`, namespace `monitoring`). That goes beyond the card's "third Application" wording, so it needs the user's approval.
3. **Q2: should the monitoring Application track `main` or `development`? (D2)**
   - `main` (chosen): changes to monitoring roll out together with prod, and the first install happens only after promotion to `main`.
   - `development`: faster feedback, but untested monitoring changes hit the only monitoring instance, which also watches prod.
   - Please confirm.
4. **The ServiceMonitor guard can hide a missing CRD (D4).**
   - If a pet-project app's manifests were generated and cached before the CRD existed, the ServiceMonitor appears only after Argo CD regenerates them: on the next commit or image-tag change (every merge brings one), or after a hard refresh (documented).
   - CI always renders the guarded path, so a template error cannot hide behind the guard.
5. **Version pinned in two places** (`apps.yaml` and `ci.yml`). The CI step fails with an explicit error if they differ. A Renovate or Dependabot setup would have to bump both.
6. **k3s assumptions (D6).**
   - Disabling the four control-plane scrapers assumes k3s/k3d, as the README and `ingress.yaml` state. On a kubeadm cluster they should be re-enabled.
   - node-exporter on k3d can fail with "path / is mounted on / but it is not a shared or slave mount" (host root mount propagation inside Docker-based nodes). If M2 shows that, set `prometheus-node-exporter.hostRootFsMount.enabled: false`. It is not set preemptively.
7. **Sizing is an estimate.**
   - Prometheus at 1Gi requested and 2Gi limit is enough for kubelet/cAdvisor, kube-state-metrics, node-exporter and 3 backend pods with HTTP histograms (about 70 buckets per uri/status/method combination).
   - A sharp rise in series can OOM-kill Prometheus. M5 checks real usage.
   - `retentionSize: 18GiB` is a deliberate addition beyond the card. It protects the 20Gi volume, whose size local-path does not enforce. Drop it if the user prefers to stick strictly to the card.
8. **No CPU limits.** This follows the existing chart convention (`infra/helm/pet-project/values.yaml` sets only memory limits) and avoids CPU throttling. If the user reads "requests/limits set" as requiring CPU limits too, add `limits.cpu` per component.
9. **Grafana has no persistence.** Users, preferences and UI-edited dashboards are lost on every restart, and the admin password is re-applied from the Secret at each pod start (the README's rotation relies on this). Dashboards will come from ConfigMaps in the next card. Enable `grafana.persistence` if UI state must survive.
10. **Grafana over plain HTTP** at `grafana.pet.local`, protected only by the admin login (anonymous access is off by default). Acceptable for the local k3d entry point. TLS is out of scope.
11. **Admission webhooks are kept at upstream defaults.**
    - They run as Helm hook Jobs, which Argo CD treats as PreSync and PostSync hooks on every sync.
    - If they cause trouble, disable `prometheusOperator.admissionWebhooks.enabled` **and** `prometheusOperator.tls.enabled` together. The operator's TLS Secret is created by the webhook Job, so disabling only the webhooks leaves the operator pod without its certificate.
12. **Deleting the `monitoring` Application removes the CRDs** (prune or cascade). All ServiceMonitors go with them. The guarded pet-project apps keep syncing, and the Prometheus PVC (20Gi) stays behind.
13. **Default PrometheusRules are evaluated with nowhere to send alerts.** This is harmless, and alerting is the next card. They are not tuned here.
14. **Nothing validates the sealed file automatically.** Unlike the Postgres file, it is applied raw by Argo CD, not through chart `fail()` checks. Protection comes from V5 and the merge blocker. Optional question Q3: should CI also run the V5 structural checks (`test -f` plus yq)? That would make the PR red until M1 is done.
15. **Sync ordering.** The Grafana pod may start before the controller has created `grafana-admin`, and it stays in `CreateContainerConfigError` until then. Kubelet retries on its own, so no sync waves are used. This matches Postgres Risk 5.
16. **CI depends on `prometheus-community.github.io`.** An outage turns the step red. Argo CD has the same dependency.
17. **All ServiceMonitors in all namespaces are scraped (D5).** Any future ServiceMonitor anywhere gets picked up. This is intended.

## Out of scope
- Alerting: Alertmanager, custom PrometheusRules, receivers (card).
- Loki and logs (card). Custom Grafana dashboards (next card).
- Prometheus or Alertmanager Ingress, TLS for Grafana, SSO, Grafana persistence.
- `revisionHistoryLimit` for monitoring components and the Postgres StatefulSet. Only the backend and frontend Deployments are in scope.
- Backend changes: AC8 and AC9 are already done in PR #32 and only verified. No frontend or DB changes.
- The deploy repo `Katran1990/pet-project-deploy` and any git operation.
- Running `kubeseal`, `kubectl` or `argocd` against the cluster, and creating the Grafana ciphertext (M1-M5 are for the user).
- Thanos, remote write, cert-manager, tuning the admission webhooks, Renovate/Dependabot for chart versions.
- Editing historical plans in `docs/plans/`.

## Amendments approved by the user (2026-10-04)
These override the sections above where they differ.
- **Q1:** the new dependency kube-prometheus-stack 91.9.0 is confirmed.
- **Q2:** the repo source of the `monitoring` Application tracks `main` (D2 stays as written).
- **Q3:** no CI check of the sealed file.
- **Grafana memory (section 2):** `grafana.resources` becomes `requests: { cpu: 50m, memory: 256Mi }`, `limits: { memory: 512Mi }`. Sidecar sizing stays as written.
- **Risk 6:** node-exporter `hostRootFsMount` stays at the upstream default (not disabled).
- **Risk 7:** `retentionSize: 18GiB` stays.
- **Risk 8:** no CPU limits (memory limits only), as written.
- **CI version-parity check (section 8):** anchor the match instead of a substring grep, e.g. `grep -qE "targetRevision: ${KPS_VERSION}([[:space:]]|$)" infra/argocd/apps.yaml`.
- **CI render-step comment (section 8a):** do not claim the step renders "exactly as Argo CD does"; say it renders as Argo CD does on a cluster where the ServiceMonitor CRD exists, and that the no-CRD path is covered by the `helm lint` steps (no `--api-versions`).
- **Sealed file (section 3, D3):** the user has already added `infra/monitoring/sealed-secrets/grafana-admin.yaml` (SealedSecret `grafana-admin`, namespace `monitoring`, strict scope, `encryptedData` keys `admin-user` and `admin-password`). Do not create, modify or delete it. V5 runs against it and is no longer PENDING-USER.

## Amendment 2 approved by the user (2026-10-04): separate `monitoring-secrets` Application
This replaces D1 (and the related parts of D3, Risk 2, section 1, section 7, section 8 and V1). It deliberately deviates from the card's "a third Argo CD Application" wording: there are now four Applications, at the user's explicit request.
- **`monitoring`** Application: chart source (kube-prometheus-stack 91.9.0, `releaseName: monitoring`, `valueFiles: [$values/infra/monitoring/values.yaml]`) plus this repo as a pure `ref: values` source (`targetRevision: main`) **without `path`**. Sync policy and syncOptions unchanged (prune, selfHeal, CreateNamespace, ServerSideApply).
- **`monitoring-secrets`** Application (new, 4th document in `infra/argocd/apps.yaml`): single directory source `repoURL: https://github.com/Katran1990/pet-project.git`, `targetRevision: main`, `path: infra/monitoring/sealed-secrets`; destination namespace `monitoring`; `project: default`; automated `prune: true`, `selfHeal: true`; `syncOptions: [CreateNamespace=true]`. Comments explain why it is separate (keeps the chart app to the documented chart + `$values` pattern; the SealedSecret applies independently of the chart sync) and what happens until/unless the file decrypts (Grafana in `CreateContainerConfigError`, rest of the stack healthy). Spell repoURLs exactly like the other Applications. Update the apps.yaml header comment (four Applications, prerequisite: Sealed Secrets controller).
- **D3 "until the file exists"** now reads: without the directory, `monitoring-secrets` shows "app path does not exist"; `monitoring` still installs, and Grafana waits in `CreateContainerConfigError` for Secret `grafana-admin`. (The file already exists in this branch.)
- **Risk 2** is resolved by this design; fallback A/B no longer apply.
- **CI (section 8, step "Render kube-prometheus-stack with monitoring values"):** replace the whole-file version grep with a check scoped to the Applications, using `yq` (mikefarah v4, preinstalled on GitHub-hosted ubuntu-latest runners — state that in a comment; no new action): fail with `::error::` unless (a) the `monitoring` Application's chart source has `chart: kube-prometheus-stack` and `targetRevision` == `${KPS_VERSION}`, (b) its `ref: values` source has no `path`, (c) an Application `monitoring-secrets` exists whose source has `path: infra/monitoring/sealed-secrets`, `targetRevision: main` and destination namespace `monitoring`. Keep it a few lines; update the step comment.
- **README:** describe the four Applications and the `monitoring-secrets` app (what it applies, how to check it: `kubectl -n argocd get application monitoring-secrets`, `kubectl -n monitoring get sealedsecret grafana-admin`); fix every place that says the SealedSecret comes from the `monitoring` app's `ref` source / `path`, and every "three Applications".
- **Reviewer suggestion 1:** in README "Upgrading the chart", list every place the version `91.9.0` appears (apps.yaml `targetRevision`, ci.yml `KPS_VERSION`, the README local render command and any other README mention, the header comment of `infra/monitoring/values.yaml`), or make the non-pinned mentions version-agnostic; either way, after the change a `grep -rn "91\.9\.0"` over the repo (excluding docs/plans) must list only places the upgrade section names.
- **Verification:** V1 now expects four Applications (pet-project-dev, pet-project-prod, monitoring, monitoring-secrets), the `monitoring` ref source without `path`, and `monitoring-secrets` as above; the CI check from this amendment must pass locally on the real apps.yaml and must fail on a copy where the version or the `monitoring-secrets` path is altered.
