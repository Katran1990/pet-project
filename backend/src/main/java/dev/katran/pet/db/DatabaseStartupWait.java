package dev.katran.pet.db;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits for Postgres to accept connections during application startup, trying again at a fixed
 * interval until it succeeds or a time limit runs out. Plain class with no Spring annotations,
 * so it is unit-testable without a Spring context.
 *
 * <p>A failure is retried only if it looks temporary: SQLState class {@code 08} (connection
 * exceptions) and {@code 57P01}/{@code 57P02}/{@code 57P03} (server starting up or shutting
 * down). Any other {@link SQLException} fails immediately, since waiting out the timeout would
 * not help, for example on a wrong password or an unknown database.
 */
public class DatabaseStartupWait {

	private static final Logger log = LoggerFactory.getLogger(DatabaseStartupWait.class);

	private static final Set<String> RETRYABLE_57_STATES = Set.of("57P01", "57P02", "57P03");

	private final String maskedUrl;
	private final ConnectionAttempt attempt;
	private final Duration timeout;
	private final Duration interval;

	public DatabaseStartupWait(String jdbcUrl, ConnectionAttempt attempt, Duration timeout, Duration interval) {
		this.maskedUrl = maskQueryString(jdbcUrl);
		this.attempt = attempt;
		this.timeout = timeout;
		this.interval = interval;
	}

	public void awaitDatabase() {
		long startNanos = System.nanoTime();
		log.info("Waiting up to {} for database at {}", Durations.format(timeout), maskedUrl);

		int attempts = 0;
		while (true) {
			attempts++;
			try {
				attempt.tryConnect();
				log.info("Database is available after {} ({} attempts)", Durations.format(elapsedSince(startNanos)), attempts);
				return;
			}
			catch (SQLException e) {
				Duration elapsed = elapsedSince(startNanos);
				if (!isRetryable(e)) {
					throw new DatabaseUnavailableException(maskedUrl, timeout, attempts, false, e);
				}
				if (elapsed.compareTo(timeout) >= 0) {
					throw new DatabaseUnavailableException(maskedUrl, timeout, attempts, true, e);
				}
				Duration remaining = timeout.minus(elapsed);
				Duration sleepDuration = interval.compareTo(remaining) < 0 ? interval : remaining;
				log.warn("Database not available yet (attempt {}, {}); retrying in {}, {} left",
						attempts, DatabaseUnavailableException.describe(e), Durations.format(sleepDuration), Durations.format(remaining));
				sleep(sleepDuration);
			}
		}
	}

	// An interrupt aborts startup with an IllegalStateException, not going through a FailureAnalyzer.
	private void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for database at " + maskedUrl, e);
		}
	}

	private static Duration elapsedSince(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos);
	}

	private static boolean isRetryable(SQLException e) {
		String sqlState = e.getSQLState();
		if (sqlState == null) {
			return false;
		}
		return sqlState.startsWith("08") || RETRYABLE_57_STATES.contains(sqlState);
	}

	/** URL with the query string removed, because pgjdbc URLs can carry {@code ?password=...}. */
	private static String maskQueryString(String jdbcUrl) {
		int queryStart = jdbcUrl.indexOf('?');
		return queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
	}

	@FunctionalInterface
	public interface ConnectionAttempt {

		void tryConnect() throws SQLException;

	}

}
