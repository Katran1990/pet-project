package dev.katran.pet.db;

import java.sql.DriverManager;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Waits for Postgres before Flyway runs its first migration. The wait is wired through
 * {@link FlywayMigrationStrategy}, the extension point Spring Boot calls exactly where Flyway
 * would open its first connection, and JPA's {@code entityManagerFactory} already depends on
 * the {@code flywayInitializer} that triggers it.
 *
 * <p>Note: this wait is tied to Flyway. If Flyway is ever disabled
 * ({@code spring.flyway.enabled=false}) or another {@link FlywayMigrationStrategy} bean is
 * defined, this wait no longer runs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseStartupWaitProperties.class)
public class DatabaseStartupConfiguration {

	@Bean
	FlywayMigrationStrategy waitForDatabaseThenMigrate(JdbcConnectionDetails db, DatabaseStartupWaitProperties props) {
		return flyway -> {
			new DatabaseStartupWait(db.getJdbcUrl(),
					() -> DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()).close(),
					props.timeout(), props.interval()).awaitDatabase();
			flyway.migrate();
		};
	}

}
