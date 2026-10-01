package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import dev.katran.pet.TestcontainersConfiguration;
import dev.katran.pet.category.Category;
import dev.katran.pet.category.CategoryRepository;
import dev.katran.pet.expense.Expense;
import dev.katran.pet.expense.ExpenseRepository;
import dev.katran.pet.expense.ExpenseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
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
class QuickTemplateControllerIT {

	private static final String TODAY = "2026-07-01";

	// "Today" is 2026-07-01 in Europe/Warsaw (00:30 CEST) while it is still 2026-06-30 in UTC
	// (another day and another month), and far from the real system date.
	@TestBean
	Clock clock;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
	}

	@LocalServerPort
	private int port;

	@Autowired
	private QuickTemplateRepository templates;

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
		return createActiveCategory(prefix, "cart");
	}

	private Category createActiveCategory(String prefix, String icon) {
		return categories.saveAndFlush(new Category(uniqueName(prefix), icon));
	}

	private Category createArchivedCategory(String prefix) {
		Category category = new Category(uniqueName(prefix), "cart");
		category.setArchived(true);
		return categories.saveAndFlush(category);
	}

	private QuickTemplate createTemplate(Category category, String name, String amount, int sortOrder) {
		return templates.saveAndFlush(new QuickTemplate(name, category, new BigDecimal(amount), sortOrder));
	}

	private static long idFromLocation(String location) {
		return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
	}

	private String dbName(long id) {
		return jdbcTemplate.queryForObject("select name from quick_template where id = ?", String.class, id);
	}

	private BigDecimal dbAmount(long id) {
		return jdbcTemplate.queryForObject("select amount from quick_template where id = ?", BigDecimal.class, id);
	}

	private Long dbCategoryId(long id) {
		return jdbcTemplate.queryForObject("select category_id from quick_template where id = ?", Long.class, id);
	}

	private int dbSortOrder(long id) {
		return jdbcTemplate.queryForObject("select sort_order from quick_template where id = ?", Integer.class, id);
	}

	private long countTemplatesByCategory(Long categoryId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from quick_template where category_id = ?", Long.class, categoryId);
	}

	private long countExpensesByCategory(Long categoryId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from expense where category_id = ?", Long.class, categoryId);
	}

	private List<QuickTemplateResponse> fetchList() {
		return client.get().uri("/api/quick-templates").exchange()
				.expectStatus().isOk()
				.expectBody(new ParameterizedTypeReference<List<QuickTemplateResponse>>() {
				})
				.returnResult()
				.getResponseBody();
	}

	private QuickTemplateResponse fetchOne(long id) {
		return client.get().uri("/api/quick-templates/{id}", id).exchange()
				.expectStatus().isOk()
				.expectBody(QuickTemplateResponse.class)
				.returnResult()
				.getResponseBody();
	}

	private static QuickTemplateResponse findById(List<QuickTemplateResponse> list, Long id) {
		return list.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
	}

	private QuickTemplateResponse patch(long id, Object body) {
		return client.patch().uri("/api/quick-templates/{id}", id)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isOk()
				.expectBody(QuickTemplateResponse.class)
				.returnResult()
				.getResponseBody();
	}

	private void assertUnchangedInDb(long id, QuickTemplateResponse expected) {
		assertThat(dbName(id)).isEqualTo(expected.name());
		assertThat(dbAmount(id)).isEqualByComparingTo(expected.amount());
		assertThat(dbCategoryId(id)).isEqualTo(expected.category().id());
		assertThat(dbSortOrder(id)).isEqualTo(expected.sortOrder());
	}

	// Builds a raw create body from raw JSON literal fragments (strings must include their own quotes).
	private static String createBody(String nameLiteral, String categoryIdLiteral, String amountLiteral,
			String sortOrderLiteral) {
		return "{\"name\":" + nameLiteral + ",\"categoryId\":" + categoryIdLiteral
				+ ",\"amount\":" + amountLiteral + ",\"sortOrder\":" + sortOrderLiteral + "}";
	}

	// T1
	@Test
	void createsTemplateAndReturns201() {
		Category category = createActiveCategory("T1");
		String rawName = uniqueName("Coffee");

		EntityExchangeResult<QuickTemplateResponse> result = client.post()
				.uri("/api/quick-templates")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"  " + rawName + "  \",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"12.5\",\"sortOrder\":3}")
				.exchange()
				.expectStatus().isCreated()
				.expectBody(QuickTemplateResponse.class)
				.returnResult();

		QuickTemplateResponse created = result.getResponseBody();
		assertThat(created.id()).isNotNull();
		assertThat(created.name()).isEqualTo(rawName);
		assertThat(created.amount()).isEqualByComparingTo("12.50");
		assertThat(created.sortOrder()).isEqualTo(3);
		assertThat(created.category().id()).isEqualTo(category.getId());
		assertThat(created.category().name()).isEqualTo(category.getName());
		assertThat(created.category().icon()).isEqualTo(category.getIcon());
		assertThat(created.category().archived()).isFalse();

		String location = result.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();
		long id = idFromLocation(location);
		assertThat(location).endsWith("/api/quick-templates/" + id);
		assertThat(created.id()).isEqualTo(id);

		// GET on Location returns exactly the same body as POST (full record comparison).
		assertThat(fetchOne(id)).isEqualTo(created);

		// Neither response shape carries "categoryId" (checked via a second, idempotent GET).
		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.categoryId").doesNotExist();

		assertThat(dbName(id)).isEqualTo(rawName);
		assertThat(dbAmount(id)).isEqualByComparingTo("12.50");
		assertThat(dbCategoryId(id)).isEqualTo(category.getId());
		assertThat(dbSortOrder(id)).isEqualTo(3);

		// sortOrder -5 and 0 are both accepted (any 32-bit integer, decision 2).
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T1-neg") + "\",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"1.00\",\"sortOrder\":-5}")
				.exchange().expectStatus().isCreated()
				.expectBody().jsonPath("$.sortOrder").isEqualTo(-5);

		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T1-zero") + "\",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"1.00\",\"sortOrder\":0}")
				.exchange().expectStatus().isCreated()
				.expectBody().jsonPath("$.sortOrder").isEqualTo(0);

		// A 64-character name with surrounding spaces is accepted and stripped.
		String name64 = "n".repeat(64);
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"  " + name64 + "  \",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"1.00\",\"sortOrder\":1}")
				.exchange().expectStatus().isCreated()
				.expectBody().jsonPath("$.name").isEqualTo(name64);
	}

	// T2
	private static Stream<Arguments> amountFormats() {
		return Stream.of(
				Arguments.of("\"200\"", "200.00"),
				Arguments.of("\"0.01\"", "0.01"),
				Arguments.of("\"9999999999.99\"", "9999999999.99"),
				Arguments.of("200", "200.00"),
				Arguments.of("19.99", "19.99"));
	}

	@ParameterizedTest
	@MethodSource("amountFormats")
	void acceptsAmountFormats(String amountLiteral, String expected) {
		Category category = createActiveCategory("T2");

		EntityExchangeResult<byte[]> result = client.post().uri("/api/quick-templates")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T2") + "\",\"categoryId\":" + category.getId()
						+ ",\"amount\":" + amountLiteral + ",\"sortOrder\":0}")
				.exchange().expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo(expected)
				.returnResult();

		long id = idFromLocation(result.getResponseHeaders().getFirst("Location"));
		assertThat(dbAmount(id)).isEqualByComparingTo(expected);
	}

	// T3
	private static Stream<Arguments> invalidCreateCases() {
		return Stream.of(
				Arguments.of("NAME_MISSING", List.of("name")),
				Arguments.of("NAME_NULL", List.of("name")),
				Arguments.of("NAME_EMPTY", List.of("name")),
				Arguments.of("NAME_BLANK_SPACES", List.of("name")),
				Arguments.of("NAME_BLANK_TAB_NEWLINE", List.of("name")),
				Arguments.of("NAME_TOO_LONG", List.of("name")),
				Arguments.of("CATEGORY_ID_MISSING", List.of("categoryId")),
				Arguments.of("CATEGORY_ID_NULL", List.of("categoryId")),
				Arguments.of("AMOUNT_ZERO", List.of("amount")),
				Arguments.of("AMOUNT_ZERO_STRING", List.of("amount")),
				Arguments.of("AMOUNT_NEGATIVE", List.of("amount")),
				Arguments.of("AMOUNT_TOO_MANY_DECIMALS", List.of("amount")),
				Arguments.of("AMOUNT_FLOATING_POINT_NOISE", List.of("amount")),
				Arguments.of("AMOUNT_MISSING", List.of("amount")),
				Arguments.of("AMOUNT_NULL", List.of("amount")),
				Arguments.of("AMOUNT_STRING_OVERFLOW", List.of("amount")),
				Arguments.of("AMOUNT_NUMBER_OVERFLOW", List.of("amount")),
				Arguments.of("AMOUNT_TOO_MANY_INT_DIGITS", List.of("amount", "amount")),
				Arguments.of("SORT_ORDER_MISSING", List.of("sortOrder")),
				Arguments.of("SORT_ORDER_NULL", List.of("sortOrder")),
				Arguments.of("EMPTY_BODY", List.of("amount", "categoryId", "name", "sortOrder")));
	}

	@ParameterizedTest
	@MethodSource("invalidCreateCases")
	void rejectsInvalidCreate(String caseName, List<String> expectedFields) {
		Category category = createActiveCategory("T3");
		long before = templates.count();
		String longName = "n".repeat(65);

		String body = switch (caseName) {
			case "NAME_MISSING" -> "{\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "NAME_NULL" ->
				"{\"name\":null,\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "NAME_EMPTY" ->
				"{\"name\":\"\",\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "NAME_BLANK_SPACES" ->
				"{\"name\":\"   \",\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "NAME_BLANK_TAB_NEWLINE" ->
				"{\"name\":\"\\t\\n\",\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "NAME_TOO_LONG" ->
				"{\"name\":\"" + longName + "\",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "CATEGORY_ID_MISSING" -> "{\"name\":\"X\",\"amount\":\"12.50\",\"sortOrder\":0}";
			case "CATEGORY_ID_NULL" -> "{\"name\":\"X\",\"categoryId\":null,\"amount\":\"12.50\",\"sortOrder\":0}";
			case "AMOUNT_ZERO" -> "{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":0,\"sortOrder\":0}";
			case "AMOUNT_ZERO_STRING" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":\"0.00\",\"sortOrder\":0}";
			case "AMOUNT_NEGATIVE" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":-1,\"sortOrder\":0}";
			case "AMOUNT_TOO_MANY_DECIMALS" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":\"1.234\",\"sortOrder\":0}";
			case "AMOUNT_FLOATING_POINT_NOISE" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId()
						+ ",\"amount\":0.30000000000000004,\"sortOrder\":0}";
			case "AMOUNT_MISSING" -> "{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"sortOrder\":0}";
			case "AMOUNT_NULL" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":null,\"sortOrder\":0}";
			case "AMOUNT_STRING_OVERFLOW" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"1e2147483647\",\"sortOrder\":0}";
			case "AMOUNT_NUMBER_OVERFLOW" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":1e2147483647,\"sortOrder\":0}";
			case "AMOUNT_TOO_MANY_INT_DIGITS" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId()
						+ ",\"amount\":\"10000000000\",\"sortOrder\":0}";
			case "SORT_ORDER_MISSING" -> "{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\"}";
			case "SORT_ORDER_NULL" ->
				"{\"name\":\"X\",\"categoryId\":" + category.getId() + ",\"amount\":\"12.50\",\"sortOrder\":null}";
			case "EMPTY_BODY" -> "{}";
			default -> throw new IllegalArgumentException(caseName);
		};

		var spec = client.post()
				.uri("/api/quick-templates")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates")
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(templates.count()).isEqualTo(before);
	}

	// T4
	private void assertSameAsNull(String field, String bodyUnderTest, String nullBody) {
		AtomicReference<String> nullMessage = new AtomicReference<>();
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body(nullBody).exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo(field)
				.jsonPath("$.errors[0].message").value(String.class, nullMessage::set);

		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body(bodyUnderTest).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo(field)
				.jsonPath("$.errors[0].message").isEqualTo(nullMessage.get());
	}

	// Float tokens and malformed JSON: 400, $.errors must be absent (decision 16).
	private void assertUnreadableNoErrors(String body) {
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.errors").doesNotExist();
	}

	// Other unreadable input: 400, but $.errors is deliberately not asserted either way (plan, T4).
	private void assertBadRequestOnly(String body) {
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400);
	}

	private static Stream<Arguments> numericEdgeInputCases() {
		return Stream.of(
				Arguments.of("AMOUNT_EMPTY_SAME_AS_NULL"),
				Arguments.of("CATEGORY_ID_EMPTY_SAME_AS_NULL"),
				Arguments.of("SORT_ORDER_EMPTY_SAME_AS_NULL"),
				Arguments.of("AMOUNT_BLANK_SAME_AS_NULL"),
				Arguments.of("CATEGORY_ID_BLANK_SAME_AS_NULL"),
				Arguments.of("SORT_ORDER_BLANK_SAME_AS_NULL"),
				Arguments.of("SORT_ORDER_FLOAT_FRACTIONAL"),
				Arguments.of("SORT_ORDER_FLOAT_WHOLE"),
				Arguments.of("SORT_ORDER_FLOAT_EXPONENT"),
				Arguments.of("CATEGORY_ID_FLOAT_WHOLE"),
				Arguments.of("CATEGORY_ID_FLOAT_FRACTIONAL"),
				Arguments.of("MALFORMED_JSON"),
				Arguments.of("AMOUNT_STRING_ABC"),
				Arguments.of("AMOUNT_BOOLEAN"),
				Arguments.of("SORT_ORDER_STRING_ABC"),
				Arguments.of("SORT_ORDER_INT_OVERFLOW"),
				Arguments.of("CATEGORY_ID_STRING_ABC"));
	}

	@ParameterizedTest
	@MethodSource("numericEdgeInputCases")
	void numericEdgeInputsOnCreate(String caseName) {
		Category c = createActiveCategory("T4");
		long before = templates.count();
		String validName = "\"" + uniqueName("T4") + "\"";
		String validCategoryId = String.valueOf(c.getId());
		String validAmount = "\"12.50\"";
		String validSortOrder = "0";

		switch (caseName) {
			// decision 15: "" is read as null, the same error as an explicit null, for each numeric field.
			case "AMOUNT_EMPTY_SAME_AS_NULL" -> assertSameAsNull("amount",
					createBody(validName, validCategoryId, "\"\"", validSortOrder),
					createBody(validName, validCategoryId, "null", validSortOrder));
			case "CATEGORY_ID_EMPTY_SAME_AS_NULL" -> assertSameAsNull("categoryId",
					createBody(validName, "\"\"", validAmount, validSortOrder),
					createBody(validName, "null", validAmount, validSortOrder));
			case "SORT_ORDER_EMPTY_SAME_AS_NULL" -> assertSameAsNull("sortOrder",
					createBody(validName, validCategoryId, validAmount, "\"\""),
					createBody(validName, validCategoryId, validAmount, "null"));

			// Blank strings ("   "): OBSERVED (manual probe against a running instance, 2026-10-01) to be
			// coerced exactly like "" for all three fields (same error as null). This is the expected
			// outcome per decision 15/R1d; the allowed alternative (400 without errors[]) was not observed.
			case "AMOUNT_BLANK_SAME_AS_NULL" -> assertSameAsNull("amount",
					createBody(validName, validCategoryId, "\"   \"", validSortOrder),
					createBody(validName, validCategoryId, "null", validSortOrder));
			case "CATEGORY_ID_BLANK_SAME_AS_NULL" -> assertSameAsNull("categoryId",
					createBody(validName, "\"   \"", validAmount, validSortOrder),
					createBody(validName, "null", validAmount, validSortOrder));
			case "SORT_ORDER_BLANK_SAME_AS_NULL" -> assertSameAsNull("sortOrder",
					createBody(validName, validCategoryId, validAmount, "\"   \""),
					createBody(validName, validCategoryId, validAmount, "null"));

			// Float tokens (decision 16): rejected without errors[], including whole-valued ones.
			case "SORT_ORDER_FLOAT_FRACTIONAL" ->
				assertUnreadableNoErrors(createBody(validName, validCategoryId, validAmount, "1.5"));
			case "SORT_ORDER_FLOAT_WHOLE" ->
				assertUnreadableNoErrors(createBody(validName, validCategoryId, validAmount, "1.0"));
			case "SORT_ORDER_FLOAT_EXPONENT" ->
				assertUnreadableNoErrors(createBody(validName, validCategoryId, validAmount, "1e1"));
			case "CATEGORY_ID_FLOAT_WHOLE" ->
				assertUnreadableNoErrors(createBody(validName, c.getId() + ".0", validAmount, validSortOrder));
			case "CATEGORY_ID_FLOAT_FRACTIONAL" ->
				assertUnreadableNoErrors(createBody(validName, c.getId() + ".5", validAmount, validSortOrder));

			// Malformed JSON: errors[] absent.
			case "MALFORMED_JSON" -> assertUnreadableNoErrors("{\"name\":");

			// Other unreadable input: 400, errors[] not asserted either way.
			case "AMOUNT_STRING_ABC" ->
				assertBadRequestOnly(createBody(validName, validCategoryId, "\"abc\"", validSortOrder));
			case "AMOUNT_BOOLEAN" ->
				assertBadRequestOnly(createBody(validName, validCategoryId, "true", validSortOrder));
			case "SORT_ORDER_STRING_ABC" ->
				assertBadRequestOnly(createBody(validName, validCategoryId, validAmount, "\"abc\""));
			case "SORT_ORDER_INT_OVERFLOW" ->
				assertBadRequestOnly(createBody(validName, validCategoryId, validAmount, "3000000000"));
			case "CATEGORY_ID_STRING_ABC" ->
				assertBadRequestOnly(createBody(validName, "\"abc\"", validAmount, validSortOrder));
			default -> throw new IllegalArgumentException(caseName);
		}

		assertThat(templates.count()).isEqualTo(before);
	}

	// T5
	@Test
	void rejectsUnknownCategoryOnCreate() {
		long before = templates.count();

		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T5") + "\",\"categoryId\":" + Long.MAX_VALUE
						+ ",\"amount\":\"12.50\",\"sortOrder\":0}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.detail").isEqualTo("Invalid request content.")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryId")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found")
				.jsonPath("$.instance").isEqualTo("/api/quick-templates");

		assertThat(templates.count()).isEqualTo(before);
	}

	// T6
	@Test
	void rejectsArchivedCategoryOnCreate() {
		Category archived = createArchivedCategory("T6");
		long before = templates.count();

		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T6") + "\",\"categoryId\":" + archived.getId()
						+ ",\"amount\":\"12.50\",\"sortOrder\":0}")
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.title").isEqualTo("Conflict")
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo("/api/quick-templates");

		assertThat(templates.count()).isEqualTo(before);
		assertThat(countTemplatesByCategory(archived.getId())).isZero();

		// Precedence: validation runs before the archived check.
		client.post().uri("/api/quick-templates").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + uniqueName("T6b") + "\",\"categoryId\":" + archived.getId()
						+ ",\"amount\":0,\"sortOrder\":0}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates")
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("amount");

		assertThat(templates.count()).isEqualTo(before);
	}

	// T7
	@Test
	void listIsSortedBySortOrderThenIdWithEmbeddedCategory() {
		Category p = createActiveCategory("T7-P", "cart");
		Category q = createActiveCategory("T7-Q", null);

		QuickTemplate a = createTemplate(p, uniqueName("T7-A"), "1.00", 2);
		QuickTemplate b = createTemplate(q, uniqueName("T7-B"), "1.00", 0);
		QuickTemplate c = createTemplate(p, uniqueName("T7-C"), "1.00", 1);
		QuickTemplate d = createTemplate(q, uniqueName("T7-D"), "1.00", 1);
		QuickTemplate e = createTemplate(p, uniqueName("T7-E"), "1.00", 0);

		Category managedQ = categories.findById(q.getId()).orElseThrow();
		managedQ.setArchived(true);
		categories.saveAndFlush(managedQ);

		List<QuickTemplateResponse> list = fetchList();

		List<Long> createdIds = List.of(a.getId(), b.getId(), c.getId(), d.getId(), e.getId());
		List<Long> relativeOrder = list.stream().map(QuickTemplateResponse::id)
				.filter(createdIds::contains).toList();
		assertThat(relativeOrder).containsExactly(b.getId(), e.getId(), c.getId(), d.getId(), a.getId());

		// Whole-list ordering invariant: consecutive (sortOrder, id) pairs are strictly ascending.
		for (int i = 1; i < list.size(); i++) {
			QuickTemplateResponse prev = list.get(i - 1);
			QuickTemplateResponse cur = list.get(i);
			boolean strictlyAscending = prev.sortOrder() < cur.sortOrder()
					|| (prev.sortOrder() == cur.sortOrder() && prev.id() < cur.id());
			assertThat(strictlyAscending)
					.as("(%s,%s) should be before (%s,%s)", prev.sortOrder(), prev.id(), cur.sortOrder(), cur.id())
					.isTrue();
		}

		QuickTemplateResponse bResponse = findById(list, b.getId());
		assertThat(bResponse.category()).isEqualTo(new QuickTemplateCategory(q.getId(), q.getName(), null, true));

		QuickTemplateResponse aResponse = findById(list, a.getId());
		assertThat(aResponse.category().archived()).isFalse();

		EntityExchangeResult<byte[]> raw = client.get().uri("/api/quick-templates").exchange()
				.expectStatus().isOk().expectBody().returnResult();
		assertThat(new String(raw.getResponseBody())).doesNotContain("\"categoryId\"");

		// Rename P -> GET shows the new name for A, C and E.
		Category managedP = categories.findById(p.getId()).orElseThrow();
		String newName = uniqueName("T7-P-Renamed");
		managedP.setName(newName);
		categories.saveAndFlush(managedP);

		List<QuickTemplateResponse> afterRename = fetchList();
		assertThat(findById(afterRename, a.getId()).category().name()).isEqualTo(newName);
		assertThat(findById(afterRename, c.getId()).category().name()).isEqualTo(newName);
		assertThat(findById(afterRename, e.getId()).category().name()).isEqualTo(newName);
	}

	// T8
	@Test
	void getUnknownOrNonNumericId() {
		client.get().uri("/api/quick-templates/{id}", Long.MAX_VALUE).exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Quick template not found")
				.jsonPath("$.id").isEqualTo(Long.MAX_VALUE)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE);

		client.get().uri("/api/quick-templates/abc").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/abc");
	}

	// T9
	@Test
	void patchChangesEachFieldIndividually() {
		Category p = createActiveCategory("T9-P");
		Category r = createActiveCategory("T9-R");
		QuickTemplate t = createTemplate(p, uniqueName("T9-Old"), "10.00", 5);
		long id = t.getId();

		String rawName = uniqueName("T9-New");
		QuickTemplateResponse afterName = patch(id, "{\"name\":\"  " + rawName + "  \"}");
		assertThat(afterName.name()).isEqualTo(rawName);
		assertThat(afterName.amount()).isEqualByComparingTo("10.00");
		assertThat(afterName.sortOrder()).isEqualTo(5);
		assertThat(fetchOne(id)).isEqualTo(afterName);
		assertUnchangedInDb(id, afterName);

		QuickTemplateResponse afterAmount = patch(id, "{\"amount\":\"20.5\"}");
		assertThat(afterAmount.amount()).isEqualByComparingTo("20.50");
		assertThat(afterAmount.name()).isEqualTo(rawName);
		assertThat(fetchOne(id)).isEqualTo(afterAmount);
		assertUnchangedInDb(id, afterAmount);

		QuickTemplateResponse afterCategory = patch(id, "{\"categoryId\":" + r.getId() + "}");
		assertThat(afterCategory.category().id()).isEqualTo(r.getId());
		assertThat(afterCategory.category().archived()).isFalse();
		assertThat(fetchOne(id)).isEqualTo(afterCategory);
		assertUnchangedInDb(id, afterCategory);

		QuickTemplateResponse afterSortOrder = patch(id, "{\"sortOrder\":-1}");
		assertThat(afterSortOrder.sortOrder()).isEqualTo(-1);
		assertThat(fetchOne(id)).isEqualTo(afterSortOrder);
		assertUnchangedInDb(id, afterSortOrder);

		String finalName = uniqueName("T9-Final");
		QuickTemplateResponse afterAll = patch(id,
				"{\"name\":\"" + finalName + "\",\"amount\":\"33.33\",\"categoryId\":" + p.getId()
						+ ",\"sortOrder\":7}");
		assertThat(afterAll.name()).isEqualTo(finalName);
		assertThat(afterAll.amount()).isEqualByComparingTo("33.33");
		assertThat(afterAll.category().id()).isEqualTo(p.getId());
		assertThat(afterAll.sortOrder()).isEqualTo(7);
		assertThat(fetchOne(id)).isEqualTo(afterAll);
		assertUnchangedInDb(id, afterAll);
	}

	// T10
	@Test
	void patchWithEmptyOrNullFieldsChangesNothing() {
		Category p = createActiveCategory("T10");
		QuickTemplate t = createTemplate(p, uniqueName("T10"), "10.00", 5);
		long id = t.getId();

		QuickTemplateResponse before = fetchOne(id);

		QuickTemplateResponse afterEmpty = patch(id, "{}");
		assertThat(afterEmpty).isEqualTo(before);
		assertUnchangedInDb(id, before);

		QuickTemplateResponse afterNulls = patch(id,
				"{\"name\":null,\"amount\":null,\"categoryId\":null,\"sortOrder\":null}");
		assertThat(afterNulls).isEqualTo(before);
		assertUnchangedInDb(id, before);
	}

	// T11
	private void patchRejectedWithFields(long id, String body, String instance, List<String> expectedFields) {
		var spec = client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());
		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}
	}

	private void patchRejectedNoErrors(long id, String body, String instance) {
		client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.errors").doesNotExist();
	}

	private void patchBadRequestOnly(long id, String body, String instance) {
		client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance);
	}

	private static Stream<Arguments> patchValidationCases() {
		return Stream.of(
				Arguments.of("NAME_EMPTY"),
				Arguments.of("NAME_BLANK"),
				Arguments.of("NAME_TOO_LONG"),
				Arguments.of("AMOUNT_ZERO"),
				Arguments.of("AMOUNT_NEGATIVE_STRING"),
				Arguments.of("AMOUNT_TOO_MANY_DECIMALS"),
				Arguments.of("AMOUNT_TOO_MANY_INT_DIGITS"),
				Arguments.of("MALFORMED_JSON"),
				Arguments.of("SORT_ORDER_STRING_ABC"),
				Arguments.of("AMOUNT_EMPTY_UNCHANGED"),
				Arguments.of("CATEGORY_ID_EMPTY_UNCHANGED"),
				Arguments.of("SORT_ORDER_EMPTY_UNCHANGED"),
				Arguments.of("AMOUNT_BLANK_UNCHANGED"),
				Arguments.of("CATEGORY_ID_BLANK_UNCHANGED"),
				Arguments.of("SORT_ORDER_BLANK_UNCHANGED"),
				Arguments.of("SORT_ORDER_FLOAT_FRACTIONAL"),
				Arguments.of("SORT_ORDER_FLOAT_WHOLE"),
				Arguments.of("CATEGORY_ID_FLOAT_WHOLE"));
	}

	@ParameterizedTest
	@MethodSource("patchValidationCases")
	void patchValidationAndNumericEdgeInputs(String caseName) {
		Category p = createActiveCategory("T11-P");
		Category r = createActiveCategory("T11-R");
		QuickTemplate t = createTemplate(p, uniqueName("T11"), "10.00", 5);
		long id = t.getId();
		String instance = "/api/quick-templates/" + id;

		QuickTemplateResponse before = fetchOne(id);

		switch (caseName) {
			case "NAME_EMPTY" -> patchRejectedWithFields(id, "{\"name\":\"\"}", instance, List.of("name"));
			case "NAME_BLANK" -> patchRejectedWithFields(id, "{\"name\":\"   \"}", instance, List.of("name"));
			case "NAME_TOO_LONG" ->
				patchRejectedWithFields(id, "{\"name\":\"" + "n".repeat(65) + "\"}", instance, List.of("name"));

			case "AMOUNT_ZERO" -> patchRejectedWithFields(id, "{\"amount\":0}", instance, List.of("amount"));
			case "AMOUNT_NEGATIVE_STRING" ->
				patchRejectedWithFields(id, "{\"amount\":\"-1\"}", instance, List.of("amount"));
			case "AMOUNT_TOO_MANY_DECIMALS" ->
				patchRejectedWithFields(id, "{\"amount\":\"1.234\"}", instance, List.of("amount"));
			case "AMOUNT_TOO_MANY_INT_DIGITS" -> patchRejectedWithFields(
					id, "{\"amount\":\"10000000000\"}", instance, List.of("amount", "amount"));

			case "MALFORMED_JSON" -> patchRejectedNoErrors(id, "{\"amount\":", instance);

			// sortOrder "abc": 400, errors[] not asserted either way (same style as T4).
			case "SORT_ORDER_STRING_ABC" -> patchBadRequestOnly(id, "{\"sortOrder\":\"abc\"}", instance);

			// "" (decision 15): 200, unchanged -- same as the null case in T10.
			case "AMOUNT_EMPTY_UNCHANGED" -> assertThat(patch(id, "{\"amount\":\"\"}")).isEqualTo(before);
			case "CATEGORY_ID_EMPTY_UNCHANGED" -> assertThat(patch(id, "{\"categoryId\":\"\"}")).isEqualTo(before);
			case "SORT_ORDER_EMPTY_UNCHANGED" -> assertThat(patch(id, "{\"sortOrder\":\"\"}")).isEqualTo(before);

			// Blank strings ("   "): OBSERVED (manual probe, 2026-10-01) to behave exactly like "" (200,
			// unchanged). This is the expected outcome per decision 15/R1d.
			case "AMOUNT_BLANK_UNCHANGED" -> assertThat(patch(id, "{\"amount\":\"   \"}")).isEqualTo(before);
			case "CATEGORY_ID_BLANK_UNCHANGED" -> assertThat(patch(id, "{\"categoryId\":\"   \"}")).isEqualTo(before);
			case "SORT_ORDER_BLANK_UNCHANGED" -> assertThat(patch(id, "{\"sortOrder\":\"   \"}")).isEqualTo(before);

			// Float tokens (decision 16): 400, errors[] absent; sortOrder/category unchanged.
			case "SORT_ORDER_FLOAT_FRACTIONAL" -> patchRejectedNoErrors(id, "{\"sortOrder\":1.5}", instance);
			case "SORT_ORDER_FLOAT_WHOLE" -> patchRejectedNoErrors(id, "{\"sortOrder\":1.0}", instance);
			case "CATEGORY_ID_FLOAT_WHOLE" ->
				patchRejectedNoErrors(id, "{\"categoryId\":" + r.getId() + ".0}", instance);

			default -> throw new IllegalArgumentException(caseName);
		}

		assertUnchangedInDb(id, before);
	}

	// T12
	@Test
	void patchCategoryRules() {
		Category p = createActiveCategory("T12-P");
		Category r = createActiveCategory("T12-R");
		Category x = createArchivedCategory("T12-X");
		QuickTemplate t = createTemplate(p, uniqueName("T12"), "10.00", 5);
		long id = t.getId();
		String instance = "/api/quick-templates/" + id;
		QuickTemplateResponse before = fetchOne(id);

		// (a) unknown categoryId
		client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body("{\"categoryId\":" + Long.MAX_VALUE + "}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("categoryId")
				.jsonPath("$.errors[0].message").isEqualTo("Category not found");
		assertUnchangedInDb(id, before);

		// (b) archived X
		client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body("{\"categoryId\":" + x.getId() + "}")
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo(instance);
		assertUnchangedInDb(id, before);

		// (c) name change + archived category together -> 409, row (including name) unchanged
		String renamed = uniqueName("T12-Renamed");
		client.patch().uri("/api/quick-templates/{id}", id).contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"" + renamed + "\",\"categoryId\":" + x.getId() + "}")
				.exchange().expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.detail").isEqualTo("Category is archived");
		assertThat(dbName(id)).isEqualTo(before.name());
		assertUnchangedInDb(id, before);

		// (d) archive P, keep current categoryId == P (no check runs on the unchanged category)
		Category managedP = categories.findById(p.getId()).orElseThrow();
		managedP.setArchived(true);
		categories.saveAndFlush(managedP);

		String stillName = uniqueName("T12-Still");
		QuickTemplateResponse afterKeep = patch(id,
				"{\"categoryId\":" + p.getId() + ",\"name\":\"" + stillName + "\",\"sortOrder\":9}");
		assertThat(afterKeep.category().archived()).isTrue();
		assertThat(afterKeep.name()).isEqualTo(stillName);
		assertThat(afterKeep.sortOrder()).isEqualTo(9);

		QuickTemplateResponse afterAmount = patch(id, "{\"amount\":\"3\"}");
		assertThat(afterAmount.amount()).isEqualByComparingTo("3.00");
		assertThat(afterAmount.category().id()).isEqualTo(p.getId());

		// (e) switch to active R
		QuickTemplateResponse afterSwitch = patch(id, "{\"categoryId\":" + r.getId() + "}");
		assertThat(afterSwitch.category().id()).isEqualTo(r.getId());
		assertThat(afterSwitch.category().archived()).isFalse();
	}

	// T13
	@Test
	void patchUnknownTemplate() {
		client.patch().uri("/api/quick-templates/{id}", Long.MAX_VALUE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"x\"}")
				.exchange().expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Quick template not found")
				.jsonPath("$.id").isEqualTo(Long.MAX_VALUE)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE);

		client.patch().uri("/api/quick-templates/{id}", Long.MAX_VALUE).contentType(MediaType.APPLICATION_JSON)
				.body("{\"amount\":0}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE)
				.jsonPath("$.errors.length()").isEqualTo(1)
				.jsonPath("$.errors[0].field").isEqualTo("amount");

		client.patch().uri("/api/quick-templates/abc").contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":\"x\"}")
				.exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/abc");
	}

	// T14
	@Test
	void deleteReturns204AndKeepsExpenses() {
		Category c = createActiveCategory("T14");
		QuickTemplate t = createTemplate(c, uniqueName("T14"), "12.00", 0);
		long id = t.getId();

		ExpenseResponse applied = client.post().uri("/api/quick-templates/{id}/apply", id).exchange()
				.expectStatus().isCreated()
				.expectBody(ExpenseResponse.class).returnResult().getResponseBody();

		client.delete().uri("/api/quick-templates/{id}", id).exchange()
				.expectStatus().isNoContent()
				.expectBody().isEmpty();

		client.get().uri("/api/quick-templates/{id}", id).exchange().expectStatus().isNotFound();

		List<Long> remainingIds = fetchList().stream().map(QuickTemplateResponse::id).toList();
		assertThat(remainingIds).doesNotContain(id);

		client.delete().uri("/api/quick-templates/{id}", id).exchange().expectStatus().isNotFound();

		client.get().uri("/api/expenses/{id}", applied.id()).exchange().expectStatus().isOk()
				.expectBody(ExpenseResponse.class)
				.isEqualTo(applied);

		assertThat(categories.existsById(c.getId())).isTrue();
	}

	// T15
	@Test
	void deleteEdgeCases() {
		Category archived = createArchivedCategory("T15");
		QuickTemplate t = createTemplate(archived, uniqueName("T15"), "5.00", 0);

		client.delete().uri("/api/quick-templates/{id}", t.getId()).exchange().expectStatus().isNoContent();

		client.delete().uri("/api/quick-templates/{id}", Long.MAX_VALUE).exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(404)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE)
				.jsonPath("$.id").isEqualTo(Long.MAX_VALUE);

		client.delete().uri("/api/quick-templates/abc").exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/abc");
	}

	// A1
	@Test
	void applyWithoutBodyCreatesExactlyOneExpense() {
		Category c = createActiveCategory("A1", "cart");
		QuickTemplate t = createTemplate(c, uniqueName("A1"), "12.50", 0);
		long before = expenses.count();

		EntityExchangeResult<byte[]> result = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.id").exists()
				.jsonPath("$.amount").isEqualTo("12.50")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(TODAY)
				.jsonPath("$.note").isEqualTo(null)
				.jsonPath("$.createdAt").exists()
				.jsonPath("$.category.id").isEqualTo(c.getId().intValue())
				.jsonPath("$.category.name").isEqualTo(c.getName())
				.jsonPath("$.category.icon").isEqualTo("cart")
				.jsonPath("$.category.archived").doesNotExist()
				.jsonPath("$.categoryId").doesNotExist()
				.returnResult();

		String location = result.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();
		long expenseId = idFromLocation(location);
		assertThat(location).endsWith("/api/expenses/" + expenseId);

		client.get().uri(location).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("12.50")
				.jsonPath("$.currency").isEqualTo("PLN")
				.jsonPath("$.spentOn").isEqualTo(TODAY)
				.jsonPath("$.note").isEqualTo(null)
				.jsonPath("$.category.id").isEqualTo(c.getId().intValue());

		assertThat(expenses.count()).isEqualTo(before + 1);
		assertThat(countExpensesByCategory(c.getId())).isEqualTo(1);

		Expense stored = expenses.findById(expenseId).orElseThrow();
		assertThat(stored.getAmount()).isEqualByComparingTo("12.50");
		assertThat(stored.getCategory().getId()).isEqualTo(c.getId());
		assertThat(stored.getSpentOn()).isEqualTo(LocalDate.parse(TODAY));
		assertThat(stored.getNote()).isNull();

		QuickTemplate stillThere = templates.findById(t.getId()).orElseThrow();
		assertThat(stillThere.getAmount()).isEqualByComparingTo("12.50");
		assertThat(stillThere.getCategory().getId()).isEqualTo(c.getId());
	}

	// A2
	@Test
	void applyUsesTodayFromClockInConfiguredZone() {
		assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.parse(TODAY));
		assertThat(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).isEqualTo(LocalDate.parse("2026-06-30"));

		Category c = createActiveCategory("A2", "cart");
		QuickTemplate t = createTemplate(c, uniqueName("A2"), "40.00", 0);

		EntityExchangeResult<byte[]> result = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.exchange()
				.expectStatus().isCreated()
				.expectBody().jsonPath("$.spentOn").isEqualTo(TODAY)
				.returnResult();
		long expenseId = idFromLocation(result.getResponseHeaders().getFirst("Location"));

		client.get().uri("/api/expenses?categoryIds=" + c.getId()).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.totalItems").isEqualTo(1)
				.jsonPath("$.totalAmount").isEqualTo("40.00")
				.jsonPath("$.items[0].id").isEqualTo((int) expenseId);
	}

	// A3
	private static Stream<Arguments> emptyApplyBodies() {
		return Stream.of(
				Arguments.of("NO_BODY"),
				Arguments.of("EMPTY_BODY_JSON_CONTENT_TYPE"),
				Arguments.of("EMPTY_OBJECT"),
				Arguments.of("EXPLICIT_NULLS"),
				Arguments.of("EMPTY_NOTE"));
	}

	@ParameterizedTest
	@MethodSource("emptyApplyBodies")
	void applyWithEmptyBodiesUsesTemplateValues(String caseName) {
		Category c = createActiveCategory("A3");
		QuickTemplate t = createTemplate(c, uniqueName("A3"), "7.00", 0);
		long before = expenses.count();

		var response = switch (caseName) {
			case "NO_BODY" -> client.post().uri("/api/quick-templates/{id}/apply", t.getId()).exchange();
			case "EMPTY_BODY_JSON_CONTENT_TYPE" -> client.post().uri("/api/quick-templates/{id}/apply", t.getId())
					.contentType(MediaType.APPLICATION_JSON).exchange();
			case "EMPTY_OBJECT" -> client.post().uri("/api/quick-templates/{id}/apply", t.getId())
					.contentType(MediaType.APPLICATION_JSON).body("{}").exchange();
			case "EXPLICIT_NULLS" -> client.post().uri("/api/quick-templates/{id}/apply", t.getId())
					.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":null,\"note\":null}").exchange();
			case "EMPTY_NOTE" -> client.post().uri("/api/quick-templates/{id}/apply", t.getId())
					.contentType(MediaType.APPLICATION_JSON).body("{\"note\":\"\"}").exchange();
			default -> throw new IllegalArgumentException(caseName);
		};

		response.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("7.00")
				.jsonPath("$.note").isEqualTo(null);

		assertThat(expenses.count()).isEqualTo(before + 1);
	}

	// A4
	private void applyAndAssert(long templateId, String body, String expectedAmount, String expectedNote) {
		long before = expenses.count();
		EntityExchangeResult<byte[]> result = client.post().uri("/api/quick-templates/{id}/apply", templateId)
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo(expectedAmount)
				.jsonPath("$.note").isEqualTo(expectedNote)
				.returnResult();
		long expenseId = idFromLocation(result.getResponseHeaders().getFirst("Location"));
		Expense stored = expenses.findById(expenseId).orElseThrow();
		assertThat(stored.getAmount()).isEqualByComparingTo(expectedAmount);
		assertThat(stored.getNote()).isEqualTo(expectedNote);
		assertThat(expenses.count()).isEqualTo(before + 1);
	}

	@Test
	void applyOverridesAmountAndNote() {
		Category c = createActiveCategory("A4", "cart");
		QuickTemplate t = createTemplate(c, uniqueName("A4"), "12.50", 0);

		applyAndAssert(t.getId(), "{\"amount\":\"7.5\"}", "7.50", null);
		applyAndAssert(t.getId(), "{\"note\":\"Latte\"}", "12.50", "Latte");
		applyAndAssert(t.getId(), "{\"amount\":99,\"note\":\"Big\"}", "99.00", "Big");
		applyAndAssert(t.getId(), "{\"amount\":\"0.01\"}", "0.01", null);
		applyAndAssert(t.getId(), "{\"amount\":\"9999999999.99\"}", "9999999999.99", null);
		String longNote = "n".repeat(255);
		applyAndAssert(t.getId(), "{\"note\":\"" + longNote + "\"}", "12.50", longNote);
		applyAndAssert(t.getId(), "{\"note\":\"  spaced  \"}", "12.50", "  spaced  ");

		QuickTemplate stillThere = templates.findById(t.getId()).orElseThrow();
		assertThat(stillThere.getAmount()).isEqualByComparingTo("12.50");
	}

	// A5
	private static Stream<Arguments> applyOverrideRejections() {
		return Stream.of(
				Arguments.of("AMOUNT_ZERO", List.of("amount")),
				Arguments.of("AMOUNT_ZERO_STRING", List.of("amount")),
				Arguments.of("AMOUNT_NEGATIVE", List.of("amount")),
				Arguments.of("AMOUNT_TOO_MANY_DECIMALS", List.of("amount")),
				Arguments.of("AMOUNT_FLOATING_POINT_NOISE", List.of("amount")),
				Arguments.of("AMOUNT_STRING_OVERFLOW", List.of("amount")),
				Arguments.of("AMOUNT_NUMBER_OVERFLOW", List.of("amount")),
				Arguments.of("AMOUNT_TOO_MANY_INT_DIGITS", List.of("amount", "amount")),
				Arguments.of("NOTE_TOO_LONG", List.of("note")),
				Arguments.of("AMOUNT_AND_NOTE_INVALID", List.of("amount", "note")));
	}

	@ParameterizedTest
	@MethodSource("applyOverrideRejections")
	void applyOverrideValidationAndEdgeInputs(String caseName, List<String> expectedFields) {
		Category c = createActiveCategory("A5");
		QuickTemplate t = createTemplate(c, uniqueName("A5"), "12.50", 0);
		long before = expenses.count();
		String longNote = "n".repeat(256);

		String body = switch (caseName) {
			case "AMOUNT_ZERO" -> "{\"amount\":0}";
			case "AMOUNT_ZERO_STRING" -> "{\"amount\":\"0.00\"}";
			case "AMOUNT_NEGATIVE" -> "{\"amount\":-1}";
			case "AMOUNT_TOO_MANY_DECIMALS" -> "{\"amount\":\"1.234\"}";
			case "AMOUNT_FLOATING_POINT_NOISE" -> "{\"amount\":0.30000000000000004}";
			case "AMOUNT_STRING_OVERFLOW" -> "{\"amount\":\"1e2147483647\"}";
			case "AMOUNT_NUMBER_OVERFLOW" -> "{\"amount\":1e2147483647}";
			case "AMOUNT_TOO_MANY_INT_DIGITS" -> "{\"amount\":\"10000000000\"}";
			case "NOTE_TOO_LONG" -> "{\"note\":\"" + longNote + "\"}";
			case "AMOUNT_AND_NOTE_INVALID" -> "{\"amount\":0,\"note\":\"" + longNote + "\"}";
			default -> throw new IllegalArgumentException(caseName);
		};

		var spec = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON).body(body).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + t.getId() + "/apply")
				.jsonPath("$.errors.length()").isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			spec = spec.jsonPath("$.errors[" + i + "].field").isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message").value(String.class, m -> assertThat(m).isNotBlank());
		}

		assertThat(expenses.count()).isEqualTo(before);
	}

	// A5 (continued): "" means no override (decision 15), the same result as null in A3.
	@Test
	void applyOverrideEmptyAmountMeansNoOverride() {
		Category c = createActiveCategory("A5-Empty");
		QuickTemplate t = createTemplate(c, uniqueName("A5-Empty"), "12.50", 0);
		long before = expenses.count();

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":\"\"}").exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("12.50")
				.jsonPath("$.note").isEqualTo(null);

		assertThat(expenses.count()).isEqualTo(before + 1);
	}

	// A6
	@Test
	void applyRejectsUnreadableBody() {
		Category c = createActiveCategory("A6");
		QuickTemplate t = createTemplate(c, uniqueName("A6"), "12.50", 0);
		long before = expenses.count();
		String instance = "/api/quick-templates/" + t.getId() + "/apply";

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.errors").doesNotExist();

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":\"abc\"}").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance);

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":true}").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance);

		assertThat(expenses.count()).isEqualTo(before);
	}

	// A7
	@Test
	void applyIgnoresOtherFields() {
		Category c = createActiveCategory("A7-C");
		Category d = createActiveCategory("A7-D");
		QuickTemplate t = createTemplate(c, uniqueName("A7"), "12.50", 0);

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"spentOn\":\"2020-01-01\",\"categoryId\":" + d.getId()
						+ ",\"currency\":\"EUR\",\"name\":\"x\"}")
				.exchange().expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.spentOn").isEqualTo(TODAY)
				.jsonPath("$.category.id").isEqualTo(c.getId().intValue())
				.jsonPath("$.currency").isEqualTo("PLN");

		assertThat(countExpensesByCategory(d.getId())).isZero();
	}

	// A8
	@Test
	void applyForArchivedCategoryReturns409() {
		Category c = createActiveCategory("A8");
		QuickTemplate t = createTemplate(c, uniqueName("A8"), "10.00", 0);
		long id = t.getId();
		String instance = "/api/quick-templates/" + id + "/apply";

		Category managed = categories.findById(c.getId()).orElseThrow();
		managed.setArchived(true);
		categories.saveAndFlush(managed);

		long before = expenses.count();

		client.post().uri("/api/quick-templates/{id}/apply", id).exchange()
				.expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.detail").isEqualTo("Category is archived")
				.jsonPath("$.instance").isEqualTo(instance);

		client.post().uri("/api/quick-templates/{id}/apply", id)
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":\"5\"}").exchange()
				.expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.instance").isEqualTo(instance)
				.jsonPath("$.detail").isEqualTo("Category is archived");

		client.post().uri("/api/quick-templates/{id}/apply", id)
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":0}").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo(instance);

		assertThat(expenses.count()).isEqualTo(before);

		List<QuickTemplateResponse> list = fetchList();
		assertThat(findById(list, id).category().archived()).isTrue();

		client.get().uri("/api/quick-templates/{id}", id).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.category.archived").isEqualTo(true);

		Category unarchived = categories.findById(c.getId()).orElseThrow();
		unarchived.setArchived(false);
		categories.saveAndFlush(unarchived);

		client.get().uri("/api/quick-templates/{id}", id).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.category.archived").isEqualTo(false);

		client.post().uri("/api/quick-templates/{id}/apply", id).exchange().expectStatus().isCreated();

		assertThat(expenses.count()).isEqualTo(before + 1);
	}

	// A9
	@Test
	void applyUnknownTemplate() {
		long before = expenses.count();

		client.post().uri("/api/quick-templates/{id}/apply", Long.MAX_VALUE).exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.detail").isEqualTo("Quick template not found")
				.jsonPath("$.id").isEqualTo(Long.MAX_VALUE)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE + "/apply");

		client.post().uri("/api/quick-templates/{id}/apply", Long.MAX_VALUE)
				.contentType(MediaType.APPLICATION_JSON).body("{\"amount\":0}").exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + Long.MAX_VALUE + "/apply")
				.jsonPath("$.errors[0].field").isEqualTo("amount");

		client.post().uri("/api/quick-templates/abc/apply").exchange().expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/abc/apply");

		assertThat(expenses.count()).isEqualTo(before);
	}

	// A10
	@Test
	void eachApplyCreatesOneNewExpense() {
		Category c = createActiveCategory("A10");
		QuickTemplate t = createTemplate(c, uniqueName("A10"), "5.00", 0);

		EntityExchangeResult<byte[]> first = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.exchange().expectStatus().isCreated().expectBody().returnResult();
		EntityExchangeResult<byte[]> second = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.exchange().expectStatus().isCreated().expectBody().returnResult();

		long firstId = idFromLocation(first.getResponseHeaders().getFirst("Location"));
		long secondId = idFromLocation(second.getResponseHeaders().getFirst("Location"));
		assertThat(firstId).isNotEqualTo(secondId);

		assertThat(countExpensesByCategory(c.getId())).isEqualTo(2);
	}

	// A11
	@Test
	void applyUsesCurrentTemplateValuesAndCopiesThem() {
		Category c = createActiveCategory("A11-C");
		Category d = createActiveCategory("A11-D");
		QuickTemplate t = createTemplate(c, uniqueName("A11"), "12.50", 0);

		EntityExchangeResult<byte[]> first = client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.exchange()
				.expectStatus().isCreated()
				.expectBody().jsonPath("$.amount").isEqualTo("12.50").returnResult();
		long firstExpenseId = idFromLocation(first.getResponseHeaders().getFirst("Location"));

		client.patch().uri("/api/quick-templates/{id}", t.getId()).contentType(MediaType.APPLICATION_JSON)
				.body("{\"amount\":\"15\",\"categoryId\":" + d.getId() + "}")
				.exchange().expectStatus().isOk();

		client.post().uri("/api/quick-templates/{id}/apply", t.getId()).exchange()
				.expectStatus().isCreated()
				.expectBody()
				.jsonPath("$.amount").isEqualTo("15.00")
				.jsonPath("$.category.id").isEqualTo(d.getId().intValue());

		Expense firstExpense = expenses.findById(firstExpenseId).orElseThrow();
		assertThat(firstExpense.getAmount()).isEqualByComparingTo("12.50");
		assertThat(firstExpense.getCategory().getId()).isEqualTo(c.getId());
	}

	// A12
	@Test
	void applyRejectsNonJsonContentType() {
		Category c = createActiveCategory("A12");
		QuickTemplate t = createTemplate(c, uniqueName("A12"), "12.50", 0);
		long before = expenses.count();

		client.post().uri("/api/quick-templates/{id}/apply", t.getId())
				.contentType(MediaType.TEXT_PLAIN).body("{\"amount\":\"5\"}").exchange()
				.expectStatus().isEqualTo(415)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(415)
				.jsonPath("$.instance").isEqualTo("/api/quick-templates/" + t.getId() + "/apply");

		assertThat(expenses.count()).isEqualTo(before);
	}

	// A12 (continued): a non-empty body sent with NO Content-Type header at all is also 415
	// (decision 9 / README). RestTestClient's body(Object) always sets a Content-Type for a String
	// body, so a raw java.net.http.HttpClient request is used here to omit the header entirely.
	@Test
	void applyRejectsNonEmptyBodyWithoutAnyContentType() throws Exception {
		Category c = createActiveCategory("A12-NoCT");
		QuickTemplate t = createTemplate(c, uniqueName("A12-NoCT"), "12.50", 0);
		long before = expenses.count();

		HttpClient rawClient = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/quick-templates/" + t.getId() + "/apply"))
				.POST(HttpRequest.BodyPublishers.ofString("{\"amount\":\"5\"}"))
				.build();
		assertThat(request.headers().firstValue("Content-Type")).isEmpty();

		HttpResponse<String> response = rawClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(415);
		Optional<String> contentType = response.headers().firstValue("Content-Type");
		assertThat(contentType).hasValueSatisfying(ct -> assertThat(ct).contains("application/problem+json"));

		assertThat(expenses.count()).isEqualTo(before);
	}

	// S1
	@Test
	void schemaConstraints() {
		Category category = createActiveCategory("S1");

		jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, ?, ?)",
				"S1-Dup", category.getId(), new BigDecimal("1.00"), 5);
		// A duplicate name and sort_order both succeed: neither is unique (decisions 2, 3).
		jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, ?, ?)",
				"S1-Dup", category.getId(), new BigDecimal("2.00"), 5);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, 0, 0)",
				"S1-Zero", category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_quick_template_amount_positive");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, -1, 0)",
				"S1-Negative", category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_quick_template_amount_positive");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, 1.00, 0)",
				"S1-UnknownCat", Long.MAX_VALUE))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_quick_template_category");

		Category toDelete = createActiveCategory("S1-Delete");
		jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, 1.00, 0)",
				"S1-References", toDelete.getId());
		assertThatThrownBy(() -> jdbcTemplate.update("delete from category where id = ?", toDelete.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_quick_template_category");
		assertThat(categories.existsById(toDelete.getId())).isTrue();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (null, ?, 1.00, 0)",
				category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values ('X', null, 1.00, 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, null, 0)",
				"X", category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into quick_template (name, category_id, amount, sort_order) values (?, ?, 1.00, null)",
				"X", category.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);

		Map<String, Object> nameCol = jdbcTemplate.queryForMap(
				"select data_type, character_maximum_length, is_nullable from information_schema.columns "
						+ "where table_name = 'quick_template' and column_name = 'name'");
		assertThat(nameCol.get("data_type")).isEqualTo("character varying");
		assertThat(((Number) nameCol.get("character_maximum_length")).intValue()).isEqualTo(64);
		assertThat(nameCol.get("is_nullable")).isEqualTo("NO");

		Map<String, Object> amountCol = jdbcTemplate.queryForMap(
				"select data_type, numeric_precision, numeric_scale, is_nullable from information_schema.columns "
						+ "where table_name = 'quick_template' and column_name = 'amount'");
		assertThat(amountCol.get("data_type")).isEqualTo("numeric");
		assertThat(((Number) amountCol.get("numeric_precision")).intValue()).isEqualTo(12);
		assertThat(((Number) amountCol.get("numeric_scale")).intValue()).isEqualTo(2);
		assertThat(amountCol.get("is_nullable")).isEqualTo("NO");

		Map<String, Object> sortOrderCol = jdbcTemplate.queryForMap(
				"select data_type, is_nullable from information_schema.columns "
						+ "where table_name = 'quick_template' and column_name = 'sort_order'");
		assertThat(sortOrderCol.get("data_type")).isEqualTo("integer");
		assertThat(sortOrderCol.get("is_nullable")).isEqualTo("NO");

		Map<String, Object> categoryIdCol = jdbcTemplate.queryForMap(
				"select is_nullable from information_schema.columns "
						+ "where table_name = 'quick_template' and column_name = 'category_id'");
		assertThat(categoryIdCol.get("is_nullable")).isEqualTo("NO");

		Long templateColumnsInExpense = jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'expense' "
						+ "and column_name like '%template%'", Long.class);
		assertThat(templateColumnsInExpense).isZero();
	}

}
