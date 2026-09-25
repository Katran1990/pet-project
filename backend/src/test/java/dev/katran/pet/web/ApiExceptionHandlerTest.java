package dev.katran.pet.web;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

	@Test
	void unexpectedExceptionIsGeneric500() {
		ProblemDetail result = new ApiExceptionHandler()
				.handleUnexpected(new IllegalStateException("secret SQL detail"));

		assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(result.getTitle()).isEqualTo("Internal Server Error");
		assertThat(result.getDetail()).isEqualTo("Unexpected error");
		assertThat(result.getDetail()).doesNotContain("secret SQL detail");

		Map<String, Object> properties = result.getProperties();
		if (properties != null) {
			assertThat(properties.values())
					.noneMatch(value -> String.valueOf(value).contains("secret SQL detail"));
		}
	}

}
