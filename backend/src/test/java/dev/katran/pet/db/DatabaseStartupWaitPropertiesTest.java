package dev.katran.pet.db;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link DatabaseStartupWaitProperties}, proving AC4 (the value format and its
 * validation): plain numbers are seconds, {@code Duration} suffixes work, and invalid values are
 * rejected during binding.
 */
class DatabaseStartupWaitPropertiesTest {

	@Test
	void plainNumberIsInterpretedAsSeconds() {
		DatabaseStartupWaitProperties props = bind(Map.of("app.db.startup-wait.timeout", "90",
				"app.db.startup-wait.interval", "5"));

		assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(90));
		assertThat(props.interval()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void durationSuffixIsRespected() {
		DatabaseStartupWaitProperties props = bind(Map.of("app.db.startup-wait.timeout", "500ms",
				"app.db.startup-wait.interval", "2s"));

		assertThat(props.timeout()).isEqualTo(Duration.ofMillis(500));
		assertThat(props.interval()).isEqualTo(Duration.ofSeconds(2));
	}

	@Test
	void rejectsZeroInterval() {
		assertThatExceptionOfType(BindException.class)
				.isThrownBy(() -> bind(Map.of("app.db.startup-wait.timeout", "60s",
						"app.db.startup-wait.interval", "0")))
				.withRootCauseInstanceOf(IllegalArgumentException.class)
				.havingRootCause()
				.withMessageContaining("app.db.startup-wait.interval must be positive");
	}

	@Test
	void rejectsNegativeTimeout() {
		assertThatExceptionOfType(BindException.class)
				.isThrownBy(() -> bind(Map.of("app.db.startup-wait.timeout", "-1s",
						"app.db.startup-wait.interval", "2s")))
				.withRootCauseInstanceOf(IllegalArgumentException.class)
				.havingRootCause()
				.withMessageContaining("app.db.startup-wait.timeout must not be null or negative");
	}

	@Test
	void rejectsNegativeInterval() {
		assertThatExceptionOfType(BindException.class)
				.isThrownBy(() -> bind(Map.of("app.db.startup-wait.timeout", "60s",
						"app.db.startup-wait.interval", "-1s")))
				.withRootCauseInstanceOf(IllegalArgumentException.class)
				.havingRootCause()
				.withMessageContaining("app.db.startup-wait.interval must be positive");
	}

	private static DatabaseStartupWaitProperties bind(Map<String, String> values) {
		Binder binder = new Binder(new MapConfigurationPropertySource(values));
		return binder.bind("app.db.startup-wait", DatabaseStartupWaitProperties.class).get();
	}

}
