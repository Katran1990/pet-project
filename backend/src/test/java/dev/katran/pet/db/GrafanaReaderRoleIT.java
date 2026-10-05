package dev.katran.pet.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.katran.pet.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Shares the cached Spring Boot test context and Postgres container with ReportControllerIT
// (no @TestBean here). Only inspects the role and runs read-only or rejected statements, so it
// does not own any data.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrafanaReaderRoleIT {

	private static final String MIGRATION = "db/migration/V6__create_grafana_reader_role.sql";
	private static final List<String> REPORT_TABLES = List.of("category", "expense", "budget_limit");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate tx;

	private boolean hasTablePrivilege(String table, String privilege) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select has_table_privilege('grafana_reader', ?, ?)", Boolean.class, table, privilege));
	}

	private void assertRoleAttributes() {
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"select rolcanlogin, rolpassword is null as no_password, rolsuper, rolcreaterole, rolcreatedb"
						+ " from pg_authid where rolname = 'grafana_reader'");
		assertThat(row).containsEntry("rolcanlogin", true)
				.containsEntry("no_password", true)
				.containsEntry("rolsuper", false)
				.containsEntry("rolcreaterole", false)
				.containsEntry("rolcreatedb", false);
		String config = jdbcTemplate.queryForObject(
				"select rolconfig::text from pg_roles where rolname = 'grafana_reader'", String.class);
		assertThat(config).contains("default_transaction_read_only=on");
	}

	private void asReader(String sql) {
		tx.executeWithoutResult(status -> {
			jdbcTemplate.execute("set local role grafana_reader");
			jdbcTemplate.execute(sql);
		});
	}

	private static String readMigration() throws IOException {
		return new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
	}

	// AC2
	@Test
	void roleCanLogInButHasNoPassword() {
		assertRoleAttributes();
	}

	// AC2
	@Test
	void hasSelectOnlyOnTheReportTables() {
		for (String table : REPORT_TABLES) {
			assertThat(hasTablePrivilege(table, "SELECT")).as("SELECT on " + table).isTrue();
			for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
				assertThat(hasTablePrivilege(table, privilege)).as(privilege + " on " + table).isFalse();
			}
		}
		for (String table : List.of("quick_template", "greetings", "flyway_schema_history")) {
			assertThat(hasTablePrivilege(table, "SELECT")).as("SELECT on " + table).isFalse();
		}
		assertThat(jdbcTemplate.queryForObject(
				"select has_schema_privilege('grafana_reader', 'public', 'USAGE')", Boolean.class)).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"select has_schema_privilege('grafana_reader', 'public', 'CREATE')", Boolean.class)).isFalse();
	}

	// AC2
	@Test
	void writesAreRejectedWhenActingAsTheRole() {
		Long count = tx.execute(status -> {
			jdbcTemplate.execute("set local role grafana_reader");
			return jdbcTemplate.queryForObject("select count(*) from expense", Long.class);
		});
		assertThat(count).isNotNull();

		for (String sql : List.of(
				"insert into category (name) values ('grafana-reader-must-not-insert')",
				"update expense set amount = amount",
				"delete from budget_limit",
				"select * from quick_template")) {
			assertThatThrownBy(() -> asReader(sql)).as(sql).satisfies(e -> {
				Throwable root = e;
				while (root.getCause() != null) {
					root = root.getCause();
				}
				assertThat(root).isInstanceOf(SQLException.class);
				assertThat(((SQLException) root).getSQLState()).isEqualTo("42501");
			});
		}
		// SET LOCAL ended with the transactions: the pooled connection is not left as the reader.
		assertThat(jdbcTemplate.queryForObject("select current_user", String.class)).isNotEqualTo("grafana_reader");
	}

	// AC2
	@Test
	void migrationRunsAgainWhenTheRoleAlreadyExists() throws IOException {
		jdbcTemplate.execute(readMigration());

		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from pg_roles where rolname = 'grafana_reader'", Long.class)).isEqualTo(1L);
		assertRoleAttributes();
	}

	// AC2
	@Test
	void migrationContainsNoPasswordAndNoPlaceholder() throws IOException {
		String script = readMigration();
		assertThat(script).doesNotContain("${");
		assertThat(Pattern.compile("(?i)password\\s*'").matcher(script).find()).isFalse();
	}

}
