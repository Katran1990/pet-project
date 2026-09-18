package dev.katran.pet.greeting;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingControllerIT {

	@LocalServerPort
	private int port;

	@Test
	void returnsTheGreetingStoredByTheFlywayMigration() {
		RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();

		client.get()
				.uri("/api/greeting")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(GreetingResponse.class)
				.isEqualTo(new GreetingResponse("Hello from Postgres"));
	}

}
