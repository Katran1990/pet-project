# Plan: Category CRUD

Trello card: https://trello.com/c/pGEvCuca/9-category-crud
Part of the "Wallet" feature.

## Goal
Add a `category` table (Flyway `V2`), a JPA entity, a repository and a REST controller with `POST`, `GET` (list and by id) and `PATCH` under `/api/categories`. Names are stripped of surrounding whitespace and are unique regardless of case, which the database enforces. Categories are archived and never deleted. The card also switches the whole API to RFC 9457 Problem Details for errors, with field-level validation errors.

## Acceptance criteria
- [ ] POST /api/categories creates a category; a duplicate name (case-insensitive) returns 409
- [ ] GET /api/categories returns active categories; ?includeArchived=true includes archived ones
- [ ] PATCH /api/categories/{id} updates name, icon and the archived flag
- [ ] No DELETE endpoint; the table has archived boolean default false
- [ ] Testcontainers integration test covers create, duplicate name, archive and the includeArchived filter

## Decisions
Made by the user and recorded here:
1. **Table name** stays `category` (singular). A rule "Table names are singular." is added to `CLAUDE.md`.
2. **Uniqueness:** a regular (non-partial) unique index on `lower(name)`, so archived rows count too. You cannot create "Food" while an archived "food" exists; unarchive it instead.
3. **Names are `strip()`-ped** before saving, in both POST and PATCH. Validation runs on the stripped value, and the duplicate check therefore uses it as well (`"Food "` collides with `"food"`).
4. **`GET /api/categories/{id}`** is added (200 / 404), so the `Location` header from POST points at a resource that exists.
5. **Error format for the whole project:**
   - `spring.mvc.problemdetails.enabled=true`.
   - One `@RestControllerAdvice` that extends `ResponseEntityExceptionHandler` and adds `errors: [{field, message}]` to validation errors.
   - 404, 409, 405, malformed JSON and type mismatches are all Problem Details (`application/problem+json`).
   - A rule is added to `CLAUDE.md`.
6. The error-body assertions in the existing `GreetingControllerIT` are migrated to Problem Details in this card.
7. Plan-review suggestions are accepted:
   - No reference to local, untracked planning documents.
   - The PATCH blank-name check handles line breaks, with a test.
   - `?includeArchived=abc` returns 400 as Problem Details, with a test.
   - JSON `null` values in tests are sent via `HashMap` or a raw JSON string, never `Map.of`.
8. **Catch-all 500 handler** is added to `ApiExceptionHandler`, so unhandled exceptions are Problem Details too and the CLAUDE.md rule holds for every error. The exception is logged; its message is never echoed to the client.
9. `icon` is not stripped and control characters inside names are accepted as is (Risks 3 and 4 stay as documented behaviour).
10. Second plan-review suggestions are accepted: test 2 is self-contained, the validation override delegates to `super`, OSIV and `DuplicateKeyException` notes, and a `DELETE /api/categories` 405 test.
11. **PATCH semantics for optional strings (decided after test 13 failed).** Jackson 3.1.5 deserializes an absent `Optional` record component as `Optional.empty()`, not Java `null`, so absent and explicit `null` could not be told apart and every PATCH without `icon` wiped the icon. New rule, also added to `CLAUDE.md`: in PATCH requests, an omitted or `null` optional string field means "leave unchanged", an empty string means "clear". `icon` in `UpdateCategoryRequest` becomes a plain `String`.
12. **Empty optional strings are normalised to `null` in POST and PATCH (after code review).** POST `{"icon": ""}` stores `null` (it used to store `""`), matching PATCH. The CLAUDE.md PATCH rule is extended accordingly. `patchRejectsBlankOrTooLongName` also asserts `$.errors.length() == 1` and a non-blank `$.errors[0].message`, like `rejectsInvalidCreate`.

## Changes

### Existing state (for context)
- **Package layout** is by feature: `dev.katran.pet.greeting` holds the entity, repository, request/response records and controller. There is no service layer.
- **Migrations:** only `backend/src/main/resources/db/migration/V1__create_greetings_table.sql` exists. **V2 is free**, so the card's file name `V2__create_categories_table.sql` can be used as is. V1 uses `bigint generated always as identity primary key` and lowercase SQL. `spring.jpa.hibernate.ddl-auto=none`, so Flyway owns the schema.
- **Errors today:** `ResponseStatusException` plus Spring Boot's default error body (`timestamp`, `status`, `error`, `path`). There is no `@ControllerAdvice` and ProblemDetail is not enabled.
- **Test assertions on the error body:** only `GreetingControllerIT` asserts it: `$.status` and `$.path` in `rejectsBlankMessage`, `rejectsMissingMessage`, `rejectsNullMessage`, `rejectsMessageLongerThan200Chars` and `rejectsMalformedJson`. `DatabaseStartupRetryIT` asserts only success bodies (`/actuator/health` `$.status == "UP"` and `/api/greeting` `$.message`), so it is not affected.
- **Frontend:** `frontend/src/App.tsx` only checks `response.ok` / `response.status` on `GET /api/greeting` and never parses error bodies, so the error-format change does not affect it.
- **Validation:** `spring-boot-starter-validation`. Request DTOs are records with Jakarta annotations.
- **Integration tests:** `@SpringBootTest(RANDOM_PORT)`, `@Import(TestcontainersConfiguration.class)` (postgres:17), `RestTestClient`, and an autowired repository for checking the database.
- **Jackson:** Spring Boot 4.1 uses Jackson 3, and `Instant` is written as an ISO-8601 string by default.

### 1. New file: `backend/src/main/resources/db/migration/V2__create_categories_table.sql`
```sql
create table category (
    id         bigint generated always as identity primary key,
    name       varchar(64) not null,
    icon       varchar(32),
    archived   boolean not null default false,
    created_at timestamptz not null default now()
);

-- Names are unique regardless of case ("Food" and "food" collide), archived rows included.
-- This index replaces a plain UNIQUE on name, which would only be case-sensitive.
create unique index uq_category_name_lower on category (lower(name));
```
`not null` on `name`, `archived` and `created_at` goes beyond the card text but follows from its intent.

### 2. New file: `backend/src/main/java/dev/katran/pet/category/Category.java`
A JPA entity in the same style as `Greeting`:
- `@Entity @Table(name = "category")`
- `@Id @GeneratedValue(strategy = IDENTITY) Long id`
- `@Column(nullable = false, length = 64) String name`
- `@Column(length = 32) String icon`
- `@Column(nullable = false) boolean archived`
- `@CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt`. This is Hibernate's built-in feature, so the value is set without a refresh after insert. The DB default `now()` covers rows inserted by SQL.
- A protected no-arg constructor for JPA, and a public `Category(String name, String icon)`.
- Getters for all fields, plus `setName`, `setIcon` and `setArchived` for PATCH.

### 3. New file: `backend/src/main/java/dev/katran/pet/category/CategoryRepository.java`
```java
public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findAllByArchivedFalseOrderByIdAsc();
    List<Category> findAllByOrderByIdAsc();
}
```
Derived queries, no custom SQL. `findById` comes from `JpaRepository`.

### 4. New files: request and response records (package `dev.katran.pet.category`)
**How stripping is done.** Each request record strips `name` in its compact constructor:
```java
public CreateCategoryRequest {
    name = name == null ? null : name.strip();
}
```
- Jackson 3 builds records through the canonical constructor, so the compact constructor runs during deserialization.
- Bean Validation (`@Valid`) runs afterwards on the record's components, so **every constraint sees the stripped value**.
- The controller and the database also only ever see the stripped value, so uniqueness is checked on it.
- This needs no custom validator, no custom Jackson deserializer and no global string-trimming config, only language features and standard constraints.

The records:
- `CreateCategoryRequest(@NotBlank @Size(max = 64) String name, @Size(max = 32) String icon)`
  - `"   "`, `"\t\n"` and `" \n "` strip to `""`, and `@NotBlank` rejects them with 400.
  - `"  " + 64 chars + "  "` strips to 64 chars and is accepted.
  - `icon` is optional and may be null. It is not stripped (see Risks 4); `""` is normalised to `null` in the compact constructor (decision 12).
- `UpdateCategoryRequest(@Size(min = 1, max = 64) String name, @Size(max = 32) String icon, Boolean archived)` with the same compact-constructor strip of `name` (decision 11: `icon` is a plain `String`; `null`/absent = unchanged, `""` = clear, stored as `null`).
  - Each field is optional. `null` or absent means "leave unchanged".
  - `name`: stripping first means `@Size(min = 1, max = 64)` alone rejects a present but blank name (`"   "` or `"\n"` become `""`). Bean Validation skips `null`, so an absent name is allowed. The earlier `@Pattern(".*\\S.*")` is dropped. That regex would have wrongly rejected an internal line break without `(?s)`, and after stripping it is redundant.
  - A name with an internal line break, such as `"Food\nOut"`, passes validation and is stored as is (see Risks 3).
  - ~~`icon` is `Optional<String>`~~ Superseded by decision 11: `icon` is a plain `String`. `null` or absent leaves the icon unchanged; `""` clears it (the controller stores `null`). Any other value up to 32 chars replaces it.
  - `archived` is a `Boolean` wrapper, so absent means unchanged.
- `CategoryResponse(Long id, String name, String icon, boolean archived, Instant createdAt)` with `static CategoryResponse from(Category c)`. A static factory is used because four endpoints map the entity, which does not justify MapStruct.

### 5. New file: `backend/src/main/java/dev/katran/pet/category/CategoryController.java`
`@RestController @RequestMapping("/api/categories")` with the repository injected through the constructor, like `GreetingController`.

| Method | Path | Request | Success | Errors (all `application/problem+json`) |
|---|---|---|---|---|
| `POST` | `/api/categories` | `{"name": "Food", "icon": "cart"}` (`icon` optional; `name` stripped) | `201`, `CategoryResponse`, header `Location: .../api/categories/{id}` | `400` validation (with `errors[]`) or malformed JSON (no `errors[]`); `409` duplicate name (case-insensitive, after strip, archived included) |
| `GET` | `/api/categories?includeArchived=false` | `includeArchived` is a boolean, default `false` | `200`, JSON array of `CategoryResponse`, ordered by id | `400` if `includeArchived` is not a boolean (e.g. `abc`) |
| `GET` | `/api/categories/{id}` | | `200`, `CategoryResponse` (archived categories are returned too) | `404` unknown id; `400` non-numeric id |
| `PATCH` | `/api/categories/{id}` | any subset of `{"name": "...", "icon": "..."\|null, "archived": true\|false}` | `200`, updated `CategoryResponse` | `400` validation (with `errors[]`) or malformed JSON; `404` unknown id; `409` name collides with another category |
| `DELETE` | none | | | `405 Method Not Allowed` (Problem Details, `Allow` header), produced by Spring MVC |

Success JSON:
```json
{"id": 1, "name": "Food", "icon": "cart", "archived": false, "createdAt": "2026-09-24T10:15:30.123456Z"}
```

Implementation notes:
- **POST:** `saveOrConflict(new Category(req.name(), req.icon()))`, then `ResponseEntity.created(location).body(CategoryResponse.from(saved))`. `Location` is built as in `GreetingController`.
- **GET list:** `@RequestParam(defaultValue = "false") boolean includeArchived`, then `findAllByOrderByIdAsc()` or `findAllByArchivedFalseOrderByIdAsc()`, mapped with `CategoryResponse::from`. A non-boolean value raises `MethodArgumentTypeMismatchException`, which the handler in section 6 renders as a 400 Problem Detail.
- **GET by id:** `@GetMapping("/{id}")`, `findById(id).map(CategoryResponse::from).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Category not found"))`.
- **PATCH:**
  1. `findById`, or throw 404 as above.
  2. Apply the non-null fields: `name` (already stripped); `icon` when non-null (`""` → `setIcon(null)`, otherwise `setIcon(icon)`); `archived`.
  3. Call `saveOrConflict(category)`.
  4. Renaming a category to a different case of its own name (for example `food` to `Food`) is allowed, because the index only conflicts with *other* rows.
- **409, enforced in the DB and safe under races:**
  ```java
  private Category saveOrConflict(Category category) {
      try {
          return categories.saveAndFlush(category);
      } catch (DataIntegrityViolationException e) {
          if (isNameUniqueViolation(e)) {
              throw new ResponseStatusException(HttpStatus.CONFLICT, "Category name already exists");
          }
          throw e;
      }
  }
  ```
  - `saveAndFlush` makes the `INSERT`/`UPDATE` run, and fail, inside the call. `JpaRepository` methods are already transactional, so no service layer or `@Transactional` is needed.
  - `isNameUniqueViolation` walks the cause chain for `org.hibernate.exception.ConstraintViolationException` and compares `getConstraintName()` case-insensitively with `uq_category_name_lower`. Any other integrity error still surfaces as 500 (as a Problem Detail, via the catch-all in section 6).
  - Spring may translate the unique violation to `DuplicateKeyException`, a subclass of `DataIntegrityViolationException`. Both are expected; catching the parent and walking the cause chain covers both.
  - **OSIV note:** with Boot's default `spring.jpa.open-in-view=true`, the entity returned by `findById` is still managed when PATCH mutates it before `saveAndFlush`. This is fine: on a failed flush `JpaTransactionManager` rolls back and clears the EntityManager, so nothing half-applied is written (test 17 checks the DB). Do **not** add `@Transactional`, a service layer or a refresh "to be safe".
  - There is no `existsByNameIgnoreCase` pre-check. The index is the single source of truth.
- No `@DeleteMapping`.

### 6. New file: `backend/src/main/java/dev/katran/pet/web/ApiExceptionHandler.java`
This is a new package `dev.katran.pet.web` for cross-cutting web concerns. Its sole content is this handler, which does not belong to any feature package.

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new InvalidField(e.getField(), e.getDefaultMessage()))
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
                .toList();
        ex.getBody().setProperty("errors", errors);
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    // Catch-all: anything not handled above becomes a generic 500 Problem Detail.
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    record InvalidField(String field, String message) {}
}
```

**Catch-all 500 (decision 8):**
- `@ExceptionHandler(Exception.class)` is less specific than every exception type `ResponseEntityExceptionHandler` declares, so Spring's `ExceptionDepthComparator` still routes the standard MVC exceptions and `ErrorResponseException` / `ResponseStatusException` to the base class handlers. The catch-all only receives what nothing else handles.
- The body never contains `ex.getMessage()` (it may leak SQL, class names or data). The full exception goes to the log.
- `instance` is filled by the framework as for other Problem Details.

**Why extend `ResponseEntityExceptionHandler`, and why there is no bean conflict:**
- With `spring.mvc.problemdetails.enabled=true`, Spring Boot registers `ProblemDetailsExceptionHandler`, a `@ControllerAdvice` subclass of `ResponseEntityExceptionHandler`.
- It is guarded by `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)`, so Boot's handler backs off once our subclass exists. There is no second advice competing for the same exceptions.
- Test 21 checks exactly one `ResponseEntityExceptionHandler` bean. The implementer confirms the condition in the Boot 4.1 `WebMvcAutoConfiguration` source (see Risks 2).

**What the handler covers:**
- `ResponseEntityExceptionHandler` already turns every standard Spring MVC exception into a `ProblemDetail` with `application/problem+json`:
  - `MethodArgumentNotValidException`: 400, plus our `errors[]`.
  - `HttpMessageNotReadableException` (malformed or missing JSON): 400, no `errors[]`.
  - `MethodArgumentTypeMismatchException` / `TypeMismatchException` (`?includeArchived=abc`, `/api/categories/abc`): 400.
  - `HttpRequestMethodNotSupportedException` (DELETE): 405.
  - `NoResourceFoundException`: 404.
  - `ErrorResponseException`, the superclass of **`ResponseStatusException`**: our 404 and 409 become Problem Details with `status`, `title` (reason phrase) and `detail` (the exception's reason, e.g. `"Category name already exists"`). No extra handler is needed.
- `instance` is filled with the request path by the framework (verified by tests; see Risks 2).
- **Why only field errors:** `errors[]` lists field errors only. Object-level errors cannot occur because there are no class-level constraints. Sorting makes the order deterministic for tests and clients.
- **Where `message` comes from:** Hibernate Validator's default message (e.g. `"must not be blank"`, `"size must be between 1 and 64"`), without i18n.
- **`field` for `icon`:** it is `icon`; tests 7 and 15 assert it.

Example 400:
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/categories",
  "errors": [{"field": "name", "message": "must not be blank"}]
}
```
Example 409:
```json
{"type": "about:blank", "title": "Conflict", "status": 409, "detail": "Category name already exists", "instance": "/api/categories"}
```

### 7. Change: `backend/src/main/resources/application.properties`
Add:
```properties
# API errors are RFC 9457 Problem Details (see ApiExceptionHandler)
spring.mvc.problemdetails.enabled=true
```
- Once `ApiExceptionHandler` exists, Boot's own handler backs off, so this property has no runtime effect. It is kept because the user asked for it, it records the intent, and it acts as a fallback if the custom advice is ever removed.

### 8. Change: `backend/src/test/java/dev/katran/pet/greeting/GreetingControllerIT.java`
Migrate every error-body assertion to Problem Details. Success-path tests are unchanged.
- `rejectsBlankMessage` (all three values), `rejectsMissingMessage`, `rejectsNullMessage`, `rejectsMessageLongerThan200Chars`:
  - `expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)`
  - `$.status == 400`
  - `$.instance == "/api/greetings"`, replacing `$.path`
  - `$.errors.length() == 1`, `$.errors[0].field == "message"`, `$.errors[0].message` exists (not blank)
  - The count-unchanged assertions stay.
- `rejectsMalformedJson`: same content type, `$.status == 400`, `$.instance == "/api/greetings"`, and `$.errors` does not exist.
- `rejectsNullMessage` already builds its body with `HashMap`. Keep that.

### 9. Change: `CLAUDE.md` (section "Rules")
Add three bullets:
- `Table names are singular.`
- ``API errors are RFC 9457 Problem Details (application/problem+json); validation errors add `errors: [{field, message}]`.``
- `An empty string in an optional string field is normalised to null in POST and PATCH. In PATCH, an omitted or null optional string field means "leave unchanged", an empty string means "clear".` (decisions 11, 12)

V1's `greetings` is left as is. Renaming an existing table is not part of this card.

### 10. Change: `README.md`
**API table** (around line 75). Add these rows:
- `| POST | /api/categories | Creates a category ({"name": "...", "icon": "..."}); name is stripped; 409 on duplicate name, case-insensitive |`
- `| GET | /api/categories | Lists active categories; ?includeArchived=true includes archived ones |`
- `| GET | /api/categories/{id} | Returns one category (archived ones too); 404 if unknown |`
- `| PATCH | /api/categories/{id} | Updates name, icon and/or archived; omitted or null fields are unchanged, "icon": "" clears the icon |`

**Errors note.** Add a short paragraph right below the API table:
> Errors are returned as RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`, `detail` and `instance`. Validation errors (400) additionally contain `errors: [{"field": "...", "message": "..."}]`.

### 11. Dependencies and frontend
- **No new dependencies.** JPA, validation, Flyway, Jackson `Optional` support, Hibernate `@CreationTimestamp`, Spring's `ProblemDetail` / `ResponseEntityExceptionHandler` and Testcontainers are already on the classpath. `gradle.lockfile` is unchanged.
- No frontend changes (see Existing state).

## Tests
New file: `backend/src/test/java/dev/katran/pet/category/CategoryControllerIT.java`. It has the same setup as `GreetingControllerIT` (`@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, `RestTestClient` built in `@BeforeEach`), plus `@Autowired CategoryRepository` and `JdbcTemplate`.

Conventions:
- **Isolation:** the Spring context and container are shared between test classes. Every test uses unique names (`"Food-" + UUID`), checks lists by the ids it created (contains / does not contain), never asserts global counts, and only compares counts before and after a single request.
- **Request bodies:** built by Jackson from a `Map` or record. **A body that contains a JSON `null` (e.g. `{"icon": null}`, `{"name": null}`) uses a `HashMap` or a raw JSON string, never `Map.of`,** which throws on null values. Raw strings are also used for `{}` and malformed JSON.
- **Problem Details assertions:** every error test asserts `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`, `$.status`, and `$.instance` equal to the request path. 400 validation tests also assert `$.errors[*].field` and a non-blank `$.errors[*].message`.
- **`createdAt`:** never compared exactly (Risks 5).

| # | Test | Criterion |
|---|---|---|
| 1 | `createsCategoryAndReturns201`: POST `{"name","icon"}` returns 201. The body has id, name, icon, `archived == false` and a non-null `createdAt`. `Location` ends with `/api/categories/{id}`. The repository has the row. | AC1, AC5 (create) |
| 2 | `locationPointsToExistingResource`: self-contained: POSTs its own category, then GETs the URL from that response's `Location` header; returns 200 with the same id, name and icon. | AC1 (decision 4) |
| 3 | `createsCategoryWithoutIcon`: `icon` omitted returns 201 with `icon == null`. | AC1 |
| 4 | `stripsNameOnCreate`: POST `"  Food-x \n"` returns 201 with `name == "Food-x"`, and the DB stores `"Food-x"`. Boundary: 2 spaces + 64 chars + 2 spaces returns 201 with a 64-char name. | AC1 (decision 3) |
| 4a | `createNormalisesEmptyIconToNull`: POST `{"name": <unique>, "icon": ""}` returns 201 with `icon == null`, and the DB column is `null`. | AC1 (decision 12) |
| 5 | `rejectsDuplicateNameCaseInsensitive`: create `"Food-x"`, then POST `"FOOD-X"` returns 409. Problem Details: content type, `$.status == 409`, `$.title == "Conflict"`, `$.detail == "Category name already exists"`, `$.instance == "/api/categories"`. The count is unchanged. | AC1, AC5 (duplicate) |
| 6 | `rejectsDuplicateNameAfterStrip`: create `"food-y"`, then POST `" Food-y "` returns 409. `rejectsDuplicateNameOfArchivedCategory`: archive a category, then POST the same name in another case returns 409. | AC1 (decisions 2, 3) |
| 7 | `rejectsInvalidCreate` (parameterized, raw JSON / HashMap bodies). Each returns 400 Problem Details with exactly this `errors[].field`, and the count is unchanged. Boundary: a 64-char name and a 32-char icon return 201.<br>- `""`, `"   "`, `"\t\n"`, missing, `null` → `name`<br>- 65 chars after strip → `name`<br>- 33-char icon → `icon`<br>- Both invalid → two entries, `icon` and `name`, sorted. | AC1 (validation, error format) |
| 8 | `rejectsMalformedJsonOnCreate`: body `{"name":` returns 400 Problem Details, with `$.errors` absent. | AC1 (error format) |
| 9 | `listsOnlyActiveByDefault`: create A and B, archive B via PATCH. `GET /api/categories` contains A, not B. | AC2, AC5 (archive + filter) |
| 10 | `includeArchivedTrueListsArchived`: `?includeArchived=true` contains A and B with correct `archived` flags. `?includeArchived=false` equals the default. | AC2, AC5 (includeArchived) |
| 11 | `rejectsNonBooleanIncludeArchived`: `?includeArchived=abc` returns 400 Problem Details (`$.status == 400`, `$.instance == "/api/categories"`). | AC2 (review c) |
| 12 | `patchUpdatesNameIconAndArchived`: PATCH all three returns 200 with the new values, and the DB has them. Then PATCH `{"archived": false}` unarchives it. | AC3, AC5 (archive) |
| 13 | `patchWithPartialBodyKeepsOtherFields`:<br>- `{"archived": true}` (icon absent) leaves name and icon unchanged.<br>- Raw `{"icon": null}` or `HashMap` leaves the icon unchanged.<br>- `{}` changes nothing.<br>`patchWithEmptyIconClearsIcon`: `{"icon": ""}` returns 200 with `icon == null`, and the DB column is `null`. | AC3 (decision 11) |
| 14 | `patchStripsName`: `{"name": "  New-z  "}` returns 200 with `"New-z"`. `patchSameNameDifferentCaseOfItself` returns 200. | AC3 (decision 3) |
| 15 | `patchRejectsBlankOrTooLongName` (parameterized). Each returns 400 Problem Details with `errors[0].field == "name"`, and the row is unchanged.<br>- `""`, `"   "`, `"\n"`, `" \r\n\t "`<br>- 65 chars<br>- 33-char icon → `errors[0].field == "icon"` | AC3 (review b) |
| 16 | `patchAcceptsNameWithInnerLineBreak`: `{"name": "Food\nOut-<uuid>"}` returns 200. The name is stored with the line break. | AC3 (review b) |
| 17 | `patchToExistingNameReturns409`: renaming B to `" " + A.upper + " "` returns 409 Problem Details (`$.instance == "/api/categories/{idB}"`), and B is unchanged in the DB. | AC1, AC3 |
| 18 | `getAndPatchUnknownIdReturn404`: `GET` and `PATCH /api/categories/{max+1000}` return 404 Problem Details with `$.detail == "Category not found"`. `GET /api/categories/abc` returns 400 Problem Details. | AC3 (decision 4) |
| 19 | `deleteIsNotSupported`: `DELETE /api/categories/{id}` and `DELETE /api/categories` both return 405 Problem Details with an `Allow` header that does not contain `DELETE`. The row still exists. | AC4 |
| 20 | `archivedDefaultsToFalseInSchema`: `jdbcTemplate.queryForObject("insert into category (name) values (?) returning archived", Boolean.class, uniqueName)` is `false`. The same pattern checks that `created_at` is not null. This proves the column default in the migration. | AC4 |

New file: `backend/src/test/java/dev/katran/pet/web/ApiExceptionHandlerIT.java`. It uses the same `@SpringBootTest` setup, so the context is cached and shared.

| # | Test | Criterion |
|---|---|---|
| 21 | `exactlyOneResponseEntityExceptionHandler`: `context.getBeansOfType(ResponseEntityExceptionHandler.class)` has size 1, and the single bean is an `ApiExceptionHandler`. Proves Boot's `ProblemDetailsExceptionHandler` backed off. | Decision 5 |
| 22 | `unknownRouteIsProblemDetails`: `GET /api/does-not-exist` returns 404 with `application/problem+json`. | Decision 5 |

New file: `backend/src/test/java/dev/katran/pet/web/ApiExceptionHandlerTest.java` (plain unit test, no Spring context, so the shared test context is not polluted by a test-only controller).

| # | Test | Criterion |
|---|---|---|
| 23 | `unexpectedExceptionIsGeneric500`: call `new ApiExceptionHandler().handleUnexpected(new IllegalStateException("secret SQL detail"))`. The result has status 500, title "Internal Server Error", detail "Unexpected error", and neither detail nor properties contain "secret SQL detail". | Decision 8 |

Updated file: `GreetingControllerIT`, as described in Changes section 8 (decision 6).

Done means `cd backend && ./gradlew test` is green: the new tests, the updated `GreetingControllerIT`, `PetApplicationTests` and the `db` tests.

## Risks and open questions
1. **PATCH null semantics for `icon`: resolved.** The `Optional` approach failed (absent became `Optional.empty()` in Jackson 3.1.5). Resolved by decision 11: `""` clears, `null`/absent leaves unchanged.
2. **Framework details to confirm during implementation.** These are backed by tests, not guessed:
   - Boot 4.1's `ProblemDetailsExceptionHandler` is `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)` (test 21).
   - `instance` is populated with the request path (`$.instance` assertions).
   - The `errors` property is serialized at the top level of the JSON, not under `properties`, with Jackson 3's `ProblemDetail` mixin (tests 7 and 15).

   If `instance` turns out not to be set automatically, set it in `handleExceptionInternal` from `request.getDescription(false)`. That is one override, and it keeps the tests unchanged.
3. **Internal whitespace and control characters in names.** `strip()` only removes leading and trailing whitespace. `"Food\nOut"` or `"Food\tOut"` is accepted and stored as is (test 16). Should names reject control characters, or collapse internal whitespace? The plan assumes no.
4. **`icon` is not stripped** (the decision covers names only). `""` is normalised to `null` (decision 12), but a whitespace-only icon such as `"   "` is stored as is (decision 9).
5. **`createdAt` precision.** `@CreationTimestamp` uses JVM time, which may have nanoseconds, while Postgres stores microseconds. The POST response can differ from a later GET in the last digits. Alternative: `@CreationTimestamp(source = SourceType.DB)`.
6. **List order.** The card leaves ordering out of scope. The plan returns categories by `id asc` for determinism. Ordering by name would suit a select box better, but case and collation behaviour would need a decision.
7. **`lower()` vs full Unicode case folding.** `lower(name)` depends on the database ctype. It covers Latin and Cyrillic in a UTF-8 database, but not special folding such as ß/SS. `citext` or ICU nondeterministic collations would cover this but add complexity.
8. **Errors outside Spring MVC** (e.g. thrown by a servlet filter before dispatch) never reach a `@ControllerAdvice` and still get Boot's `/error` body. There are no custom filters today, so this is theoretical.
9. **The error format change is breaking** for any external client that parses the old `timestamp/status/error/path` body. Inside the repo, only tests do, and they are updated here. The frontend only reads status codes.
10. **New dependencies:** none.

## Out of scope
- User ownership (`user_id`), category ordering (sort_order / reordering), nested categories (from the card).
- `PUT` and any `DELETE` for categories (categories are archived, never deleted).
- Rules about archived categories in other entities (e.g. 409 when an expense references an archived category) belong to later cards.
- Renaming the existing `greetings` table to singular.
- i18n of validation messages.
- Frontend changes and seed data for categories.
