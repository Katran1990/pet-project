package dev.katran.pet.metrics;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// git.properties comes from the gradle-git-properties plugin (see gitProperties in build.gradle)
// and feeds /actuator/info, which the post-deploy e2e workflow polls for the deployed commit
// (ADR 0022).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfoEndpointIT {

	@LocalServerPort
	private int port;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	@Test
	void gitPropertiesHoldsOnlyCommitIdAbbreviationAndTime() throws Exception {
		ClassPathResource resource = new ClassPathResource("git.properties");
		assertThat(resource.exists())
				.as("git.properties missing: build from a git clone, or pass -PgitDir (see gitProperties in build.gradle)")
				.isTrue();

		Properties properties = new Properties();
		try (InputStream in = resource.getInputStream()) {
			properties.load(in);
		}

		assertThat(properties.stringPropertyNames())
				.containsExactlyInAnyOrderElementsOf(Set.of("git.commit.id", "git.commit.id.abbrev", "git.commit.time"));
	}

	@Test
	void infoEndpointReportsTheCommitWithoutBranch() throws Exception {
		Properties properties = new Properties();
		try (InputStream in = new ClassPathResource("git.properties").getInputStream()) {
			properties.load(in);
		}

		client.get()
				.uri("/actuator/info")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.git.commit.id")
				.value(String.class, id -> {
					assertThat(id).matches("^[0-9a-f]{7,40}$");
					assertThat(id).isEqualTo(properties.getProperty("git.commit.id.abbrev"));
				})
				.jsonPath("$.git.commit.time")
				.exists()
				.jsonPath("$.git.branch")
				.doesNotExist();
	}

	@Test
	void gitPropertiesValuesAreConsistent() throws Exception {
		Properties properties = new Properties();
		try (InputStream in = new ClassPathResource("git.properties").getInputStream()) {
			properties.load(in);
		}
		String full = properties.getProperty("git.commit.id");
		String abbrev = properties.getProperty("git.commit.id.abbrev");
		assertThat(full).matches("^[0-9a-f]{40}$");
		assertThat(abbrev).matches("^[0-9a-f]{7,40}$");
		assertThat(full).startsWith(abbrev);
		assertThat(properties.getProperty("git.commit.time")).isNotBlank();
	}

	@Test
	void infoEndpointExposesNoAuthorOrMessageData() throws Exception {
		String body = client.get()
				.uri("/actuator/info")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();
		assertThat(body).doesNotContain("user").doesNotContain("message").doesNotContain("email").doesNotContain("branch");
	}

}
