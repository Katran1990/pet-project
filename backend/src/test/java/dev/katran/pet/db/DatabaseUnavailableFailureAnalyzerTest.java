package dev.katran.pet.db;

import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DatabaseUnavailableFailureAnalyzer}. Proves AC3 (a clear final error):
 * Spring Boot's "APPLICATION FAILED TO START / Description / Action" block is built from this
 * analyzer, so its description and action text are what the operator actually sees.
 */
class DatabaseUnavailableFailureAnalyzerTest {

	private final DatabaseUnavailableFailureAnalyzer analyzer = new DatabaseUnavailableFailureAnalyzer();

	@Test
	void describesTimeoutErrorAndSuggestsIncreasingTheTimeout() {
		DatabaseUnavailableException cause = new DatabaseUnavailableException(
				"jdbc:postgresql://postgres:5432/app", Duration.ofSeconds(60), 30, true,
				new SQLException("Connection to postgres:5432 refused", "08001"));
		BeanCreationException failure = new BeanCreationException("flywayInitializer", cause);

		FailureAnalysis analysis = analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).isEqualTo(cause.getMessage());
		assertThat(analysis.getAction()).contains("DB_URL").contains("DB_STARTUP_WAIT_TIMEOUT");
	}

	@Test
	void describesNonRetryableErrorWithoutMentioningTheTimeout() {
		DatabaseUnavailableException cause = new DatabaseUnavailableException(
				"jdbc:postgresql://postgres:5432/app", Duration.ofSeconds(60), 1, false,
				new SQLException("password authentication failed for user \"app\"", "28P01"));
		BeanCreationException failure = new BeanCreationException("flywayInitializer", cause);

		FailureAnalysis analysis = analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).isEqualTo(cause.getMessage());
		assertThat(analysis.getAction()).contains("DB_URL").doesNotContain("DB_STARTUP_WAIT_TIMEOUT");
	}

	@Test
	void returnsNullWhenTheFailureIsUnrelated() {
		FailureAnalysis analysis = analyzer.analyze(new RuntimeException("unrelated failure"));

		assertThat(analysis).isNull();
	}

}
