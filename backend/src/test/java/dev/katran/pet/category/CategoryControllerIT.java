package dev.katran.pet.category;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import dev.katran.pet.TestcontainersConfiguration;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CategoryControllerIT {

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

	private static String uniqueName(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private CategoryResponse createCategory(String name, String icon) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("icon", icon);
		return client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();
	}

	private CategoryResponse patchCategory(Long id, Map<String, Object> body) {
		return client.patch()
				.uri("/api/categories/{id}", id)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();
	}

	@Test
	void createsCategoryAndReturns201() {
		String name = uniqueName("Food");

		EntityExchangeResult<CategoryResponse> result = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name, "icon", "cart"))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult();

		CategoryResponse created = result.getResponseBody();
		assertThat(created).isNotNull();
		assertThat(created.id()).isNotNull();
		assertThat(created.name()).isEqualTo(name);
		assertThat(created.icon()).isEqualTo("cart");
		assertThat(created.archived()).isFalse();
		assertThat(created.createdAt()).isNotNull();

		String location = result.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull().endsWith("/api/categories/" + created.id());

		assertThat(categories.findById(created.id())).isPresent();
	}

	@Test
	void locationPointsToExistingResource() {
		String name = uniqueName("Food");

		EntityExchangeResult<CategoryResponse> created = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name, "icon", "cart"))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult();

		String location = created.getResponseHeaders().getFirst("Location");
		assertThat(location).isNotNull();
		Long id = created.getResponseBody().id();

		client.get()
				.uri(location)
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.id")
				.isEqualTo(id.intValue())
				.jsonPath("$.name")
				.isEqualTo(name)
				.jsonPath("$.icon")
				.isEqualTo("cart");
	}

	@Test
	void createsCategoryWithoutIcon() {
		String name = uniqueName("Food");

		CategoryResponse created = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.icon()).isNull();
	}

	@Test
	void createNormalisesEmptyIconToNull() {
		String name = uniqueName("Food-Empty-Icon");

		CategoryResponse created = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name, "icon", ""))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.icon()).isNull();
		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getIcon)
				.isNull();
	}

	@Test
	void stripsNameOnCreate() {
		String core = uniqueName("Food-x");
		String padded = "  " + core + " \n";

		CategoryResponse created = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", padded))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.name()).isEqualTo(core);
		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getName)
				.isEqualTo(core);

		// Boundary: 2 leading/trailing spaces around a 64-char name.
		String uniquePrefix = "Bd-" + UUID.randomUUID();
		String sixtyFourChars = uniquePrefix + "a".repeat(64 - uniquePrefix.length());
		assertThat(sixtyFourChars).hasSize(64);
		String paddedBoundary = "  " + sixtyFourChars + "  ";

		CategoryResponse boundary = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", paddedBoundary))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(boundary).isNotNull();
		assertThat(boundary.name()).isEqualTo(sixtyFourChars).hasSize(64);
	}

	@Test
	void rejectsDuplicateNameCaseInsensitive() {
		String name = uniqueName("Food-x");
		createCategory(name, null);
		long before = categories.count();

		client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name.toUpperCase()))
				.exchange()
				.expectStatus()
				.isEqualTo(409)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(409)
				.jsonPath("$.title")
				.isEqualTo("Conflict")
				.jsonPath("$.detail")
				.isEqualTo("Category name already exists")
				.jsonPath("$.instance")
				.isEqualTo("/api/categories");

		assertThat(categories.count()).isEqualTo(before);
	}

	@Test
	void rejectsDuplicateNameAfterStrip() {
		String name = uniqueName("food-y");
		createCategory(name, null);
		long before = categories.count();

		client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", " " + name.toUpperCase() + " "))
				.exchange()
				.expectStatus()
				.isEqualTo(409)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(409)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories");

		assertThat(categories.count()).isEqualTo(before);
	}

	@Test
	void rejectsDuplicateNameOfArchivedCategory() {
		String name = uniqueName("food-z");
		CategoryResponse created = createCategory(name, null);
		patchCategory(created.id(), Map.of("archived", true));
		long before = categories.count();

		client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", name.toUpperCase()))
				.exchange()
				.expectStatus()
				.isEqualTo(409)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(409)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories");

		assertThat(categories.count()).isEqualTo(before);
	}

	private static Stream<Arguments> invalidCreateBodies() {
		String longName = "a".repeat(65);
		String longIcon = "b".repeat(33);
		return Stream.of(
				Arguments.of("{\"name\": \"\"}", List.of("name")),
				Arguments.of("{\"name\": \"   \"}", List.of("name")),
				Arguments.of("{\"name\": \"\\t\\n\"}", List.of("name")),
				Arguments.of("{}", List.of("name")),
				Arguments.of("{\"name\": null}", List.of("name")),
				Arguments.of("{\"name\": \"" + longName + "\"}", List.of("name")),
				Arguments.of("{\"name\": \"" + uniqueName("Icon-too-long") + "\", \"icon\": \"" + longIcon + "\"}",
						List.of("icon")),
				Arguments.of("{\"name\": \"\", \"icon\": \"" + longIcon + "\"}", List.of("icon", "name")));
	}

	@ParameterizedTest
	@MethodSource("invalidCreateBodies")
	void rejectsInvalidCreate(String rawJsonBody, List<String> expectedFields) {
		long before = categories.count();

		var body = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(rawJsonBody)
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories")
				.jsonPath("$.errors.length()")
				.isEqualTo(expectedFields.size());

		for (int i = 0; i < expectedFields.size(); i++) {
			body = body.jsonPath("$.errors[" + i + "].field")
					.isEqualTo(expectedFields.get(i))
					.jsonPath("$.errors[" + i + "].message")
					.value(String.class, message -> assertThat(message).isNotBlank());
		}

		assertThat(categories.count()).isEqualTo(before);
	}

	@Test
	void createsCategoryAtNameAndIconLengthBoundary() {
		String uniquePrefix = "Bd2-" + UUID.randomUUID();
		String sixtyFourChars = uniquePrefix + "a".repeat(64 - uniquePrefix.length());
		String thirtyTwoIcon = "i".repeat(32);
		assertThat(sixtyFourChars).hasSize(64);

		CategoryResponse created = client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", sixtyFourChars, "icon", thirtyTwoIcon))
				.exchange()
				.expectStatus()
				.isCreated()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();

		assertThat(created).isNotNull();
		assertThat(created.name()).isEqualTo(sixtyFourChars);
		assertThat(created.icon()).isEqualTo(thirtyTwoIcon);
	}

	@Test
	void rejectsMalformedJsonOnCreate() {
		client.post()
				.uri("/api/categories")
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"name\":")
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories")
				.jsonPath("$.errors")
				.doesNotExist();
	}

	@Test
	void listsOnlyActiveByDefault() {
		CategoryResponse a = createCategory(uniqueName("Active-A"), null);
		CategoryResponse b = createCategory(uniqueName("Active-B"), null);
		patchCategory(b.id(), Map.of("archived", true));

		List<Long> ids = client.get()
				.uri("/api/categories")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(new ParameterizedTypeReference<List<CategoryResponse>>() {
				})
				.returnResult()
				.getResponseBody()
				.stream()
				.map(CategoryResponse::id)
				.toList();

		assertThat(ids).contains(a.id()).doesNotContain(b.id());
	}

	@Test
	void includeArchivedTrueListsArchived() {
		CategoryResponse a = createCategory(uniqueName("Inc-A"), null);
		CategoryResponse b = createCategory(uniqueName("Inc-B"), null);
		patchCategory(b.id(), Map.of("archived", true));

		List<CategoryResponse> all = client.get()
				.uri("/api/categories?includeArchived=true")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(new ParameterizedTypeReference<List<CategoryResponse>>() {
				})
				.returnResult()
				.getResponseBody();

		Map<Long, CategoryResponse> byId = new HashMap<>();
		all.forEach(c -> byId.put(c.id(), c));

		assertThat(byId).containsKey(a.id());
		assertThat(byId).containsKey(b.id());
		assertThat(byId.get(a.id()).archived()).isFalse();
		assertThat(byId.get(b.id()).archived()).isTrue();

		List<Long> defaultFalse = client.get()
				.uri("/api/categories?includeArchived=false")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(new ParameterizedTypeReference<List<CategoryResponse>>() {
				})
				.returnResult()
				.getResponseBody()
				.stream()
				.map(CategoryResponse::id)
				.toList();

		List<Long> defaultOmitted = client.get()
				.uri("/api/categories")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(new ParameterizedTypeReference<List<CategoryResponse>>() {
				})
				.returnResult()
				.getResponseBody()
				.stream()
				.map(CategoryResponse::id)
				.toList();

		assertThat(defaultFalse).contains(a.id()).doesNotContain(b.id());
		assertThat(defaultOmitted).contains(a.id()).doesNotContain(b.id());
	}

	@Test
	void rejectsNonBooleanIncludeArchived() {
		client.get()
				.uri("/api/categories?includeArchived=abc")
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories");
	}

	@Test
	void patchUpdatesNameIconAndArchived() {
		CategoryResponse created = createCategory(uniqueName("Patch-All"), "old-icon");
		String newName = uniqueName("Patch-All-New");

		Map<String, Object> patchBody = new HashMap<>();
		patchBody.put("name", newName);
		patchBody.put("icon", "new-icon");
		patchBody.put("archived", true);

		CategoryResponse updated = patchCategory(created.id(), patchBody);

		assertThat(updated).isNotNull();
		assertThat(updated.name()).isEqualTo(newName);
		assertThat(updated.icon()).isEqualTo("new-icon");
		assertThat(updated.archived()).isTrue();

		assertThat(categories.findById(created.id())).isPresent().get().satisfies(c -> {
			assertThat(c.getName()).isEqualTo(newName);
			assertThat(c.getIcon()).isEqualTo("new-icon");
			assertThat(c.isArchived()).isTrue();
		});

		CategoryResponse unarchived = patchCategory(created.id(), Map.of("archived", false));
		assertThat(unarchived).isNotNull();
		assertThat(unarchived.archived()).isFalse();
	}

	@Test
	void patchWithPartialBodyKeepsOtherFields() {
		String name = uniqueName("Partial");
		CategoryResponse created = createCategory(name, "keep-icon");

		CategoryResponse afterArchive = patchCategory(created.id(), Map.of("archived", true));
		assertThat(afterArchive).isNotNull();
		assertThat(afterArchive.name()).isEqualTo(name);
		assertThat(afterArchive.icon()).isEqualTo("keep-icon");
		assertThat(afterArchive.archived()).isTrue();

		// icon: null (absent-or-null means "leave unchanged" under decision 11)
		CategoryResponse afterNullIcon = client.patch()
				.uri("/api/categories/{id}", created.id())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"icon\": null}")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();
		assertThat(afterNullIcon).isNotNull();
		assertThat(afterNullIcon.icon()).isEqualTo("keep-icon");
		assertThat(afterNullIcon.name()).isEqualTo(name);
		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getIcon)
				.isEqualTo("keep-icon");

		Map<String, Object> hashMapBody = new HashMap<>();
		hashMapBody.put("icon", null);
		CategoryResponse afterHashMapNullIcon = patchCategory(created.id(), hashMapBody);
		assertThat(afterHashMapNullIcon).isNotNull();
		assertThat(afterHashMapNullIcon.icon()).isEqualTo("keep-icon");
		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getIcon)
				.isEqualTo("keep-icon");

		CategoryResponse afterEmpty = client.patch()
				.uri("/api/categories/{id}", created.id())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{}")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody(CategoryResponse.class)
				.returnResult()
				.getResponseBody();
		assertThat(afterEmpty).isNotNull();
		assertThat(afterEmpty.name()).isEqualTo(name);
		assertThat(afterEmpty.icon()).isEqualTo("keep-icon");
		assertThat(afterEmpty.archived()).isTrue();
	}

	@Test
	void patchWithEmptyIconClearsIcon() {
		CategoryResponse created = createCategory(uniqueName("ClearIcon"), "original-icon");

		CategoryResponse updated = patchCategory(created.id(), Map.of("icon", ""));

		assertThat(updated).isNotNull();
		assertThat(updated.icon()).isNull();
		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getIcon)
				.isNull();
	}

	@Test
	void patchStripsName() {
		CategoryResponse created = createCategory(uniqueName("Strip"), null);
		String newCore = uniqueName("New-Strip");

		CategoryResponse updated = patchCategory(created.id(), Map.of("name", "  " + newCore + "  "));
		assertThat(updated).isNotNull();
		assertThat(updated.name()).isEqualTo(newCore);
	}

	@Test
	void patchSameNameDifferentCaseOfItself() {
		String name = uniqueName("food");
		CategoryResponse created = createCategory(name, null);

		CategoryResponse updated = patchCategory(created.id(), Map.of("name", name.toUpperCase()));
		assertThat(updated).isNotNull();
		assertThat(updated.name()).isEqualTo(name.toUpperCase());
	}

	private static Stream<Arguments> invalidPatchBodies() {
		String longIcon = "c".repeat(33);
		return Stream.of(
				Arguments.of("{\"name\": \"\"}", "name"),
				Arguments.of("{\"name\": \"   \"}", "name"),
				Arguments.of("{\"name\": \"\\n\"}", "name"),
				Arguments.of("{\"name\": \" \\r\\n\\t \"}", "name"),
				Arguments.of("{\"name\": \"" + "d".repeat(65) + "\"}", "name"),
				Arguments.of("{\"icon\": \"" + longIcon + "\"}", "icon"));
	}

	@ParameterizedTest
	@MethodSource("invalidPatchBodies")
	void patchRejectsBlankOrTooLongName(String rawJsonBody, String expectedField) {
		CategoryResponse created = createCategory(uniqueName("Patch-Invalid"), "orig-icon");

		client.patch()
				.uri("/api/categories/{id}", created.id())
				.contentType(MediaType.APPLICATION_JSON)
				.body(rawJsonBody)
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories/" + created.id())
				.jsonPath("$.errors.length()")
				.isEqualTo(1)
				.jsonPath("$.errors[0].field")
				.isEqualTo(expectedField)
				.jsonPath("$.errors[0].message")
				.value(String.class, message -> assertThat(message).isNotBlank());

		assertThat(categories.findById(created.id())).isPresent().get().satisfies(c -> {
			assertThat(c.getName()).isEqualTo(created.name());
			assertThat(c.getIcon()).isEqualTo("orig-icon");
		});
	}

	@Test
	void patchAcceptsNameWithInnerLineBreak() {
		CategoryResponse created = createCategory(uniqueName("LineBreak"), null);
		String newName = "Food\nOut-" + UUID.randomUUID();

		CategoryResponse updated = patchCategory(created.id(), Map.of("name", newName));
		assertThat(updated).isNotNull();
		assertThat(updated.name()).isEqualTo(newName);

		assertThat(categories.findById(created.id()))
				.isPresent()
				.get()
				.extracting(Category::getName)
				.isEqualTo(newName);
	}

	@Test
	void patchToExistingNameReturns409() {
		CategoryResponse a = createCategory(uniqueName("Existing-A"), null);
		CategoryResponse b = createCategory(uniqueName("Existing-B"), null);

		client.patch()
				.uri("/api/categories/{id}", b.id())
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", " " + a.name().toUpperCase() + " "))
				.exchange()
				.expectStatus()
				.isEqualTo(409)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(409)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories/" + b.id());

		assertThat(categories.findById(b.id()))
				.isPresent()
				.get()
				.extracting(Category::getName)
				.isEqualTo(b.name());
	}

	@Test
	void getAndPatchUnknownIdReturn404() {
		Long maxId = categories.findAllByOrderByIdAsc().stream()
				.map(Category::getId)
				.max(Long::compareTo)
				.orElse(0L);
		long unknownId = maxId + 1000;

		client.get()
				.uri("/api/categories/{id}", unknownId)
				.exchange()
				.expectStatus()
				.isNotFound()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(404)
				.jsonPath("$.detail")
				.isEqualTo("Category not found")
				.jsonPath("$.instance")
				.isEqualTo("/api/categories/" + unknownId);

		client.patch()
				.uri("/api/categories/{id}", unknownId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("name", uniqueName("Whatever")))
				.exchange()
				.expectStatus()
				.isNotFound()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(404)
				.jsonPath("$.detail")
				.isEqualTo("Category not found")
				.jsonPath("$.instance")
				.isEqualTo("/api/categories/" + unknownId);

		client.get()
				.uri("/api/categories/abc")
				.exchange()
				.expectStatus()
				.isBadRequest()
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status")
				.isEqualTo(400)
				.jsonPath("$.instance")
				.isEqualTo("/api/categories/abc");
	}

	@Test
	void deleteIsNotSupported() {
		CategoryResponse created = createCategory(uniqueName("NoDelete"), null);

		client.delete()
				.uri("/api/categories/{id}", created.id())
				.exchange()
				.expectStatus()
				.isEqualTo(405)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader()
				.value("Allow", allow -> assertThat(allow).doesNotContain("DELETE"));

		client.delete()
				.uri("/api/categories")
				.exchange()
				.expectStatus()
				.isEqualTo(405)
				.expectHeader()
				.contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader()
				.value("Allow", allow -> assertThat(allow).doesNotContain("DELETE"));

		assertThat(categories.findById(created.id())).isPresent();
	}

	@Test
	void archivedDefaultsToFalseInSchema() {
		String nameForArchived = uniqueName("SchemaDefault-Archived");
		Boolean archived = jdbcTemplate.queryForObject(
				"insert into category (name) values (?) returning archived", Boolean.class, nameForArchived);
		assertThat(archived).isFalse();

		String nameForCreatedAt = uniqueName("SchemaDefault-CreatedAt");
		Timestamp createdAt = jdbcTemplate.queryForObject(
				"insert into category (name) values (?) returning created_at", Timestamp.class, nameForCreatedAt);
		assertThat(createdAt).isNotNull();
	}

}
