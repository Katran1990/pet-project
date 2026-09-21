package dev.katran.pet.db;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.katran.pet.PetApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests proving that the backend retries the Postgres connection on startup instead
 * of failing immediately (AC1), starts normally once Postgres becomes available within the wait
 * period (AC2), fails with a clear error once the period runs out (AC3), and that the period and
 * interval are read from the {@code DB_STARTUP_WAIT_TIMEOUT} / {@code DB_STARTUP_WAIT_INTERVAL}
 * environment variables (AC4).
 *
 * <p>Neither {@code @Testcontainers} nor {@code @SpringBootTest} is used. Each test starts the
 * real application through {@link #start(String...)}, inside try-with-resources, so the test can
 * measure how long startup took and observe a failed startup ({@code SpringApplication.run}
 * throws). The Postgres container is started only inside test 1, as a local variable, so test 2
 * needs no Docker.
 */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseStartupRetryIT {

	@Test
	void startsNormallyWhenPostgresBecomesAvailableLate(CapturedOutput output) throws Exception {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))) {
			postgres.start();

			int port = freePort();
			// The proxy binds nothing until open() is called, so every connection attempted before
			// that is refused, exactly like a Postgres that is not ready yet. The app is started on
			// its own thread and we poll the captured output for a second failed attempt before
			// opening the proxy, so the test is deterministic regardless of how long context startup
			// before the Flyway hook takes on a given machine.
			try (LateTcpProxy proxy = new LateTcpProxy(port, postgres.getHost(), postgres.getMappedPort(5432))) {

				ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
				Future<ConfigurableApplicationContext> future = null;
				try {
					Callable<ConfigurableApplicationContext> startApp = () -> start(
							"--server.port=0",
							"--DB_URL=jdbc:postgresql://localhost:" + port + "/" + postgres.getDatabaseName(),
							"--DB_USER=" + postgres.getUsername(),
							"--DB_PASSWORD=" + postgres.getPassword(),
							"--DB_STARTUP_WAIT_TIMEOUT=30s",
							"--DB_STARTUP_WAIT_INTERVAL=500ms");
					future = executor.submit(startApp);

					awaitOutputContains(output, "Database not available yet (attempt 2", Duration.ofSeconds(60),
							Duration.ofMillis(500));

					int outputLengthAtOpen = output.getOut().length();
					proxy.open();

					ConfigurableApplicationContext context = future.get(60, TimeUnit.SECONDS);
					// Ownership of the context has been transferred to this try block; the outer finally
					// below must not close it again.
					future = null;
					try {
						// The proxy refused every connection until open() was called above, so the
						// "Database is available after" line can only have been logged after that point.
						assertThat(output.getOut().indexOf("Database is available after"))
								.isGreaterThanOrEqualTo(outputLengthAtOpen);

						assertThat(output.getOut())
								.contains("Waiting up to 30s for database at jdbc:postgresql://localhost:" + port + "/");

						assertThat(context.getBean(JdbcConnectionDetails.class).getJdbcUrl()).contains("localhost:" + port);
						assertThat(context.getBeanNamesForType(PostgreSQLContainer.class)).isEmpty();

						List<String> notAvailableLines = output.getOut().lines()
								.filter(line -> line.contains("Database not available yet"))
								.toList();
						assertThat(notAvailableLines.size()).isGreaterThanOrEqualTo(2);

						int lastNotAvailableIndex = output.getOut().lastIndexOf("Database not available yet");
						int availableIndex = output.getOut().indexOf("Database is available after");
						assertThat(availableIndex).isGreaterThan(lastNotAvailableIndex);

						int localPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
						RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + localPort).build();

						client.get()
								.uri("/actuator/health")
								.exchange()
								.expectStatus()
								.isOk()
								.expectBody()
								.jsonPath("$.status")
								.isEqualTo("UP");

						client.get()
								.uri("/api/greeting")
								.exchange()
								.expectStatus()
								.isOk()
								.expectBody()
								.jsonPath("$.message")
								.isEqualTo("Hello from Postgres");
					}
					finally {
						context.close();
					}
				}
				finally {
					// If awaitOutputContains() timed out or future.get() above threw (e.g. timed out),
					// the application may still finish starting right afterwards; close its context so
					// it is not leaked.
					if (future != null && future.isDone() && !future.isCancelled()) {
						try {
							future.get().close();
						}
						catch (Exception e) {
							// Startup failed or was interrupted; there is no context to close.
						}
					}
					executor.shutdownNow();
				}
			}
		}
	}

	/** Polls {@code output.getOut()} until it contains {@code text}, or fails after {@code deadline}. */
	private static void awaitOutputContains(CapturedOutput output, String text, Duration deadline, Duration pollInterval)
			throws InterruptedException {
		Instant giveUpAt = Instant.now().plus(deadline);
		while (!output.getOut().contains(text)) {
			if (Instant.now().isAfter(giveUpAt)) {
				fail("Timed out after %s waiting for captured output to contain %s. Output so far:%n%s"
						.formatted(deadline, text, output.getOut()));
			}
			Thread.sleep(pollInterval);
		}
	}

	@Test
	void failsWithClearErrorWhenPostgresNeverBecomesAvailable(CapturedOutput output) throws IOException {
		int port = freePort();

		Instant begin = Instant.now();
		Throwable startupFailure = catchThrowable(() -> start(
				"--server.port=0",
				"--DB_URL=jdbc:postgresql://localhost:" + port + "/app",
				"--DB_USER=app",
				"--DB_PASSWORD=app",
				"--DB_STARTUP_WAIT_TIMEOUT=2s",
				"--DB_STARTUP_WAIT_INTERVAL=250ms"));
		Duration elapsed = Duration.between(begin, Instant.now());

		assertThat(startupFailure).isNotNull();

		Optional<DatabaseUnavailableException> unavailable = Stream
				.iterate(startupFailure, Objects::nonNull, Throwable::getCause)
				.filter(DatabaseUnavailableException.class::isInstance)
				.map(DatabaseUnavailableException.class::cast)
				.findFirst();
		assertThat(unavailable).isPresent();
		assertThat(unavailable.get().getMessage()).contains("localhost:" + port).contains("2s");

		assertThat(output.getOut())
				.contains("Waiting up to 2s for database at jdbc:postgresql://localhost:" + port + "/");

		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(2)).isLessThan(Duration.ofSeconds(12));

		assertThat(output.getOut()).contains("APPLICATION FAILED TO START").contains("DB_STARTUP_WAIT_TIMEOUT");
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static ConfigurableApplicationContext start(String... args) {
		return new SpringApplicationBuilder(PetApplication.class)
				.initializers(ctx -> ctx.getBeanFactory()
						.registerSingleton("excludeTestComponents", new ExcludeTestComponents()))
				.run(args);
	}

	/** Same rule as Boot's {@code TestTypeExcludeFilter}: skip {@code @TestComponent} / {@code @TestConfiguration} classes. */
	static final class ExcludeTestComponents extends TypeExcludeFilter {

		@Override
		public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
			return reader.getAnnotationMetadata().isAnnotated(TestComponent.class.getName());
		}

		@Override
		public boolean equals(Object o) {
			return o != null && o.getClass() == getClass();
		}

		@Override
		public int hashCode() {
			return getClass().hashCode();
		}

	}

}
