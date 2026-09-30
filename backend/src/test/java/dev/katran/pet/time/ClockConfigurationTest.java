package dev.katran.pet.time;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A plain unit test with no Docker: {@link ApplicationContextRunner} plus
 * {@link ConfigDataApplicationContextInitializer} loads the real
 * {@code application.properties}, so the default is tested where it is defined.
 */
class ClockConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(ClockConfiguration.class);

	@Test
	void defaultZoneIsEuropeWarsaw() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			Clock clock = context.getBean(Clock.class);
			assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
			// A system clock, not a fixed one.
			assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
		});
	}

	@Test
	void zoneComesFromAppTimeZoneEnvironmentVariable() {
		contextRunner.withPropertyValues("APP_TIME_ZONE=America/New_York").run(context -> {
			assertThat(context).hasNotFailed();
			Clock clock = context.getBean(Clock.class);
			assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
		});
	}

	@Test
	void invalidZoneFailsStartup() {
		contextRunner.withPropertyValues("APP_TIME_ZONE=Mars/Olympus").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context).getFailure().hasRootCauseInstanceOf(DateTimeException.class);
		});
	}

}
