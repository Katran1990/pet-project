package dev.katran.pet.db;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * How long the backend waits for Postgres to accept connections on startup, and how often it
 * retries. See README "Configuration" for the corresponding environment variables.
 */
@ConfigurationProperties("app.db.startup-wait")
public record DatabaseStartupWaitProperties(
		@DurationUnit(ChronoUnit.SECONDS) Duration timeout,
		@DurationUnit(ChronoUnit.SECONDS) Duration interval) {

	public DatabaseStartupWaitProperties {
		if (timeout == null || timeout.isNegative()) {
			throw new IllegalArgumentException("app.db.startup-wait.timeout must not be null or negative: " + timeout);
		}
		if (interval == null || interval.isZero() || interval.isNegative()) {
			throw new IllegalArgumentException("app.db.startup-wait.interval must be positive: " + interval);
		}
	}

}
