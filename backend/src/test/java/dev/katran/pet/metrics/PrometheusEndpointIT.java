package dev.katran.pet.metrics;

import java.time.Duration;
import java.util.List;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

}
