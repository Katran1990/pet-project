package dev.katran.pet.expense;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpenseListIT {

	// 2026-07-01 00:30 CEST in Europe/Warsaw (current month: July) while it is still 2026-06-30 in UTC (June).
	@TestBean
	Clock clock;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
	}

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

	private Category createActiveCategory(String prefix) {
		return categories.saveAndFlush(new Category(uniqueName(prefix), "cart"));
	}

	private Category createArchivedCategory(String prefix) {
		Category category = new Category(uniqueName(prefix), "cart");
		category.setArchived(true);
		return categories.saveAndFlush(category);
	}

	private static Instant defaultCreatedAt(String spentOn) {
		return LocalDate.parse(spentOn).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
	}

	private long insertExpense(Category category, String amount, String spentOn, String note) {
		return insertExpense(category.getId(), amount, spentOn, note, defaultCreatedAt(spentOn));
	}

	private long insertExpense(long categoryId, String amount, String spentOn, String note, Instant createdAt) {
		return jdbcTemplate.queryForObject(
				"insert into expense (category_id, amount, spent_on, note, created_at) values (?, ?, ?, ?, ?) "
						+ "returning id",
				Long.class, categoryId, new BigDecimal(amount), LocalDate.parse(spentOn), note,
				Timestamp.from(createdAt));
	}

	private void insertManyExpenses(long categoryId, int count, LocalDate firstDate) {
		List<Object[]> batchArgs = new ArrayList<>();
		Instant base = firstDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		for (int i = 0; i < count; i++) {
			LocalDate spentOn = firstDate.plusDays(i % 28);
			batchArgs.add(new Object[] {
					categoryId, new BigDecimal("1.00"), spentOn, null, Timestamp.from(base.plusSeconds(i)) });
		}
		jdbcTemplate.batchUpdate(
				"insert into expense (category_id, amount, spent_on, note, created_at) values (?, ?, ?, ?, ?)",
				batchArgs);
	}

	// ---- HTTP helpers -----------------------------------------------------
	// JSON numbers come back through JsonPath (json-smart) as Integer, not Long, so ids (and other
	// long/Long values) must be compared as int - the same convention ExpenseControllerIT uses via
	// Long#intValue(). Ids stay well within int range for the lifetime of this test class.

	private static List<Integer> ids(long... values) {
		List<Integer> result = new ArrayList<>();
		for (long value : values) {
			result.add((int) value);
		}
		return result;
	}

	private RestTestClient.BodyContentSpec ok(String uri) {
		return client.get().uri(uri).exchange().expectStatus().isOk().expectBody();
	}

	private RestTestClient.BodyContentSpec ok(String uri, String headerName, String headerValue) {
		return client.get().uri(uri).header(headerName, headerValue).exchange()
				.expectStatus().isOk().expectBody();
	}

	private RestTestClient.BodyContentSpec badRequest(String uri) {
		return client.get().uri(uri).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses");
	}

	// L1
	@Test
	void filtersByInclusiveDateRange() {
		Category c = createActiveCategory("L1-C");
		insertExpense(c, "10.00", "2025-03-09", null); // outside the range: must not appear
		long id0310 = insertExpense(c, "20.00", "2025-03-10", null);
		long id0315 = insertExpense(c, "30.00", "2025-03-15", null);
		long id0320 = insertExpense(c, "40.00", "2025-03-20", null);
		insertExpense(c, "50.00", "2025-03-21", null); // outside the range: must not appear

		ok("/api/expenses?from=2025-03-10&to=2025-03-20&categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(id0320, id0315, id0310))
				.jsonPath("$.totalItems").isEqualTo(3)
				.jsonPath("$.totalAmount").isEqualTo("90.00");

		ok("/api/expenses?from=2025-03-10&to=2025-03-10&categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(id0310))
				.jsonPath("$.totalItems").isEqualTo(1)
				.jsonPath("$.totalAmount").isEqualTo("20.00");
	}

	// L2
	@Test
	void filtersByMultipleCategoryIds() {
		Category a = createActiveCategory("L2-A");
		Category b = createActiveCategory("L2-B");
		Category c = createActiveCategory("L2-C");
		long idA = insertExpense(a, "10.00", "2025-04-05", null);
		long idB = insertExpense(b, "20.00", "2025-04-15", null);
		long idC = insertExpense(c, "40.00", "2025-04-25", null);

		String range = "from=2025-04-01&to=2025-04-30";

		ok("/api/expenses?" + range + "&categoryIds=" + a.getId() + "," + b.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(idB, idA))
				.jsonPath("$.totalItems").isEqualTo(2)
				.jsonPath("$.totalAmount").isEqualTo("30.00");

		ok("/api/expenses?" + range + "&categoryIds=" + a.getId() + "," + b.getId() + "," + a.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(idB, idA))
				.jsonPath("$.totalItems").isEqualTo(2)
				.jsonPath("$.totalAmount").isEqualTo("30.00");

		ok("/api/expenses?" + range + "&categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(idC))
				.jsonPath("$.totalItems").isEqualTo(1)
				.jsonPath("$.totalAmount").isEqualTo("40.00");
	}

	// L3
	@Test
	void archivedCategoryIdIsKnown() {
		Category x = createArchivedCategory("L3-X");
		Category a = createActiveCategory("L3-A");
		long idX = insertExpense(x, "15.00", "2025-05-10", null);
		long idA = insertExpense(a, "25.00", "2025-05-20", null);

		String range = "from=2025-05-01&to=2025-05-31";

		ok("/api/expenses?" + range + "&categoryIds=" + x.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(idX))
				.jsonPath("$.items[0].category.id").isEqualTo(x.getId().intValue());

		ok("/api/expenses?" + range + "&categoryIds=" + x.getId() + "," + a.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(idA, idX));
	}

	// L4
	@Test
	void omittedOrEmptyCategoryIdsMeansAllCategories() {
		Category a = createActiveCategory("L4-A");
		Category b = createArchivedCategory("L4-B");
		long idA = insertExpense(a, "10.00", "2025-06-05", null);
		long idB = insertExpense(b, "20.00", "2025-06-20", null);

		ok("/api/expenses?from=2025-06-01&to=2025-06-30")
				.jsonPath("$.items[*].id").isEqualTo(ids(idB, idA))
				.jsonPath("$.totalItems").isEqualTo(2)
				.jsonPath("$.totalAmount").isEqualTo("30.00");

		ok("/api/expenses?from=2025-06-01&to=2025-06-30&categoryIds=")
				.jsonPath("$.items[*].id").isEqualTo(ids(idB, idA))
				.jsonPath("$.totalItems").isEqualTo(2)
				.jsonPath("$.totalAmount").isEqualTo("30.00");
	}

	// L5
	@Test
	void unknownCategoryIdReturns400() {
		Category a = createActiveCategory("L5-A");
		long max = Long.MAX_VALUE;

		badRequest("/api/expenses?categoryIds=" + a.getId() + "," + max)
				.jsonPath("$.title").isEqualTo("Bad Request")
				.jsonPath("$.detail").isEqualTo("Invalid request content.")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryIds")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found: " + max);

		badRequest("/api/expenses?categoryIds=0")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryIds")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found: 0");

		badRequest("/api/expenses?categoryIds=" + max + "," + (max - 1) + "," + max)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryIds")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found: " + max + ", " + (max - 1));
	}

	// L6
	@Test
	void sortsBySpentOnDescThenCreatedAtDesc() {
		Category c = createActiveCategory("L6-C");
		long r1 = insertExpense(c.getId(), "1.00", "2025-07-10", null, Instant.parse("2025-07-10T08:00:00Z"));
		long r2 = insertExpense(c.getId(), "1.00", "2025-07-12", null, Instant.parse("2025-07-12T07:00:00Z"));
		long r3 = insertExpense(c.getId(), "1.00", "2025-07-10", null, Instant.parse("2025-07-10T12:00:00Z"));
		long r4 = insertExpense(c.getId(), "1.00", "2025-07-08", null, Instant.parse("2025-07-20T00:00:00Z"));
		long r5 = insertExpense(c.getId(), "1.00", "2025-07-10", null, Instant.parse("2025-07-10T10:00:00Z"));
		long r6 = insertExpense(c.getId(), "1.00", "2025-07-09", null, Instant.parse("2025-07-09T09:00:00Z"));
		long r7 = insertExpense(c.getId(), "1.00", "2025-07-09", null, Instant.parse("2025-07-09T09:00:00Z"));

		// Expected overall order: r2, r3, r5, r1, r7, r6, r4 (spentOn desc, createdAt desc, id desc
		// as the final tie-breaker for r6/r7, which share both spentOn and createdAt).
		String base = "/api/expenses?from=2025-07-01&to=2025-07-31&categoryIds=" + c.getId() + "&size=3";

		ok(base + "&page=0").jsonPath("$.items[*].id").isEqualTo(ids(r2, r3, r5));
		ok(base + "&page=1").jsonPath("$.items[*].id").isEqualTo(ids(r1, r7, r6));
		ok(base + "&page=2").jsonPath("$.items[*].id").isEqualTo(ids(r4));
	}

	// L7
	@Test
	void totalAmountCoversAllPages() {
		Category c = createActiveCategory("L7-C");
		long id1 = insertExpense(c, "10.00", "2025-08-01", null);
		long id2 = insertExpense(c, "20.50", "2025-08-02", null);
		long id3 = insertExpense(c, "0.01", "2025-08-03", null);
		long id4 = insertExpense(c, "100.00", "2025-08-04", null);
		long id5 = insertExpense(c, "5.49", "2025-08-05", null);

		String base = "/api/expenses?from=2025-08-01&to=2025-08-31&categoryIds=" + c.getId() + "&size=2";

		ok(base + "&page=0")
				.jsonPath("$.items[*].id").isEqualTo(ids(id5, id4))
				.jsonPath("$.page").isEqualTo(0)
				.jsonPath("$.size").isEqualTo(2)
				.jsonPath("$.totalItems").isEqualTo(5)
				.jsonPath("$.totalAmount").isEqualTo("136.00");

		ok(base + "&page=1")
				.jsonPath("$.items[*].id").isEqualTo(ids(id3, id2))
				.jsonPath("$.page").isEqualTo(1)
				.jsonPath("$.size").isEqualTo(2)
				.jsonPath("$.totalItems").isEqualTo(5)
				.jsonPath("$.totalAmount").isEqualTo("136.00");

		ok(base + "&page=2")
				.jsonPath("$.items[*].id").isEqualTo(ids(id1))
				.jsonPath("$.page").isEqualTo(2)
				.jsonPath("$.size").isEqualTo(2)
				.jsonPath("$.totalItems").isEqualTo(5)
				.jsonPath("$.totalAmount").isEqualTo("136.00");

		ok(base + "&page=3")
				.jsonPath("$.items").isEmpty()
				.jsonPath("$.totalItems").isEqualTo(5)
				.jsonPath("$.totalAmount").isEqualTo("136.00");

		ok("/api/expenses?from=2025-08-01&to=2025-08-31&categoryIds=" + c.getId() + "&page=2147483647&size=200")
				.jsonPath("$.items").isEmpty()
				.jsonPath("$.totalItems").isEqualTo(5)
				.jsonPath("$.totalAmount").isEqualTo("136.00");
	}

	// L8
	@Test
	void appliesDefaultAndMaximumPageSize() {
		Category c = createActiveCategory("L8-C");
		insertManyExpenses(c.getId(), 51, LocalDate.parse("2025-09-01"));
		String range = "from=2025-09-01&to=2025-09-30&categoryIds=" + c.getId();

		ok("/api/expenses?" + range)
				.jsonPath("$.page").isEqualTo(0)
				.jsonPath("$.size").isEqualTo(50)
				.jsonPath("$.items.length()").isEqualTo(50)
				.jsonPath("$.totalItems").isEqualTo(51);

		ok("/api/expenses?" + range + "&page=1")
				.jsonPath("$.items.length()").isEqualTo(1);

		ok("/api/expenses?" + range + "&size=200")
				.jsonPath("$.items.length()").isEqualTo(51)
				.jsonPath("$.size").isEqualTo(200);

		ok("/api/expenses?" + range + "&size=1")
				.jsonPath("$.items.length()").isEqualTo(1);

		ok("/api/expenses?" + range + "&page=&size=")
				.jsonPath("$.page").isEqualTo(0)
				.jsonPath("$.size").isEqualTo(50)
				.jsonPath("$.items.length()").isEqualTo(50);
	}

	// L9
	@Test
	void emptyResult() {
		Category c = createActiveCategory("L9-C");

		ok("/api/expenses?from=2025-10-01&to=2025-10-31&categoryIds=" + c.getId())
				.jsonPath("$.items").isEmpty()
				.jsonPath("$.page").isEqualTo(0)
				.jsonPath("$.size").isEqualTo(50)
				.jsonPath("$.totalItems").isEqualTo(0)
				.jsonPath("$.totalAmount").isEqualTo("0.00");

		ok("/api/expenses?from=1990-01-01&to=1990-01-31")
				.jsonPath("$.items").isEmpty()
				.jsonPath("$.totalItems").isEqualTo(0)
				.jsonPath("$.totalAmount").isEqualTo("0.00");
	}

	// L10
	@Test
	void defaultsToCurrentMonthInAppZone() {
		Category c = createActiveCategory("L10-C");
		long id0630 = insertExpense(c, "1.00", "2026-06-30", null);
		long id0701 = insertExpense(c, "2.00", "2026-07-01", null);
		long id0731 = insertExpense(c, "3.00", "2026-07-31", null);
		long id0801 = insertExpense(c, "4.00", "2026-08-01", null);

		ok("/api/expenses?categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(id0731, id0701))
				.jsonPath("$.totalItems").isEqualTo(2)
				.jsonPath("$.totalAmount").isEqualTo("5.00");

		ok("/api/expenses?from=2026-06-30&categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(id0731, id0701, id0630));

		ok("/api/expenses?to=2026-08-01&categoryIds=" + c.getId())
				.jsonPath("$.items[*].id").isEqualTo(ids(id0801, id0731, id0701));

		ok("/api/expenses")
				.jsonPath("$.items[*].id").value(List.class, list -> assertThat(list)
						.contains((int) id0701, (int) id0731)
						.doesNotContain((int) id0630, (int) id0801));

		Map<String, Object> body = new HashMap<>();
		body.put("amount", "5.00");
		body.put("categoryId", c.getId());
		body.put("spentOn", "2026-07-01");
		body.put("note", null);
		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON).body(body)
				.exchange().expectStatus().isCreated();

		ok("/api/expenses?categoryIds=" + c.getId())
				.jsonPath("$.totalItems").isEqualTo(3);
	}

	// L11
	@Test
	void rejectsFromAfterTo() {
		badRequest("/api/expenses?from=2025-03-20&to=2025-03-10")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("from")
				.jsonPath("$.errors[0].message").isEqualTo("must not be after to (2025-03-20 > 2025-03-10)");

		badRequest("/api/expenses?to=2026-01-31")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("from")
				.jsonPath("$.errors[0].message").isEqualTo("must not be after to (2026-07-01 > 2026-01-31)");

		badRequest("/api/expenses?from=2026-08-01")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("from")
				.jsonPath("$.errors[0].message").isEqualTo("must not be after to (2026-08-01 > 2026-07-31)");

		badRequest("/api/expenses?from=2025-03-20&to=2025-03-10&categoryIds=" + Long.MAX_VALUE)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("from");
	}

	// L12
	private static Stream<Arguments> outOfRangePageOrSize() {
		return Stream.of(
				Arguments.of("size=201", List.of("size")),
				Arguments.of("size=0", List.of("size")),
				Arguments.of("size=-1", List.of("size")),
				Arguments.of("page=-1", List.of("page")),
				Arguments.of("page=-1&size=201", List.of("page", "size")));
	}

	@ParameterizedTest
	@MethodSource("outOfRangePageOrSize")
	void rejectsOutOfRangePageOrSize(String query, List<String> expectedFields) {
		var spec = badRequest("/api/expenses?" + query)
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}
	}

	// L13
	private static Stream<Arguments> malformedParams() {
		return Stream.of(
				Arguments.of("from=2026-02-30", "from"),
				Arguments.of("from=31.01.2026", "from"),
				Arguments.of("from=1/31/26", "from"),
				Arguments.of("to=2026/01/31", "to"),
				Arguments.of("to=abc", "to"),
				Arguments.of("page=abc", "page"),
				Arguments.of("page=1.5", "page"),
				Arguments.of("page=2147483648", "page"),
				Arguments.of("size=abc", "size"),
				Arguments.of("categoryIds=abc", "categoryIds"),
				Arguments.of("categoryIds=1,x", "categoryIds"),
				Arguments.of("categoryIds=1.5", "categoryIds"),
				Arguments.of("categoryIds=9223372036854775808", "categoryIds"),
				Arguments.of("categoryIds=1,,2", "categoryIds"),
				Arguments.of("categoryIds=,", "categoryIds"));
	}

	@ParameterizedTest
	@MethodSource("malformedParams")
	void rejectsMalformedParams(String query, String expectedField) {
		badRequest("/api/expenses?" + query)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo(expectedField)
				.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m)
						.isEqualTo("invalid value")
						.doesNotContain("java.")
						.doesNotContain("Failed to convert"));
	}

	@Test
	void rejectsTwoMalformedTypeErrors() {
		badRequest("/api/expenses?from=abc&page=abc")
				.jsonPath("$.errors.length()").isEqualTo(2)
				.jsonPath("$.errors[0].field").isEqualTo("from")
				.jsonPath("$.errors[0].message").isEqualTo("invalid value")
				.jsonPath("$.errors[1].field").isEqualTo("page")
				.jsonPath("$.errors[1].message").isEqualTo("invalid value");
	}

	// Accepted behaviour: Spring Framework 7.0.9's DataBinder.validateConstructorArgument calls
	// SmartValidator.validateValue, which Spring Boot 4.1.1's ValidatorAdapter does not override.
	// The default implementation throws IllegalArgumentException, which DataBinder swallows, so a
	// type error on one constructor argument (page=abc) suppresses Bean Validation of every other
	// argument (size=500 never gets its own error, unlike a request with only size=500).
	@Test
	void typeErrorSkipsBeanValidation() {
		badRequest("/api/expenses?page=abc&size=500")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("page")
				.jsonPath("$.errors[0].message").isEqualTo("invalid value");
	}

	// L14
	@Test
	void itemsUseExpenseResponseShape() {
		Category c = createActiveCategory("L14-C");
		long id = insertExpense(c, "12.3", "2025-11-15", null);

		ok("/api/expenses?from=2025-11-01&to=2025-11-30&categoryIds=" + c.getId())
				.jsonPath("$.items.length()").isEqualTo(1)
				.jsonPath("$.items[0].id").isEqualTo((int) id)
				.jsonPath("$.items[0].amount").isEqualTo("12.30")
				.jsonPath("$.items[0].currency").isEqualTo("PLN")
				.jsonPath("$.items[0].spentOn").isEqualTo("2025-11-15")
				.jsonPath("$.items[0].note").isEqualTo(null)
				.jsonPath("$.items[0].createdAt").exists()
				.jsonPath("$.items[0].category.id").isEqualTo(c.getId().intValue())
				.jsonPath("$.items[0].category.name").isEqualTo(c.getName())
				.jsonPath("$.items[0].category.icon").isEqualTo("cart")
				.jsonPath("$.items[0].categoryId").doesNotExist()
				.jsonPath("$.items[0].category.archived").doesNotExist()
				.jsonPath("$.totalAmount").isEqualTo("12.30");
	}

	// L15
	@Test
	void totalAmountIsExact() {
		Category c = createActiveCategory("L15-C");
		Category d = createActiveCategory("L15-D");
		insertExpense(c, "0.10", "2025-12-01", null);
		insertExpense(c, "0.20", "2025-12-02", null);
		insertExpense(d, "9999999999.99", "2025-12-03", null);
		insertExpense(d, "9999999999.99", "2025-12-04", null);

		ok("/api/expenses?from=2025-12-01&to=2025-12-31&categoryIds=" + c.getId())
				.jsonPath("$.totalAmount").isEqualTo("0.30");

		ok("/api/expenses?from=2025-12-01&to=2025-12-31&categoryIds=" + d.getId())
				.jsonPath("$.totalAmount").isEqualTo("19999999999.98");
	}

	// L16
	@Test
	void ignoresFromRequestHeader() {
		Category c = createActiveCategory("L16-C");
		long id = insertExpense(c, "1.00", "2026-07-15", null);

		ok("/api/expenses?categoryIds=" + c.getId(), "From", "bot@example.com")
				.jsonPath("$.items[*].id").isEqualTo(ids(id));
	}

}
