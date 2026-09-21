package dev.katran.pet.db;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a {@link DatabaseUnavailableException} into the short "APPLICATION FAILED TO START /
 * Description / Action" block instead of a nested {@code BeanCreationException} stack trace.
 * Registered through {@code META-INF/spring.factories}.
 */
public class DatabaseUnavailableFailureAnalyzer extends AbstractFailureAnalyzer<DatabaseUnavailableException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, DatabaseUnavailableException cause) {
		String action = "Make sure Postgres is running and reachable at %s and that DB_URL, DB_USER and DB_PASSWORD are correct."
				.formatted(cause.jdbcUrl());
		if (cause.retryable()) {
			action += " If the database needs more time to start, increase DB_STARTUP_WAIT_TIMEOUT (current: %s)."
					.formatted(Durations.format(cause.timeout()));
		}
		return new FailureAnalysis(cause.getMessage(), action, cause);
	}

}
