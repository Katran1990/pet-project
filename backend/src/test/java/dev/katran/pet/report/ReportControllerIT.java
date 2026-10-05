package dev.katran.pet.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import dev.katran.pet.TestcontainersConfiguration;
import dev.katran.pet.category.Category;
import dev.katran.pet.category.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Shares the cached Spring Boot test context and Postgres container with CategoryControllerIT,
// GreetingControllerIT, ApiExceptionHandlerIT, BudgetLimitControllerIT, GrafanaReaderRoleIT and
// WalletDashboardSqlIT (no @TestBean here).
// This class owns the following months in the shared database, and no other IT in this context
// must write expenses/limits into them:
// 2016-01, 2016-02, 2016-03, 2017-01, 2017-02, 2017-03, 2017-04, 2017-05, 2017-06, 2017-07,
// 2017-08, 2017-09, 2017-10, 2017-11, 2017-12 and 2099-12.
// Other owners in this context: WalletDashboardSqlIT owns 2018-01; BudgetLimitControllerIT uses
// 2020-01, 2030-01 to 2030-06, 2030-08, 2030-09, 2031-01, 2031-02, 2031-05 (data), 2031-03
// (GET only) and 2039-01 to 2039-06.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportControllerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private CategoryRepository categories;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

	// ---- fixtures -------------------------------------------------------

	private static String uniqueName(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private Category createActiveCategory(String prefix, String icon) {
		return categories.saveAndFlush(new Category(uniqueName(prefix), icon));
	}

	private Category createArchivedCategory(String prefix) {
		Category category = new Category(uniqueName(prefix), "cart");
		category.setArchived(true);
		return categories.saveAndFlush(category);
	}

	private long insertExpense(long categoryId, String amount, String spentOn) {
		return jdbcTemplate.queryForObject(
				"insert into expense (category_id, amount, spent_on) values (?, ?, ?) returning id",
				Long.class, categoryId, new BigDecimal(amount), LocalDate.parse(spentOn));
	}

	private long insertLimit(long categoryId, String month, String amount) {
		return jdbcTemplate.queryForObject(
				"insert into budget_limit (category_id, month, amount) values (?, ?, ?) returning id",
				Long.class, categoryId, YearMonth.parse(month).atDay(1), new BigDecimal(amount));
	}

	// ---- HTTP helpers -----------------------------------------------------
	// JSON numbers come back through JsonPath (json-smart) as Integer, not Long, so ids must be
	// compared as int, the same convention as ExpenseListIT.

	private static List<Integer> ids(long... values) {
		List<Integer> result = new ArrayList<>();
		for (long value : values) {
			result.add((int) value);
		}
		return result;
	}

	private RestTestClient.BodyContentSpec get(String uri) {
		return client.get().uri(uri).exchange().expectStatus().isOk().expectBody();
	}

	private RestTestClient.BodyContentSpec badRequest(String uri) {
		return client.get().uri(uri).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/reports/by-category");
	}

	@SuppressWarnings("unchecked")
	private void assertSharesSumTo100(String month) {
		AtomicReference<List<Object>> sharesRef = new AtomicReference<>();
		AtomicReference<List<Object>> amountsRef = new AtomicReference<>();

		get("/api/reports/by-category?month=" + month)
				.jsonPath("$.rows[*].share").value(List.class, sharesRef::set)
				.jsonPath("$.rows[*].amount").value(List.class, amountsRef::set);

		List<String> shares = sharesRef.get().stream().map(String.class::cast).toList();
		List<String> amounts = amountsRef.get().stream().map(String.class::cast).toList();
		BigDecimal sum = shares.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
		long n = amounts.stream().filter(a -> !a.equals("0.00")).count();

		if (n > 0) {
			BigDecimal maxDiff = new BigDecimal("0.05").multiply(BigDecimal.valueOf(n));
			assertThat(sum.subtract(BigDecimal.valueOf(100)).abs())
					.isLessThanOrEqualTo(maxDiff);
		}
		else {
			assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
		}
	}

	// R1
	@Test
	void returnsMonthTotalAndRowsWithAllFields() {
		Category c = createActiveCategory("R1-C", "star");
		Category b = createActiveCategory("R1-B", null);
		Category a = createActiveCategory("R1-A", "cart");
		Category d = createActiveCategory("R1-D", null);
		Category e = createActiveCategory("R1-E", null);
		assertThat(c.getId()).isLessThan(b.getId());
		assertThat(b.getId()).isLessThan(a.getId());
		assertThat(a.getId()).isLessThan(d.getId());
		assertThat(d.getId()).isLessThan(e.getId());

		insertExpense(a.getId(), "500.00", "2017-01-01");
		insertExpense(a.getId(), "300.00", "2017-01-31");
		insertLimit(a.getId(), "2017-01", "700.00");

		insertExpense(b.getId(), "434.50", "2017-01-15");

		insertLimit(c.getId(), "2017-01", "200.00");

		insertExpense(d.getId(), "10.00", "2017-02-01");
		insertLimit(d.getId(), "2017-02", "50.00");

		get("/api/reports/by-category?month=2017-01")
				.jsonPath("$.month").isEqualTo("2017-01")
				.jsonPath("$.totalAmount").isEqualTo("1234.50")
				.jsonPath("$.rows.length()").isEqualTo(3)
				.jsonPath("$.rows[*].category.id").isEqualTo(ids(a.getId(), b.getId(), c.getId()))
				.jsonPath("$.rows[0].amount").isEqualTo("800.00")
				.jsonPath("$.rows[0].share").isEqualTo("64.8")
				.jsonPath("$.rows[0].limit").isEqualTo("700.00")
				.jsonPath("$.rows[0].remaining").isEqualTo("-100.00")
				.jsonPath("$.rows[0].category.name").isEqualTo(a.getName())
				.jsonPath("$.rows[0].category.icon").isEqualTo("cart")
				.jsonPath("$.rows[1].amount").isEqualTo("434.50")
				.jsonPath("$.rows[1].share").isEqualTo("35.2")
				.jsonPath("$.rows[1].limit").isEqualTo(null)
				.jsonPath("$.rows[1].remaining").isEqualTo(null)
				.jsonPath("$.rows[1].category.icon").isEqualTo(null)
				.jsonPath("$.rows[2].amount").isEqualTo("0.00")
				.jsonPath("$.rows[2].share").isEqualTo("0.0")
				.jsonPath("$.rows[2].limit").isEqualTo("200.00")
				.jsonPath("$.rows[2].remaining").isEqualTo("200.00")
				.jsonPath("$.rows[2].category.name").isEqualTo(c.getName())
				.jsonPath("$.rows[2].category.icon").isEqualTo("star")
				.jsonPath("$.rows[0].categoryId").doesNotExist()
				.jsonPath("$.rows[0].limitId").doesNotExist()
				.jsonPath("$.rows[0].limitAmount").doesNotExist()
				.jsonPath("$.rows[0].totalAmount").doesNotExist()
				.jsonPath("$.rows[0].category.archived").doesNotExist();

		get("/api/reports/by-category?month=2017-02")
				.jsonPath("$.rows.length()").isEqualTo(1)
				.jsonPath("$.rows[0].category.id").isEqualTo(d.getId().intValue());

		assertSharesSumTo100("2017-01");
	}

	// R2
	@Test
	void countsOnlyExpensesAndLimitsOfTheRequestedMonth() {
		Category f = createActiveCategory("R2-F", null);
		Category g = createActiveCategory("R2-G", null);

		insertExpense(f.getId(), "10.00", "2016-01-31");
		insertExpense(f.getId(), "1.00", "2016-02-01");
		insertExpense(f.getId(), "2.00", "2016-02-29"); // leap day
		insertExpense(f.getId(), "100.00", "2016-03-01");

		insertExpense(g.getId(), "5.00", "2016-01-15");
		insertLimit(g.getId(), "2016-03", "30.00");

		get("/api/reports/by-category?month=2016-02")
				.jsonPath("$.rows.length()").isEqualTo(1)
				.jsonPath("$.rows[0].category.id").isEqualTo(f.getId().intValue())
				.jsonPath("$.rows[0].amount").isEqualTo("3.00")
				.jsonPath("$.rows[0].share").isEqualTo("100.0")
				.jsonPath("$.rows[0].limit").isEqualTo(null)
				.jsonPath("$.totalAmount").isEqualTo("3.00");

		get("/api/reports/by-category?month=2016-01")
				.jsonPath("$.rows.length()").isEqualTo(2)
				.jsonPath("$.rows[0].category.id").isEqualTo(f.getId().intValue())
				.jsonPath("$.rows[0].amount").isEqualTo("10.00")
				.jsonPath("$.rows[0].share").isEqualTo("66.7")
				.jsonPath("$.rows[1].category.id").isEqualTo(g.getId().intValue())
				.jsonPath("$.rows[1].amount").isEqualTo("5.00")
				.jsonPath("$.rows[1].share").isEqualTo("33.3")
				.jsonPath("$.totalAmount").isEqualTo("15.00");

		get("/api/reports/by-category?month=2016-03")
				.jsonPath("$.rows.length()").isEqualTo(2)
				.jsonPath("$.rows[0].category.id").isEqualTo(f.getId().intValue())
				.jsonPath("$.rows[0].amount").isEqualTo("100.00")
				.jsonPath("$.rows[1].category.id").isEqualTo(g.getId().intValue())
				.jsonPath("$.rows[1].amount").isEqualTo("0.00")
				.jsonPath("$.rows[1].limit").isEqualTo("30.00")
				.jsonPath("$.rows[1].remaining").isEqualTo("30.00")
				.jsonPath("$.totalAmount").isEqualTo("100.00");
	}

	// R3
	@Test
	void monthWithoutExpensesOrLimitsIsEmpty() {
		get("/api/reports/by-category?month=2017-03")
				.jsonPath("$.month").isEqualTo("2017-03")
				.jsonPath("$.totalAmount").isEqualTo("0.00")
				.jsonPath("$.rows.length()").isEqualTo(0);

		get("/api/reports/by-category?month=2099-12")
				.jsonPath("$.month").isEqualTo("2099-12")
				.jsonPath("$.totalAmount").isEqualTo("0.00")
				.jsonPath("$.rows.length()").isEqualTo(0);
	}

	// R4
	@Test
	void limitsWithoutExpensesAppearWithZeroAmount() {
		// Three rows tied at amount 0.00. Names and limits are deliberately out of step with the
		// id order in every other direction, so that an ORDER BY on name (asc or desc) or on limit
		// (asc or desc) could not reproduce this id-ascending result by accident:
		// id order:    k, h, j
		// name order:  h, j, k (asc) / k, j, h (desc)
		// limit order: h, k, j (asc) / j, k, h (desc)
		Category k = createActiveCategory("R4-K", null);
		Category h = createActiveCategory("R4-H", null);
		Category j = createActiveCategory("R4-J", null);
		assertThat(k.getId()).isLessThan(h.getId());
		assertThat(h.getId()).isLessThan(j.getId());

		insertLimit(k.getId(), "2017-04", "200.00");
		insertLimit(h.getId(), "2017-04", "50.00");
		insertLimit(j.getId(), "2017-04", "300.00");

		get("/api/reports/by-category?month=2017-04")
				.jsonPath("$.totalAmount").isEqualTo("0.00")
				.jsonPath("$.rows.length()").isEqualTo(3)
				.jsonPath("$.rows[*].category.id").isEqualTo(ids(k.getId(), h.getId(), j.getId()))
				.jsonPath("$.rows[0].amount").isEqualTo("0.00")
				.jsonPath("$.rows[0].share").isEqualTo("0.0")
				.jsonPath("$.rows[0].limit").isEqualTo("200.00")
				.jsonPath("$.rows[0].remaining").isEqualTo("200.00")
				.jsonPath("$.rows[1].amount").isEqualTo("0.00")
				.jsonPath("$.rows[1].share").isEqualTo("0.0")
				.jsonPath("$.rows[1].limit").isEqualTo("50.00")
				.jsonPath("$.rows[1].remaining").isEqualTo("50.00")
				.jsonPath("$.rows[2].amount").isEqualTo("0.00")
				.jsonPath("$.rows[2].share").isEqualTo("0.0")
				.jsonPath("$.rows[2].limit").isEqualTo("300.00")
				.jsonPath("$.rows[2].remaining").isEqualTo("300.00");

		assertSharesSumTo100("2017-04");
	}

	// R5
	@Test
	void remainingIsLimitMinusAmountAndNegativeWhenExceeded() {
		Category j = createActiveCategory("R5-J", null);
		Category k = createActiveCategory("R5-K", null);
		Category l = createActiveCategory("R5-L", null);
		Category m = createActiveCategory("R5-M", null);

		insertExpense(j.getId(), "120.00", "2017-05-01");
		insertExpense(j.getId(), "30.50", "2017-05-02");
		insertLimit(j.getId(), "2017-05", "100.00");

		insertExpense(k.getId(), "100.00", "2017-05-01");
		insertLimit(k.getId(), "2017-05", "100.00");

		insertExpense(l.getId(), "20.00", "2017-05-01");
		insertLimit(l.getId(), "2017-05", "25.00");

		insertExpense(m.getId(), "10.00", "2017-05-01");

		get("/api/reports/by-category?month=2017-05")
				.jsonPath("$.totalAmount").isEqualTo("280.50")
				.jsonPath("$.rows[*].category.id").isEqualTo(ids(j.getId(), k.getId(), l.getId(), m.getId()))
				.jsonPath("$.rows[0].amount").isEqualTo("150.50")
				.jsonPath("$.rows[0].remaining").isEqualTo("-50.50")
				.jsonPath("$.rows[0].share").isEqualTo("53.7")
				.jsonPath("$.rows[1].amount").isEqualTo("100.00")
				.jsonPath("$.rows[1].remaining").isEqualTo("0.00")
				.jsonPath("$.rows[1].share").isEqualTo("35.7")
				.jsonPath("$.rows[2].amount").isEqualTo("20.00")
				.jsonPath("$.rows[2].remaining").isEqualTo("5.00")
				.jsonPath("$.rows[2].share").isEqualTo("7.1")
				.jsonPath("$.rows[3].amount").isEqualTo("10.00")
				.jsonPath("$.rows[3].limit").isEqualTo(null)
				.jsonPath("$.rows[3].remaining").isEqualTo(null)
				.jsonPath("$.rows[3].share").isEqualTo("3.6");

		assertSharesSumTo100("2017-05");
	}

	// R6
	@Test
	void sortsByAmountDescThenCategoryId() {
		// Two tied pairs: N2/N3 at amount 50.00, N4/N5 at amount 0.00. Their names (and, for
		// N4/N5, limits) are chosen so that alphabetical name order, and limit order, run opposite
		// to the expected id-ascending tie-break, instead of coinciding with it.
		Category n1 = createActiveCategory("R6-N1", null);
		Category n2 = createActiveCategory("R6-Y2", null);
		Category n3 = createActiveCategory("R6-A3", null);
		Category n4 = createActiveCategory("R6-Z4", null);
		Category n5 = createActiveCategory("R6-B5", null);
		Category n6 = createActiveCategory("R6-N6", null);

		insertExpense(n1.getId(), "10.00", "2017-06-01");
		insertExpense(n3.getId(), "20.00", "2017-06-01");
		insertExpense(n3.getId(), "30.00", "2017-06-02");
		insertExpense(n2.getId(), "50.00", "2017-06-01");
		insertLimit(n4.getId(), "2017-06", "500.00");
		insertLimit(n5.getId(), "2017-06", "5.00");
		insertExpense(n6.getId(), "0.01", "2017-06-01");

		get("/api/reports/by-category?month=2017-06")
				.jsonPath("$.rows[*].category.id")
				.isEqualTo(ids(n2.getId(), n3.getId(), n1.getId(), n6.getId(), n4.getId(), n5.getId()));
	}

	// R7
	@Test
	void sharesSumTo100WithinRounding() {
		Category p = createActiveCategory("R7-P", null);
		Category q = createActiveCategory("R7-Q", null);
		Category r = createActiveCategory("R7-R", null);
		Category s = createActiveCategory("R7-S", null);

		insertExpense(p.getId(), "10.00", "2017-07-01");
		insertExpense(q.getId(), "10.00", "2017-07-01");
		insertExpense(r.getId(), "10.00", "2017-07-01");
		insertLimit(s.getId(), "2017-07", "40.00");

		get("/api/reports/by-category?month=2017-07")
				.jsonPath("$.rows[*].category.id").isEqualTo(ids(p.getId(), q.getId(), r.getId(), s.getId()))
				.jsonPath("$.rows[0].share").isEqualTo("33.3")
				.jsonPath("$.rows[1].share").isEqualTo("33.3")
				.jsonPath("$.rows[2].share").isEqualTo("33.3")
				.jsonPath("$.rows[3].share").isEqualTo("0.0");
		assertSharesSumTo100("2017-07");

		Category t = createActiveCategory("R7-T", null);
		Category u = createActiveCategory("R7-U", null);
		insertExpense(t.getId(), "3.51", "2017-08-01");
		insertExpense(u.getId(), "0.49", "2017-08-01");

		get("/api/reports/by-category?month=2017-08")
				.jsonPath("$.totalAmount").isEqualTo("4.00")
				.jsonPath("$.rows[0].category.id").isEqualTo(t.getId().intValue())
				.jsonPath("$.rows[0].share").isEqualTo("87.8")
				.jsonPath("$.rows[1].category.id").isEqualTo(u.getId().intValue())
				.jsonPath("$.rows[1].share").isEqualTo("12.3");
		assertSharesSumTo100("2017-08");

		Category v = createActiveCategory("R7-V", null);
		insertExpense(v.getId(), "42.00", "2017-09-01");

		get("/api/reports/by-category?month=2017-09")
				.jsonPath("$.rows[0].share").isEqualTo("100.0");
		assertSharesSumTo100("2017-09");
	}

	// R8
	@Test
	void includesArchivedCategories() {
		Category w = createActiveCategory("R8-W", null);
		Category y = createActiveCategory("R8-Y", null);

		insertExpense(w.getId(), "60.00", "2017-10-01");
		Category managedW = categories.findById(w.getId()).orElseThrow();
		managedW.setArchived(true);
		categories.saveAndFlush(managedW);

		Category x = createArchivedCategory("R8-X");
		insertLimit(x.getId(), "2017-10", "80.00");

		insertExpense(y.getId(), "40.00", "2017-10-01");

		get("/api/reports/by-category?month=2017-10")
				.jsonPath("$.totalAmount").isEqualTo("100.00")
				.jsonPath("$.rows[*].category.id").isEqualTo(ids(w.getId(), y.getId(), x.getId()))
				.jsonPath("$.rows[0].amount").isEqualTo("60.00")
				.jsonPath("$.rows[0].share").isEqualTo("60.0")
				.jsonPath("$.rows[1].amount").isEqualTo("40.00")
				.jsonPath("$.rows[1].share").isEqualTo("40.0")
				.jsonPath("$.rows[2].amount").isEqualTo("0.00")
				.jsonPath("$.rows[2].share").isEqualTo("0.0")
				.jsonPath("$.rows[2].limit").isEqualTo("80.00")
				.jsonPath("$.rows[2].remaining").isEqualTo("80.00")
				.jsonPath("$.rows[0].category.archived").doesNotExist();
	}

	// R9
	@Test
	void totalAmountIsExactAndMatchesExpenseList() {
		Category z1 = createActiveCategory("R9-Z1", null);
		Category z2 = createActiveCategory("R9-Z2", null);

		insertExpense(z1.getId(), "9999999999.99", "2017-11-01");
		insertExpense(z1.getId(), "9999999999.99", "2017-11-02");
		insertLimit(z1.getId(), "2017-11", "9999999999.99");

		insertExpense(z2.getId(), "0.10", "2017-11-03");
		insertExpense(z2.getId(), "0.20", "2017-11-04");

		AtomicReference<String> reportTotal = new AtomicReference<>();
		get("/api/reports/by-category?month=2017-11")
				.jsonPath("$.totalAmount").isEqualTo("20000000000.28")
				.jsonPath("$.totalAmount").value(String.class, reportTotal::set)
				.jsonPath("$.rows[0].category.id").isEqualTo(z1.getId().intValue())
				.jsonPath("$.rows[0].amount").isEqualTo("19999999999.98")
				.jsonPath("$.rows[0].share").isEqualTo("100.0")
				.jsonPath("$.rows[0].remaining").isEqualTo("-9999999999.99")
				.jsonPath("$.rows[1].category.id").isEqualTo(z2.getId().intValue())
				.jsonPath("$.rows[1].amount").isEqualTo("0.30")
				.jsonPath("$.rows[1].share").isEqualTo("0.0");

		client.get().uri("/api/expenses?from=2017-11-01&to=2017-11-30")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.totalAmount").isEqualTo(reportTotal.get());
	}

	// R10
	private static Stream<Arguments> missingOrMalformedMonths() {
		return Stream.of(
				Arguments.of("/api/reports/by-category", false),
				Arguments.of("/api/reports/by-category?month=", false),
				Arguments.of("/api/reports/by-category?month=2026-7", true),
				Arguments.of("/api/reports/by-category?month=26-07", true),
				Arguments.of("/api/reports/by-category?month=2026-13", true),
				Arguments.of("/api/reports/by-category?month=2026-00", true),
				Arguments.of("/api/reports/by-category?month=2026-07-01", true),
				Arguments.of("/api/reports/by-category?month=07-2026", true),
				Arguments.of("/api/reports/by-category?month=2026/07", true),
				Arguments.of("/api/reports/by-category?month=abc", true));
	}

	@ParameterizedTest
	@MethodSource("missingOrMalformedMonths")
	void rejectsMissingOrMalformedMonth(String uri, boolean malformed) {
		var spec = badRequest(uri)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("month");

		if (malformed) {
			spec.jsonPath("$.errors[0].message").value(String.class, msg -> assertThat(msg)
					.isEqualTo("invalid value")
					.doesNotContain("java.")
					.doesNotContain("Failed to convert"));
		}
		else {
			spec.jsonPath("$.errors[0].message").value(String.class, msg -> assertThat(msg).isNotBlank());
		}
	}

	// R11
	@Test
	void reflectsLimitAndExpenseChanges() {
		Category aa = createActiveCategory("R11-AA", null);

		get("/api/reports/by-category?month=2017-12")
				.jsonPath("$.rows.length()").isEqualTo(0);

		Map<String, Object> putBody = new HashMap<>();
		putBody.put("categoryId", aa.getId());
		putBody.put("month", "2017-12");
		putBody.put("amount", "150.00");

		AtomicReference<Long> limitIdRef = new AtomicReference<>();
		client.put().uri("/api/budget-limits").contentType(MediaType.APPLICATION_JSON).body(putBody)
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, limitIdRef::set);

		get("/api/reports/by-category?month=2017-12")
				.jsonPath("$.rows.length()").isEqualTo(1)
				.jsonPath("$.rows[0].amount").isEqualTo("0.00")
				.jsonPath("$.rows[0].limit").isEqualTo("150.00")
				.jsonPath("$.rows[0].remaining").isEqualTo("150.00");

		// The expense is inserted through JdbcTemplate with "returning id", so the id is captured
		// directly for the final DELETE below.
		long expenseId = insertExpense(aa.getId(), "200.00", "2017-12-24");

		get("/api/reports/by-category?month=2017-12")
				.jsonPath("$.rows.length()").isEqualTo(1)
				.jsonPath("$.rows[0].amount").isEqualTo("200.00")
				.jsonPath("$.rows[0].share").isEqualTo("100.0")
				.jsonPath("$.rows[0].remaining").isEqualTo("-50.00")
				.jsonPath("$.totalAmount").isEqualTo("200.00");

		client.delete().uri("/api/budget-limits/{id}", limitIdRef.get())
				.exchange().expectStatus().isNoContent();

		get("/api/reports/by-category?month=2017-12")
				.jsonPath("$.rows.length()").isEqualTo(1)
				.jsonPath("$.rows[0].limit").isEqualTo(null)
				.jsonPath("$.rows[0].remaining").isEqualTo(null);

		client.delete().uri("/api/expenses/{id}", expenseId)
				.exchange().expectStatus().isNoContent();

		get("/api/reports/by-category?month=2017-12")
				.jsonPath("$.rows.length()").isEqualTo(0);
	}

}
