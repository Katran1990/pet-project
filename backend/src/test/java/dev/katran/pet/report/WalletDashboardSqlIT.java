package dev.katran.pet.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.katran.pet.TestcontainersConfiguration;
import dev.katran.pet.category.Category;
import dev.katran.pet.category.CategoryRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// Shares the cached Spring Boot test context and Postgres container with ReportControllerIT
// (no @TestBean here). This class owns month 2018-01 in the shared database: no other IT in this
// context may write expenses/limits into it.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletDashboardSqlIT {

	private static final String MONTH_REF = "${month:sqlstring}";
	private static final YearMonth FIXTURE_MONTH = YearMonth.of(2018, 1);
	// Relative to Gradle's test working directory backend/.
	private static final Path DASHBOARD = Path.of("../infra/monitoring/dashboards/wallet.json");

	@Autowired
	private CategoryRepository categories;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private TransactionTemplate tx;

	@Autowired
	private CategoryReportRepository reportRepository;

	private static JsonNode dashboard() throws IOException {
		assertThat(DASHBOARD).as("wallet.json is read from the repository, not the classpath").exists();
		return JsonMapper.builder().build().readTree(Files.readString(DASHBOARD, StandardCharsets.UTF_8));
	}

	private static String reportSql() throws IOException {
		return new ClassPathResource("db/report/by-category.sql").getContentAsString(StandardCharsets.UTF_8);
	}

	// What Grafana's sqlstring format renders for a single value.
	private static String grafanaSqlString(String v) {
		return "'" + v.replace("'", "''") + "'";
	}

	private static String panelRawSql(JsonNode dashboard) {
		List<JsonNode> values = dashboard.findValues("rawSql");
		assertThat(values).hasSize(1);
		return values.get(0).asString();
	}

	// AC3
	@Test
	void dashboardHasOneQueryAndItIsTheReportSql() throws IOException {
		JsonNode dashboard = dashboard();
		String file = reportSql();
		assertThat(file.split(":month", -1)).as(":month occurrences + 1").hasSize(2);

		assertThat(panelRawSql(dashboard).strip()).isEqualTo(file.strip().replace(":month", MONTH_REF));

		int sqlPanelId = -1;
		for (JsonNode panel : dashboard.get("panels")) {
			if (!panel.findValues("rawSql").isEmpty()) {
				sqlPanelId = panel.get("id").asInt();
			}
		}
		int sqlPanels = 0;
		for (JsonNode panel : dashboard.get("panels")) {
			if (!panel.findValues("rawSql").isEmpty()) {
				sqlPanels++;
				assertThat(panel.get("datasource").get("uid").asString()).isEqualTo("wallet-postgres");
				continue;
			}
			for (JsonNode target : panel.get("targets")) {
				assertThat(target.get("datasource").get("uid").asString()).isEqualTo("-- Dashboard --");
				assertThat(target.get("panelId").asInt()).isEqualTo(sqlPanelId);
				JsonNode withTransforms = target.get("withTransforms");
				assertThat(withTransforms == null || !withTransforms.asBoolean()).isTrue();
			}
		}
		assertThat(sqlPanels).isEqualTo(1);
	}

	// AC3
	@Test
	void monthVariableIsASingleSelectQueryOnWalletPostgres() throws IOException {
		JsonNode list = dashboard().get("templating").get("list");
		assertThat(list.size()).isEqualTo(1);
		JsonNode month = list.get(0);
		assertThat(month.get("name").asString()).isEqualTo("month");
		assertThat(month.get("type").asString()).isEqualTo("query");
		assertThat(month.get("datasource").get("uid").asString()).isEqualTo("wallet-postgres");
		assertThat(month.get("query").isString()).isTrue();
		assertThat(month.get("multi").asBoolean(true)).isFalse();
		assertThat(month.get("includeAll").asBoolean(true)).isFalse();
		assertThat(month.get("allowCustomValue").asBoolean(true)).isFalse();
		JsonNode current = month.get("current");
		assertThat(current == null || current.isEmpty()).isTrue();
	}

	// AC3, AC2
	@Test
	void monthVariableListsTheLastTwelveMonthsNewestFirstWhenRunAsGrafanaReader() throws IOException {
		JsonNode dashboard = dashboard();
		String variableQuery = dashboard.get("templating").get("list").get(0).get("query").asString();
		String panelSql = panelRawSql(dashboard);

		// One transaction: now() is the transaction start time, so a month boundary cannot split it.
		tx.executeWithoutResult(status -> {
			jdbcTemplate.execute("set local role grafana_reader");
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(variableQuery);
			String current = jdbcTemplate.queryForObject(
					"select to_char(date_trunc('month', now() at time zone 'Europe/Warsaw'), 'YYYY-MM-DD')",
					String.class);

			assertThat(rows).hasSize(12);
			assertThat(rows.get(0).get("__value")).isEqualTo(current);
			LocalDate previous = null;
			for (Map<String, Object> row : rows) {
				String text = (String) row.get("__text");
				String value = (String) row.get("__value");
				LocalDate date = LocalDate.parse(value);
				assertThat(date.getDayOfMonth()).isEqualTo(1);
				assertThat(text).isEqualTo(value.substring(0, 7)).matches("^\\d{4}-\\d{2}$");
				if (previous != null) {
					assertThat(date).isEqualTo(previous.minusMonths(1));
				}
				previous = date;
			}

			String sql = panelSql.replace(MONTH_REF, grafanaSqlString((String) rows.get(0).get("__value")));
			jdbcTemplate.queryForList(sql);
		});
	}

	// AC3
	@Test
	void dashboardSqlReturnsTheSameRowsAsTheReportWhenRunAsGrafanaReader() throws IOException {
		Category a = categories.saveAndFlush(new Category("WD-A-" + UUID.randomUUID(), "star"));
		Category b = categories.saveAndFlush(new Category("WD-B-" + UUID.randomUUID(), null));
		LocalDate day = FIXTURE_MONTH.atDay(1);
		for (String amount : List.of("100.00", "50.50")) {
			jdbcTemplate.update("insert into expense (category_id, amount, spent_on) values (?, ?, ?)",
					a.getId(), new BigDecimal(amount), day);
		}
		jdbcTemplate.update("insert into budget_limit (category_id, month, amount) values (?, ?, ?)",
				a.getId(), day, new BigDecimal("120.00"));
		jdbcTemplate.update("insert into budget_limit (category_id, month, amount) values (?, ?, ?)",
				b.getId(), day, new BigDecimal("300.00"));

		List<CategoryReportLine> expected = reportRepository.findByMonth(FIXTURE_MONTH);

		String sql = panelRawSql(dashboard()).replace(MONTH_REF, grafanaSqlString("2018-01-01"));
		List<CategoryReportLine> actual = tx.execute(status -> {
			jdbcTemplate.execute("set local role grafana_reader");
			return new ArrayList<>(jdbcClient.sql(sql).query(CategoryReportLine.class).list());
		});

		assertThat(actual).isEqualTo(expected);
		CategoryReportLine lineA = actual.stream().filter(l -> a.getId().equals(l.categoryId())).findFirst().orElseThrow();
		CategoryReportLine lineB = actual.stream().filter(l -> b.getId().equals(l.categoryId())).findFirst().orElseThrow();
		assertThat(lineA.amount()).isEqualTo(new BigDecimal("150.50"));
		assertThat(lineA.remaining()).isEqualTo(new BigDecimal("-30.50"));
		assertThat(lineB.amount()).isEqualTo(new BigDecimal("0.00"));
	}

}
