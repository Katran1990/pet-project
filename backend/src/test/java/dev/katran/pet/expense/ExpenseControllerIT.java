package dev.katran.pet.expense;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpenseControllerIT {

	private static final String TODAY = "2026-06-16";
	private static final String PAST_DATE = "2026-06-10";

	// "Today" is 2026-06-16 in Europe/Warsaw (00:30 CEST) while it is still 2026-06-15 in UTC.
	@TestBean
	Clock clock;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-06-15T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
	}

	@LocalServerPort
	private int port;

	@Autowired
	private ExpenseRepository expenses;

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

	private Map<String, Object> requestBody(Long categoryId, String amount, String spentOn, String note) {
		Map<String, Object> body = new HashMap<>();
		body.put("amount", amount);
		body.put("categoryId", categoryId);
		body.put("spentOn", spentOn);
		body.put("note", note);
		return body;
	}

	private EntityExchangeResult<byte[]> postExpenseRaw(Object body) {
		return client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.returnResult();
	}

	private static long idFromLocation(String location) {
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private BigDecimal dbAmount(long id) {
		return jdbcTemplate.queryForObject("select amount from expense where id = ?", BigDecimal.class, id);
	}

	private String dbCurrency(long id) {
		return jdbcTemplate.queryForObject("select currency from expense where id = ?", String.class, id);
	}

	private Long dbCategoryId(long id) {
		return jdbcTemplate.queryForObject("select category_id from expense where id = ?", Long.class, id);
	}

	private LocalDate dbSpentOn(long id) {
		return jdbcTemplate.queryForObject("select spent_on from expense where id = ?", LocalDate.class, id);
	}

	private String dbNote(long id) {
		return jdbcTemplate.queryForObject("select note from expense where id = ?", String.class, id);
	}

	// 1
	@Test
	void createsExpenseAndReturns201() {
		Category category = createActiveCategory("Food");

		EntityExchangeResult<byte[]> result = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "200.00", PAST_DATE, "Lunch"))
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.id").exists()
				.jsonPath("$.amount").isEqualTo("200.00")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(PAST_DATE)
				.jsonPath("$.note").isEqualTo("Lunch")
				.jsonPath("$.createdAt").exists()
				.jsonPath("$.category.id").isEqualTo(category.getId().intValue())
				.jsonPath("$.category.name").isEqualTo(category.getName())
				.jsonPath("$.category.icon").isEqualTo(category.getIcon())
				.jsonPath("$.categoryId").doesNotExist()
				.returnResult();

		String location = result.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();
		long id = idFromLocation(location);
		assertThat(location).endsWith("/api/expenses/" + id);

		assertThat(dbAmount(id)).isEqualByComparingTo("200.00");
		assertThat(dbCurrency(id)).isEqualTo("PLN");
		assertThat(dbCategoryId(id)).isEqualTo(category.getId());
		assertThat(dbSpentOn(id)).isEqualTo(LocalDate.parse(PAST_DATE));
		assertThat(dbNote(id)).isEqualTo("Lunch");
	}

	// 2
	@Test
	void getReturnsExpenseWithEmbeddedCategory() {
		Category category = createActiveCategory("Food-Get");

		EntityExchangeResult<byte[]> created = postExpenseRaw(
				requestBody(category.getId(), "200.00", PAST_DATE, "Lunch"));
		String location = created.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();

		client.get()
				.uri(location)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("200.00")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(PAST_DATE)
				.jsonPath("$.note").isEqualTo("Lunch")
				.jsonPath("$.createdAt").exists()
				.jsonPath("$.category.id").isEqualTo(category.getId().intValue())
				.jsonPath("$.category.name").isEqualTo(category.getName())
				.jsonPath("$.category.icon").isEqualTo(category.getIcon())
				.jsonPath("$.category.archived").doesNotExist()
				.jsonPath("$.categoryId").doesNotExist();

		// Rename and clear the icon directly, to prove GET joins the category rather than copying it.
		Category managed = categories.findById(category.getId()).orElseThrow();
		String newName = uniqueName("Food-Get-Renamed");
		managed.setName(newName);
		managed.setIcon(null);
		categories.saveAndFlush(managed);

		client.get()
				.uri(location)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.category.name").isEqualTo(newName)
				.jsonPath("$.category.icon").isEqualTo(null);
	}

	// 3
	@Test
	void createNormalisesNote() {
		Category category = createActiveCategory("Note");

		Map<String, Object> withoutNote = new HashMap<>();
		withoutNote.put("amount", "10.00");
		withoutNote.put("categoryId", category.getId());
		withoutNote.put("spentOn", PAST_DATE);
		assertNoteNormalisedToNull(withoutNote);

		assertNoteNormalisedToNull(requestBody(category.getId(), "10.00", PAST_DATE, null));

		assertNoteNormalisedToNull(requestBody(category.getId(), "10.00", PAST_DATE, ""));

		EntityExchangeResult<byte[]> whitespace = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", PAST_DATE, "   "))
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.note").isEqualTo("   ")
				.returnResult();
		long id = idFromLocation(whitespace.getResponseHeaders().getFirst("Location"));
		assertThat(dbNote(id)).isEqualTo("   ");
	}

	private void assertNoteNormalisedToNull(Object body) {
		EntityExchangeResult<byte[]> result = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.note").isEqualTo(null)
				.returnResult();
		long id = idFromLocation(result.getResponseHeaders().getFirst("Location"));
		assertThat(dbNote(id)).isNull();
	}

	// 4
	private static Stream<Arguments> amountFormats() {
		return Stream.of(
				Arguments.of("\"200\"", "200.00"),
				Arguments.of("\"200.5\"", "200.50"),
				Arguments.of("\"200.50\"", "200.50"),
				Arguments.of("\"0.01\"", "0.01"),
				Arguments.of("\"9999999999.99\"", "9999999999.99"),
				Arguments.of("200", "200.00"),
				Arguments.of("19.99", "19.99"));
	}

	@ParameterizedTest
	@MethodSource("amountFormats")
	void acceptsAmountFormats(String amountLiteral, String expected) {
		Category category = createActiveCategory("Amount");
		String body = "{\"amount\":" + amountLiteral + ",\"categoryId\":" + category.getId()
				+ ",\"spentOn\":\"" + PAST_DATE + "\"}";

		client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo(expected);

		BigDecimal db = jdbcTemplate.queryForObject(
				"select amount from expense where category_id = ?", BigDecimal.class, category.getId());
		assertThat(db).isEqualByComparingTo(expected);
	}

	// 5
	// expectedErrorCount is 1 for every case, except the three amounts whose integer part exceeds
	// numeric(12,2) (11 digits): those violate both @Digits(integer = 10) and @DecimalMax at once,
	// so Bean Validation (no fail-fast configured) reports two field errors, not one.
	private static Stream<Arguments> invalidAmounts() {
		return Stream.of(
				Arguments.of("0", 1),
				Arguments.of("\"0\"", 1),
				Arguments.of("\"0.00\"", 1),
				Arguments.of("-1", 1),
				Arguments.of("\"-200.00\"", 1),
				Arguments.of("\"200.555\"", 1),
				Arguments.of("200.555", 1),
				Arguments.of("\"0.001\"", 1),
				Arguments.of("\"200.500\"", 1),
				Arguments.of("0.30000000000000004", 1),
				Arguments.of("\"10000000000\"", 2),
				Arguments.of("10000000000.00", 2),
				Arguments.of("\"99999999999.99\"", 2),
				Arguments.of((Object) null, 1),
				Arguments.of("null", 1));
	}

	@ParameterizedTest
	@MethodSource("invalidAmounts")
	void rejectsInvalidAmount(String amountLiteral, int expectedErrorCount) {
		Category category = createActiveCategory("BadAmount");
		long before = expenses.count();

		String body = amountLiteral == null
				? "{\"categoryId\":" + category.getId() + ",\"spentOn\":\"" + PAST_DATE + "\"}"
				: "{\"amount\":" + amountLiteral + ",\"categoryId\":" + category.getId()
						+ ",\"spentOn\":\"" + PAST_DATE + "\"}";

		var spec = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses")
				.jsonPath("$.errors.length()").isEqualTo(expectedErrorCount);

		List<String> messages = new ArrayList<>();
		for (int i = 0; i < expectedErrorCount; i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo("amount")
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> {
						assertThat(m).isNotBlank();
						messages.add(m);
					});
		}

		// Two distinct messages (locale-independent) prove two different constraints fired,
		// not the same violation duplicated.
		if (expectedErrorCount == 2) {
			assertThat(messages.get(0)).isNotEqualTo(messages.get(1));
		}

		assertThat(expenses.count()).isEqualTo(before);
	}

	// 6
	// expectDecimalMaxError is true only for the two "1e2147483647" forms: their exponent is so
	// large that Hibernate Validator's @Digits does int arithmetic (precision() - scale()) that
	// overflows and wrongly lets the value pass (decision 7.1), but @DecimalMax compares with
	// BigDecimal.compareTo (no overflow) and correctly rejects it with exactly one field error.
	// For every other case, Jackson itself rejects the body (no errors[]), so only a plain 400
	// Problem Details is asserted, never weaker.
	private static Stream<Arguments> unparseableAmounts() {
		return Stream.of(
				Arguments.of("\"abc\"", false),
				Arguments.of("\"\"", false),
				Arguments.of("true", false),
				Arguments.of("1e1000000", false),
				Arguments.of("\"1e2147483647\"", true),
				Arguments.of("1e2147483647", true));
	}

	@ParameterizedTest
	@MethodSource("unparseableAmounts")
	void rejectsUnparseableAmount(String amountLiteral, boolean expectDecimalMaxError) {
		Category category = createActiveCategory("Unparseable");
		long before = expenses.count();
		String body = "{\"amount\":" + amountLiteral + ",\"categoryId\":" + category.getId()
				+ ",\"spentOn\":\"" + PAST_DATE + "\"}";

		var spec = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses");

		if (expectDecimalMaxError) {
			spec.jsonPath("$.errors.length()").isEqualTo(1)
					.jsonPath("$.errors[0].field").isEqualTo("amount")
					.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(expenses.count()).isEqualTo(before);
	}

	// 7
	@Test
	void spentOnBoundaryUsesClockAndZone() {
		assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.parse(TODAY));

		Category category = createActiveCategory("Boundary");

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", TODAY, null))
				.exchange().expectStatus().isCreated();

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", "2026-06-15", null))
				.exchange().expectStatus().isCreated();

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", "2026-06-17", null))
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("spentOn")
				.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m).isNotBlank());

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", "2099-01-01", null))
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("spentOn")
				.jsonPath("$.errors[0].message").value(String.class, m -> assertThat(m).isNotBlank());
	}

	// 8
	private static Stream<Arguments> invalidCreateCases() {
		return Stream.of(
				Arguments.of("MISSING_CATEGORY_ID", List.of("categoryId")),
				Arguments.of("NULL_CATEGORY_ID", List.of("categoryId")),
				Arguments.of("MISSING_SPENT_ON", List.of("spentOn")),
				Arguments.of("NULL_SPENT_ON", List.of("spentOn")),
				Arguments.of("LONG_NOTE", List.of("note")),
				Arguments.of("EMPTY_BODY", List.of("amount", "categoryId", "spentOn")));
	}

	@ParameterizedTest
	@MethodSource("invalidCreateCases")
	void rejectsInvalidCreate(String caseName, List<String> expectedFields) {
		Category category = createActiveCategory("InvalidCreate");
		long before = expenses.count();
		String longNote = "n".repeat(256);

		String body = switch (caseName) {
			case "MISSING_CATEGORY_ID" -> "{\"amount\":\"10.00\",\"spentOn\":\"" + PAST_DATE + "\"}";
			case "NULL_CATEGORY_ID" ->
				"{\"amount\":\"10.00\",\"categoryId\":null,\"spentOn\":\"" + PAST_DATE + "\"}";
			case "MISSING_SPENT_ON" -> "{\"amount\":\"10.00\",\"categoryId\":" + category.getId() + "}";
			case "NULL_SPENT_ON" ->
				"{\"amount\":\"10.00\",\"categoryId\":" + category.getId() + ",\"spentOn\":null}";
			case "LONG_NOTE" -> "{\"amount\":\"10.00\",\"categoryId\":" + category.getId()
					+ ",\"spentOn\":\"" + PAST_DATE + "\",\"note\":\"" + longNote + "\"}";
			case "EMPTY_BODY" -> "{}";
			default -> throw new IllegalArgumentException(caseName);
		};

		var spec = client.post()
				.uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses")
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(expenses.count()).isEqualTo(before);
	}

	@Test
	void createAccepts255CharNote() {
		Category category = createActiveCategory("Note255");
		String note = "n".repeat(255);

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", PAST_DATE, note))
				.exchange().expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.note").isEqualTo(note);
	}

	// 9
	private static Stream<Arguments> unreadableBodies() {
		return Stream.of(
				Arguments.of("{\"amount\":"),
				Arguments.of("{\"amount\":\"10.00\",\"categoryId\":1,\"spentOn\":\"2026-02-30\"}"),
				Arguments.of("{\"amount\":\"10.00\",\"categoryId\":1,\"spentOn\":\"16.06.2026\"}"),
				Arguments.of("{\"amount\":\"10.00\",\"categoryId\":\"abc\",\"spentOn\":\"" + PAST_DATE + "\"}"));
	}

	@ParameterizedTest
	@MethodSource("unreadableBodies")
	void rejectsUnreadableBody(String rawJsonBody) {
		long before = expenses.count();

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(rawJsonBody)
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses")
				.jsonPath("$.errors").doesNotExist();

		assertThat(expenses.count()).isEqualTo(before);
	}

	// 10
	@Test
	void rejectsUnknownCategoryOnCreate() {
		long before = expenses.count();

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(Long.MAX_VALUE, "10.00", PAST_DATE, null))
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.title").isEqualTo("Bad Request")
				.jsonPath("$.detail").isEqualTo("Invalid request content.")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryId")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found")
				.jsonPath("$.instance").isEqualTo("/api/expenses");

		assertThat(expenses.count()).isEqualTo(before);
	}

	// 11
	@Test
	void rejectsArchivedCategoryOnCreate() {
		Category archived = createArchivedCategory("Archived");
		long before = expenses.count();

		client.post().uri("/api/expenses").contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(archived.getId(), "10.00", PAST_DATE, null))
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.title").isEqualTo("Conflict")
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo("/api/expenses");

		assertThat(expenses.count()).isEqualTo(before);
	}

	// 12
	@Test
	void ignoresCurrencyInRequest() {
		Category category = createActiveCategory("Currency");
		Map<String, Object> body = requestBody(category.getId(), "10.00", PAST_DATE, null);
		body.put("currency", "EUR");

		EntityExchangeResult<byte[]> result = client.post().uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange().expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.currency").isEqualTo("PLN")
				.returnResult();

		long id = idFromLocation(result.getResponseHeaders().getFirst("Location"));
		assertThat(dbCurrency(id)).isEqualTo("PLN");
	}

	// 13
	@Test
	void putReplacesExpense() {
		Category a = createActiveCategory("Put-A");
		Category b = createActiveCategory("Put-B");

		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(a.getId(), "20.00", PAST_DATE, "Original"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		AtomicReference<String> currencyRef = new AtomicReference<>();
		AtomicReference<String> createdAtRef = new AtomicReference<>();
		client.get().uri(location).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.currency").value(String.class, currencyRef::set)
				.jsonPath("$.createdAt").value(String.class, createdAtRef::set);

		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(b.getId(), "75.5", "2026-06-01", "Taxi"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo((int) id)
				.jsonPath("$.amount").isEqualTo("75.50")
				.jsonPath("$.category.id").isEqualTo(b.getId().intValue())
				.jsonPath("$.category.name").isEqualTo(b.getName())
				.jsonPath("$.spentOn").isEqualTo("2026-06-01")
				.jsonPath("$.note").isEqualTo("Taxi")
				.jsonPath("$.currency").isEqualTo(currencyRef.get())
				.jsonPath("$.createdAt").isEqualTo(createdAtRef.get());

		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("75.50")
				.jsonPath("$.category.id").isEqualTo(b.getId().intValue())
				.jsonPath("$.spentOn").isEqualTo("2026-06-01")
				.jsonPath("$.note").isEqualTo("Taxi");

		assertThat(dbAmount(id)).isEqualByComparingTo("75.50");
		assertThat(dbCategoryId(id)).isEqualTo(b.getId());
		assertThat(dbSpentOn(id)).isEqualTo(LocalDate.parse("2026-06-01"));
		assertThat(dbNote(id)).isEqualTo("Taxi");
	}

	// 14
	@Test
	void putWithoutNoteClearsNote() {
		Category category = createActiveCategory("Put-Note");
		EntityExchangeResult<byte[]> created = postExpenseRaw(
				requestBody(category.getId(), "10.00", PAST_DATE, "Has note"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		Map<String, Object> withoutNote = new HashMap<>();
		withoutNote.put("amount", "10.00");
		withoutNote.put("categoryId", category.getId());
		withoutNote.put("spentOn", PAST_DATE);
		assertPutClearsNote(location, id, withoutNote);

		putNote(location, category.getId(), "Has note again");
		assertPutClearsNote(location, id, requestBody(category.getId(), "10.00", PAST_DATE, null));

		putNote(location, category.getId(), "Has note again");
		assertPutClearsNote(location, id, requestBody(category.getId(), "10.00", PAST_DATE, ""));
	}

	private void putNote(String location, Long categoryId, String note) {
		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(categoryId, "10.00", PAST_DATE, note))
				.exchange().expectStatus().isOk();
	}

	private void assertPutClearsNote(String location, long id, Object body) {
		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.note").isEqualTo(null);
		assertThat(dbNote(id)).isNull();
	}

	// 15
	private static Stream<Arguments> invalidPutCases() {
		return Stream.of(
				Arguments.of("AMOUNT_ZERO", List.of("amount")),
				Arguments.of("AMOUNT_TOO_MANY_DECIMALS", List.of("amount")),
				Arguments.of("SPENT_ON_FUTURE", List.of("spentOn")),
				Arguments.of("MISSING_CATEGORY_ID", List.of("categoryId")),
				Arguments.of("LONG_NOTE", List.of("note")));
	}

	@ParameterizedTest
	@MethodSource("invalidPutCases")
	void putRejectsInvalidBody(String caseName, List<String> expectedFields) {
		Category category = createActiveCategory("Put-Invalid");
		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(category.getId(), "10.00", PAST_DATE, "Keep"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		String longNote = "n".repeat(256);
		String body = switch (caseName) {
			case "AMOUNT_ZERO" ->
				"{\"amount\":0,\"categoryId\":" + category.getId() + ",\"spentOn\":\"" + PAST_DATE + "\"}";
			case "AMOUNT_TOO_MANY_DECIMALS" ->
				"{\"amount\":\"1.234\",\"categoryId\":" + category.getId() + ",\"spentOn\":\"" + PAST_DATE + "\"}";
			case "SPENT_ON_FUTURE" ->
				"{\"amount\":\"10.00\",\"categoryId\":" + category.getId() + ",\"spentOn\":\"2026-06-17\"}";
			case "MISSING_CATEGORY_ID" -> "{\"amount\":\"10.00\",\"spentOn\":\"" + PAST_DATE + "\"}";
			case "LONG_NOTE" -> "{\"amount\":\"10.00\",\"categoryId\":" + category.getId()
					+ ",\"spentOn\":\"" + PAST_DATE + "\",\"note\":\"" + longNote + "\"}";
			default -> throw new IllegalArgumentException(caseName);
		};

		var spec = client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + id)
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(dbAmount(id)).isEqualByComparingTo("10.00");
		assertThat(dbCategoryId(id)).isEqualTo(category.getId());
		assertThat(dbSpentOn(id)).isEqualTo(LocalDate.parse(PAST_DATE));
		assertThat(dbNote(id)).isEqualTo("Keep");
	}

	@Test
	void putWithMalformedJsonReturns400() {
		Category category = createActiveCategory("Put-Malformed");
		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(category.getId(), "10.00", PAST_DATE, "Keep"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body("{\"amount\":")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + id)
				.jsonPath("$.errors").doesNotExist();

		assertThat(dbAmount(id)).isEqualByComparingTo("10.00");
		assertThat(dbNote(id)).isEqualTo("Keep");
	}

	// 16
	@Test
	void putKeepsCurrentArchivedCategory() {
		Category c = createActiveCategory("Keep-C");
		Category a = createActiveCategory("Keep-A");

		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(c.getId(), "50.00", PAST_DATE, "Original"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		Category managedC = categories.findById(c.getId()).orElseThrow();
		managedC.setArchived(true);
		categories.saveAndFlush(managedC);

		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(c.getId(), "300", PAST_DATE, "Updated"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("300.00")
				.jsonPath("$.note").isEqualTo("Updated")
				.jsonPath("$.category.id").isEqualTo(c.getId().intValue());

		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.category.id").isEqualTo(c.getId().intValue());

		assertThat(dbCategoryId(id)).isEqualTo(c.getId());
		assertThat(dbNote(id)).isEqualTo("Updated");

		// Moving out of an archived category to an active one is allowed.
		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(a.getId(), "300", PAST_DATE, "Updated"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.category.id").isEqualTo(a.getId().intValue());

		// A second, older expense of the (still) archived category C: GET and DELETE both work.
		Expense second = expenses.saveAndFlush(
				new Expense(managedC, new BigDecimal("20.00"), LocalDate.parse(PAST_DATE), "Second"));
		long secondId = second.getId();

		client.get().uri("/api/expenses/{id}", secondId).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.category.id").isEqualTo(c.getId().intValue());

		client.delete().uri("/api/expenses/{id}", secondId).exchange().expectStatus().isNoContent();
	}

	// 17
	@Test
	void putToDifferentArchivedCategoryReturns409() {
		Category active = createActiveCategory("Switch-Active");
		Category archivedD = createArchivedCategory("Switch-D");

		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(active.getId(), "10.00", PAST_DATE, "Original"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		// The rejected PUT asks for a different amount, spentOn and note than the stored row, so
		// "the DB row is unchanged" below is a meaningful assertion, not a tautology.
		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(archivedD.getId(), "999.99", "2026-06-01", "Attempted change"))
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + id);

		assertThat(dbAmount(id)).isEqualByComparingTo("10.00");
		assertThat(dbNote(id)).isEqualTo("Original");
		assertThat(dbSpentOn(id)).isEqualTo(LocalDate.parse(PAST_DATE));
		assertThat(dbCategoryId(id)).isEqualTo(active.getId());

		Category archivedC = createArchivedCategory("Switch-C");
		Expense inArchivedC = expenses.saveAndFlush(
				new Expense(archivedC, new BigDecimal("10.00"), LocalDate.parse(PAST_DATE), null));
		long secondId = inArchivedC.getId();

		client.put().uri("/api/expenses/{id}", secondId).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(archivedD.getId(), "10.00", PAST_DATE, null))
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + secondId);

		assertThat(dbCategoryId(secondId)).isEqualTo(archivedC.getId());
	}

	// 18
	@Test
	void putUnknownCategoryReturns400() {
		Category category = createActiveCategory("Put-UnknownCat");
		EntityExchangeResult<byte[]> created = postExpenseRaw(
				requestBody(category.getId(), "10.00", PAST_DATE, "Original"));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		// The rejected PUT asks for a different amount, spentOn and note than the stored row, so
		// "the DB row is unchanged" below is a meaningful assertion, not a tautology.
		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(Long.MAX_VALUE, "999.99", "2026-06-01", "Attempted change"))
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.title").isEqualTo("Bad Request")
				.jsonPath("$.detail").isEqualTo("Invalid request content.")
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + id)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryId")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found");

		assertThat(dbAmount(id)).isEqualByComparingTo("10.00");
		assertThat(dbNote(id)).isEqualTo("Original");
		assertThat(dbSpentOn(id)).isEqualTo(LocalDate.parse(PAST_DATE));
		assertThat(dbCategoryId(id)).isEqualTo(category.getId());
	}

	// 19
	@Test
	void deleteReturns204() {
		Category category = createActiveCategory("Delete");
		EntityExchangeResult<byte[]> created = postExpenseRaw(requestBody(category.getId(), "10.00", PAST_DATE, null));
		String location = created.getResponseHeaders().getFirst("Location");
		long id = idFromLocation(location);

		client.delete().uri(location).exchange().expectStatus().isNoContent()
				.expectBody().isEmpty();

		client.get().uri(location).exchange().expectStatus().isNotFound();
		assertThat(expenses.existsById(id)).isFalse();

		client.delete().uri(location).exchange().expectStatus().isNotFound();

		assertThat(categories.existsById(category.getId())).isTrue();
	}

	// 20
	@Test
	void unknownExpenseReturns404() {
		long unknownId = Long.MAX_VALUE;
		Category category = createActiveCategory("Unknown404");

		client.get().uri("/api/expenses/{id}", unknownId).exchange().expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Expense not found")
				.jsonPath("$.id").isEqualTo(unknownId)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + unknownId);

		client.put().uri("/api/expenses/{id}", unknownId).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(category.getId(), "10.00", PAST_DATE, null))
				.exchange().expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Expense not found")
				.jsonPath("$.id").isEqualTo(unknownId)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + unknownId);

		client.delete().uri("/api/expenses/{id}", unknownId).exchange().expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Expense not found")
				.jsonPath("$.id").isEqualTo(unknownId)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + unknownId);

		// Validation runs before the id lookup: PUT {} on the same unknown id is a 400, not a 404.
		client.put().uri("/api/expenses/{id}", unknownId).contentType(MediaType.APPLICATION_JSON)
				.body("{}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses/" + unknownId)
				.jsonPath("$.errors.length()").isEqualTo(3);

		client.get().uri("/api/expenses/abc").exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/expenses/abc");
	}

	// 21
	@Test
	void crudRoundTrip() {
		Category a = createActiveCategory("Round-A");
		Category b = createActiveCategory("Round-B");

		EntityExchangeResult<byte[]> created = client.post().uri("/api/expenses")
				.contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(a.getId(), "42.00", PAST_DATE, "Round trip"))
				.exchange().expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("42.00")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(PAST_DATE)
				.jsonPath("$.note").isEqualTo("Round trip")
				.jsonPath("$.category.id").isEqualTo(a.getId().intValue())
				.returnResult();
		String location = created.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();

		// GET matches POST, except createdAt, which is only asserted to exist (decision 7.3).
		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("42.00")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(PAST_DATE)
				.jsonPath("$.note").isEqualTo("Round trip")
				.jsonPath("$.category.id").isEqualTo(a.getId().intValue())
				.jsonPath("$.createdAt").exists();

		client.put().uri(location).contentType(MediaType.APPLICATION_JSON)
				.body(requestBody(b.getId(), "99.99", "2026-06-01", "Updated"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("99.99")
				.jsonPath("$.category.id").isEqualTo(b.getId().intValue())
				.jsonPath("$.spentOn").isEqualTo("2026-06-01")
				.jsonPath("$.note").isEqualTo("Updated");

		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("99.99")
				.jsonPath("$.category.id").isEqualTo(b.getId().intValue())
				.jsonPath("$.spentOn").isEqualTo("2026-06-01")
				.jsonPath("$.note").isEqualTo("Updated");

		client.delete().uri(location).exchange().expectStatus().isNoContent();

		client.get().uri(location).exchange().expectStatus().isNotFound();
	}

	// 22
	@Test
	void schemaDefaultsAndConstraints() {
		Category category = createActiveCategory("Schema");

		String currency = jdbcTemplate.queryForObject(
				"insert into expense (category_id, amount, spent_on) values (?, 1.00, current_date) returning currency",
				String.class, category.getId());
		assertThat(currency).isEqualTo("PLN");

		Timestamp createdAt = jdbcTemplate.queryForObject(
				"insert into expense (category_id, amount, spent_on) values (?, 1.00, current_date) returning created_at",
				Timestamp.class, category.getId());
		assertThat(createdAt).isNotNull();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into expense (category_id, amount, spent_on) values (?, 0, current_date)", category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_expense_amount_positive");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into expense (category_id, amount, spent_on) values (?, 1.00, current_date)",
				Long.MAX_VALUE))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_expense_category");

		Category toDelete = createActiveCategory("Schema-Delete");
		expenses.saveAndFlush(new Expense(toDelete, new BigDecimal("5.00"), LocalDate.parse(PAST_DATE), null));

		assertThatThrownBy(() -> jdbcTemplate.update("delete from category where id = ?", toDelete.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_expense_category");
		assertThat(categories.existsById(toDelete.getId())).isTrue();

		String indexDef = jdbcTemplate.queryForObject(
				"select indexdef from pg_indexes where tablename = 'expense' and indexname = 'ix_expense_spent_on_category_id'",
				String.class);
		assertThat(indexDef).contains("(spent_on, category_id)");
	}

}
