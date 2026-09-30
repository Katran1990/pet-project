# Plan: Monthly budget limits

Trello card: https://trello.com/c/24bBhnVQ/12-monthly-budget-limits
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add the `budget_limit` table (Flyway `V4`) and a `/api/budget-limits` resource with three endpoints: an upsert `PUT`, `GET ?month=YYYY-MM` and `DELETE /{id}`. Each category has at most one limit per month. The resource reuses the expense card's rules for money, unknown or archived categories and error shapes. Cards 5 and 9 read these limits.

## Acceptance criteria
- [ ] PUT /api/budget-limits with categoryId, month (YYYY-MM), amount creates or updates the limit for that pair (upsert) and returns 200 with the stored row
- [ ] GET /api/budget-limits?month=YYYY-MM returns limits of that month with embedded category; month is required
- [ ] DELETE /api/budget-limits/{id} returns 204
- [ ] 400 on malformed month or amount <= 0; 409 when the category is archived
- [ ] Integration tests cover upsert (second PUT updates, does not duplicate) and the unique constraint

### Decisions not stated on the card (proposals, to be confirmed by the user)
No acceptance criterion is blocked. Each point below has a proposed default in bold, and the plan implements that default. The alternatives are listed under "Open questions".

1. **Month format is strict `YYYY-MM`**, in both the PUT body and the GET query: a 4-digit year, `-`, and a 2-digit month from `01` to `12`.
   - `2026-7`, `26-07`, `2026-13`, `2026-00` and `2026-07-01` each return 400.
   - Responses write `month` as `"2026-07"`, never as a date.
   - The database stores the first day of the month (`2026-07-01`).
2. **Error shape for a malformed month:**
   - **GET:** 400 with `errors: [{field: "month", message: "invalid value"}]`. This is the same binding path as `GET /api/expenses`.
   - **PUT body:** 400 Problem Details **without** `errors[]`. Jackson rejects the value while it reads the body. This is the behaviour already accepted for `spentOn: "2026-02-30"` on `POST /api/expenses` (expense-crud Q5).
   - **A missing or `null` month** is a Bean Validation error with `errors[{field: "month"}]` in both endpoints.
   - See Q1 for the alternative.
3. **Amount rules are the same as for expense amounts** (the card only names `amount <= 0`):
   - `@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99")`.
   - Input may be a JSON string or a JSON number.
   - Output is a JSON string with two decimals (`"1500.00"`).
   - More than two decimals, or a value that does not fit `numeric(12,2)`, returns 400 with `errors[{amount}]`.
4. **Unknown or missing `categoryId`:**
   - An unknown id returns 400 with `errors[{categoryId, "Category not found"}]` (expense-crud decision 4).
   - A missing or `null` id returns 400 with `errors[{categoryId}]`.
5. **An archived category returns 409 `"Category is archived"` for every PUT.** This includes updating a limit that already exists for a category that was archived later.
   - Expense PUT has a "keep the current archived category" exception. It does not apply here, because the category is part of the limit's key.
   - To change such a limit, the client can delete it or unarchive the category. See Q2.
6. **Limits of archived categories can still be read and deleted.** `GET` lists them, and `DELETE` works. The embedded category is `CategorySummary(id, name, icon)` without an `archived` flag, as everywhere else.
7. **PUT always returns 200 with the stored row** (AC1), whether it inserted or updated.
   - There is no `Location` header.
   - An update keeps the row's `id`.
   - The PUT response has the same shape as a GET item.
8. **Upsert strategy is find-then-save:**
   - The controller loads the row for `(category, month)`, then either updates its amount or inserts a new row.
   - If two PUTs try to insert the same new pair at the same moment, the later one hits `uq_budget_limit_category_month`. The existing constraint-to-409 map in `ApiExceptionHandler` turns that into 409 `"Budget limit for this category and month already exists"`. Retrying the PUT then updates the row.
   - See Q3 for the native `ON CONFLICT` alternative.
9. **GET returns a bare JSON array**, like `GET /api/categories`:
   - Sorted by category id, ascending (the same order as `GET /api/categories`, and no collation question).
   - `[]` for a month without limits.
   - No wrapper object and no totals.
10. **Any month is accepted: past, current or future.** Nothing depends on "today", so no `Clock` is needed.
11. **DELETE errors:**
    - An unknown id returns 404 `"Budget limit not found"` with an `id` property (existing `NotFoundException`).
    - A non-numeric id returns 400.
12. **Schema details beyond the card text:**
    - `not null` on every column.
    - The FK uses `on delete restrict`, like `expense`.
    - No `created_at`/`updated_at`: the card lists the columns, and limit history is out of scope.
    - The unique constraint keeps the card's column order `(category_id, month)`, and there is no extra index on `month` (see Q5).
13. **Names:**
    - Package `dev.katran.pet.budgetlimit`, classes `BudgetLimit*`.
    - Constraints `fk_budget_limit_category`, `ck_budget_limit_month_first_day`, `ck_budget_limit_amount_positive` and `uq_budget_limit_category_month`.
    - 404 entity name `"Budget limit"`.
14. **The active-category check is copied, not extracted.** `BudgetLimitController` gets its own private `activeCategory`, a copy of the 7-line helper in `ExpenseController`, with identical messages. The shared helper can be extracted when card 6 (quick templates) adds a third caller (Q6).

## Changes

### Existing state (verified in the code)
- **Migrations:** only V1–V3 exist, so **V4 is free**. `docs/wallet-backlog.md` reserves `V4__create_budget_limits_table.sql` for this card.
  - Style: lowercase SQL and `bigint generated always as identity primary key`.
  - Constraint names follow `<kind>_<table>_<what>`, e.g. `fk_expense_category` and `ck_expense_amount_positive`.
  - The card's plural file name creates a singular table, as `V3__create_expenses_table.sql` creates `expense`.
- **Package layout** is by feature (`category`, `expense`).
  - There is no service layer: controllers call repositories directly, write with `saveAndFlush` and use no `@Transactional`.
  - Web-level exceptions live in `dev.katran.pet.web`.
- **`ExpenseController.activeCategory(Long)`** is private:
  - `categories.findById` fails with `InvalidFieldException("categoryId", "Category not found")`.
  - An archived category fails with `ConflictException("Category is archived")`.
- **`ApiExceptionHandler`:**
  - `MethodArgumentNotValidException` → 400 with `errors[]`, sorted by field, then message. Binding failures get the message `"invalid value"`.
  - `InvalidFieldException` → 400 with `detail` `"Invalid request content."` and one error.
  - `ConflictException` → 409.
  - `NotFoundException(entity, id)` → 404 with `"<Entity> not found"` and an `id` property.
  - `DataIntegrityViolationException` → looks up `CONSTRAINT_CONFLICT_DETAILS`, which today holds only `uq_category_name_lower`. A known name gives 409; anything else gives the generic 500.
  - Catch-all → generic 500.
- **Money pattern:** `ExpenseRequest.amount` constraints (see decision 3). `ExpenseResponse` writes the amount with `@JsonFormat(shape = STRING)` plus `setScale(2, RoundingMode.UNNECESSARY)`.
- **`CategorySummary(id, name, icon)`** in `dev.katran.pet.category` was introduced for embedding in cards 4–6.
- **Query-param binding precedent:** `GET /api/expenses` binds into a `@Valid @ModelAttribute` record (`ExpenseListQuery`), so type errors and Bean Validation errors both come back as `errors[]`.
- **Tests:**
  - `CategoryControllerIT`, `GreetingControllerIT` and `ApiExceptionHandlerIT` share one cached Spring context and Postgres container, because none of them overrides beans.
  - `ExpenseControllerIT` and `ExpenseListIT` each have their own context (`@TestBean Clock`).
  - There is no `src/test/resources`.
- **Versions** (`backend/gradle.lockfile`): Spring Boot 4.1.1, spring-webmvc 7.0.9, Spring Data JPA 4.1.1, Hibernate ORM 7.4.5, Hibernate Validator 9.1.3, jackson-databind 3.1.7, Flyway 12.4.0. Postgres 17 runs via Testcontainers.
- **Frontend:** no budget-limit code.

### API contract

| Method | Path | Request | Success | Errors (all `application/problem+json`) |
|---|---|---|---|---|
| `PUT` | `/api/budget-limits` | `{"categoryId": 7, "month": "2026-07", "amount": "1500.00"}` | `200`, `BudgetLimitResponse` (insert or update) | `400` validation (`errors[]`); `400` malformed JSON or malformed month (no `errors[]`); `400` unknown category (`errors[categoryId]`); `409` archived category; `409` concurrent insert of the same pair (decision 8) |
| `GET` | `/api/budget-limits?month=2026-07` | `month` required | `200`, JSON array of `BudgetLimitResponse`, ordered by category id; `[]` if none | `400` missing, empty or malformed `month` (`errors[month]`) |
| `DELETE` | `/api/budget-limits/{id}` | | `204`, empty body | `404` unknown id; `400` non-numeric id |

Other methods on these paths are not mapped. Spring MVC answers them with 405 Problem Details (for example `POST /api/budget-limits` or `GET /api/budget-limits/{id}`).

**Response item** (PUT body, and each element of the GET array):
```json
{"id": 3, "month": "2026-07", "amount": "1500.00", "category": {"id": 7, "name": "Food", "icon": "cart"}}
```
- There is no `categoryId` property and no `category.archived`.
- `category.icon` may be `null`.

**GET example:**
```json
[
  {"id": 3, "month": "2026-07", "amount": "1500.00", "category": {"id": 7, "name": "Food", "icon": "cart"}},
  {"id": 9, "month": "2026-07", "amount": "300.00", "category": {"id": 12, "name": "Transport", "icon": null}}
]
```

**Error examples:**
```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Invalid request content.",
 "instance": "/api/budget-limits", "errors": [{"field": "amount", "message": "must be greater than 0"}]}
```
```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Invalid request content.",
 "instance": "/api/budget-limits", "errors": [{"field": "month", "message": "invalid value"}]}
```
```json
{"type": "about:blank", "title": "Conflict", "status": 409, "detail": "Category is archived", "instance": "/api/budget-limits"}
```
```json
{"type": "about:blank", "title": "Not Found", "status": 404, "detail": "Budget limit not found", "instance": "/api/budget-limits/42", "id": 42}
```
A malformed month in the PUT body produces Spring's standard unreadable-body Problem Detail: status 400, `instance`, and no `errors[]`.

**PUT evaluation order** (this also documents precedence):
1. **Unreadable body:** malformed JSON, a malformed `month`, a non-numeric `amount` or `categoryId`. Returns 400 without `errors[]`.
2. **Bean Validation:** 400 listing all field errors at once, sorted.
3. **Unknown `categoryId`:** 400 with `errors[{categoryId, "Category not found"}]`.
4. **Archived category:** 409 `"Category is archived"`.
5. **Upsert:** returns 200.

So an archived category combined with `amount: 0` returns 400, not 409.

**GET order:**
1. A malformed `month` is a binding failure: `errors[{month, "invalid value"}]`.
2. A missing or empty `month` is a Bean Validation error: `errors[{month, "must not be null"}]`.
3. Otherwise 200.

### 1. New file: `backend/src/main/resources/db/migration/V4__create_budget_limits_table.sql`
```sql
-- One limit per category and month. month is always the first day of the month; the API speaks YYYY-MM.
create table budget_limit (
    id          bigint generated always as identity primary key,
    category_id bigint         not null,
    month       date           not null,
    amount      numeric(12, 2) not null,
    constraint fk_budget_limit_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_budget_limit_month_first_day check (extract(day from month) = 1),
    constraint ck_budget_limit_amount_positive check (amount > 0),
    constraint uq_budget_limit_category_month unique (category_id, month)
);
```
- **Table name** is singular per CLAUDE.md. The file name is the card's, as for V2 and V3.
- **`month` as a column name** is fine: it is a non-reserved keyword in Postgres.
- **Uniqueness** is a named table constraint rather than a bare index. Its name reaches `ApiExceptionHandler` for decision 8, and a later `ON CONFLICT` could reference it.
- **No separate index on `month`** (Q5). The unique constraint's index serves the upsert lookup `(category_id, month)`.

### 2. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimit.java`
A JPA entity in the style of `Expense`:
- `@Entity @Table(name = "budget_limit")`, with `@Id @GeneratedValue(strategy = IDENTITY) Long id`.
- `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false, updatable = false) Category category`.
- `@Column(nullable = false, updatable = false) LocalDate month`, with the comment "always the first day of the month (ck_budget_limit_month_first_day)".
  - `category` and `month` together are the limit's identity. They are never changed; PUT only changes the amount.
- `@Column(nullable = false, precision = 12, scale = 2) BigDecimal amount`.
- A protected no-arg constructor.
- `public BudgetLimit(Category category, YearMonth month, BigDecimal amount)`. It stores `month.atDay(1)`, so the entity cannot hold anything other than the first day of the month.
- Getters:
  - `getId()`, `getCategory()` and `getAmount()`.
  - `YearMonth getMonth()` returns `YearMonth.from(month)`. JPA uses field access, so the getter type may differ from the field type.
- Only one setter: `setAmount(BigDecimal)`.
- **Why `LocalDate` and not `YearMonth` in the entity:** it avoids relying on whatever default JDBC mapping Hibernate may pick for `YearMonth`, and it needs no `AttributeConverter`.
- **No Bean Validation annotations on the entity** (expense decision 7.4).

### 3. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitRepository.java`
```java
public interface BudgetLimitRepository extends JpaRepository<BudgetLimit, Long> {

	@EntityGraph(attributePaths = "category")
	Optional<BudgetLimit> findWithCategoryByCategoryIdAndMonth(Long categoryId, LocalDate month);

	@EntityGraph(attributePaths = "category")
	List<BudgetLimit> findAllByMonthOrderByCategoryIdAsc(LocalDate month);
}
```
- These are derived queries with no hand-written JPQL. `CategoryId` resolves to `category.id`.
- The naming follows `ExpenseRepository.findWithCategoryById`; Spring Data ignores the `WithCategory` part.
- The entity graph fetches the category in the same SQL join. There is no N+1, and the code does not depend on OSIV.
- `findById` and `delete` are inherited.

### 4. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitRequest.java`
```java
public record BudgetLimitRequest(
		@NotNull Long categoryId,
		@NotNull @JsonFormat(pattern = "uuuu-MM") YearMonth month,
		@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount) {
}
```
- **Amount constraints** are copied from `ExpenseRequest` (decision 3). This includes `@DecimalMax`, the fix for the `@Digits` overflow (expense decision 7.1).
- **`@JsonFormat(pattern = "uuuu-MM")`** makes the body format explicit and strict (decision 1):
  - `uuuu` needs at least 4 digits.
  - `MM` needs exactly 2 digits.
  - `ResolverStyle.SMART` rejects months 00 and 13.
  - The whole text must be consumed, so `2026-07-01` is rejected.
  - Jackson's default `YearMonth` format is not relied on, because it may accept a 1–3 digit year (Risk R1a).
- **No string normalisation:** there is no optional string field, so the CLAUDE.md empty-string rule does not apply.

### 5. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitListQuery.java`
```java
public record BudgetLimitListQuery(@NotNull YearMonth month) {
}
```
- It is bound with `@Valid @ModelAttribute`, the same as `ExpenseListQuery`.
  - Spring's built-in `YearMonthFormatter` parses with `YearMonth.parse`, which is strict ISO `uuuu-MM`.
  - A conversion failure becomes a binding failure, which the existing handler maps to `"invalid value"`.
  - A missing value, or `month=`, becomes `null`, which `@NotNull` rejects.
- **`@RequestParam YearMonth month` was rejected.** It would give `MissingServletRequestParameterException` or `MethodArgumentTypeMismatchException`, both without `errors[]`. The same reasoning is in expense-list D3.
- Do **not** put constraint annotations on the controller method parameters (expense-list section 1).

### 6. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitResponse.java`
```java
public record BudgetLimitResponse(
		Long id,
		YearMonth month,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		CategorySummary category) {

	public static BudgetLimitResponse from(BudgetLimit limit) {
		return new BudgetLimitResponse(limit.getId(), limit.getMonth(),
				limit.getAmount().setScale(2, RoundingMode.UNNECESSARY), CategorySummary.from(limit.getCategory()));
	}
}
```
- **`month`** relies on the Jackson 3 / Boot default ISO output `"2026-07"`, like `LocalDate` in `ExpenseResponse`. Test B1 pins this; for the fallback, see R1b.
- **`amount`** is serialised exactly as in `ExpenseResponse`. `UNNECESSARY` never rounds, because validation guarantees at most two decimals.
- **A static factory** is used, as elsewhere. MapStruct is not justified for four fields.

### 7. New file: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitController.java`
`@RestController @RequestMapping("/api/budget-limits")`, with `BudgetLimitRepository` and `CategoryRepository` injected through the constructor.
```java
@PutMapping
public BudgetLimitResponse upsert(@Valid @RequestBody BudgetLimitRequest request) {
	Category category = activeCategory(request.categoryId());
	BudgetLimit limit = limits.findWithCategoryByCategoryIdAndMonth(category.getId(), request.month().atDay(1))
			.orElseGet(() -> new BudgetLimit(category, request.month(), request.amount()));
	limit.setAmount(request.amount());   // no-op for a new instance; the update for an existing one
	limits.saveAndFlush(limit);
	return BudgetLimitResponse.from(limit);
}

@GetMapping
public List<BudgetLimitResponse> list(@Valid @ModelAttribute BudgetLimitListQuery query) {
	return limits.findAllByMonthOrderByCategoryIdAsc(query.month().atDay(1)).stream()
			.map(BudgetLimitResponse::from).toList();
}

@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable Long id) {
	BudgetLimit limit = limits.findById(id).orElseThrow(() -> new NotFoundException("Budget limit", id));
	limits.delete(limit);
}

private Category activeCategory(Long categoryId) { /* identical copy of ExpenseController.activeCategory (decision 14) */ }
```
**PUT:**
- **Order matters:** every check that can throw (400 or 409) runs **before** any setter. That way, with OSIV on, no half-modified managed entity exists (expense-crud section 7).
- **The response is built from the instance the controller holds**, not from the return value of `saveAndFlush` (expense decision 7.2). That instance is one of two things:
  - the loaded row, whose category is initialised by the entity graph;
  - the new entity, whose category is the instance loaded by `activeCategory`.

  So the response works whether OSIV is on or off.
- **Insert:** `saveAndFlush` on a new entity calls `persist`, and IDENTITY assigns `id` on the same instance.
- **Update:** `saveAndFlush` merges and flushes an `UPDATE`.
- **No `@Transactional` and no service layer**, following project convention.

**DELETE:** `deleteById` alone is not used, because it silently ignores missing ids and a 404 is required. Deleting a limit whose category is archived is allowed (decision 6).

### 8. Change: `backend/src/main/java/dev/katran/pet/web/ApiExceptionHandler.java`
Extend the map. Nothing else changes.
```java
private static final Map<String, String> CONSTRAINT_CONFLICT_DETAILS = Map.of(
		"uq_category_name_lower", "Category name already exists",
		"uq_budget_limit_category_month", "Budget limit for this category and month already exists");
```
- **Why a 409 is correct here:** a concurrent first insert of the same pair really can violate this constraint through the API (decision 8). That differs from the expense constraints, which the API can never violate (expense-crud section 1).
- **The other new constraints are not mapped**, because the API cannot violate them:
  - `ck_budget_limit_amount_positive` is covered by `@Positive`.
  - `ck_budget_limit_month_first_day` is covered by `atDay(1)`.
  - `fk_budget_limit_category` is covered by the category lookup.

  If one of them is ever hit, the result is the generic 500.

### 9. Change: `README.md`
Add these rows to the API table after the expense rows:
- `| PUT | /api/budget-limits | Sets the limit of a category for a month ({"categoryId": 1, "month": "2026-07", "amount": "1500.00"}); creates or updates (upsert) and always returns 200 with the stored limit; amount > 0 with at most two decimals; 400 on malformed month or unknown category, 409 on archived category |`
- `| GET | /api/budget-limits?month=YYYY-MM | Lists the limits of a month (month is required) with the category embedded as {id, name, icon}, ordered by category id |`
- `| DELETE | /api/budget-limits/{id} | Deletes a limit; 204, or 404 if unknown |`

### 10. Not changed
- **No new dependencies.** Everything used is already on the classpath:
  - JPA and Spring Data derived queries with `@EntityGraph`.
  - Bean Validation.
  - Jackson `@JsonFormat` and its built-in `YearMonth` support.
  - Spring's built-in `YearMonthFormatter`.

  `backend/gradle.lockfile` is unchanged, so `./gradlew dependencies --write-locks` is not needed. It becomes mandatory only if the implementer touches dependencies anyway.
- `application.properties`, `ExpenseController`, `CategoryRepository` and `CategorySummary` are unchanged.
- No frontend change (card 9), no Helm change, no `CLAUDE.md` change, no change to `docs/wallet-backlog.md`.

### Implementation order
1. Add the V4 migration. Run `cd backend && ./gradlew test`: Flyway applies V4 in every IT context, and existing tests stay green.
2. Add `BudgetLimit` and `BudgetLimitRepository`. Spring Data validates derived queries at startup, so a naming problem (R1d) shows up in the next test run.
3. Add `BudgetLimitRequest`, `BudgetLimitListQuery` and `BudgetLimitResponse`.
4. Add `BudgetLimitController`, including the copied `activeCategory`.
5. Extend `CONSTRAINT_CONFLICT_DETAILS` in `ApiExceptionHandler`.
6. Write `BudgetLimitControllerIT` and the new unit test in `ApiExceptionHandlerTest`.
7. Update `README.md`.
8. `cd backend && ./gradlew test` must be green, with the new tests and all existing ones.

## Tests
Done means `cd backend && ./gradlew test` is green. The existing tests stay unchanged and must still pass: `CategoryControllerIT`, `ExpenseControllerIT`, `ExpenseListIT`, `GreetingControllerIT`, `ApiExceptionHandlerIT`, `ApiExceptionHandlerTest`, `ClockConfigurationTest`, `PetApplicationTests` and the `db` tests.

### New file: `backend/src/test/java/dev/katran/pet/budgetlimit/BudgetLimitControllerIT.java`
**Setup.** Copy `CategoryControllerIT`:
- `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)` and a `RestTestClient` built in `@BeforeEach`.
- Autowired `BudgetLimitRepository`, `CategoryRepository` and `JdbcTemplate`.
- **No `@TestBean`**, so this class shares the cached context and container of `CategoryControllerIT`. No extra startup cost.

**Conventions** (same as the expense ITs):
- **Setup data:** categories are created through `CategoryRepository` with unique names (`createActiveCategory` / `createArchivedCategory` helpers, as in `ExpenseControllerIT`).
- **Isolation:** only this class writes `budget_limit`, and **each test uses months no other test uses** (listed per test). Exact list assertions on `GET ?month=` are therefore safe. Card 5's tests must use other months.
- **Request bodies:** raw JSON strings or `HashMap` for bodies with `null`s, malformed values or numbers-versus-strings. Never `Map.of`.
- **Error tests:** assert `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`, `$.status` and `$.instance`. 400 validation tests also assert `$.errors.length()`, each field, and a non-blank message.
- **Money:** amounts are always asserted as JSON strings.
- **Unknown id:** `Long.MAX_VALUE`.

| # | Test | Proves |
|---|---|---|
| B1 | `putCreatesLimitAndReturns200`. Category C, months 2030-01 and 2020-01.<br>- PUT `{"categoryId":C,"month":"2030-01","amount":"1500.00"}` → 200.<br>- Body: `$.id` exists; `$.month == "2030-01"`; `$.amount == "1500.00"`; `$.category.id/name/icon` match C; `$.categoryId` and `$.category.archived` do not exist; no `Location` header.<br>- DB row via `JdbcTemplate`: `month = 2030-01-01`, `amount = 1500.00`, `category_id = C`.<br>- A past month (`"2020-01"`, amount `"10"`) → 200 with `"10.00"`. | AC1, decisions 1, 7, 10 |
| B2 | `secondPutUpdatesInsteadOfDuplicating`. Categories C then D (so C.id < D.id), months 2030-02 and 2030-03.<br>- PUT (C, 2030-02, `"100.00"`) → id1.<br>- PUT (C, 2030-02, `"250.5"`) → 200, `$.id == id1`, `$.amount == "250.50"`.<br>- `select count(*) from budget_limit where category_id = C and month = date '2030-02-01'` is 1, and the DB amount is 250.50.<br>- The same PUT again → 200, same id and amount (idempotent).<br>- PUT (C, 2030-03) → a new id, not id1.<br>- PUT (D, 2030-02, `80`) → another new id.<br>- `GET ?month=2030-02` → exactly [C's id1, D's] with `"250.50"` and `"80.00"`. | AC1, **AC5 (upsert: second PUT updates, no duplicate)**, decision 7 |
| B3 | `acceptsAmountFormats` (parameterized; each case gets its own category, month 2030-04):<br>- `"1500"` → `"1500.00"`<br>- `"0.01"` → `"0.01"`<br>- `"9999999999.99"` → `"9999999999.99"`<br>- JSON number `200` → `"200.00"`<br>- `19.99` → `"19.99"`<br>The DB value is `compareTo`-equal to the expected amount. | Decision 3, money rule |
| B4 | `rejectsInvalidAmount` (parameterized, month 2030-05). Each returns 400 with only `field == "amount"` errors; the row count for the category stays 0.<br>- `0`, `"0"`, `"0.00"`, `-1`, `"-5.00"` → 1 error each (**amount <= 0**).<br>- `"1.234"`, `"200.500"`, `0.30000000000000004` → 1 error each.<br>- `"10000000000"` → 2 errors (`@Digits` + `@DecimalMax`, as in expense test 5).<br>- Missing, `null` → 1 error each. | **AC4 (amount <= 0)**, decision 3 |
| B5 | `rejectsUnparseableAmount` (month 2030-05): `"abc"`, `true`, `1e1000000`, `"1e2147483647"` → 400 Problem Details, never 500. `errors[]` is not asserted (expense test 6); nothing is stored. | Decision 3 |
| B6 | `rejectsMalformedMonthInBody` (parameterized):<br>- Month values `"2026-7"`, `"26-07"`, `"2026-13"`, `"2026-00"`, `"2026-07-01"`, `"07-2026"`, `"2026/07"`, `"abc"` and the JSON number `202607`.<br>- Each → 400 Problem Details, `$.instance == "/api/budget-limits"`, **`$.errors` does not exist**; the row count for the category stays 0.<br>- Separately, `"month": ""` → 400 Problem Details. `errors[]` is not asserted, because Jackson may turn `""` into `null`, which `@NotNull` then reports. | **AC4 (malformed month)**, decisions 1, 2 |
| B7 | `rejectsInvalidPutBody` (parameterized, raw JSON). Each → 400 with exactly these sorted `errors[].field`:<br>- `categoryId` missing / `null` → [categoryId]<br>- `month` missing / `null` → [month]<br>- `{}` → [amount, categoryId, month] | Error format (CLAUDE.md), decision 4 |
| B8 | `rejectsUnknownCategory`: `categoryId = Long.MAX_VALUE`, month 2030-06 → 400. Asserts:<br>- `$.title == "Bad Request"` and `$.detail == "Invalid request content."`<br>- `$.errors == [{categoryId, "Category not found"}]`<br>- `$.instance == "/api/budget-limits"`<br>- `select count(*) from budget_limit where month = date '2030-06-01'` is 0 | Decision 4 |
| B9 | `archivedCategoryReturns409`, month 2030-08:<br>- (a) Archived X, PUT (X, `"100"`) → 409, `$.title == "Conflict"`, `$.detail == "Category is archived"`, `$.instance == "/api/budget-limits"`; X has no rows.<br>- (b) Active Y, PUT (Y, `"100.00"`) → 200. Archive Y through the repository. PUT (Y, `"200"`) → 409 and the DB amount is still 100.00. `GET ?month=2030-08` still contains Y's limit with the embedded category. `DELETE` of that limit → 204.<br>- (c) Precedence: PUT (X, `"0"`) → 400 `[amount]`, not 409. | **AC4 (409)**, decisions 5, 6 |
| B10 | `rejectedPutLeavesExistingLimitUnchanged`: existing (C, 2030-09, `"100.00"`). Then:<br>- PUT amount `"0"` → 400<br>- PUT amount `"1.234"` → 400<br>- PUT month `"2030-9"` → 400<br>- PUT malformed JSON `{"amount":` → 400 without `errors[]`<br>Afterwards the DB amount is still 100.00 and the count for the pair is 1. | AC1 and AC4 (failed updates do not write) |
| B11 | `listReturnsLimitsOfMonthWithEmbeddedCategory`. Categories A, B, C in that order.<br>- Limits: A/2031-01 `"100"`, B/2031-01 `"20.5"`, C/2031-01 `"300"`, then archive C; A/2031-02 `"999"`.<br>- `GET ?month=2031-01` → 200, length 3, `$[*].id` equals [A's, B's, C's] in category-id order. Every `month == "2031-01"`; `$[1].amount == "20.50"`; `$[0].category.id/name/icon` match A; `$[0].categoryId` and `$[0].category.archived` do not exist; `$[2].category.id == C` (archived included).<br>- `?month=2031-02` → only A's 2031-02 limit.<br>- `?month=2031-03` → 200 `[]`.<br>- Rename B through the repository → `GET ?month=2031-01` shows the new name (the category is joined, not copied). | **AC2**, decisions 6, 9 |
| B12 | `listRejectsMissingOrMalformedMonth` (parameterized). Each → 400 Problem Details, `$.instance == "/api/budget-limits"`, exactly one error with `field == "month"`:<br>- No query string, and `month=` → non-blank Bean Validation message.<br>- `month=2026-7`, `26-07`, `2026-13`, `2026-00`, `2026-07-01`, `07-2026`, `2026/07`, `abc` → message exactly `"invalid value"`, containing neither `java.` nor `Failed to convert`. | **AC2 (month required)**, **AC4 (malformed month)**, decision 2 |
| B13 | `deleteReturns204`: (C, 2031-05, `"10"`).<br>- `DELETE /api/budget-limits/{id}` → 204, empty body.<br>- `GET ?month=2031-05` → `[]`; `existsById` is false; the category still exists.<br>- A second DELETE → 404.<br>- PUT the same pair again → 200 with a new id, different from the deleted one (the pair is free again). | **AC3** |
| B14 | `deleteUnknownOrNonNumericId`:<br>- `DELETE /api/budget-limits/{Long.MAX_VALUE}` → 404, `$.detail == "Budget limit not found"`, `$.id` equals the id, `$.instance` is the path.<br>- `DELETE /api/budget-limits/abc` → 400 Problem Details. | Decision 11 |
| B15 | `schemaConstraints` (`JdbcTemplate`, months 2039-xx, categories C and D without expenses):<br>- Insert (C, `2039-01-01`, 1.00) succeeds. The same insert again → `DataIntegrityViolationException` mentioning `uq_budget_limit_category_month`, and the count for the pair is still 1.<br>- (C, `2039-02-01`) and (D, `2039-01-01`) succeed: the key is the pair.<br>- `month = 2039-03-15` → `ck_budget_limit_month_first_day`.<br>- `amount` 0 and -1 → `ck_budget_limit_amount_positive`.<br>- `category_id = Long.MAX_VALUE` → `fk_budget_limit_category`.<br>- `delete from category where id = C` → `fk_budget_limit_category`, and C still exists (on delete restrict). | **AC5 (unique constraint)**, card migration, decision 12 |

### Change: `backend/src/test/java/dev/katran/pet/web/ApiExceptionHandlerTest.java`
This is a plain unit test with no Docker.

| # | Test | Proves |
|---|---|---|
| U1 | `budgetLimitUniqueViolationIs409`:<br>- Call `new ApiExceptionHandler().handleDataIntegrityViolation(...)` with a `DataIntegrityViolationException` whose cause is `new org.hibernate.exception.ConstraintViolationException("duplicate key", new SQLException("duplicate key", "23505"), "uq_budget_limit_category_month")`. The method is package-private and the test is in the same package.<br>- Result: status 409, detail `"Budget limit for this category and month already exists"`.<br>- Verify the exact Hibernate 7.4.5 constructor signature while implementing. | Decision 8 (race mapping; an IT cannot trigger the race deterministically) |

### Criterion → tests
- **AC1:** B1, B2, B3, B10
- **AC2:** B11 (month's limits with embedded category), B12 (month required)
- **AC3:** B13 (and B14 for 404/400)
- **AC4:**
  - amount <= 0: B4
  - malformed month: B6 (body), B12 (query)
  - 409 archived: B9
- **AC5:**
  - upsert (second PUT updates, no duplicate): B2 (and B10, B13)
  - unique constraint: B15, plus U1 for its API mapping
- **Decisions:**
  - 1 → B1, B6, B12
  - 2 → B6, B12
  - 3 → B3, B4, B5
  - 4 → B7, B8
  - 5 → B9
  - 6 → B9, B11
  - 7 → B1, B2
  - 8 → U1
  - 9 → B11
  - 10 → B1
  - 11 → B14
  - 12 → B15

## Risks and open questions

### Open questions
Each proposal below is in bold and is already implemented by the plan. The user confirms it or picks the alternative.
1. **Q1. Malformed month in the PUT body (decision 2).**
   - **Proposal: typed `YearMonth` with `@JsonFormat(pattern = "uuuu-MM")`**, giving a 400 without `errors[]`. This matches how `spentOn` works today.
   - Alternative: `String month` with `@NotNull @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be in the format YYYY-MM")`, parsed with `YearMonth.parse` in the controller.
     - Gain: a field-level `errors[{month}]`, and years limited to 4 digits, which also removes R3 for PUT.
     - Cost: manual parsing, and a different style from the other typed date fields.
2. **Q2. Updating the limit of a category archived later (decision 5).**
   - **Proposal: 409, as the card literally says.**
   - Alternative: mirror expense PUT. That means 409 only when *creating* a limit for an archived category, while updating an existing one is allowed.
3. **Q3. Upsert strategy (decision 8).**
   - **Proposal: find-then-save, with the unique constraint as a backstop that yields 409 on a concurrent first insert.**
   - Alternative: a native `insert into budget_limit (...) values (...) on conflict (category_id, month) do update set amount = excluded.amount`, in a `@Modifying @Transactional @Query(nativeQuery = true)` repository method, followed by the entity-graph select for the response.
     - Gain: race-free, and no 409 ever.
     - Cost: the first native SQL and the first `@Transactional` repository method in the codebase.
4. **Q4. GET shape (decision 9).** **Proposal: bare array ordered by category id.** Alternatives: a wrapper `{month, items}`, or ordering by category name (which needs a collation decision, see category-crud Risk 6).
5. **Q5. Index column order (decision 12).**
   - The card fixes `unique (category_id, month)`. Lookups by `month` alone (`GET`, card 5's report) cannot use that index efficiently, because Postgres 17 has no skip scan.
   - **Proposal: keep the card's order.** With a few categories times a few months, a sequential scan is trivial.
   - Alternative: declare `unique (month, category_id)`. Uniqueness is identical, and it also serves month lookups.
6. **Q6. Shared active-category check (decision 14).** **Proposal: copy it now and extract it when card 6 adds the third caller.** Alternative: extract it now into the `category` package. That would introduce a new component type, because the project has no service layer.
7. **Q7. Names (decision 13):**
   - package `budgetlimit`;
   - 404 detail `"Budget limit not found"`;
   - 409 detail `"Budget limit for this category and month already exists"`;
   - the constraint names in the migration.
8. **Q8. New dependencies: none.** Nothing needs confirmation.

### Risks
1. **R1. Framework behaviour, confirmed by tests rather than assumed:**
   - **a) Body parsing.** `@JsonFormat(pattern = "uuuu-MM")` on a record component is applied when jackson-databind 3.1.7 deserializes `YearMonth` (B6: `"26-07"` must be 400). Without it, Jackson's default `YearMonth` pattern may accept a 1–3 digit year. Fallback: Q1's `String` + `@Pattern`.
   - **b) Output format.** Jackson writes `YearMonth` as `"2026-07"` (B1). Fallback: the same `@JsonFormat(pattern = "uuuu-MM")` on `BudgetLimitResponse.month`.
   - **c) Query parsing.** Spring's built-in `YearMonthFormatter` converts `?month=`, and a failure becomes a binding failure (`"invalid value"`, B12). Fallback: `@DateTimeFormat(pattern = "yyyy-MM")` on the query component.
   - **d) Derived queries.** `CategoryId` must resolve to `category.id` in the predicate and in `OrderBy`. The attribute and column `month` must work in generated JPQL and SQL: `month` is non-reserved in Postgres, and a path `b.month` does not clash with HQL's `month()` function. Any failure shows up at startup and fails every IT. Fallback: `Category_Id` property syntax or an explicit `@Query`.
   - **e) Constraint name.** Hibernate reports the constraint name for a violation of a named unique table constraint. U1 simulates this; `uq_category_name_lower` already works the same way end to end.
2. **R2. Concurrency.**
   - Concurrent first inserts of one pair: 409 (decision 8).
   - Concurrent updates of an existing row: last write wins.
   - The archived check races with a concurrent archive, as in expense-crud Risk 2.

   All of this is accepted for a single-user app.
3. **R3. Extreme years.** `?month=+999999999-12` parses as a `YearMonth` but is out of Postgres' `date` range, so it surfaces as the generic 500. This is the same class of issue as expense-crud Risk 5 and expense-list R4, and it is not addressed. Q1's alternative removes it for PUT only.
4. **R4. JSON array input.** Jackson's `YearMonth` deserializer may also accept `"month": [2026, 7]`. It is harmless (a valid month); it is not documented and not tested.
5. **R5. Duplicated check.** `activeCategory` now exists in two controllers and could drift. Both ITs assert the exact texts `"Category not found"` and `"Category is archived"`.
6. **R6. Shared test database.** Only `BudgetLimitControllerIT` writes `budget_limit` today. Later cards (5, 9) must use months that this class does not use (2020-01, 2030-xx, 2031-xx, 2039-xx), or assert with contains / does-not-contain.
7. **R7. OSIV stays on** (Boot default). The plan does not rely on it: the entity graph plus building the response from the instance the controller holds (section 7).

## Out of scope
- From the card: a default limit that applies to every month, limit history, and notifications on overspend.
- The monthly report, including `remaining` and `share` (card 5), and the frontend "Month" page (card 9).
- Other endpoints: `GET`/`PUT`/`PATCH /api/budget-limits/{id}`, `POST /api/budget-limits`, filtering limits by category, bulk operations, and copying limits to the next month.
- Currency on limits (multi-currency is out of scope project-wide).
- Extracting the shared active-category check (Q6).
- Mapping JSON body type errors to `errors[]` project-wide (expense-crud Q5).
- Bounding years (R3), and locking against concurrent writes (R2).
- Extra indexes (Q5), `created_at`/`updated_at` columns, and changing OSIV.
- Helm values, `CLAUDE.md` and `gradle.lockfile` changes.
