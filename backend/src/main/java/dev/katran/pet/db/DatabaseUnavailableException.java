package dev.katran.pet.db;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Thrown by {@link DatabaseStartupWait} when Postgres is still unavailable after the configured
 * timeout, or when a connection attempt fails with an error that is not worth retrying.
 */
public class DatabaseUnavailableException extends RuntimeException {

	private final String jdbcUrl;
	private final Duration timeout;
	private final int attempts;
	private final boolean retryable;

	DatabaseUnavailableException(String jdbcUrl, Duration timeout, int attempts, boolean retryable, SQLException cause) {
		super(buildMessage(jdbcUrl, timeout, attempts, retryable, cause), cause);
		this.jdbcUrl = jdbcUrl;
		this.timeout = timeout;
		this.attempts = attempts;
		this.retryable = retryable;
	}

	private static String buildMessage(String jdbcUrl, Duration timeout, int attempts, boolean retryable, SQLException cause) {
		if (retryable) {
			return "Database at %s is not available after waiting %s (%d attempts). Last error: %s"
					.formatted(jdbcUrl, Durations.format(timeout), attempts, describe(cause));
		}
		return "Database at %s rejected the connection with a non-retryable error: %s"
				.formatted(jdbcUrl, describe(cause));
	}

	/** Renders a {@link SQLException} as {@code [SQLSTATE] message}, the form used throughout this package. */
	static String describe(SQLException e) {
		return "[%s] %s".formatted(e.getSQLState(), e.getMessage());
	}

	/** JDBC URL with the query string removed, so it never contains credentials such as {@code ?password=...}. */
	public String jdbcUrl() {
		return jdbcUrl;
	}

	public Duration timeout() {
		return timeout;
	}

	public int attempts() {
		return attempts;
	}

	/** {@code true} when the last error was of a kind that is retried but the timeout ran out anyway. */
	public boolean retryable() {
		return retryable;
	}

}
