# Plan: Expense create, read, update, delete

Trello card: https://trello.com/c/FhFglXQj/10-expense-create-read-update-delete
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add the `expense` table (Flyway `V3`), a JPA entity, a repository and a REST controller with `POST /api/expenses` and `GET`, `PUT` and `DELETE /api/expenses/{id}`. Amounts are exact `BigDecimal`s and are returned as strings with two decimals. The "spentOn is not in the future" check uses a `Clock` bean whose time zone comes from configuration.

## Acceptance criteria
- [ ] POST /api/expenses accepts amount, categoryId, spentOn, note and returns 201 with the created expense
- [ ] 400 when amount <= 0, spentOn is in the future, or categoryId is missing; 409 when the category is archived
- [ ] GET /api/expenses/{id} returns the expense with an embedded category object (id, name, icon), not a bare categoryId
- [ ] PUT /api/expenses/{id} replaces the expense; DELETE /api/expenses/{id} returns 204
- [ ] Amounts are serialized as strings with two decimals ("200.00"); input with more than two decimals is rejected with 400
- [ ] Integration tests cover each validation rule and the create/read/update/delete round trip

### Decisions
Made by the user and recorded here:
1. **Table name** is `expense` (singular, per CLAUDE.md). The migration file keeps the card's name `V3__create_expenses_table.sql`, the same approach as `V2__create_categories_table.sql` creating table `category`. V3 has been checked and is free (see Existing state).
2. **Currency:** column `currency char(3) not null default 'PLN'`. Multi-currency is out of scope. Responses include `currency`, but requests do NOT accept it, so the DB default always applies.
3. **Archived category:**
   - Creating an expense in an archived category returns 409 Problem Details.
   - PUT that switches an expense to a *different* archived category returns 409.
   - PUT that keeps the expense's *current* category is allowed even if that category has been archived since. Old expenses of an archived category stay editable.
4. **Unknown (non-existent) `categoryId`** in POST or PUT returns 400 Problem Details with `errors: [{field: "categoryId", message: "Category not found"}]`. This is the same shape as the "categoryId missing" validation error.
5. **"spentOn is in the future"** is checked against "today" in a configurable time zone:
   - The zone is a property backed by an environment variable, default `Europe/Warsaw`.
   - It is exposed through a `java.time.Clock` bean, so tests can control "today".
   - `spentOn == today` is valid. `today + 1` is rejected with 400 and a field error on `spentOn`.
6. **Open questions Q1-Q8 are accepted as proposed** (plan approval, 2026-09-30). For Q3, `CLAUDE.md` is **not** changed.
7. **Plan-review suggestions 1-5 are accepted** and already worked into the sections below:
   1. `@DecimalMax("9999999999.99")` on `amount`, because `@Digits` overflows for literals like `"1e2147483647"` (section 4, test 6).
   2. PUT builds its response from the managed `expense`, not from the return value of `saveAndFlush`, so it does not depend on OSIV (section 7, Risk 8).
   3. Tests 2 and 21 leave `createdAt` out of POST-vs-GET comparisons.
   4. Bean Validation constraints live only on `ExpenseRequest`, never on the `Expense` entity (section 2).
   5. Test 16 also checks that GET and DELETE work for an expense of an archived category.

## Changes

### Existing state (verified in the code)
- **Package layout** is by feature: `dev.katran.pet.greeting` and `dev.katran.pet.category` each hold their entity, repository, request/response records and controller. Other packages:
  - `dev.katran.pet.db`: startup wait for the database.
  - `dev.katran.pet.web`: `ApiExceptionHandler` and `NotFoundException`.

  There is no service layer. Controllers call repositories directly, write with `saveAndFlush`, and use no `@Transactional`.
- **Migrations:** only `V1__create_greetings_table.sql` and `V2__create_categories_table.sql` exist in `backend/src/main/resources/db/migration`, so **V3 is free**. Style: lowercase SQL, `bigint generated always as identity primary key`, named index `uq_category_name_lower`.
- **`application.properties`:**
  - `spring.jpa.hibernate.ddl-auto=none` (Flyway owns the schema) and `spring.mvc.problemdetails.enabled=true`.
  - **OSIV:** `spring.jpa.open-in-view` is not set anywhere (and there is no `src/test/resources`), so Boot's default `true` applies, with its startup warning.
  - There is no `Clock` bean and no time-zone setting today.
- **`ApiExceptionHandler`** (extends `ResponseEntityExceptionHandler`):
  - `MethodArgumentNotValidException` → 400 with `errors[]`, sorted by field, then message.
  - `DataIntegrityViolationException` → looks up the constraint name in `CONSTRAINT_CONFLICT_DETAILS` (only `uq_category_name_lower`). Known name → 409; otherwise the generic 500.
  - `NotFoundException(entity, id)` → 404 with `detail = "<Entity> not found"` and an `id` property.
  - Catch-all `Exception` → generic 500 (logged; the message is never echoed).
- **`Category`** has public getters, `isArchived()`, and setters for name, icon and archived.
- **Tests:**
  - `CategoryControllerIT` uses `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, a `RestTestClient` built in `@BeforeEach`, and autowired repository and `JdbcTemplate`. It uses unique names per test, raw JSON strings or `HashMap` for `null`s, and Problem Details assertions on content type, `$.status` and `$.instance`.
  - There is no shared base test class.
  - `DatabaseStartupRetryIT` starts the real app with a filter that skips `@TestComponent`/`@TestConfiguration` classes.
- **Library versions** (from `backend/gradle.lockfile`):
  - Spring Boot 4.1.1 and Spring Framework 7.0.9.
  - Hibernate ORM 7.4.5 (has `org.hibernate.annotations.Generated` and `CreationTimestamp`).
  - Hibernate Validator 9.1.3.
  - Jackson databind 3.1.5 with `jackson-annotations` 2.21 (has `@JsonFormat`).
  - `spring-boot-validation` 4.1.1 contains `org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer`.
  - `spring-test` 7.0.9 contains `org.springframework.test.context.bean.override.convention.TestBean`.

### API contract

| Method | Path | Request | Success | Errors (all `application/problem+json`) |
|---|---|---|---|---|
| `POST` | `/api/expenses` | `{"amount": "200.00", "categoryId": 7, "spentOn": "2026-06-16", "note": "Lunch"}` (`note` optional) | `201`, `ExpenseResponse`, header `Location: .../api/expenses/{id}` | `400` validation (`errors[]`) or unreadable body (no `errors[]`); `400` unknown category (`errors[categoryId]`); `409` archived category |
| `GET` | `/api/expenses/{id}` | | `200`, `ExpenseResponse` | `404` unknown id; `400` non-numeric id |
| `PUT` | `/api/expenses/{id}` | same body as POST (full replacement) | `200`, `ExpenseResponse` | as POST, plus `404` unknown id; `409` only when `categoryId` differs from the current one and that category is archived |
| `DELETE` | `/api/expenses/{id}` | | `204`, empty body | `404` unknown id; `400` non-numeric id |

**Response shape.** POST, GET and PUT all return the same `ExpenseResponse`, so POST also embeds the category:
```json
{
  "id": 42,
  "amount": "200.00",
  "currency": "PLN",
  "spentOn": "2026-06-16",
  "note": "Lunch",
  "createdAt": "2026-06-16T10:15:30.123456Z",
  "category": {"id": 7, "name": "Food", "icon": "cart"}
}
```
- There is no `categoryId` property.
- The embedded category has exactly `id`, `name` and `icon` (no `archived`, no `createdAt`).
- `note: null` and `category.icon: null` are serialized as `null`.

**Money:**
- **Java type:** `BigDecimal` in the request, entity and response. Never `float` or `double`.
- **Input:** `amount` is accepted as a JSON string (`"200.00"`, `"200"`, `"200.5"`) **or** a JSON number (`200`, `200.5`, `19.99`). Jackson builds a `BigDecimal` for both from the literal text, with no `double` in between. This is Jackson's default behaviour, so no custom deserializer is needed (open question Q1).
- **Validation:** Bean Validation on the request record, so every violation is a 400 with `errors: [{field: "amount", ...}]`:
  - `@NotNull`: missing or `null`.
  - `@Positive`: `0`, `"0.00"` and negative values (AC2).
  - `@Digits(integer = 10, fraction = 2)`:
    - More than two decimals: `"200.555"`, `"0.001"`, JSON `0.30000000000000004`, and also `"200.500"`, because the literal scale counts (Q2).
    - More than 10 integer digits: `"10000000000"`, `"99999999999.99"`.

  - `@DecimalMax("9999999999.99")` (decision 7.1): Hibernate Validator's `@Digits` computes `precision() - scale()` as an `int`, which overflows for a literal such as `"1e2147483647"` and lets it pass. `@DecimalMax` compares with `BigDecimal.compareTo` and cannot overflow.

    Together these rules cover everything `numeric(12,2)` could not store exactly, so the database never sees an out-of-range amount and amount input can never cause a 500. The maximum is `9999999999.99`.
  - Values that are not numbers (`"abc"`, `true`) fail in Jackson and get the existing 400 Problem Detail without `errors[]` (Q5).
- **Normalisation:**
  - `"200"` becomes `"200.00"` and `"200.5"` becomes `"200.50"`. `ExpenseResponse.from` applies `amount.setScale(2, RoundingMode.UNNECESSARY)`.
  - This never rounds, because validation already guarantees at most two decimals. `UNNECESSARY` makes a broken guarantee fail loudly instead of rounding silently.
  - The `numeric(12,2)` column stores `200.00` either way.
- **Output:** `@JsonFormat(shape = JsonFormat.Shape.STRING)` on `ExpenseResponse.amount`. With scale 2, `BigDecimal.toString()` never uses exponent notation, so the output is always `"200.00"`.

**`note` (optional string):**
- `@Size(max = 255)`: 255 characters are accepted; 256 give 400 with field `note`.
  - `@Size` counts UTF-16 units and Postgres `varchar(255)` counts code points, so Java is never less strict than the DB.
- `""` is normalised to `null` in the record's compact constructor (CLAUDE.md rule for POST).
- `note` is not stripped, mirroring category `icon` (Q7).
- **PUT is a full replacement:** omitted, `null` and `""` all store `null`, and any other value replaces the note. CLAUDE.md's "omitted = unchanged" rule is for PATCH only (Q3).

**`currency`:** is not a request property. Spring's Jackson setup ignores unknown properties, so `"currency": "EUR"` in a body is ignored and the stored value is always `PLN` (test 12 pins this behaviour; Q4).

**`spentOn`:**
- `@NotNull @PastOrPresent LocalDate`.
- Hibernate Validator checks `@PastOrPresent` against `LocalDate.now(clockProvider.getClock())`. The `Clock` bean is wired into Bean Validation (section 11), so "today" is taken in the configured zone.
- The default message is "must be a date in the past or in the present".

**Evaluation order** (also documents precedence):
1. Unreadable body (malformed JSON, wrong JSON type, impossible date such as `"2026-02-30"`): 400 without `errors[]`.
2. Bean Validation: 400 listing all field errors at once, sorted.
3. (GET, PUT, DELETE) The expense does not exist: 404 `"Expense not found"`.
4. `categoryId` does not exist: 400 `errors[{categoryId, "Category not found"}]`.
5. The category is archived: 409 `"Category is archived"`. Always checked for POST; for PUT only when `categoryId` differs from the current one.

### 1. New file: `backend/src/main/resources/db/migration/V3__create_expenses_table.sql`
```sql
create table expense (
    id          bigint generated always as identity primary key,
    category_id bigint         not null,
    amount      numeric(12, 2) not null,
    currency    char(3)        not null default 'PLN',
    spent_on    date           not null,
    note        varchar(255),
    created_at  timestamptz    not null default now(),
    constraint fk_expense_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_expense_amount_positive check (amount > 0)
);

create index ix_expense_spent_on_category_id on expense (spent_on, category_id);
```
- **Names** follow the `<kind>_<table>_<what>` style of `uq_category_name_lower`: `fk_expense_category`, `ck_expense_amount_positive`, `ix_expense_spent_on_category_id`.
- **`not null`** on `category_id`, `amount` and `created_at` goes beyond the card text but follows from its intent (the check alone lets `null` through), as in the category plan.
- **No separate `category_id` index.** `on delete restrict` would scan `expense` by `category_id` only if a category were deleted, and categories are archived, never deleted.

**No new `ApiExceptionHandler` mapping for these constraints.** None of them can be violated through the API:
- `ck_expense_amount_positive` and numeric overflow are prevented by `@Positive` and `@Digits`.
- `fk_expense_category` is prevented by the category lookup before insert/update, and there is no category delete endpoint.
- `on delete restrict` only fires when a category row is deleted.

If one is ever hit (a bug or manual SQL), the existing `DataIntegrityViolationException` handler finds no mapping and returns the generic, logged 500. Adding them to `CONSTRAINT_CONFLICT_DETAILS` would be wrong, because that map produces 409.

### 2. New file: `backend/src/main/java/dev/katran/pet/expense/Expense.java`
A JPA entity in the style of `Category`:
- `@Entity @Table(name = "expense")`.
- `@Id @GeneratedValue(strategy = IDENTITY) Long id`.
- `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false) Category category`, referencing `dev.katran.pet.category.Category`.
- `@Column(nullable = false, precision = 12, scale = 2) BigDecimal amount`.
- `@Generated @Column(nullable = false, length = 3, insertable = false, updatable = false) String currency`:
  - Hibernate's `@Generated` (event INSERT, the default) leaves the column out of the `INSERT` and reads the DB default back after insert, so the POST response already says `"PLN"`.
  - The default is not duplicated in Java.
  - There is no setter.
- `@Column(name = "spent_on", nullable = false) LocalDate spentOn`.
- `@Column(length = 255) String note`.
- `@CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt`, same as `Category`.
- A protected no-arg constructor for JPA, and `public Expense(Category category, BigDecimal amount, LocalDate spentOn, String note)`.
- Getters for all fields; setters for `category`, `amount`, `spentOn` and `note` (used by PUT).
- **No Bean Validation annotations on the entity** (decision 7.4). Hibernate ORM's pre-insert validation builds its own `ValidatorFactory` without the `Clock` bean, so a "defensive" `@PastOrPresent` here would use the JVM default zone. All constraints live on `ExpenseRequest`.

### 3. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseRepository.java`
```java
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @EntityGraph(attributePaths = "category")
    Optional<Expense> findWithCategoryById(Long id);
}
```
- This is a derived query with an entity graph. GET and PUT load the expense and its category in **one** `select ... join`, with no lazy load and no N+1.
- It does not depend on OSIV.
- `findById` and `delete` come from `JpaRepository`.

### 4. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseRequest.java`
One record serves both POST and PUT, because PUT is a full replacement with the same fields and rules. This departs from the `Create...`/`Update...` pair in `category`, which exists there only because PATCH has different semantics.
```java
public record ExpenseRequest(
        @NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
        @NotNull Long categoryId,
        @NotNull @PastOrPresent LocalDate spentOn,
        @Size(max = 255) String note) {

    public ExpenseRequest {
        note = note == null || note.isEmpty() ? null : note;
    }
}
```
- There is no `currency` component (decision 2).
- The compact constructor runs during Jackson deserialization, before validation, like in `CreateCategoryRequest`.
- The amount is deliberately **not** rescaled here. `setScale(2)` on `"200.555"` would throw inside Jackson and turn a field error into a body-level 400.

### 5. New file: `backend/src/main/java/dev/katran/pet/category/CategorySummary.java`
`public record CategorySummary(Long id, String name, String icon)` with `static CategorySummary from(Category c)`.
- It is the embedded category view required by AC3.
- It lives in the `category` package because it is a view of a category, and cards 4-6 (budget limits, report, quick templates) embed the same `(id, name, icon)`.

### 6. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseResponse.java`
```java
public record ExpenseResponse(
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        LocalDate spentOn,
        String note,
        Instant createdAt,
        CategorySummary category) {

    public static ExpenseResponse from(Expense e) { ... }
}
```
- `from` passes `e.getAmount().setScale(2, RoundingMode.UNNECESSARY)` and `CategorySummary.from(e.getCategory())`.
- A static factory is used, as in `CategoryResponse`; there is not enough mapping to justify MapStruct.
- `LocalDate` is written as `"2026-06-16"` and `Instant` as ISO-8601; both are Jackson 3 / Boot defaults and the tests assert them.

### 7. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseController.java`
`@RestController @RequestMapping("/api/expenses")`, with `ExpenseRepository` and `CategoryRepository` injected through the constructor. There is no service layer, following the category card.

- **POST**
  1. `Category category = activeCategory(request.categoryId())` (see the helper below).
  2. `expenses.saveAndFlush(new Expense(category, amount, spentOn, note))`.
  3. `ResponseEntity.created(location).body(ExpenseResponse.from(saved))`. `Location` is built with `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`, as in `CategoryController`.
  4. The category in the response is the instance loaded in step 1, so there is no lazy load.
- **GET `/{id}`:** `expenses.findWithCategoryById(id).map(ExpenseResponse::from).orElseThrow(() -> new NotFoundException("Expense", id))`.
- **PUT `/{id}`**
  1. `findWithCategoryById(id)`, or throw `NotFoundException("Expense", id)`.
  2. If `!request.categoryId().equals(expense.getCategory().getId())`, then `expense.setCategory(activeCategory(request.categoryId()))`. Otherwise keep the current category **without** checking `archived` (decision 3).
  3. Set `amount`, `spentOn` and `note` (`null` when omitted, `null` or `""`: full replacement).
  4. `expenses.saveAndFlush(expense); return ExpenseResponse.from(expense);` (decision 7.2). The response is built from the instance loaded in step 1, whose category is already initialised. The return value of `saveAndFlush` (`merge`) is not used: with OSIV off it would be a copy with an uninitialised LAZY `category` proxy.

  **Order matters (OSIV):**
  - With `open-in-view=true`, one `EntityManager` spans the whole request, and the loaded expense stays managed between repository calls.
  - Step 2 can throw 400/409, so it runs **before** any setter is called. No half-modified managed entity can then be flushed by a later transaction in the same request.
  - As in the category card, do **not** add `@Transactional`, a service layer or a refresh.
- **DELETE `/{id}`**
  - `@ResponseStatus(HttpStatus.NO_CONTENT)`.
  - `Expense e = expenses.findById(id).orElseThrow(() -> new NotFoundException("Expense", id)); expenses.delete(e);`
  - `deleteById` alone is not used because Spring Data silently ignores missing ids, and a 404 is needed.
  - Deleting an expense of an archived category is allowed.
- **Private helper**
  ```java
  private Category activeCategory(Long categoryId) {
      Category category = categories.findById(categoryId)
              .orElseThrow(() -> new InvalidFieldException("categoryId", "Category not found"));
      if (category.isArchived()) {
          throw new ConflictException("Category is archived");
      }
      return category;
  }
  ```
- **Not mapped here:** `GET /api/expenses` (list) and `PATCH`. Spring MVC answers them with 405 Problem Details.

### 8. New file: `backend/src/main/java/dev/katran/pet/web/InvalidFieldException.java`
- A `RuntimeException(String field, String message)` with `getField()`.
- Used for field-level 400s that Bean Validation cannot detect (decision 4).
- It sits in `web` next to `NotFoundException`, following decision 13 of the category card: controllers throw domain exceptions, and `ApiExceptionHandler` maps them.

### 9. New file: `backend/src/main/java/dev/katran/pet/web/ConflictException.java`
- A `RuntimeException(String detail)` for 409s decided by the application (e.g. "Category is archived").
- The alternative, `ResponseStatusException(CONFLICT, ...)`, works with no new class but goes against the category card's move away from `ResponseStatusException` in controllers.

### 10. Change: `backend/src/main/java/dev/katran/pet/web/ApiExceptionHandler.java`
Add two handlers; nothing else changes (`CONSTRAINT_CONFLICT_DETAILS` stays as it is, see section 1):
```java
@ExceptionHandler(InvalidFieldException.class)
ProblemDetail handleInvalidField(InvalidFieldException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
    problem.setProperty("errors", List.of(new InvalidField(ex.getField(), ex.getMessage())));
    return problem;
}

@ExceptionHandler(ConflictException.class)
ProblemDetail handleConflict(ConflictException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
}
```
- The 400 uses the same `detail` text as Spring's `MethodArgumentNotValidException` Problem Detail and the same `InvalidField` record. A client therefore cannot tell "category not found" apart from any other field error, which is what decision 4 asks for.
- Both exception types are more specific than the catch-all `Exception` handler, so `ExceptionDepthComparator` routes them correctly.
- `instance` is filled by the framework, as for `NotFoundException`.

### 11. New file: `backend/src/main/java/dev/katran/pet/time/ClockConfiguration.java`
This adds a new package `dev.katran.pet.time`, because "today in the app's zone" is cross-cutting: card 3 defaults to the current month and card 6 creates an expense "dated today".
```java
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock(@Value("${app.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }

    // Bean Validation's @PastOrPresent / @FutureOrPresent evaluate "now" with this clock and its zone.
    @Bean
    ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
```
- **String to `ZoneId` conversion** uses Spring's built-in `ZoneIdEditor`. An invalid zone fails startup, which is covered by a test.
- **Why `@Value`:** it is used instead of a `@ConfigurationProperties` record like `DatabaseStartupWaitProperties`, because this is a single scalar with no validation beyond parsing.
- **Why `ValidationConfigurationCustomizer`:** it is Boot's built-in hook. Boot applies it to the auto-configured `LocalValidatorFactoryBean`, and Spring MVC's `@Valid` also uses that validator (via `ValidatorAdapter`). No custom validator and no controller-side date check are needed.
- **Why not `TZ` / `-Duser.timezone`:** that would change the JVM default zone for everything (logs included), and tests could not swap it.

### 12. Change: `backend/src/main/resources/application.properties`
Add:
```properties
# Zone in which "today" is evaluated, e.g. an expense's spentOn must not be after today (see README "Configuration")
app.time-zone=${APP_TIME_ZONE:Europe/Warsaw}
```

### 13. Change: `README.md`
- **Configuration table:** add the row `| APP_TIME_ZONE | Europe/Warsaw |` and one sentence: "`APP_TIME_ZONE` is the IANA zone in which "today" is evaluated (an expense's `spentOn` must not be after today in this zone); an invalid zone fails startup."
- **CI and Docker images:** add `APP_TIME_ZONE` to the bullet "The backend image reads ...".
- **API table:** add these rows:
  - `| POST | /api/expenses | Creates an expense ({"amount": "200.00", "categoryId": 1, "spentOn": "2026-06-16", "note": "..."}); amount > 0 with at most two decimals, spentOn not after today (APP_TIME_ZONE); 400 on unknown category, 409 on archived category |`
  - `| GET | /api/expenses/{id} | Returns one expense with its category embedded as {id, name, icon}; 404 if unknown |`
  - `| PUT | /api/expenses/{id} | Replaces amount, categoryId, spentOn and note (an omitted note clears it); 409 when switching to an archived category |`
  - `| DELETE | /api/expenses/{id} | Deletes an expense; 204, or 404 if unknown |`
- **Below the errors paragraph:** "Money amounts are JSON strings with two decimals (`"200.00"`). `currency` is always `PLN` for now and is not accepted in requests."

### 14. Dependencies, build, frontend, deployment
- **No new dependencies.** Everything used is already on the classpath (see Existing state for versions):
  - JPA, Hibernate's `@Generated`/`@EntityGraph`, Bean Validation (`@Positive`, `@Digits`, `@PastOrPresent`), Boot's `ValidationConfigurationCustomizer`.
  - Jackson `@JsonFormat`.
  - Spring's `@TestBean` and Boot's `ApplicationContextRunner` for tests.
- `backend/gradle.lockfile` is unchanged, so the `./gradlew dependencies --write-locks` step from CLAUDE.md is **not** needed. If the implementer ends up touching dependencies anyway, that step becomes mandatory.
- No frontend changes (card 7).
- No Helm change: the backend Deployment does not need to set `APP_TIME_ZONE` because the default applies.
- No `CLAUDE.md` change (Q3 decided: not changed).

## Tests
Done means `cd backend && ./gradlew test` is green. Existing tests (`GreetingControllerIT`, `CategoryControllerIT`, `ApiExceptionHandlerIT`, `ApiExceptionHandlerTest`, `PetApplicationTests`, the `db` tests) stay unchanged and must still pass.

### New file: `backend/src/test/java/dev/katran/pet/expense/ExpenseControllerIT.java`
**Setup.** No shared base class exists and none is introduced. The class copies `CategoryControllerIT`'s setup:
- `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, `RestTestClient` in `@BeforeEach`.
- Autowired `ExpenseRepository`, `CategoryRepository` and `JdbcTemplate`.

It also adds a controlled clock:
```java
// "Today" is 2026-06-16 in Europe/Warsaw (00:30 CEST) while it is still 2026-06-15 in UTC.
@TestBean
Clock clock;

static Clock clock() {
    return Clock.fixed(Instant.parse("2026-06-15T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
}
```
- `@TestBean` replaces the `clock` bean before `validationClock` receives it, so Bean Validation sees the fixed clock.
- The override gives this class its own cached Spring context and its own Postgres container, which adds a few seconds.
- All dates in this class are relative to `TODAY = 2026-06-16`; a normal past date is `2026-06-10`.

**Conventions** (same as `CategoryControllerIT`):
- Categories for setup are created through `CategoryRepository` (`new Category("Food-" + UUID, "cart")`, `setArchived(true)` + `saveAndFlush`). This avoids coupling to the category API.
- Global counts are never asserted; only `expenses.count()` before vs after a single rejected request.
- Bodies containing `null`, JSON numbers vs strings, or malformed JSON are raw JSON strings (or a `HashMap`), never `Map.of`.
- Every error test asserts `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`, `$.status` and `$.instance`.
- 400 validation tests assert `$.errors.length()`, each `$.errors[i].field` and a non-blank `$.errors[i].message`.
- `amount` is always asserted as a JSON **string** (`jsonPath("$.amount").isEqualTo("200.00")`, which fails if the value is a number).
- `createdAt` is never compared exactly with the POST response (category plan, Risk 5).
- An unknown id is `Long.MAX_VALUE` (identity values never reach it).

| # | Test | Proves |
|---|---|---|
| 1 | `createsExpenseAndReturns201`: POST `{"amount":"200.00","categoryId":C,"spentOn":"2026-06-10","note":"Lunch"}` returns 201.<br>- Body: `$.id`; `$.amount == "200.00"`; `$.currency == "PLN"`; `$.spentOn == "2026-06-10"`; `$.note == "Lunch"`; `$.createdAt` exists; `$.category.id/name/icon` match C; `$.categoryId` does not exist.<br>- `Location` ends with `/api/expenses/{id}`.<br>- DB row via `JdbcTemplate`: `amount = 200.00`, `currency = 'PLN'`, `category_id = C`, `spent_on`, `note`. | AC1, AC3 (POST embeds too), AC5, decision 2 |
| 2 | `getReturnsExpenseWithEmbeddedCategory`: GET the `Location` from a POST returns 200 with the same fields, except `createdAt`, which is only asserted to exist (decision 7.3).<br>- `$.category` has exactly `id`, `name`, `icon` (`$.category.archived` and `$.categoryId` do not exist).<br>- Then rename the category and clear its icon via the repository: GET shows the new name and `"icon": null`, which proves the category is joined, not copied. | AC3 |
| 3 | `createNormalisesNote`: `note` omitted, `null` and `""` each return 201 with `$.note == null` and a `null` DB column. `"   "` is stored as is (Q7). | CLAUDE.md empty-string rule |
| 4 | `acceptsAmountFormats` (parameterized; raw amount literal → expected response string, and the DB value `compareTo` equal):<br>- `"200"` → `"200.00"`; `"200.5"` → `"200.50"`; `"200.50"` → `"200.50"`<br>- `"0.01"` → `"0.01"` (minimum)<br>- `"9999999999.99"` → `"9999999999.99"` (maximum of `numeric(12,2)`)<br>- JSON number `200` → `"200.00"`; JSON number `19.99` → `"19.99"` (no `double` round trip) | AC5, Q1 |
| 5 | `rejectsInvalidAmount` (parameterized). Each returns 400 with exactly one error `field == "amount"`; the count is unchanged.<br>- `<= 0`: `0`, `"0"`, `"0.00"`, `-1`, `"-200.00"`<br>- `> 2 decimals`: `"200.555"`, `200.555`, `"0.001"`, `"200.500"`, `0.30000000000000004`<br>- exceeds `numeric(12,2)`: `"10000000000"`, `10000000000.00`, `"99999999999.99"` (400, never 500)<br>- missing, `null` | AC2 (amount <= 0), AC5 (> 2 decimals), money rule |
| 6 | `rejectsUnparseableAmount`: `"abc"`, `""`, `true`, `1e1000000`, `"1e2147483647"` and `1e2147483647` (decision 7.1: rejected either by Jackson or by `@DecimalMax`) each return 400 Problem Details (status, content type, instance); the count is unchanged. `errors[]` is not asserted, because the path depends on Jackson (Q5); the point is "400, never 500". | AC5, money rule |
| 7 | `spentOnBoundaryUsesClockAndZone`:<br>- Sanity: `LocalDate.now(clock)` is `2026-06-16`.<br>- `"2026-06-16"` (today in Warsaw, still tomorrow in UTC) → 201.<br>- `"2026-06-15"` → 201.<br>- `"2026-06-17"` → 400 with exactly one error `field == "spentOn"`.<br>- `"2099-01-01"` → 400 `spentOn`.<br>A check using UTC or the real system clock fails this test. | AC2 (future), decision 5 |
| 8 | `rejectsInvalidCreate` (parameterized, raw JSON; each returns 400 with exactly these sorted `errors[].field`; the count is unchanged):<br>- `categoryId` missing → `categoryId`; `categoryId: null` → `categoryId`<br>- `spentOn` missing / `null` → `spentOn`<br>- 256-char `note` → `note`<br>- `{}` → `amount`, `categoryId`, `spentOn`<br>Boundary: a 255-char note returns 201. | AC2 (categoryId missing), AC6 |
| 9 | `rejectsUnreadableBody`: `{"amount":` (malformed), `spentOn: "2026-02-30"`, `spentOn: "16.06.2026"`, `categoryId: "abc"` each return 400 Problem Details with `$.errors` absent; the count is unchanged. | Error format (existing behaviour), Q5 |
| 10 | `rejectsUnknownCategoryOnCreate`: `categoryId = Long.MAX_VALUE` returns 400, `$.title == "Bad Request"`, `$.detail == "Invalid request content."`, `$.errors.length() == 1`, `$.errors[0].field == "categoryId"`, `$.errors[0].message == "Category not found"`, `$.instance == "/api/expenses"`; the count is unchanged. | Decision 4 |
| 11 | `rejectsArchivedCategoryOnCreate`: returns 409, `$.title == "Conflict"`, `$.detail == "Category is archived"`, `$.instance == "/api/expenses"`; the count is unchanged. | AC2 (409), decision 3 |
| 12 | `ignoresCurrencyInRequest`: POST with `"currency": "EUR"` returns 201 with `$.currency == "PLN"`; the DB has `PLN`. | Decision 2, Q4 |
| 13 | `putReplacesExpense`: create in A with a note. PUT `{"amount":"75.5","categoryId":B,"spentOn":"2026-06-01","note":"Taxi"}` returns 200 with `$.amount == "75.50"`, embedded B, the new `spentOn` and `note`. `id`, `currency` and `createdAt` equal those of a GET made before the PUT. A following GET and the DB row match. | AC4 (PUT) |
| 14 | `putWithoutNoteClearsNote`: for an expense that has a note, PUT without `note`, with `"note": null` and with `"note": ""` each return 200 with `$.note == null` and a `null` DB column. | PUT full replacement, Q3 |
| 15 | `putRejectsInvalidBody` (parameterized): amount `0`, amount `"1.234"`, spentOn `"2026-06-17"`, missing `categoryId`, 256-char note → 400 with the expected field; malformed JSON → 400 without `errors[]`. `$.instance == "/api/expenses/{id}"`; the DB row is unchanged. | AC2 on PUT, AC5 |
| 16 | `putKeepsCurrentArchivedCategory`: create in C, archive C. PUT with the same `categoryId`, `"amount":"300"` and a new note returns 200 with `"300.00"` and category C. GET of that expense returns 200 with category C. Then PUT to active A returns 200 with A (moving *out of* an archived category is allowed). A second expense in archived C can be deleted: DELETE returns 204 (decision 7.5). | Decision 3 |
| 17 | `putToDifferentArchivedCategoryReturns409`:<br>- (a) Expense in active A, PUT `categoryId` = archived D → 409, `$.detail == "Category is archived"`, `$.instance == "/api/expenses/{id}"`, DB row unchanged.<br>- (b) Expense in archived C, PUT to archived D → 409 too. | Decision 3 |
| 18 | `putUnknownCategoryReturns400`: returns 400 with `errors[0] == {categoryId, "Category not found"}`; the DB row is unchanged. | Decision 4 |
| 19 | `deleteReturns204`: DELETE returns 204 with an empty body. Afterwards GET → 404, `existsById` is false, a second DELETE → 404, and the category still exists. | AC4 (DELETE) |
| 20 | `unknownExpenseReturns404`:<br>- GET, PUT (valid body with an active category) and DELETE on `Long.MAX_VALUE` → 404, `$.detail == "Expense not found"`, `$.id` equals the id, `$.instance` is the path.<br>- PUT `{}` on the same unknown id → 400 (validation first; documents precedence).<br>- `GET /api/expenses/abc` → 400. | 404 via `NotFoundException` |
| 21 | `crudRoundTrip`: POST 201 → GET 200 (same body except `createdAt`, decision 7.3) → PUT 200 → GET 200 (reflects the PUT) → DELETE 204 → GET 404. | AC6 (round trip), AC1, AC3, AC4 |
| 22 | `schemaDefaultsAndConstraints` (JdbcTemplate):<br>- `insert into expense (category_id, amount, spent_on) values (?, 1.00, current_date) returning currency` → `"PLN"`; the same pattern checks `created_at` is not null.<br>- An insert with `amount = 0` → `DataIntegrityViolationException` mentioning `ck_expense_amount_positive`.<br>- An insert with a non-existent `category_id` → mentions `fk_expense_category`.<br>- `delete from category where id = ?` for a category that has expenses → mentions `fk_expense_category`, and the category still exists (on delete restrict).<br>- `select indexdef from pg_indexes where tablename = 'expense' and indexname = 'ix_expense_spent_on_category_id'` contains `(spent_on, category_id)`. | Card migration, decision 1, decision 2 |

### New file: `backend/src/test/java/dev/katran/pet/time/ClockConfigurationTest.java`
A plain unit test with no Docker. It uses `new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(ClockConfiguration.class)`; the initializer loads the real `application.properties`, so the default is tested where it is defined.

| # | Test | Proves |
|---|---|---|
| 23 | `defaultZoneIsEuropeWarsaw`: `Clock` zone is `Europe/Warsaw`, and `clock.instant()` is within a few seconds of `Instant.now()` (a system clock, not a fixed one). | Decision 5 (default) |
| 24 | `zoneComesFromAppTimeZoneEnvironmentVariable`: `withPropertyValues("APP_TIME_ZONE=America/New_York")` → zone is `America/New_York`. | Decision 5 (env-var backed) |
| 25 | `invalidZoneFailsStartup`: `APP_TIME_ZONE=Mars/Olympus` → the context has failed, with a root cause that is a `DateTimeException`. | Fail-fast configuration |

Test 7 proves that the `ValidationConfigurationCustomizer` actually reaches Spring MVC's `@Valid`.

### Criterion → tests
- AC1: 1, 3, 12, 21
- AC2: 5 (amount <= 0), 7 (future), 8 (categoryId missing), 11 and 17 (409), 15 (same rules on PUT)
- AC3: 1, 2
- AC4: 13, 14, 19, 21
- AC5: 1, 4, 5, 6
- AC6: all of the above; 21 is the explicit round trip
- Decisions: 1 → 22; 2 → 1, 12, 22; 3 → 11, 16, 17; 4 → 10, 18; 5 → 7, 23-25

## Risks and open questions

### Open questions (resolved: every proposal in bold was accepted by the user, see decision 6)
1. **Q1: JSON numbers for `amount` input.** **Proposal: accept both string and number.** Jackson maps both exactly to `BigDecimal`, and float artefacts such as `0.30000000000000004` are rejected by `@Digits`. Accepting strings only would need a custom deserializer (about 20 lines plus tests) for little gain.
2. **Q2: trailing zeros beyond two decimals** (`"200.500"`). **Proposal: reject with 400.** The literal scale counts, which is the plain reading of "more than two decimals". The alternative, `stripTrailingZeros()` before validation, would accept it as `200.50`.
3. **Q3: PUT and optional strings.** CLAUDE.md defines the empty-string rule for POST and PATCH only. **Proposal: in PUT, an omitted, `null` or `""` note stores `null`; any other value replaces it.** Should CLAUDE.md be extended with "In PUT (full replacement), an omitted or null optional string field clears it; an empty string is normalised to null"? This plan does **not** edit CLAUDE.md without the user's explicit approval.
4. **Q4: `currency` sent in a request.** **Proposal: silently ignored**, relying on the fact that Spring's Jackson configuration does not fail on unknown properties; test 12 pins it. The alternative is a 400 with a field error (custom check or `@JsonIgnoreProperties(ignoreUnknown = false)` on the record). If the implementer finds that Boot 4.1 fails on unknown properties, test 12 fails and this must be decided.
5. **Q5: type errors have no `errors[]`.** Values such as `amount: "abc"`, `spentOn: "2026-02-30"` or `categoryId: "x"` get the existing body-level 400 without `errors[]`. Card 7 (frontend) wants errors "next to the corresponding field". **Proposal: keep the current behaviour in this card.** A follow-up could map Jackson's `MismatchedInputException` path to `errors[{field}]` in `handleHttpMessageNotReadable`. That would change behaviour for all endpoints, so it deserves its own card.
6. **Q6: the 409 carries no field hint.** **Proposal: plain 409 with `detail = "Category is archived"`.** The frontend can map a 409 from `/api/expenses` to the category field. Alternatively, add `errors[{categoryId}]` to the 409, but CLAUDE.md reserves `errors[]` for validation errors.
7. **Q7: `note` whitespace.** **Proposal: not stripped;** only `""` becomes `null`, and whitespace-only is stored as is. This mirrors category `icon` (category plan decision 9).
8. **Q8: names.** **Proposal:**
   - Property `app.time-zone`, environment variable `APP_TIME_ZONE`.
   - New package `dev.katran.pet.time`.
   - Exceptions `InvalidFieldException` and `ConflictException` in `web`.
   - `CategorySummary` in `category`.
9. **New dependencies:** none. Nothing needs confirmation here.

### Risks
1. **Framework wiring, confirmed by tests rather than assumed:**
   - Boot's MVC validator picks up the `ValidationConfigurationCustomizer` clock (test 7). Fallback if not: check `spentOn.isAfter(LocalDate.now(clock))` in the controller and throw `InvalidFieldException("spentOn", ...)`. The downside is that this error would no longer be reported together with the other field errors.
   - `@Generated` returns the DB default `currency` in the POST response (test 1). Fallback: initialise the field to `"PLN"` in Java. That duplicates the default, so it is a last resort.
   - `@JsonFormat(shape = STRING)` on a `BigDecimal` record component writes a JSON string in Jackson 3.1.5 (tests 1 and 4).
   - Jackson coerces JSON strings to `BigDecimal` (test 4).
2. **The archived check races with a concurrent archive.** The category is read, then the expense is written in separate transactions, so an expense can land in a category archived a moment earlier. This is accepted for a single-user app. A strict fix would need `@Transactional` plus a `FOR SHARE` lock on the category row.
3. **An extra Spring context.** `@TestBean` gives `ExpenseControllerIT` its own context and Postgres container (a few seconds per run). The alternative, a `@Primary` fixed clock in `TestcontainersConfiguration`, would affect every test and `TestPetApplication` (`bootTestRun`), so it was rejected.
4. **`ClockConfigurationTest` sees real environment variables.** If CI ever sets `APP_TIME_ZONE`, test 23 fails. No CI workflow sets it today.
5. **Dates outside Postgres' range.** A `spentOn` before 4713 BC passes Bean Validation but is rejected by Postgres (SQLState 22008), which surfaces as the generic 500 Problem Detail. Adding a lower bound would need a custom constraint or a decision on a minimum date; not done here.
6. **A NUL character (`\u0000`) in `note`** is rejected by Postgres and becomes a 500. The same is already true for category `name`/`icon`, so this is not specific to this card.
7. **A fractional `categoryId`** (e.g. `1.9`) may be truncated to `1` by Jackson's default float-to-int coercion. This is not tested and not addressed.
8. **OSIV stays on** (Boot default, not configured). The plan does not rely on it: every serialized category is loaded explicitly (entity graph, or the looked-up instance), and PUT serializes the instance it loaded, not the `merge` result (decision 7.2). Turning OSIV off is a separate, project-wide decision.

## Out of scope
- `GET /api/expenses` list, filters, paging and totals (card 3). Until then, `GET /api/expenses` returns 405.
- Multi-currency: `currency` is not accepted in requests, has no format check constraint, and there is no conversion.
- Attachments or receipts.
- `PATCH /api/expenses/{id}`.
- User ownership (`user_id`).
- A separate index on `expense.category_id` (categories are never deleted).
- Mapping Jackson type errors to field-level `errors[]` (Q5).
- Locking against concurrent category archiving (Risk 2).
- Changing the OSIV setting.
- Frontend work (card 7), Helm values for `APP_TIME_ZONE`, and any `CLAUDE.md` edit (Q3).
