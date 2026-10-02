package dev.katran.pet.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	@Bean
	Clock clock(@Value("${app.time-zone}") ZoneId zone) {
		return Clock.system(zone);
	}

	// Bean Validation's @PastOrPresent / @FutureOrPresent evaluate "now" with this clock and its zone.
	@Bean
	ValidationConfigurationCustomizer validationClock(Clock clock) {
		return configuration -> configuration.clockProvider(() -> clock);
	}

}
