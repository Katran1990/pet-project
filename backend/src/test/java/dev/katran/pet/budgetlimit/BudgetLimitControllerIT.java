package dev.katran.pet.budgetlimit;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BudgetLimitControllerIT {

	@LocalServerPort
	private int port;

	@Autowired
	private BudgetLimitRepository limits;

	@Autowired
	private CategoryRepository categories;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + this.port).build();
	}

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

	private Map<String, Object> putBody(Object categoryId, String month, Object amount) {
		Map<String, Object> body = new HashMap<>();
		body.put("categoryId", categoryId);
		body.put("month", month);
		body.put("amount", amount);
		return body;
	}

	private RestTestClient.ResponseSpec put(Object body) {
		return client.put()
				.uri("/api/budget-limits")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange();
	}

	private BigDecimal dbAmount(long id) {
		return jdbcTemplate.queryForObject("select amount from budget_limit where id = ?", BigDecimal.class, id);
	}

	private LocalDate dbMonth(long id) {
		return jdbcTemplate.queryForObject("select month from budget_limit where id = ?", LocalDate.class, id);
	}

	private Long dbCategoryId(long id) {
		return jdbcTemplate.queryForObject("select category_id from budget_limit where id = ?", Long.class, id);
	}

	private long countByCategoryAndMonth(Long categoryId, LocalDate month) {
		return jdbcTemplate.queryForObject(
				"select count(*) from budget_limit where category_id = ? and month = ?",
				Long.class, categoryId, month);
	}

	private long countByCategory(Long categoryId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from budget_limit where category_id = ?", Long.class, categoryId);
	}

	private long countByMonth(LocalDate month) {
		return jdbcTemplate.queryForObject(
				"select count(*) from budget_limit where month = ?", Long.class, month);
	}

	// B1
	@Test
	void putCreatesLimitAndReturns200() {
		Category category = createActiveCategory("B1");
		AtomicReference<Long> idRef = new AtomicReference<>();

		EntityExchangeResult<byte[]> result = put(putBody(category.getId(), "2030-01", "1500.00"))
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").value(Long.class, idRef::set)
				.jsonPath("$.month").isEqualTo("2030-01")
				.jsonPath("$.amount").isEqualTo("1500.00")
				.jsonPath("$.category.id").isEqualTo(category.getId().intValue())
				.jsonPath("$.category.name").isEqualTo(category.getName())
				.jsonPath("$.category.icon").isEqualTo(category.getIcon())
				.jsonPath("$.categoryId").doesNotExist()
				.jsonPath("$.category.archived").doesNotExist()
				.returnResult();

		assertThat(result.getResponseHeaders().getFirst("Location")).isNull();
		long id = idRef.get();

		assertThat(dbMonth(id)).isEqualTo(LocalDate.of(2030, 1, 1));
		assertThat(dbAmount(id)).isEqualByComparingTo("1500.00");
		assertThat(dbCategoryId(id)).isEqualTo(category.getId());

		// A past month is accepted (decision 10).
		put(putBody(category.getId(), "2020-01", "10"))
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("10.00")
				.jsonPath("$.month").isEqualTo("2020-01");
	}

	// B2
	@Test
	void secondPutUpdatesInsteadOfDuplicating() {
		Category c = createActiveCategory("B2-C");
		Category d = createActiveCategory("B2-D");
		assertThat(c.getId()).isLessThan(d.getId());

		AtomicReference<Long> id1Ref = new AtomicReference<>();
		put(putBody(c.getId(), "2030-02", "100.00"))
				.expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, id1Ref::set);
		long id1 = id1Ref.get();

		put(putBody(c.getId(), "2030-02", "250.5"))
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo((int) id1)
				.jsonPath("$.amount").isEqualTo("250.50");

		assertThat(countByCategoryAndMonth(c.getId(), LocalDate.of(2030, 2, 1))).isEqualTo(1);
		assertThat(dbAmount(id1)).isEqualByComparingTo("250.50");

		// Idempotent repeat.
		put(putBody(c.getId(), "2030-02", "250.5"))
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo((int) id1)
				.jsonPath("$.amount").isEqualTo("250.50");

		AtomicReference<Long> id2Ref = new AtomicReference<>();
		put(putBody(c.getId(), "2030-03", "50.00"))
				.expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, id2Ref::set);
		assertThat(id2Ref.get()).isNotEqualTo(id1);

		AtomicReference<Long> idDRef = new AtomicReference<>();
		put(putBody(d.getId(), "2030-02", "80"))
				.expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, idDRef::set);
		assertThat(idDRef.get()).isNotEqualTo(id1).isNotEqualTo(id2Ref.get());

		client.get().uri("/api/budget-limits?month=2030-02")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(2)
				.jsonPath("$[0].id").isEqualTo((int) id1)
				.jsonPath("$[0].amount").isEqualTo("250.50")
				.jsonPath("$[1].id").isEqualTo(idDRef.get().intValue())
				.jsonPath("$[1].amount").isEqualTo("80.00");
	}

	// B3
	private static Stream<Arguments> amountFormats() {
		return Stream.of(
				Arguments.of("\"1500\"", "1500.00"),
				Arguments.of("\"0.01\"", "0.01"),
				Arguments.of("\"9999999999.99\"", "9999999999.99"),
				Arguments.of("200", "200.00"),
				Arguments.of("19.99", "19.99"));
	}

	@ParameterizedTest
	@MethodSource("amountFormats")
	void acceptsAmountFormats(String amountLiteral, String expected) {
		Category category = createActiveCategory("B3");
		String body = "{\"categoryId\":" + category.getId() + ",\"month\":\"2030-04\",\"amount\":" + amountLiteral + "}";

		put(body)
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo(expected);

		BigDecimal db = jdbcTemplate.queryForObject(
				"select amount from budget_limit where category_id = ? and month = date '2030-04-01'",
				BigDecimal.class, category.getId());
		assertThat(db).isEqualByComparingTo(expected);
	}

	// B4
	// expectedErrorCount is 1 for every case, except the amount whose integer part exceeds
	// numeric(12,2) (11 digits): it violates both @Digits(integer = 10) and @DecimalMax at once
	// (expense test 5 precedent), so two field errors are reported.
	private static Stream<Arguments> invalidAmounts() {
		return Stream.of(
				Arguments.of("0", 1),
				Arguments.of("\"0\"", 1),
				Arguments.of("\"0.00\"", 1),
				Arguments.of("-1", 1),
				Arguments.of("\"-5.00\"", 1),
				Arguments.of("\"1.234\"", 1),
				Arguments.of("\"200.500\"", 1),
				Arguments.of("0.30000000000000004", 1),
				Arguments.of("\"10000000000\"", 2),
				Arguments.of((Object) null, 1),
				Arguments.of("null", 1));
	}

	@ParameterizedTest
	@MethodSource("invalidAmounts")
	void rejectsInvalidAmount(String amountLiteral, int expectedErrorCount) {
		Category category = createActiveCategory("B4");
		String body = amountLiteral == null
				? "{\"categoryId\":" + category.getId() + ",\"month\":\"2030-05\"}"
				: "{\"categoryId\":" + category.getId() + ",\"month\":\"2030-05\",\"amount\":" + amountLiteral + "}";

		var spec = put(body)
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits")
				.jsonPath("$.errors.length()").isEqualTo(expectedErrorCount);

		for (int i = 0; i < expectedErrorCount; i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo("amount")
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(countByCategory(category.getId())).isZero();
	}

	// B5
	private static Stream<Arguments> unparseableAmounts() {
		return Stream.of(
				Arguments.of("\"abc\""),
				Arguments.of("true"),
				Arguments.of("1e1000000"),
				Arguments.of("\"1e2147483647\""));
	}

	@ParameterizedTest
	@MethodSource("unparseableAmounts")
	void rejectsUnparseableAmount(String amountLiteral) {
		Category category = createActiveCategory("B5");
		String body = "{\"categoryId\":" + category.getId() + ",\"month\":\"2030-05\",\"amount\":" + amountLiteral + "}";

		put(body)
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits");

		assertThat(countByCategory(category.getId())).isZero();
	}

	// B6
	private static Stream<Arguments> malformedMonths() {
		return Stream.of(
				Arguments.of("\"2026-7\""),
				Arguments.of("\"26-07\""),
				Arguments.of("\"2026-13\""),
				Arguments.of("\"2026-00\""),
				Arguments.of("\"2026-07-01\""),
				Arguments.of("\"07-2026\""),
				Arguments.of("\"2026/07\""),
				Arguments.of("\"abc\""),
				Arguments.of("202607"));
	}

	@ParameterizedTest
	@MethodSource("malformedMonths")
	void rejectsMalformedMonthInBody(String monthLiteral) {
		Category category = createActiveCategory("B6");
		String body = "{\"categoryId\":" + category.getId() + ",\"month\":" + monthLiteral + ",\"amount\":\"100.00\"}";

		put(body)
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits")
				.jsonPath("$.errors").doesNotExist();

		assertThat(countByCategory(category.getId())).isZero();
	}

	@Test
	void rejectsEmptyMonthInBody() {
		Category category = createActiveCategory("B6-Empty");
		String body = "{\"categoryId\":" + category.getId() + ",\"month\":\"\",\"amount\":\"100.00\"}";

		put(body)
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits");

		assertThat(countByCategory(category.getId())).isZero();
	}

	// B7
	private static Stream<Arguments> invalidPutBodies() {
		return Stream.of(
				Arguments.of("{\"month\":\"2030-06\",\"amount\":\"100.00\"}", List.of("categoryId")),
				Arguments.of("{\"categoryId\":null,\"month\":\"2030-06\",\"amount\":\"100.00\"}", List.of("categoryId")),
				Arguments.of("{\"categoryId\":123456789,\"amount\":\"100.00\"}", List.of("month")),
				Arguments.of("{\"categoryId\":123456789,\"month\":null,\"amount\":\"100.00\"}", List.of("month")),
				Arguments.of("{}", List.of("amount", "categoryId", "month")));
	}

	@ParameterizedTest
	@MethodSource("invalidPutBodies")
	void rejectsInvalidPutBody(String rawJsonBody, List<String> expectedFields) {
		var spec = put(rawJsonBody)
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits")
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}
	}

	// B8
	@Test
	void rejectsUnknownCategory() {
		put(putBody(Long.MAX_VALUE, "2030-06", "100.00"))
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Bad Request")
				.jsonPath("$.detail").isEqualTo("Invalid request content.")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryId")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found")
				.jsonPath("$.instance").isEqualTo("/api/budget-limits");

		assertThat(countByMonth(LocalDate.of(2030, 6, 1))).isZero();
	}

	// B9
	@Test
	void archivedCategoryReturns409() {
		Category x = createArchivedCategory("B9-X");

		put(putBody(x.getId(), "2030-08", "100"))
				.expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.title").isEqualTo("Conflict")
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo("/api/budget-limits");

		assertThat(countByCategory(x.getId())).isZero();

		Category y = createActiveCategory("B9-Y");
		AtomicReference<Long> yLimitId = new AtomicReference<>();
		put(putBody(y.getId(), "2030-08", "100.00"))
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").value(Long.class, yLimitId::set);

		Category managedY = categories.findById(y.getId()).orElseThrow();
		managedY.setArchived(true);
		categories.saveAndFlush(managedY);

		put(putBody(y.getId(), "2030-08", "200"))
				.expectStatus().isEqualTo(409);

		assertThat(dbAmount(yLimitId.get())).isEqualByComparingTo("100.00");

		client.get().uri("/api/budget-limits?month=2030-08")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(1)
				.jsonPath("$[0].id").isEqualTo(yLimitId.get().intValue())
				.jsonPath("$[0].category.id").isEqualTo(y.getId().intValue())
				.jsonPath("$[0].category.name").isEqualTo(y.getName());

		client.delete().uri("/api/budget-limits/{id}", yLimitId.get())
				.exchange().expectStatus().isNoContent();

		// (c) Precedence: an invalid amount is reported before the archived check.
		put(putBody(x.getId(), "2030-08", "0"))
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("amount");
	}

	// B10
	@Test
	void rejectedPutLeavesExistingLimitUnchanged() {
		Category category = createActiveCategory("B10");
		AtomicReference<Long> idRef = new AtomicReference<>();
		put(putBody(category.getId(), "2030-09", "100.00"))
				.expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, idRef::set);
		long id = idRef.get();

		put(putBody(category.getId(), "2030-09", "0"))
				.expectStatus().isBadRequest();

		put(putBody(category.getId(), "2030-09", "1.234"))
				.expectStatus().isBadRequest();

		String malformedMonthBody = "{\"categoryId\":" + category.getId()
				+ ",\"month\":\"2030-9\",\"amount\":\"200.00\"}";
		put(malformedMonthBody)
				.expectStatus().isBadRequest();

		put("{\"amount\":")
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.errors").doesNotExist();

		assertThat(dbAmount(id)).isEqualByComparingTo("100.00");
		assertThat(countByCategoryAndMonth(category.getId(), LocalDate.of(2030, 9, 1))).isEqualTo(1);
	}

	// B11
	@Test
	void listReturnsLimitsOfMonthWithEmbeddedCategory() {
		Category a = createActiveCategory("B11-A");
		Category b = createActiveCategory("B11-B");
		Category c = createActiveCategory("B11-C");

		AtomicReference<Long> aId = new AtomicReference<>();
		AtomicReference<Long> bId = new AtomicReference<>();
		AtomicReference<Long> cId = new AtomicReference<>();

		put(putBody(a.getId(), "2031-01", "100"))
				.expectStatus().isOk().expectBody().jsonPath("$.id").value(Long.class, aId::set);
		put(putBody(b.getId(), "2031-01", "20.5"))
				.expectStatus().isOk().expectBody().jsonPath("$.id").value(Long.class, bId::set);
		put(putBody(c.getId(), "2031-01", "300"))
				.expectStatus().isOk().expectBody().jsonPath("$.id").value(Long.class, cId::set);

		Category managedC = categories.findById(c.getId()).orElseThrow();
		managedC.setArchived(true);
		categories.saveAndFlush(managedC);

		AtomicReference<Long> aFebId = new AtomicReference<>();
		put(putBody(a.getId(), "2031-02", "999"))
				.expectStatus().isOk()
				.expectBody().jsonPath("$.id").value(Long.class, aFebId::set);

		client.get().uri("/api/budget-limits?month=2031-01")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(3)
				.jsonPath("$[0].id").isEqualTo(aId.get().intValue())
				.jsonPath("$[1].id").isEqualTo(bId.get().intValue())
				.jsonPath("$[2].id").isEqualTo(cId.get().intValue())
				.jsonPath("$[0].month").isEqualTo("2031-01")
				.jsonPath("$[1].month").isEqualTo("2031-01")
				.jsonPath("$[2].month").isEqualTo("2031-01")
				.jsonPath("$[1].amount").isEqualTo("20.50")
				.jsonPath("$[0].category.id").isEqualTo(a.getId().intValue())
				.jsonPath("$[0].category.name").isEqualTo(a.getName())
				.jsonPath("$[0].category.icon").isEqualTo(a.getIcon())
				.jsonPath("$[0].categoryId").doesNotExist()
				.jsonPath("$[0].category.archived").doesNotExist()
				.jsonPath("$[2].category.id").isEqualTo(c.getId().intValue());

		client.get().uri("/api/budget-limits?month=2031-02")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(1)
				.jsonPath("$[0].id").isEqualTo(aFebId.get().intValue())
				.jsonPath("$[0].month").isEqualTo("2031-02")
				.jsonPath("$[0].amount").isEqualTo("999.00")
				.jsonPath("$[0].category.id").isEqualTo(a.getId().intValue());

		client.get().uri("/api/budget-limits?month=2031-03")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(0);

		Category managedB = categories.findById(b.getId()).orElseThrow();
		String newName = uniqueName("B11-B-Renamed");
		managedB.setName(newName);
		categories.saveAndFlush(managedB);

		client.get().uri("/api/budget-limits?month=2031-01")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$[1].category.name").isEqualTo(newName);
	}

	// B12
	private RestTestClient.BodyContentSpec badRequestGet(String uri) {
		return client.get().uri(uri).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits");
	}

	private static Stream<Arguments> missingOrMalformedMonths() {
		return Stream.of(
				Arguments.of("/api/budget-limits", false),
				Arguments.of("/api/budget-limits?month=", false),
				Arguments.of("/api/budget-limits?month=2026-7", true),
				Arguments.of("/api/budget-limits?month=26-07", true),
				Arguments.of("/api/budget-limits?month=2026-13", true),
				Arguments.of("/api/budget-limits?month=2026-00", true),
				Arguments.of("/api/budget-limits?month=2026-07-01", true),
				Arguments.of("/api/budget-limits?month=07-2026", true),
				Arguments.of("/api/budget-limits?month=2026/07", true),
				Arguments.of("/api/budget-limits?month=abc", true));
	}

	@ParameterizedTest
	@MethodSource("missingOrMalformedMonths")
	void listRejectsMissingOrMalformedMonth(String uri, boolean malformed) {
		var spec = badRequestGet(uri)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("month");

		if (malformed) {
			spec.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m)
					.isEqualTo("invalid value")
					.doesNotContain("java.")
					.doesNotContain("Failed to convert"));
		}
		else {
			spec.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m).isNotBlank());
		}
	}

	// B13
	@Test
	void deleteReturns204() {
		Category category = createActiveCategory("B13");
		AtomicReference<Long> idRef = new AtomicReference<>();
		put(putBody(category.getId(), "2031-05", "10"))
				.expectStatus().isOk().expectBody().jsonPath("$.id").value(Long.class, idRef::set);
		long id = idRef.get();

		client.delete().uri("/api/budget-limits/{id}", id)
				.exchange().expectStatus().isNoContent().expectBody().isEmpty();

		client.get().uri("/api/budget-limits?month=2031-05")
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.length()").isEqualTo(0);

		assertThat(limits.existsById(id)).isFalse();
		assertThat(categories.existsById(category.getId())).isTrue();

		client.delete().uri("/api/budget-limits/{id}", id)
				.exchange().expectStatus().isNotFound();

		AtomicReference<Long> newIdRef = new AtomicReference<>();
		put(putBody(category.getId(), "2031-05", "10"))
				.expectStatus().isOk().expectBody().jsonPath("$.id").value(Long.class, newIdRef::set);

		assertThat(newIdRef.get()).isNotEqualTo(id);
	}

	// B14
	@Test
	void deleteUnknownOrNonNumericId() {
		long unknownId = Long.MAX_VALUE;

		client.delete().uri("/api/budget-limits/{id}", unknownId)
				.exchange().expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Budget limit not found")
				.jsonPath("$.id").isEqualTo(unknownId)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits/" + unknownId);

		client.delete().uri("/api/budget-limits/abc")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/budget-limits/abc");
	}

	// B15
	@Test
	void schemaConstraints() {
		Category c = createActiveCategory("B15-C");
		Category d = createActiveCategory("B15-D");

		jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-01-01', 1.00)",
				c.getId());

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-01-01', 2.00)",
				c.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_budget_limit_category_month");

		assertThat(countByCategoryAndMonth(c.getId(), LocalDate.of(2039, 1, 1))).isEqualTo(1);

		jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-02-01', 5.00)",
				c.getId());
		jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-01-01', 5.00)",
				d.getId());

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-03-15', 1.00)",
				c.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_budget_limit_month_first_day");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-04-01', 0)",
				c.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_budget_limit_amount_positive");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-05-01', -1)",
				c.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_budget_limit_amount_positive");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into budget_limit (category_id, month, amount) values (?, date '2039-06-01', 1.00)",
				Long.MAX_VALUE))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_budget_limit_category");

		assertThatThrownBy(() -> jdbcTemplate.update("delete from category where id = ?", c.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_budget_limit_category");
		assertThat(categories.existsById(c.getId())).isTrue();
	}

}
