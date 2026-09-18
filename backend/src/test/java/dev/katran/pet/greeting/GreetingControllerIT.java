package dev.katran.pet.greeting;

import java.util.HashMap;
import java.util.Map;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingControllerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private GreetingRepository greetings;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void returnsTheGreetingStoredByTheFlywayMigration() {
		client.get()
				.uri("/api/greeting")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.message")
				.isEqualTo("Hello from Postgres");
	}

	@Test
	void createsGreetingAndReturns201() {
		EntityExchangeResult<GreetingResponse> result = client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("message", "Hi there"))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(GreetingResponse.class)
				.returnResult();

		GreetingResponse body = result.getResponseBody();
		assertThat(body).isNotNull();
		assertThat(body.id()).isNotNull();
		assertThat(body.message()).isEqualTo("Hi there");

		String location = result.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull().endsWith("/api/greetings/" + body.id());

		assertThat(greetings.findById(body.id()))
				.isPresent()
				.get()
				.extracting(Greeting::getMessage)
				.isEqualTo("Hi there");
	}

	@Test
	void acceptsMessageOfExactly200Chars() {
		String message = "a".repeat(200);

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("message", message))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody()
				.jsonPath("$.id")
				.exists()
				.jsonPath("$.message")
				.isEqualTo(message);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "\t\n" })
	void rejectsBlankMessage(String message) {
		long before = greetings.count();

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("message", message))
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.path")
				.isEqualTo("/api/greetings");

		assertThat(greetings.count()).isEqualTo(before);
	}

	@Test
	void rejectsMissingMessage() {
		long before = greetings.count();

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}")
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.path")
				.isEqualTo("/api/greetings");

		assertThat(greetings.count()).isEqualTo(before);
	}

	@Test
	void rejectsNullMessage() {
		long before = greetings.count();

		Map<String, String> requestBody = new HashMap<>();
		requestBody.put("message", null);

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(requestBody)
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.path")
				.isEqualTo("/api/greetings");

		assertThat(greetings.count()).isEqualTo(before);
	}

	@Test
	void rejectsMessageLongerThan200Chars() {
		long before = greetings.count();
		String message = "a".repeat(201);

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("message", message))
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.path")
				.isEqualTo("/api/greetings");

		assertThat(greetings.count()).isEqualTo(before);
	}

	@Test
	void rejectsMalformedJson() {
		long before = greetings.count();

		client.post()
				.uri("/api/greetings")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"message\":")
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.path")
				.isEqualTo("/api/greetings");

		assertThat(greetings.count()).isEqualTo(before);
	}

}
