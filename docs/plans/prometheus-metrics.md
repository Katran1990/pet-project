# Plan: Backend exposes Prometheus metrics

Trello card: "Backend exposes Prometheus metrics"
Branch: `feature/prometheus-metrics` (based on `origin/development`). All paths below are relative to the repo root.

## Goal
Add the Micrometer Prometheus registry so the backend serves `GET /actuator/prometheus` in the Prometheus text format, with HTTP server and JVM metrics. Name the backend Service port `http` and label the Service `app: backend` so a ServiceMonitor can select it later. Publish HTTP server latency histograms and a common `application` tag for the Grafana dashboards. The endpoint stays unauthenticated and reachable only inside the cluster. This needs no custom code: one dependency, three properties, two lines in the Helm chart, and one integration test.

## User decisions at plan approval (2026-10-04)
- Risk 3, option (a): the backend Service gets `metadata.labels: { app: backend }` in this card.
- Reviewer suggestions 1-3 applied: relaxed lockfile check (V2), softer README wording on external reachability, explicit grep in the Helm check (V3).
- Added to this card beyond the Trello acceptance criteria (configuration only, needed by the dashboards card):
  - `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
  - `management.metrics.tags.application=${spring.application.name}`
  - Test 1 also asserts the `application` tag in the scrape output.

## Acceptance criteria
- [x] AC1: `micrometer-registry-prometheus` dependency added, `gradle.lockfile` updated
- [x] AC2: `GET /actuator/prometheus` returns text with `http_server_requests_seconds` and `jvm_memory_used_bytes`
- [x] AC3: An integration test asserts the endpoint and both metric names
- [x] AC4: Helm chart: the backend Service gets a named port `"http"` (needed by a ServiceMonitor)

Constraints from the card:
- [x] The endpoint is unauthenticated and reachable only inside the cluster. Nothing routes `/actuator/*` from outside.
- [x] No ServiceMonitor resource is added.

Additions requested by the user at plan approval:
- [x] U1: the backend Service has the label `app: backend`
- [x] U2: HTTP server request timings are published as a percentile histogram (`http_server_requests_seconds_bucket`)
- [x] U3: every meter carries the common tag `application` = `spring.application.name` (`pet`), asserted by test 1

### How the criteria are interpreted
- **AC2:**
  - "Returns text" means HTTP 200 with a `Content-Type` compatible with `text/plain`. That is the Prometheus text exposition format, which the endpoint returns by default when the client does not ask for OpenMetrics.
  - A Micrometer `Timer` appears as `http_server_requests_seconds_count` and `_sum`, plus `http_server_requests_seconds_max` (gauge). With the percentile histogram enabled (U2) the Prometheus type becomes `histogram` and `http_server_requests_seconds_bucket{...,le="..."}` lines are added; `_count` and `_sum` stay.
  - So "contains `http_server_requests_seconds`" is checked twice: as a substring, and as a real `_count` sample line.
  - `jvm_memory_used_bytes` is a gauge with `area`/`id` labels.
- **AC4:** only the Service port gets a name. `targetPort` and the container's `containerPort` stay numeric, which is the smallest change. A ServiceMonitor references the **Service** port by name (`endpoints[].port: http`).

## Changes

### Existing state (for context)
- **`backend/build.gradle`**
  - Spring Boot 4.1.1, `io.spring.dependency-management` 1.1.7, `dependencyLocking { lockAllConfigurations() }`.
  - It already has `implementation spring-boot-starter-actuator` and `testImplementation spring-boot-starter-actuator-test`.
  - The only `runtimeOnly` line is `org.postgresql:postgresql`.
- **Versions managed by the Boot BOM** (checked in `spring-boot-dependencies-4.1.1.pom` in the Gradle cache):
  - `micrometer.version=1.17.1`, and `micrometer-bom-1.17.1` manages `io.micrometer:micrometer-registry-prometheus:1.17.1`.
  - `prometheus-client.version=1.7.0`, through `io.prometheus:prometheus-metrics-bom`.
  - So build.gradle needs no version.
  - Do **not** use `micrometer-registry-prometheus-simpleclient`. It is the legacy client.
- **Auto-configuration (checked in the 4.1.1 jars):**
  - `spring-boot-micrometer-metrics` contains:
    - `...micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration`, plus `PrometheusScrapeEndpoint` and `PrometheusOutputFormat`;
    - `...autoconfigure.jvm.JvmMetricsAutoConfiguration`.
  - `spring-boot-webmvc` contains `org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration`, which records `http.server.requests`.
  - All of them are already on the runtime classpath through the actuator starter. The Prometheus export activates once the registry is on the classpath.
- **Opt-in for tests:** Spring Boot tests disable metrics export (only the simple registry is active) unless the test opts in.
  - The opt-in annotation exists as `org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics`, in `spring-boot-micrometer-metrics-test-4.1.1.jar`, already on the test classpath through `spring-boot-starter-actuator-test`.
  - Boot 3's `@AutoConfigureObservability` does not exist in Boot 4.
- **Already on the test classpath:** Awaitility 4.3.0 (`backend/gradle.lockfile`, `org.awaitility:awaitility:4.3.0=testCompileClasspath,...`, part of Boot's test starters). Using it adds no dependency.
- **Current code:**
  - `backend/src/main/resources/application.properties:25` has `management.endpoints.web.exposure.include=health,info`.
  - There is no Spring Security and no custom `MeterRegistry`, `MeterFilter` or `ObservationPredicate` code.
- **How the outside reaches the cluster (nothing exposes `/actuator`):**
  - `infra/helm/pet-project/templates/ingress.yaml` sends all paths (`/`, Prefix) to the `frontend` Service on port 80 only.
  - `frontend/nginx.conf` proxies only `location /api/` to `http://backend:8080`. Every other path, including `/actuator/prometheus`, falls through to `location /` and returns the SPA's `index.html` (200, `text/html`), never metrics.
  - `frontend/vite.config.ts` (local dev) proxies only `/api`.
  - So no Ingress, nginx or Vite change is needed.
  - Inside the cluster, any pod can reach `http://backend:8080/actuator/prometheus`. The chart has no NetworkPolicy. That is the intended "cluster-internal" state.
- **`infra/helm/pet-project/templates/backend.yaml`:**
  - The Service `backend` has `ports: - port: 8080, targetPort: 8080` with no name and **no labels** in `metadata` (see Risk 3).
  - The Deployment has an unnamed `containerPort: 8080`, and its probes use `port: 8080` numerically.
  - The comment on the Service notes that nginx proxies to `http://backend:8080`. The port number does not change, so nginx is not affected.
- **`infra/helm/pet-project/Chart.yaml`:** `version: 0.2.0  # chart version, bump when templates change`.
- **CI:** `.github/workflows/ci.yml` runs:
  - `./gradlew build` (Testcontainers);
  - `helm lint` and `helm template` with `--namespace dev|prod` against the deploy-repo values;
  - a Trivy fs scan of `backend/gradle.lockfile` that fails on fixed HIGH/CRITICAL findings.
- **README:** the API table lists `GET /actuator/health`. The "Deploy (Helm + Argo CD)" section describes the chart.

### Production changes (implementer)

The implementer must **not** run `./gradlew bootRun` and must not connect to the dev Postgres (`postgres:5432`). Verification uses `./gradlew test` (Testcontainers) only.

**1. `backend/build.gradle` (modify)**
Add one line to `dependencies`. Put it with the existing `runtimeOnly` line, before `runtimeOnly 'org.postgresql:postgresql'` (alphabetical):
```groovy
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```
- **Why `runtimeOnly`:** no code references the registry. Spring Boot's docs and start.spring.io use runtime scope for it. The test talks to the endpoint over HTTP, so it does not need it on the compile classpath either.
- No version: the Boot BOM manages it (1.17.1).

**2. `backend/gradle.lockfile` (regenerate)**
- Run `cd backend && ./gradlew dependencies --write-locks` (CLAUDE.md rule; the build fails without it).
- Expected diff, **additions only**:
  - `io.micrometer:micrometer-registry-prometheus:1.17.1`;
  - a few `io.prometheus:prometheus-metrics-*:1.7.0` artifacts (core, model, config, exposition formats, tracer modules; the exact set is whatever Gradle resolves).
  - All of them end with `=productionRuntimeClasspath,runtimeClasspath,testRuntimeClasspath`.
- No existing line may change version or disappear. See Verification V2 for the check without git.

**3. `backend/src/main/resources/application.properties` (modify line 25)**
Replace the exposure line with:
```properties
# Actuator over HTTP: health for the Kubernetes probes, prometheus for metrics scraping
# (Micrometer, Prometheus text format). Deliberately unauthenticated: the backend is only
# reachable inside the cluster, because the Ingress routes to the frontend and its nginx
# forwards only /api/ to the backend (see README "Deploy (Helm + Argo CD)").
management.endpoints.web.exposure.include=health,info,prometheus

# Metrics for the Grafana dashboards: latency histograms for HTTP server requests
# (histogram_quantile for p95/p99) and a common "application" tag on every meter.
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.tags.application=${spring.application.name}
```
- Both metrics properties were checked in `META-INF/spring-configuration-metadata.json` of `spring-boot-micrometer-metrics-4.1.1.jar`: `management.metrics.distribution.percentiles-histogram` (`Map<String,Boolean>`, key = meter name prefix) and `management.metrics.tags` (`Map<String,String>`, "Common tags that are applied to every meter"). Neither is deprecated.
- `${spring.application.name}` resolves to `pet` (line 1 of the same file).
- No other `management.*` properties:
  - `management.prometheus.metrics.export.enabled` defaults to `true`.
  - Endpoint access defaults to unrestricted.
  - No separate management port (see Out of scope).

**4. `infra/helm/pet-project/templates/backend.yaml` (modify the Service only)**
```yaml
metadata:
  name: backend            # (existing comment unchanged)
  labels: { app: backend } # selected by a Prometheus ServiceMonitor (spec.selector.matchLabels)
spec:
  selector: { app: backend }
  ports:
    - name: http           # referenced by name from a Prometheus ServiceMonitor (endpoints[].port: http)
      port: 8080
      targetPort: 8080
```
- Use the flow-style `{ app: backend }` to match the existing `selector` line.
- Adding a label is an in-place metadata update; it does not change the selector or endpoints.
- `"http"` is a valid DNS-1123 label (Kubernetes port names must be at most 15 characters).
- The port number is unchanged, so `http://backend:8080` in nginx keeps working.
- Kubernetes merges Service ports by the `port` key, so Argo CD applies this in place: the Service is not recreated and there is no downtime.
- Leave unchanged:
  - the Deployment, including `containerPort` and the probes;
  - `frontend.yaml`, `postgres.yaml` and `ingress.yaml`.

**5. `infra/helm/pet-project/Chart.yaml` (modify)**
- `version: 0.2.0` -> `0.3.0`. The file says to bump it when templates change, and the sealed-secrets card also used a minor bump.
- `appVersion` stays unchanged.
- Argo CD deploys the chart from git, so the version number has no effect on syncing (Risk 8).

**6. `README.md` (modify, small)**
- **API table:** add a row right after `GET /actuator/health`:
  `| \`GET\`  | \`/actuator/prometheus\` | Metrics in the Prometheus text format (Micrometer), e.g. \`http_server_requests_seconds\`, \`jvm_memory_used_bytes\`; unauthenticated, intended for in-cluster scraping only (see "Deploy") |`
- **"Deploy (Helm + Argo CD)" section:** add one bullet:
  - The Ingress and the frontend's nginx route only `/api/` to the backend; `/actuator/prometheus` is meant to be scraped from inside the cluster. (Do not claim more than that, e.g. not "unreachable from outside in every case".)
  - Inside the cluster it is served without authentication at `http://backend:8080/actuator/prometheus`.
  - The backend Service has the label `app: backend` and its port is named `http`, so a Prometheus Operator `ServiceMonitor` can select it (`matchLabels: { app: backend }`, `port: http`). The chart does not ship one.
  - HTTP server timings are exported as a histogram (`http_server_requests_seconds_bucket`, usable with `histogram_quantile`), and every metric carries the tag `application="pet"`.
  - Locally the endpoint is at `http://localhost:8080/actuator/prometheus`. The Vite dev server proxies only `/api`.

**7. No other files**
- No Java production code: no controller, filter or `MeterRegistry` bean.
- No Flyway migration and no frontend change.
- No change to `frontend/nginx.conf`, `ingress.yaml`, `frontend/vite.config.ts`, `backend/Dockerfile` (`EXPOSE 8080` is unchanged) or `.github/workflows/*`.
- No `ServiceMonitor`, no `.trivyignore` entry (unless V5 finds something, see Risk 5).

### New dependency

| Artifact | Version | Scope | Why it is needed | Cost of writing it by hand |
|---|---|---|---|---|
| `io.micrometer:micrometer-registry-prometheus` | 1.17.1 (managed by the Spring Boot 4.1.1 BOM; no version in build.gradle) | `runtimeOnly` | Required by AC1. Spring Boot's `PrometheusMetricsExportAutoConfiguration` activates only when this registry is on the classpath. It creates the `PrometheusMeterRegistry` and the `/actuator/prometheus` endpoint, which renders every Micrometer meter (HTTP, JVM, Hikari, Tomcat) in the Prometheus/OpenMetrics format. | A custom `MeterRegistry` and a text-format writer (naming conventions, unit suffixes, summary/histogram rendering, escaping, OpenMetrics content negotiation), plus a controller. That is several hundred lines and directly against the CLAUDE.md rule "prefer existing library features over custom code". |

Transitive additions are `io.prometheus:prometheus-metrics-*` 1.7.0, the Prometheus Java client 1.x managed by the Boot BOM. Nothing else is added: Awaitility, used by the test, is already on the test classpath.

## Tests

The task is done when `cd backend && ./gradlew test` is green. CI runs the same tests in `./gradlew build`.

### Test changes (test-writer)

The test-writer must **not** run `./gradlew bootRun` or connect to the dev Postgres (`postgres:5432`). The test uses Testcontainers only, through the existing `TestcontainersConfiguration`.

**New: `backend/src/test/java/dev/katran/pet/metrics/PrometheusEndpointIT.java`**
- **Package:** `dev.katran.pet.metrics` (new test-only package, like the existing feature packages).
- **Setup:** the same as `greeting/GreetingControllerIT` and `category/CategoryControllerIT`, plus the metrics opt-in:
  ```java
  @AutoConfigureMetrics   // org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
  @Import(TestcontainersConfiguration.class)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  class PrometheusEndpointIT {
      @LocalServerPort private int port;
      private RestTestClient client;   // RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build() in @BeforeEach
  ```
- **Why `@AutoConfigureMetrics` is required:** without it, Boot's test support disables metrics export. Then no `PrometheusMeterRegistry` exists, and `/actuator/prometheus` returns 404. Add a one-line class comment saying so, so nobody removes it as "unused".
- Do not use `@AutoConfigureObservability`: it does not exist in Boot 4.

**Test 1: `exposesHttpServerRequestAndJvmMemoryMetricsInPrometheusTextFormat`** (AC2, AC3)
1. `GET /api/greeting` -> expect 200. This is a completed application request, so `http_server_requests_seconds` has a sample. It reads the row seeded by the `V1__` migration, as in `GreetingControllerIT`.
   - Use an `/api` endpoint, not an actuator endpoint, for this request. The test then does not depend on whether Boot 4 records actuator requests in `http.server.requests`.
2. Scrape inside `await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> { ... })` (`org.awaitility.Awaitility.await`):
   - The observation for request 1 is stopped in a servlet filter after the controller returns. Polling makes the test independent of that timing.
   - Normally it passes on the first try.
   - Inside the block:
     - `client.get().uri("/actuator/prometheus").accept(MediaType.TEXT_PLAIN).exchange()`
     - `.expectStatus().isOk()`
     - `.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)`
     - `.expectBody(String.class).returnResult().getResponseBody()`
   - Assertions on the body:
     - `contains("http_server_requests_seconds")` and `contains("jvm_memory_used_bytes")`: the literal AC wording.
     - `body.lines()` has a line that starts with `http_server_requests_seconds_count{` and contains `uri="/api/greeting"`. This proves real request metrics, not only a `# HELP` line.
     - `body.lines()` has a line that starts with `jvm_memory_used_bytes{`.
     - **U3 (application tag):** the `http_server_requests_seconds_count{...uri="/api/greeting"...}` line and a `jvm_memory_used_bytes{` line both contain `application="pet"`. Checking two unrelated meters proves the tag is common, not specific to one meter. Read the expected value from the `spring.application.name` property (e.g. `@Value("${spring.application.name}")`) rather than hardcoding `pet`, so the test follows a rename of the application.
     - **U2 (histogram):** `body.lines()` has a line that starts with `http_server_requests_seconds_bucket{` and contains `uri="/api/greeting"` and `le="`. This proves the percentile-histogram property is effective.
   - Do not assert the `version=` parameter of the content type, the label order or exact values. They are format details that can change between Prometheus client versions.

**Test 2: `exposesOnlyHealthInfoAndPrometheusOverHttp`** (guards the "unauthenticated, nothing else" decision)
- `GET /actuator` (the discovery page) -> 200, and:
  - `$._links.health` exists;
  - `$._links.prometheus` exists;
  - `$._links.metrics` does not exist;
  - `$._links.env` does not exist.
- This catches someone widening the exposure later, for example to `*`, which would publish `env` and other endpoints without authentication.

**Regression:** no existing test changes.
- `PetApplicationTests` and all other `*IT` classes do not opt in to metrics export, so their contexts keep the simple registry as before.
- `PrometheusEndpointIT` has a different context cache key, so it starts one extra Spring context and one extra `postgres:17` container. That adds about 5-10 s to the test run.

**Optional sanity check (not committed; revert afterwards):**
- Temporarily remove `prometheus` from the exposure property: test 1 must fail with 404.
- Temporarily remove `@AutoConfigureMetrics`: test 1 must fail.
- This proves both are needed and that the test really checks the endpoint.

### Which test proves which criterion

| AC | Proof |
|---|---|
| AC1 dependency + lockfile | build.gradle line; V2 lockfile diff; `./gradlew build` and `test` pass with dependency locking on (a missing lock entry fails the build) |
| AC2 endpoint returns both metrics | `PrometheusEndpointIT` test 1 |
| AC3 integration test | `PrometheusEndpointIT` exists and is green in `./gradlew test` and in CI |
| AC4 named port | V3 (`helm template` output grepped for `name: http`) and the CI job "Helm chart" (lint + template for dev/prod) |
| U1 Service label | V3 (`helm template` output grepped for `labels: { app: backend }` / `app: backend` under the Service metadata) |
| U2 histogram | `PrometheusEndpointIT` test 1 (`_bucket` line) |
| U3 application tag | `PrometheusEndpointIT` test 1 (`application="pet"` on two meters) |
| Unauthenticated, cluster-internal | Test 2 (exposure limited); unchanged `ingress.yaml` and `nginx.conf` (V4) |
| No ServiceMonitor | V4 grep |

### Verification (commands)
Run from the dev container. No `bootRun` and no dev Postgres.

- **V1. Build and tests**
  - `cd /workspace/backend && ./gradlew test`: everything is green.
  - For a quick iteration first: `./gradlew test --tests dev.katran.pet.metrics.PrometheusEndpointIT`.
  - Optionally `./gradlew build`, the same as CI.
- **V2. Lockfile** (no git commands)
  - Before step 2 of the production changes: `cp /workspace/backend/gradle.lockfile <scratchpad>/gradle.lockfile.before`.
  - After `cd /workspace/backend && ./gradlew dependencies --write-locks`: `diff <scratchpad>/gradle.lockfile.before /workspace/backend/gradle.lockfile`.
  - New `>` lines are expected for `io.micrometer:micrometer-registry-prometheus:1.17.1` and `io.prometheus:prometheus-metrics-*:1.7.0`.
  - The invariant: no existing `group:artifact` entry changes version or disappears. A changed configuration list on an existing entry (the part after `=`) is acceptable; report it, do not stop on it. Compare by `group:artifact:version` (e.g. `cut -d= -f1` on both files, then `diff`).
- **V3. Helm** (helm is not installed in the dev container; use the pinned image from the README, run from `/workspace`):
  - `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 lint infra/helm/pet-project --namespace dev`
    - Expected: `1 chart(s) linted, 0 chart(s) failed`.
  - `docker run --rm -v "$PWD:/apps" -w /apps alpine/helm:3.22.0 template pet-project-dev infra/helm/pet-project --namespace dev --show-only templates/backend.yaml`
    - The `Service` shows `- name: http` with `port: 8080` and `targetPort: 8080`, and `labels: { app: backend }` in its metadata.
    - Make it explicit and repeatable: pipe the output to `grep -n -E "name: http|labels: \{ app: backend \}"` and expect both matches (exit 0).
    - Printing this template is safe: it contains only `secretKeyRef` references.
  - Repeat the template command (with the same grep) with `--namespace prod` (exit 0).
  - The CI job "Helm chart" also renders against the deploy-repo values. The README "CI and Docker images" section has the local `curl` + `helm template` variant if needed.
  - If Docker is unavailable, rely on that CI job.
- **V4. Nothing else exposed or added**
  - `ingress.yaml`, `frontend/nginx.conf` and `frontend/vite.config.ts` are unchanged.
  - `grep -rn "ServiceMonitor" /workspace/infra` matches only the two explanatory YAML comments added to the Service in step 4, and no resource of that kind.
- **V5. Dependency scan** (optional locally; CI's Trivy job is the gate)
  - Run the README's "Run locally" Trivy fs command from `/workspace`. It must exit 0. The new `io.prometheus` artifacts are now part of the scanned lockfile.

## Risks and open questions
1. **New dependency (needs confirmation, formally).**
   - `io.micrometer:micrometer-registry-prometheus` 1.17.1, `runtimeOnly`, plus transitive `io.prometheus:prometheus-metrics-*` 1.7.0.
   - The card's AC1 names this artifact, so confirmation should be a formality. It is listed per the planning rules.
2. **`@AutoConfigureMetrics` behaviour in Boot 4.1.**
   - The class name was checked in the 4.1.1 jar. Its exact behaviour could not be read, because the jar is compressed.
   - If test 1 still gets 404 with the annotation, the fallback is `@SpringBootTest(properties = "management.prometheus.metrics.export.enabled=true")`, with a comment explaining why. The registry-specific flag takes precedence over the defaults flag that the test support turns off.
   - Do not touch the production config to make the test pass.
3. **Open question for the user: the Service has no labels.**
   - A ServiceMonitor selects Services by label (`spec.selector.matchLabels`). `Service/backend` currently has no `metadata.labels`. The Deployment has `app: backend`, the Service does not.
   - The named port alone is therefore not enough for the future ServiceMonitor card.
   - Options:
     - (a) Add `labels: { app: backend }` to the Service in this card. It is one line and harmless.
     - (b) Leave it for the ServiceMonitor card.
   - **Decided by the user: (a).** See step 4.
4. **Unauthenticated inside the cluster.**
   - Any pod in the cluster can read the metrics. The chart has no NetworkPolicy.
   - The content includes URI templates (such as `/api/expenses/{id}`, not raw ids), status codes, JVM, Tomcat and Hikari stats. It contains no secrets: `env`, `configprops` and other endpoints stay unexposed, and test 2 guards that.
   - The card accepts this ("for now").
5. **Trivy gate.**
   - CI fails on fixed HIGH/CRITICAL findings in `backend/gradle.lockfile`. `build-images.yml` also scans the built image, which contains the new jars.
   - If either reports something for the new artifacts, the fix is a version override (like `ext['tomcat.version']`) or a dated `.trivyignore` entry.
   - That choice is the user's. The plan does not expect a finding.
6. **Latency histograms (now in scope, U2).**
   - Each `uri`/`method`/`status`/`outcome`/`exception` combination of `http.server.requests` gets one series per bucket (Micrometer's default bucket set, roughly 70 buckets between 1 ms and 30 s, plus `+Inf`). For this app's small number of endpoints this is acceptable; if cardinality becomes a problem, `management.metrics.distribution.minimum-expected-value`/`maximum-expected-value` or `slo` can trim it later.
7. **`application` common tag (now in scope, U3).** It applies to every meter, including JVM, Hikari and Tomcat ones. It does not collide with any existing tag name.
8. **Chart version bump.**
   - `0.2.0` -> `0.3.0` follows the "bump when templates change" comment. A patch bump (`0.2.1`) would be equally valid.
   - Argo CD deploys from git, so the number does not affect deployment.
9. **Prometheus server content negotiation.**
   - Real Prometheus servers ask for OpenMetrics first. The endpoint supports both formats through `PrometheusOutputFormat`.
   - The test checks only the text format (the AC says "text"). The metric names are the same in both formats.

## Out of scope
- Custom business metrics (card) and authentication or authorization on actuator endpoints (card).
- A `ServiceMonitor`/`PodMonitor` resource, Prometheus Operator, Prometheus or Grafana installation (card).
- Naming the Deployment's `containerPort`, or switching the probes to a named port.
- A separate management port (`management.server.port`), NetworkPolicies, and changes to Ingress, nginx or Vite routing.
- Custom bucket boundaries, SLOs and other metrics tuning beyond U2/U3 (Risks 6 and 7).
- Changes to deploy values in `pet-project-deploy`, CI workflows, frontend code and DB migrations.
