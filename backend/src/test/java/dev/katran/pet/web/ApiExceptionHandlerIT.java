package dev.katran.pet.web;

import java.util.Map;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiExceptionHandlerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void exactlyOneResponseEntityExceptionHandler() {
		Map<String, ResponseEntityExceptionHandler> beans = context.getBeansOfType(ResponseEntityExceptionHandler.class);

		assertThat(beans).hasSize(1);
		assertThat(beans.values().iterator().next()).isInstanceOf(ApiExceptionHandler.class);
	}

	@Test
	void unknownRouteIsProblemDetails() {
		client.get()
				.uri("/api/does-not-exist")
				.exchange()
				.expectStatus()
				.isNotFound()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
	}

}
