package dev.katran.pet.db;

import java.time.Duration;
import java.util.Locale;

/**
 * Renders a {@link Duration} as a short, human-readable string for log lines and error
 * messages. Never {@link Duration#toString()} ({@code PT1M}), which is hard to read.
 */
final class Durations {

	private Durations() {
	}

	static String format(Duration duration) {
		long totalMillis = duration.toMillis();
		if (totalMillis % 1000 == 0) {
			return (totalMillis / 1000) + "s";
		}
		if (totalMillis < 1000) {
			return totalMillis + "ms";
		}
		return String.format(Locale.ROOT, "%.1fs", totalMillis / 1000.0);
	}

}
