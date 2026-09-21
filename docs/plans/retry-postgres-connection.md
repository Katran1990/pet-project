# Plan: Retry Postgres connection on backend startup

Trello card: "Retry Postgres connection on backend startup" (https://trello.com/c/TgRJBRN6/3-retry-postgres-connection-on-backend-startup)

## Goal
If Postgres is not accepting connections yet, the backend waits for it for a set time, trying again at a set interval. It then starts normally, or fails with one clear, actionable error. Both the time and the interval come from environment variables with sensible defaults.

## Acceptance criteria
- [ ] Backend started before Postgres is ready keeps retrying the DB connection for a configurable period instead of exiting immediately
- [ ] Once Postgres becomes available within that period, the app starts normally (Flyway migrations run, /actuator/health is UP)
- [ ] If Postgres is still unavailable after the period, the app fails with a clear error
- [ ] Retry period/interval are configurable via environment variables with sensible defaults
- [ ] Covered by an automated test

## Changes

### Existing state (for context)
- `backend/src/main/resources/application.properties` reads `DB_URL`, `DB_USER` and `DB_PASSWORD`. There is no Flyway or Hikari tuning, so every setting is at its default: `spring.flyway.connect-retries=0` and Hikari `initializationFailTimeout=1`.
- The Spring Boot `DataSource` is a lazily started `HikariDataSource`. The first thing that calls `getConnection()` is `flywayInitializer`. JPA's `entityManagerFactory` depends on `flywayInitializer`, so Flyway always touches the DB first. That is why the startup failure shows up as `FlywaySqlUnableToConnectToDbException`.
- Tests: `PetApplicationTests` and `greeting/GreetingControllerIT` use `@Import(TestcontainersConfiguration.class)`, which declares a `postgres:17` container with `@ServiceConnection`. With `@ServiceConnection`, the connection data comes from a `JdbcConnectionDetails` bean, not from `spring.datasource.*`.
- `infra/helm/pet-project/templates/backend.yaml` has a readiness probe and a liveness probe on `/actuator/health`. The liveness probe has `initialDelaySeconds: 30`, `periodSeconds: 10`, and the default `failureThreshold: 3`. It has no `startupProbe`.

### Approach and why

These options were compared:

| Option | Period configurable | Interval configurable | Logs while waiting | Clear final error | Size |
|---|---|---|---|---|---|
| A. Flyway `spring.flyway.connect-retries` / `connect-retries-interval` | No. It takes a retry **count**. The wait between attempts doubles from 1s (1, 2, 4, 8, ...) up to the interval, so the total time is hard to predict. | Only the upper limit of that doubling wait | WARN per attempt. Each attempt goes through Hikari, which logs an ERROR stack trace for every failed pool start. | Generic `Unable to obtain connection from database`, which does not say that retries ran out | 2 config lines |
| B. Hikari `initialization-fail-timeout` (> 0) | Yes, in ms | **No.** The 1s sleep is fixed inside `HikariPool.checkFailFast`. | Nothing above DEBUG, so the app looks hung while it waits | Same generic Flyway error | 1 config line |
| C. **Custom wait with a time limit, run just before Flyway migrates** | Yes | Yes, fixed interval | One WARN line per attempt, no stack traces | Our own exception plus a `FailureAnalyzer` | About 5 small classes |

**Chosen: C.** It is the only option that meets "configurable period **and** interval" as written. It also gives readable logs and a clear error. A and B are smaller, but A has no time period and B has no interval. Both fail with the same generic Flyway error as today.

How C works:
- **Hook.** A `FlywayMigrationStrategy` bean. `FlywayMigrationInitializer` calls it exactly where Flyway would open its first connection, and JPA already depends on that initializer. The strategy first calls the wait (`awaitDatabase()`) and then `flyway.migrate()`. This is the extension point Spring Boot provides for this. It needs no `BeanFactoryPostProcessor` or `dependsOn` tricks.
- **How it connects.** The wait does **not** go through the Hikari `DataSource`. Each failed lazy pool start logs an ERROR with a stack trace, and we do not want a pool created before the DB is reachable. Instead it uses plain `DriverManager.getConnection(url, user, password)` and closes the connection right away. The URL and credentials come from the **`JdbcConnectionDetails`** bean, not `DataSourceProperties`, so it also works with Testcontainers `@ServiceConnection`.
- **Hikari stays unchanged.** `initializationFailTimeout` stays at its default of 1ms. The pool starts lazily on Flyway's first `getConnection()`, after the DB is known to be reachable, so its fail-fast check passes. We do not set `initialization-fail-timeout`, so there are not two retry layers stacked on each other.
- **What is retried.** A failure is retried only if it looks temporary. That means SQLState class `08` (connection exceptions: refused, unknown host, EOF from docker-proxy) and `57P01`, `57P02`, `57P03` (the server is starting up or shutting down, e.g. "the database system is starting up").
- **What fails at once.** Any other SQLException fails immediately with the same clear error type. Examples: `28P01` (bad password) and `3D000` (unknown database). Waiting 60s for a wrong password helps nobody.
- **Timing.**
  - It tries once right away. If that fails and the time limit (`timeout`) has not passed, it sleeps `interval` and tries again.
  - It stops when the next attempt would start after the time limit. The total wait is at most `timeout` plus one attempt.
  - An attempt that hangs (not refused) is limited by pgjdbc's default `connectTimeout` of 10s; see Risks.
  - Timing uses `System.nanoTime()`.
- **Setting `timeout=0`** means one attempt, which is today's fail-fast behaviour.

### Configuration

Properties use the prefix `app.db.startup-wait`, set in `application.properties`:

| Property | Env variable | Default | Meaning |
|---|---|---|---|
| `app.db.startup-wait.timeout` | `DB_STARTUP_WAIT_TIMEOUT` | `60s` | Total time to wait for Postgres. `0` means a single attempt (fail fast). |
| `app.db.startup-wait.interval` | `DB_STARTUP_WAIT_INTERVAL` | `2s` | Fixed pause between attempts. Must be > 0. |

- Values are `Duration`s. The record components carry `@DurationUnit(ChronoUnit.SECONDS)`, so a plain `90` means 90 seconds (not milliseconds). `500ms`, `2m` and `PT1M` also work.
- **Why these defaults:** a fresh `postgres:17` initdb takes about 3-10s, and a Kubernetes Postgres pod with a new PVC can take tens of seconds. 60s covers both while still failing within about a minute. A 2s interval keeps the logs short (at most about 30 WARN lines).
- There are no secrets in this config.

### 1. Change: `backend/src/main/resources/application.properties`
Add these lines after the datasource block:
```properties
# How long to wait for Postgres on startup before failing (see README "Configuration")
app.db.startup-wait.timeout=${DB_STARTUP_WAIT_TIMEOUT:60s}
app.db.startup-wait.interval=${DB_STARTUP_WAIT_INTERVAL:2s}
```

### 2. New package: `backend/src/main/java/dev/katran/pet/db/`
It sits next to the existing feature package `greeting`.

- **`DatabaseStartupWaitProperties.java`**
  - `@ConfigurationProperties("app.db.startup-wait") record DatabaseStartupWaitProperties(@DurationUnit(SECONDS) Duration timeout, @DurationUnit(SECONDS) Duration interval)`.
  - The compact constructor rejects a null or negative `timeout` and a null, zero or negative `interval` with `IllegalArgumentException`. A bad value then fails binding with a clear message.

- **`DatabaseStartupWait.java`**
  - A plain class with no Spring annotations, so it is unit-testable.
  - Constructor: `(String jdbcUrl, ConnectionAttempt attempt, Duration timeout, Duration interval)`. `ConnectionAttempt` is a nested `@FunctionalInterface` with `void tryConnect() throws SQLException`, so unit tests can pass in a fake.
  - Method `void awaitDatabase()`.
  - Durations in log lines and exception messages are rendered by a small package-private helper `Durations.format(Duration)`: whole seconds as `60s`, sub-second values as `300ms`, otherwise seconds with one decimal (`7.4s`). Never `Duration.toString()` (`PT1M`). Unit tests and the analyzer text rely on this format.
  - Logging goes through SLF4J:
    - INFO at the start: `Waiting up to 60s for database at jdbc:postgresql://postgres:5432/app`.
    - WARN per failed attempt, one line, no stack trace: `Database not available yet (attempt 3, [08001] Connection to postgres:5432 refused...); retrying in 2s, 54s left`.
    - INFO on success: `Database is available after 7.4s (4 attempts)`.
  - The URL is always logged **with the query string removed**, because pgjdbc URLs can carry `?password=...`.
  - It throws `DatabaseUnavailableException` when time runs out or on an error that should not be retried.

- **`DatabaseUnavailableException.java`**
  - `extends RuntimeException`. It keeps `jdbcUrl` (masked), `timeout`, `attempts` and `retryable`, and has the last `SQLException` as its cause.
  - Example message when time runs out: `Database at jdbc:postgresql://postgres:5432/app is not available after waiting 60s (30 attempts). Last error: [08001] Connection to postgres:5432 refused. ...`
  - Example message for an error that is not retried: `Database at ... rejected the connection with a non-retryable error: [28P01] password authentication failed for user "app"`.

- **`DatabaseUnavailableFailureAnalyzer.java`**
  - `extends AbstractFailureAnalyzer<DatabaseUnavailableException>`. It returns a `FailureAnalysis`:
    - Description: the exception message.
    - Action: `Make sure Postgres is running and reachable at <url> and that DB_URL, DB_USER and DB_PASSWORD are correct. If the database needs more time to start, increase DB_STARTUP_WAIT_TIMEOUT (current: 60s).` For errors that are not retried, the sentence about the time limit is left out.
  - Spring Boot then prints the usual short `APPLICATION FAILED TO START / Description / Action` block instead of a nested `BeanCreationException` stack. That is the "clear error". The process exits with a non-zero code because `SpringApplication.run` throws.

- **`DatabaseStartupConfiguration.java`**
  - `@Configuration(proxyBeanMethods = false)` and `@EnableConfigurationProperties(DatabaseStartupWaitProperties.class)`.
  - One bean:
  ```java
  @Bean
  FlywayMigrationStrategy waitForDatabaseThenMigrate(JdbcConnectionDetails db, DatabaseStartupWaitProperties props) {
      return flyway -> {
          new DatabaseStartupWait(db.getJdbcUrl(),
                  () -> DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()).close(),
                  props.timeout(), props.interval()).awaitDatabase();
          flyway.migrate();
      };
  }
  ```
  - Spring Boot 4 split auto-configuration into modules. Imports (checked against the 4.1.1 jars in the Gradle cache): `org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy` (spring-boot-flyway) and `org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails` (spring-boot-jdbc).

### 3. New file: `backend/src/main/resources/META-INF/spring.factories`
```properties
org.springframework.boot.diagnostics.FailureAnalyzer=dev.katran.pet.db.DatabaseUnavailableFailureAnalyzer
```

### 4. Change: `backend/Dockerfile`
Extend the runtime env comment: `DB_URL, DB_USER, DB_PASSWORD, DB_STARTUP_WAIT_TIMEOUT (default 60s), DB_STARTUP_WAIT_INTERVAL (default 2s)`. Comment only, no `ENV` lines.

### 5. Change: `README.md`
- **Configuration table:** add the rows `DB_STARTUP_WAIT_TIMEOUT | 60s` and `DB_STARTUP_WAIT_INTERVAL | 2s`. Add one short paragraph:
  - On startup the backend waits up to the timeout for Postgres, trying again every interval.
  - Authentication or unknown-database errors fail immediately.
  - Setting `0` restores fail-fast.
- **"CI and Docker images" section:** update the line "The backend image reads ... at runtime" to include the two new variables.

### 6. Change (confirmed by the user): `infra/helm/pet-project/templates/backend.yaml`
Add a startup probe so the liveness probe cannot kill the pod while it is still waiting for the DB:
```yaml
startupProbe:
  httpGet: { path: /actuator/health, port: 8080 }
  periodSeconds: 5
  failureThreshold: 24   # 120s >= 60s DB wait + normal startup
```
Leave the existing liveness and readiness probes unchanged (the startup probe already disables them until the app is up). Verify with `helm lint infra/helm/pet-project` if helm is available.

### 7. Database / Flyway / frontend
No migration, no Flyway config change and no frontend change.

## Tests
Done means `cd backend && ./gradlew test` is green. CI (`./gradlew build`) runs the same tests, and Docker is available there.

### Unit: `backend/src/test/java/dev/katran/pet/db/DatabaseStartupWaitTest.java`
No Spring and no Docker. It uses a fake `ConnectionAttempt` and small durations (timeout 300ms, interval 50ms), so it runs in under a second.

| Test | Proves |
|---|---|
| `returnsImmediatelyWhenFirstAttemptSucceeds`: 1 attempt, returns in < interval | No regression when the DB is already up |
| `retriesTransientFailuresUntilDatabaseIsAvailable`: fake throws `SQLException("refused", "08001")` twice, then succeeds. Returns; 3 attempts. | AC1, AC2 (logic) |
| `retriesWhileServerIsStartingUp`: SQLState `57P03`, then success | AC1 (initdb case) |
| `failsAfterTimeoutWithClearMessage`: always `08001`. Throws `DatabaseUnavailableException`. The message has the URL, `300ms`, the attempt count and the last error text. The cause is the last `SQLException`. Elapsed time is ≥ timeout and < timeout + interval + slack. | AC3 |
| `failsImmediatelyOnNonRetryableError`: `28P01` → throws after exactly 1 attempt | AC3 (clear error, no pointless wait) |
| `zeroTimeoutMeansSingleAttempt` | AC4 (fail-fast escape hatch) |
| `masksQueryStringInUrl`: URL `...?password=secret` → the message and exception do not contain `secret` | Secrets rule |

### Unit: `backend/src/test/java/dev/katran/pet/db/DatabaseUnavailableFailureAnalyzerTest.java`
- `analyze(new BeanCreationException("flywayInitializer", new DatabaseUnavailableException(...)))` returns an analysis. The description equals the message, and the action mentions `DB_URL` and `DB_STARTUP_WAIT_TIMEOUT`.
- For an error that is not retried, the action does not mention the timeout.

This proves AC3 (clear error).

### Unit: `backend/src/test/java/dev/katran/pet/db/DatabaseStartupWaitPropertiesTest.java`
- Binds with `new Binder(new MapConfigurationPropertySource(...))`.
- `"90"` becomes 90 seconds and `"500ms"` becomes 500ms.
- `interval=0` and a negative `timeout` are rejected.

This proves AC4 (value format and validation).

### Integration: `backend/src/test/java/dev/katran/pet/db/DatabaseStartupRetryIT.java`
It uses `@ExtendWith(OutputCaptureExtension.class)` (no `@Testcontainers`: the container is started only inside test 1, so test 2 needs no Docker). It **does not** use `@SpringBootTest` or `@Import(TestcontainersConfiguration.class)`. Each test starts the real app through a private helper `start(String... args)`, inside try-with-resources. This lets the test measure how long startup takes and check a failed startup (`SpringApplication.run` throws). A `@SpringBootTest` context is loaded by the TestContext framework at a time the test does not control. That makes the start time hard to measure, and the failure case cannot use `@SpringBootTest` at all. So both cases use the same mechanism.

Configuration is passed as command-line args named after the **env variables** (`--DB_URL=...`, `--DB_STARTUP_WAIT_TIMEOUT=...`). The `${DB_...}` placeholders in `application.properties` resolve from any property source, so the tests prove the env-variable wiring (AC4) end to end.

**Keeping test configuration out of the component scan (required).**
- The test runtime classpath contains `dev.katran.pet.TestcontainersConfiguration`. It is a top-level `@TestConfiguration` in the root package, so `@SpringBootApplication` scans it.
- The `@SpringBootApplication` component scan uses the plain `TypeExcludeFilter` (`org.springframework.boot.context.TypeExcludeFilter`). That filter only delegates to `TypeExcludeFilter` beans registered in the context. Spring Boot's `TestTypeExcludeFilter` is registered only by the TestContext framework (`ExcludeFilterContextCustomizer`), and a plain `SpringApplicationBuilder` bypasses that framework.
- Without an exclusion, the app would start a second `postgres:17` container. Its `@ServiceConnection` `JdbcConnectionDetails` would then replace `DB_URL`, because `PropertiesJdbcConnectionDetails` is only used when no other `JdbcConnectionDetails` bean exists. Test 1 would never retry and test 2 would start successfully.
- So the helper registers a delegate filter before the context refreshes:
  ```java
  private static ConfigurableApplicationContext start(String... args) {
      return new SpringApplicationBuilder(PetApplication.class)
              .initializers(ctx -> ctx.getBeanFactory()
                      .registerSingleton("excludeTestComponents", new ExcludeTestComponents()))
              .run(args);
  }

  /** Same rule as Boot's TestTypeExcludeFilter: skip @TestComponent / @TestConfiguration classes. */
  static final class ExcludeTestComponents extends TypeExcludeFilter {
      @Override
      public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
          return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
      }
      @Override public boolean equals(Object o) { return o != null && o.getClass() == getClass(); }
      @Override public int hashCode() { return getClass().hashCode(); }
  }
  ```
- Initializers run before `ConfigurationClassPostProcessor` does the component scan, so the singleton is already registered when the scan's `TypeExcludeFilter` looks up its delegates.
- `isAnnotated` also sees meta-annotations, so `@TestConfiguration` matches (it is meta-annotated with `@TestComponent`).
- `equals`/`hashCode` must be overridden, because the base `TypeExcludeFilter` requires it of subclasses.
- Why a custom filter instead of reusing `TestTypeExcludeFilter`: its visibility and constructor are an internal detail of spring-boot-test, so the plan does not depend on them.

1. **`startsNormallyWhenPostgresBecomesAvailableLate`** (AC1, AC2, AC4)
   - The test starts its own `PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))` locally in the test method, in try-with-resources (declared first, so it is closed last, after the app context and the proxy). It is a local variable, not a Spring bean, so the app never sees it. The DB is fresh, so the Flyway migration can be observed.
   - **Simulating a late Postgres:**
     - Take a free local port P: bind `new ServerSocket(0)`, read the port, close it.
     - Start `DelayedTcpProxy(P -> postgres.getHost():postgres.getMappedPort(5432), delay = 3s)`.
     - Until the proxy starts listening, connections to `localhost:P` are refused, exactly like a Postgres that is not ready yet. After 3s the proxy starts listening and forwards traffic.
   - Call `start(...)` with `--server.port=0 --DB_URL=jdbc:postgresql://localhost:P/<postgres.getDatabaseName()> --DB_USER=<postgres.getUsername()> --DB_PASSWORD=<postgres.getPassword()> --DB_STARTUP_WAIT_TIMEOUT=30s --DB_STARTUP_WAIT_INTERVAL=500ms`. The credentials are the ones the container generates, not hardcoded.
   - Asserts:
     - The captured output contains the INFO line `Waiting up to 30s for database at jdbc:postgresql://localhost:P/`. This proves the URL from `DB_URL` was used, not a `@ServiceConnection` URL, and that the timeout was read from `DB_STARTUP_WAIT_TIMEOUT`.
     - `context.getBean(JdbcConnectionDetails.class).getJdbcUrl()` contains `localhost:P`, and `context.getBeanNamesForType(PostgreSQLContainer.class)` is empty. This guards against the test-configuration scan problem coming back.
     - The start took ≥ 3s.
     - The captured output has at least one `Database not available yet` line, followed by `Database is available after`.
     - `GET /actuator/health` on `local.server.port` returns 200 with `$.status == "UP"`.
     - `GET /api/greeting` returns 200 with `$.message == "Hello from Postgres"` (JSON `GreetingResponse`, same as in `GreetingControllerIT`). The row is seeded by `V1__`, so this proves Flyway ran.
2. **`failsWithClearErrorWhenPostgresNeverBecomesAvailable`** (AC3, AC4)
   - No Docker is needed: free port P with nothing listening.
   - Call `start(...)` with `--server.port=0 --DB_URL=jdbc:postgresql://localhost:P/app --DB_USER=app --DB_PASSWORD=app --DB_STARTUP_WAIT_TIMEOUT=2s --DB_STARTUP_WAIT_INTERVAL=250ms`. These dummy values are never accepted by any server; they are not real credentials.
   - Asserts:
     - `start(...)` throws, and its cause chain contains a `DatabaseUnavailableException` whose message contains `localhost:P` and `2s`. Do **not** use `rootCause()`: the root is the pgjdbc `ConnectException` under the last `SQLException`. Search the chain instead, e.g. `Stream.iterate(ex, Objects::nonNull, Throwable::getCause).filter(DatabaseUnavailableException.class::isInstance).findFirst()`.
     - The captured output contains `Waiting up to 2s for database at jdbc:postgresql://localhost:P/`.
     - Elapsed time is ≥ 2s (it did not fail right away) and < timeout + 10s (it did not hang). The upper bound is loose because the measured time includes context startup before Flyway on a possibly slow CI JVM.
     - The captured output contains `APPLICATION FAILED TO START` and `DB_STARTUP_WAIT_TIMEOUT`, which proves the `FailureAnalyzer` is registered through `META-INF/spring.factories`.

Test helper: `backend/src/test/java/dev/katran/pet/db/DelayedTcpProxy.java`
- Implements `AutoCloseable`, about 50 lines. It is a plain class with no Spring annotations, so the component scan ignores it.
- One virtual thread sleeps for `delay`, binds `ServerSocket(P, 50, InetAddress.getLoopbackAddress())` and accepts connections.
- For each connection it opens a socket to the target and pipes both directions on two virtual threads with `InputStream.transferTo`.
- `close()` closes the server socket and all open sockets. The test closes the application context first and the proxy second (try-with-resources order: proxy declared first).
- It adds no new dependency. Toxiproxy or a fixed-host-port container would need an extra module or image, or would be more flaky.

### Regression
`PetApplicationTests` and `GreetingControllerIT` stay unchanged. With `@ServiceConnection` the container is already up, so the first attempt succeeds and startup time is unaffected.

### Expected extra test time
About 3s delay + about 5s app start + about 3s container start for IT 1, and about 2s wait + about 2s app start for IT 2 (no container). The unit tests take under 1s.

## Risks and open questions
1. **Kubernetes liveness probe vs. the 60s wait (resolved: add the startupProbe, change 6).**
   - Tomcat only starts listening after the context refresh, so `/actuator/health` is unreachable while the app waits.
   - With the current probe (first check at 30s, then every 10s, `failureThreshold: 3`), kubelet restarts the container after about 50s. In K8s that silently caps the wait below the 60s default, and the app's own clear error is never printed.
   - Recommendation: add the `startupProbe` from change 6.
   - Alternative: keep the chart as it is and document that `DB_STARTUP_WAIT_TIMEOUT` must stay below about 50s in K8s.
2. **The wait is tied to Flyway.** The hook is `FlywayMigrationStrategy`. If Flyway is ever disabled (`spring.flyway.enabled=false`), or someone defines another `FlywayMigrationStrategy` bean, the wait silently disappears. That is acceptable because Flyway owns the schema. A comment in `DatabaseStartupConfiguration` will state this.
3. **Hanging attempts.** If the host drops packets instead of refusing them, one attempt can block for up to pgjdbc's `connectTimeout` (10s default), so the total can exceed `timeout` by up to about 10s. This is acceptable. It can be tuned with `?connectTimeout=` in `DB_URL` if needed.
4. **Small gap after the wait.** The DB could become unreachable between a successful wait and Flyway's first real connection. Flyway would then fail with its generic error, as it does today. This is very unlikely during startup and is not handled.
5. **Choice of which errors are retried.** Only SQLStates `08xxx` and `57P01`-`57P03` are retried. If some environment reports "not ready" with a different state, it would fail fast instead of waiting. This is easy to extend.
6. **Test configuration leaking into the IT's component scan.** `DatabaseStartupRetryIT` starts the app with a plain `SpringApplicationBuilder`, which does not register Boot's `TestTypeExcludeFilter`. It depends on its own `ExcludeTestComponents` filter to keep `TestcontainersConfiguration` (and any future `@TestConfiguration` in `dev.katran.pet`) out of the context. If someone later adds a test-only `@Component` or `@Configuration` without `@TestComponent`/`@TestConfiguration` under `dev.katran.pet`, it would still be scanned. The IT's check that no container bean exists and the `Waiting up to ... localhost:P` assertion would catch this for database configuration.
7. **Free-port race in the IT.** The port is picked by bind-then-close and bound again 3s later. Another process could take it in between. This is very unlikely in CI.
8. **The card's wording "retry period/interval".** It is read as: the total wait time, plus a fixed pause between attempts, with no exponential backoff. If backoff is wanted, it could be added later as a third property.

## Out of scope
- Handling a DB that becomes unavailable **at runtime** (Hikari already handles reconnects for pooled connections).
- Health/readiness semantics (for example a DB-down readiness group), and a docker compose file.
- Changes to deploy values in the separate `pet-project-deploy` repository.
- Exponential backoff and making the wait itself work without Flyway.
- Frontend changes and DB migrations.
- Any change to the `initContainer` / `depends_on` start ordering. The point of this card is to not need it.
