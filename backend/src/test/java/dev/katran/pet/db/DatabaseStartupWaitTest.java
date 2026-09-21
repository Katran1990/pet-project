package dev.katran.pet.db;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests for {@link DatabaseStartupWait}. No Spring, no Docker: a fake {@link
 * DatabaseStartupWait.ConnectionAttempt} stands in for the real JDBC connection, and durations
 * are kept small (timeout 300ms, interval 50ms) so the whole class runs in under a second.
 */
class DatabaseStartupWaitTest {

	private static final Duration TIMEOUT = Duration.ofMillis(300);
	private static final Duration INTERVAL = Duration.ofMillis(50);
	// LARGE_INTERVAL and LARGE_TIMEOUT are deliberately large so that a regression that sleeps
	// once (instead of returning/failing on the first attempt) makes the "returns/fails
	// immediately" tests below fail fast and obviously, instead of silently passing.
	// DatabaseStartupWait clamps the sleep to min(interval, remaining-until-timeout), so
	// LARGE_INTERVAL alone is not enough: with the small TIMEOUT=300ms used elsewhere in this
	// class, "remaining" would itself be about 300ms, clamping any accidental sleep to about
	// 300ms, safely under a 1s bound. Pairing LARGE_INTERVAL with LARGE_TIMEOUT keeps "remaining"
	// large too, so an accidental sleep is the full LARGE_INTERVAL and clearly exceeds
	// NO_SLEEP_BOUND (that near-miss, plus class-init/logging warm-up time and test order, is what
	// made a bound of "< INTERVAL" with a 50ms INTERVAL flaky/meaningless).
	private static final Duration LARGE_INTERVAL = Duration.ofSeconds(5);
	private static final Duration LARGE_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration NO_SLEEP_BOUND = Duration.ofSeconds(1);
	private static final String URL = "jdbc:postgresql://postgres:5432/app";

	@Test
	void returnsImmediatelyWhenFirstAttemptSucceeds() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt.succeedingImmediately();

		Instant start = Instant.now();
		new DatabaseStartupWait(URL, attempt, LARGE_TIMEOUT, LARGE_INTERVAL).awaitDatabase();
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(attempt.callCount()).isEqualTo(1);
		assertThat(elapsed).isLessThan(NO_SLEEP_BOUND);
	}

	@Test
	void retriesTransientFailuresUntilDatabaseIsAvailable() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt.failingThenSucceeding(
				sqlException("Connection to postgres:5432 refused", "08001"),
				sqlException("Connection to postgres:5432 refused", "08001"));

		new DatabaseStartupWait(URL, attempt, TIMEOUT, INTERVAL).awaitDatabase();

		assertThat(attempt.callCount()).isEqualTo(3);
	}

	@Test
	void retriesWhileServerIsStartingUp() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt.failingThenSucceeding(
				sqlException("the database system is starting up", "57P03"));

		new DatabaseStartupWait(URL, attempt, TIMEOUT, INTERVAL).awaitDatabase();

		assertThat(attempt.callCount()).isEqualTo(2);
	}

	@Test
	void failsAfterTimeoutWithClearMessage() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt.alwaysFailingWith(
				sqlException("Connection to postgres:5432 refused", "08001"));

		Instant start = Instant.now();
		DatabaseUnavailableException exception = catchDatabaseUnavailableException(attempt);
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(exception.getMessage())
				.contains(URL)
				.contains("300ms")
				.contains(String.valueOf(attempt.callCount()))
				.contains("[08001]")
				.contains("Connection to postgres:5432 refused");
		assertThat(exception.getCause()).isInstanceOf(SQLException.class);
		assertThat(exception.attempts()).isEqualTo(attempt.callCount());
		assertThat(exception.retryable()).isTrue();
		assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(TIMEOUT.plus(INTERVAL).plus(Duration.ofMillis(250)));
	}

	@Test
	void clampsSleepToRemainingTimeoutWhenIntervalIsLarger() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt.alwaysFailingWith(
				sqlException("Connection to postgres:5432 refused", "08001"));

		Instant start = Instant.now();
		DatabaseUnavailableException exception = catchDatabaseUnavailableException(attempt, TIMEOUT, LARGE_INTERVAL);
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(attempt.callCount()).isEqualTo(2);
		assertThat(exception.attempts()).isEqualTo(2);
		assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(NO_SLEEP_BOUND);
	}

	@Test
	void failsImmediatelyOnNonRetryableError() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt
				.alwaysFailingWith(sqlException("password authentication failed for user \"app\"", "28P01"));

		Instant start = Instant.now();
		DatabaseUnavailableException exception = catchDatabaseUnavailableException(attempt, LARGE_TIMEOUT, LARGE_INTERVAL);
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(attempt.callCount()).isEqualTo(1);
		assertThat(exception.retryable()).isFalse();
		assertThat(elapsed).isLessThan(NO_SLEEP_BOUND);
	}

	@Test
	void zeroTimeoutMeansSingleAttempt() {
		FakeConnectionAttempt attempt = FakeConnectionAttempt
				.alwaysFailingWith(sqlException("Connection to postgres:5432 refused", "08001"));

		DatabaseUnavailableException exception = catchDatabaseUnavailableException(attempt, Duration.ZERO, INTERVAL);

		assertThat(attempt.callCount()).isEqualTo(1);
		assertThat(exception.attempts()).isEqualTo(1);
	}

	@Test
	void masksQueryStringInUrl() {
		String urlWithSecret = "jdbc:postgresql://postgres:5432/app?password=secret";
		FakeConnectionAttempt attempt = FakeConnectionAttempt
				.alwaysFailingWith(sqlException("Connection to postgres:5432 refused", "08001"));

		DatabaseUnavailableException exception = catchThrowableOfType(DatabaseUnavailableException.class,
				() -> new DatabaseStartupWait(urlWithSecret, attempt, Duration.ZERO, INTERVAL).awaitDatabase());

		assertThat(exception).isNotNull();
		assertThat(exception.getMessage()).doesNotContain("secret").contains("jdbc:postgresql://postgres:5432/app");
		assertThat(exception.jdbcUrl()).doesNotContain("secret").contains("jdbc:postgresql://postgres:5432/app");
	}

	private static DatabaseUnavailableException catchDatabaseUnavailableException(FakeConnectionAttempt attempt) {
		return catchDatabaseUnavailableException(attempt, TIMEOUT, INTERVAL);
	}

	private static DatabaseUnavailableException catchDatabaseUnavailableException(
			FakeConnectionAttempt attempt, Duration timeout, Duration interval) {
		DatabaseUnavailableException exception = catchThrowableOfType(DatabaseUnavailableException.class,
				() -> new DatabaseStartupWait(URL, attempt, timeout, interval).awaitDatabase());
		assertThat(exception).isNotNull();
		return exception;
	}

	private static SQLException sqlException(String message, String sqlState) {
		return new SQLException(message, sqlState);
	}

	/** Fake {@link DatabaseStartupWait.ConnectionAttempt} driven by a queue of failures. */
	private static final class FakeConnectionAttempt implements DatabaseStartupWait.ConnectionAttempt {

		private final Deque<SQLException> failures;
		private final boolean alwaysFail;
		private final AtomicInteger callCount = new AtomicInteger();

		private FakeConnectionAttempt(Deque<SQLException> failures, boolean alwaysFail) {
			this.failures = failures;
			this.alwaysFail = alwaysFail;
		}

		static FakeConnectionAttempt succeedingImmediately() {
			return new FakeConnectionAttempt(new ArrayDeque<>(), false);
		}

		static FakeConnectionAttempt failingThenSucceeding(SQLException... failuresThenSuccess) {
			return new FakeConnectionAttempt(new ArrayDeque<>(java.util.List.of(failuresThenSuccess)), false);
		}

		static FakeConnectionAttempt alwaysFailingWith(SQLException failure) {
			Deque<SQLException> deque = new ArrayDeque<>();
			deque.add(failure);
			return new FakeConnectionAttempt(deque, true);
		}

		@Override
		public void tryConnect() throws SQLException {
			callCount.incrementAndGet();
			if (alwaysFail) {
				throw failures.peek();
			}
			SQLException next = failures.poll();
			if (next != null) {
				throw next;
			}
		}

		int callCount() {
			return callCount.get();
		}

	}

}
