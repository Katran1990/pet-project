package dev.katran.pet.web;

import java.sql.SQLException;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

	// U1
	@Test
	void budgetLimitUniqueViolationIs409() {
		DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key",
				new ConstraintViolationException("duplicate key",
						new SQLException("duplicate key", "23505"), "uq_budget_limit_category_month"));

		ProblemDetail result = new ApiExceptionHandler().handleDataIntegrityViolation(ex);

		assertThat(result.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
		assertThat(result.getDetail()).isEqualTo("Budget limit for this category and month already exists");
	}

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
