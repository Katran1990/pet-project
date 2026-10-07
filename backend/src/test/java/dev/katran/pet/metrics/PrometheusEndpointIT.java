package dev.katran.pet.metrics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Spring Boot tests switch metrics export off by default; without this annotation there is no
// PrometheusMeterRegistry and /actuator/prometheus returns 404. Do not remove it as "unused".
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusEndpointIT {

	@LocalServerPort
	private int port;

	@Value("${spring.application.name}")
	private String applicationName;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void exposesHttpServerRequestAndJvmMemoryMetricsInPrometheusTextFormat() {
		client.get().uri("/api/greeting").exchange().expectStatus().isOk();

		String applicationTag = "application=\"" + applicationName + "\"";

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String body = client.get()
					.uri("/actuator/prometheus")
					.accept(MediaType.TEXT_PLAIN)
					.exchange()
					.expectStatus()
					.isOk()
					.expectHeader()
					.contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
					.expectBody(String.class)
					.returnResult()
					.getResponseBody();

			assertThat(body).isNotNull();
			assertThat(body).contains("http_server_requests_seconds").contains("jvm_memory_used_bytes");

			List<String> lines = body.lines().toList();

			assertThat(lines)
					.as("real request sample for /api/greeting, carrying the common application tag")
					.anySatisfy(line -> assertThat(line)
							.startsWith("http_server_requests_seconds_count{")
							.contains("uri=\"/api/greeting\"")
							.contains(applicationTag));

			assertThat(lines)
					.as("JVM gauge, carrying the common application tag")
					.anySatisfy(line -> assertThat(line)
							.startsWith("jvm_memory_used_bytes{")
							.contains(applicationTag));

			assertThat(lines)
					.as("percentile histogram buckets for /api/greeting")
					.anySatisfy(line -> assertThat(line)
							.startsWith("http_server_requests_seconds_bucket{")
							.contains("uri=\"/api/greeting\"")
							.contains("le=\""));
		});
	}

	@Test
	void exposesOnlyHealthInfoAndPrometheusOverHttp() {
		client.get()
				.uri("/actuator")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$._links.health")
				.exists()
				.jsonPath("$._links.info")
				.exists()
				.jsonPath("$._links.prometheus")
				.exists()
				.jsonPath("$._links.metrics")
				.doesNotExist()
				.jsonPath("$._links.env")
				.doesNotExist()
				.jsonPath("$._links.configprops")
				.doesNotExist();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/..;/actuator/prometheus", "/api/%2e%2e;/actuator/prometheus" })
	void pathParameterTraversalDoesNotReachActuator(String path) throws Exception {
		// Tomcat strips ";" path parameters before it resolves "..", so nginx's "location /api/" could
		// in theory let /api/..;/actuator/prometheus through to /actuator/prometheus. Verified: the
		// backend answers 404, so no nginx rule is needed (ADR 0022). Regression guard.
		// URI.create and HttpClient keep the path as written (no dot-segment removal).
		HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
						HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
	}

	@Test
	void probeHttpClientSendsTraversalPathsUnchanged() throws Exception {
		// Guards the probe above against passing vacuously: the request line on the wire must carry
		// the path exactly as written (no dot-segment removal by URI or HttpClient).
		for (String path : List.of("/api/..;/actuator/prometheus", "/api/%2e%2e;/actuator/prometheus")) {
			try (ServerSocket server = new ServerSocket(0)) {
				server.setSoTimeout(5000);
				Thread sender = Thread.ofVirtual().start(() -> {
					try {
						HttpClient.newHttpClient()
								.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getLocalPort() + path))
										.timeout(Duration.ofSeconds(2))
										.GET()
										.build(), HttpResponse.BodyHandlers.discarding());
					}
					catch (Exception ex) {
						// the fake server never answers; only the request line matters
					}
				});
				try (var socket = server.accept()) {
					socket.setSoTimeout(5000);
					String requestLine = new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
					assertThat(requestLine).isEqualTo("GET " + path + " HTTP/1.1");
				}
				sender.join();
			}
		}
	}

	@Test
	void positiveControlRawHttpClientSeesMetricsOnTheRealActuatorPath() throws Exception {
		// Same client and same body check as the traversal probe, but on the real path: proves the
		// "no jvm_memory_used_bytes" assertion there can fail when metrics are served.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			HttpResponse<String> response = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
							.GET()
							.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("jvm_memory_used_bytes");
		});
	}

}
