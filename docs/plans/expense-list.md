# Plan: Expense list with filters and pagination

Trello card: https://trello.com/c/wiwEnIHA/11-expense-list-with-filters-and-pagination
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add `GET /api/expenses` to the existing `ExpenseController`. It returns one page of expenses filtered by an inclusive date range and optional category ids, sorted by `spentOn desc, createdAt desc`. The response also carries `totalItems` and `totalAmount`, which one SQL aggregate query computes over all matching rows. Clients never add amounts themselves.

## Acceptance criteria
- [ ] Query params: from, to (ISO dates, inclusive), categoryIds (comma-separated list of ids, e.g. categoryIds=1,4,7), page (0-based), size (default 50, max 200)
- [ ] Omitted from/to default to the first and last day of the current month
- [ ] An unknown id in categoryIds returns 400; an empty categoryIds param is treated as "all categories"
- [ ] Sorted by spentOn desc, then createdAt desc
- [ ] Response body: items, page, size, totalItems, totalAmount (sum over all matching rows, not only the current page)
- [ ] Integration tests cover inclusive date bounds, multiple category ids, an empty result and totalAmount across pages

### Decisions
User decisions (edge cases the card does not cover):
1. **Page and size.** `size > 200`, `size < 1` or `page < 0` returns 400 Problem Details with `errors: [{field, message}]`. Values are never silently clamped.
2. **Date range.** `from` later than `to` returns 400 Problem Details with `errors[]`. Each omitted bound gets its default on its own: `from` becomes the first day of the current month and `to` the last day. So a request with only `to=2026-01-31`, sent in a later month, also returns 400.

Design decisions made in this plan (reasons in "Changes"; the debatable ones are repeated as open questions):
- D1. **"Current month"** is `YearMonth.now(clock)`. `clock` is the existing `Clock` bean, whose zone is `app.time-zone` (`APP_TIME_ZONE`, default `Europe/Warsaw`). Tests replace it with `@TestBean`.
- D2. **Archived categories are "known" ids.** They exist, expenses still reference them, and you must be able to filter history by them. "All categories" also includes expenses of archived categories.
- D3. **Parameter binding.** Query params are bound into a `@Valid @ModelAttribute` record. Type errors and range errors then both reach the existing `MethodArgumentNotValidException` handler and come back as `errors[{field, message}]`.
- D4. **Items** reuse `ExpenseResponse` unchanged. `totalAmount` is a JSON string with two decimals, and `"0.00"` when nothing matches.
- D5. **Totals.** `totalItems` and `totalAmount` come from one JPQL aggregate, `count` + `coalesce(sum)`, run over the same `where` clause as the page query. The page query runs second, and only when the requested offset is below `totalItems`.
- D6. **No new index and no migration.**
- D7. **`GET /api/expenses` is not mapped today.** It returns 405 because the path only has POST. Nothing conflicts with it.

## Changes

### Existing state (checked in the code)
- **Mappings.** `ExpenseController` (`@RequestMapping("/api/expenses")`) maps only `POST` (no sub-path) and `GET`/`PUT`/`DELETE` `/{id}`. No other controller maps `/api/expenses`. No test asserts the current 405 for `GET /api/expenses`, and the frontend does not call `/api/expenses`. A new `@GetMapping` with no path does not clash with `@GetMapping("/{id}")`.
- **Repository.** `ExpenseRepository` has only `findWithCategoryById` (`@EntityGraph(attributePaths = "category")`). There is no service layer; the controller calls repositories directly and uses no `@Transactional`.
- **Response.** `ExpenseResponse.from(expense)` writes `amount` as a string with scale 2 (`@JsonFormat(shape = STRING)` plus `setScale(2, RoundingMode.UNNECESSARY)`) and embeds `CategorySummary(id, name, icon)`.
- **`ApiExceptionHandler`** (extends `ResponseEntityExceptionHandler`):
  - `MethodArgumentNotValidException` → 400 with `errors[]` sorted by field, then message. The message is `FieldError.getDefaultMessage()`.
  - `InvalidFieldException(field, message)` → 400, `detail = "Invalid request content."`, one `errors[]` entry.
  - Standard MVC exceptions are handled by the base class.
  - A catch-all turns anything else into a generic 500 and never echoes the exception message.
- **Clock.** `ClockConfiguration` provides the `Clock` bean (`Clock.system(app.time-zone)`) and passes it to Bean Validation. `ExpenseControllerIT` fixes it with `@TestBean`.
- **Migrations.** V1–V3 exist. V3 already has `ix_expense_spent_on_category_id on expense (spent_on, category_id)`. The next free version would be V4, but `docs/wallet-backlog.md` reserves `V4__create_budget_limits_table.sql` for card 4.
- **Config.** There is no `messages.properties`, no `spring.mvc.format.*` and no `spring.data.web.*`.
- **Versions** (`backend/gradle.lockfile`): Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Data JPA 4.1.1, Hibernate ORM 7.4.5, Hibernate Validator 9.1.3, jackson-databind 3.1.7.

### API contract
`GET /api/expenses`

| Param | Type | Default | Rules |
|---|---|---|---|
| `from` | ISO date `yyyy-MM-dd` | first day of the current month (D1) | inclusive; must not be after the effective `to` |
| `to` | ISO date `yyyy-MM-dd` | last day of the current month (D1) | inclusive |
| `categoryIds` | comma-separated ids, e.g. `1,4,7` | all categories | every id must exist (archived ones count, D2); duplicates are ignored; an empty entry (`1,,4`, `,`) → 400 |
| `page` | int, 0-based | `0` | `>= 0` |
| `size` | int | `50` | `1..200` |

- An **empty value** of any param (`from=`, `page=`, `size=`, `categoryIds=`) means the same as leaving it out. For `categoryIds` the card requires this. For the others it is how Spring converts an empty string (to `null`), and the plan keeps it (Q5).
- **Page past the end** (e.g. `page=3` when there are 5 rows and `size=2`): 200 with `items: []`. The totals are still filled in.
- **Response** (`page` and `size` echo the values actually used):
```json
{
  "items": [
    {"id": 42, "amount": "200.00", "currency": "PLN", "spentOn": "2026-07-15", "note": "Lunch",
     "createdAt": "2026-07-15T10:15:30.123456Z", "category": {"id": 7, "name": "Food", "icon": "cart"}}
  ],
  "page": 0,
  "size": 50,
  "totalItems": 1,
  "totalAmount": "200.00"
}
```
- **Errors.** All errors are `application/problem+json` with status 400 and `errors[]`. Steps 1 and 2 happen during binding and are reported together in one response. Steps 3–5 run in the controller, in this order, only if binding succeeded, and each stops at the first failure:
  1. **Type or format errors** in any param (`from=31.01.2026`, `page=abc`, `categoryIds=1,x`): one entry per bad param, message `"invalid value"`.
  2. **Bean Validation:** `page < 0`, and `size < 1` or `size > 200`. All entries are sorted by field. If any param failed to convert in step 1, Bean Validation does not run for the request, so `page=abc&size=500` returns only `[page "invalid value"]`; the `size` error shows up once `page` is fixed. (Spring Framework 7.0.9's `DataBinder.validateConstructorArgument` does try to validate the converted arguments through `SmartValidator.validateValue`, but Spring Boot 4.1.1's MVC `ValidatorAdapter` does not override that method, so the default throws `IllegalArgumentException`, which `DataBinder` swallows. The user accepted this behaviour; no custom validator.)
  3. **Empty entry in `categoryIds`:** `{categoryIds, "invalid value"}`.
  4. **`from` after `to`** (checked after defaults are applied): `{from, "must not be after to (<from> > <to>)"}`, e.g. `"must not be after to (2026-07-01 > 2026-01-31)"` (Q1).
  5. **Unknown ids:** `{categoryIds, "Category not found: <ids>"}`, deduplicated, in request order, e.g. `"Category not found: 5, 9"`. This is the same wording as the existing `"Category not found"` on POST/PUT.

### 1. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseListQuery.java`
```java
public record ExpenseListQuery(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        List<Long> categoryIds,
        @Min(0) Integer page,
        @Min(1) @Max(200) Integer size) {

    public ExpenseListQuery {
        categoryIds = categoryIds == null ? List.of() : categoryIds;
        page = page == null ? 0 : page;
        size = size == null ? 50 : size;
    }
}
```
**Why a `@ModelAttribute` record (D3):**
- Spring MVC binds query params into a record through its constructor.
- A conversion failure becomes a `FieldError` with `isBindingFailure() == true`. Bean Validation failures become ordinary field errors.
- Both kinds raise `MethodArgumentNotValidException`, which `ApiExceptionHandler` already turns into `errors[{field, message}]`, with the param names as field names.

**Alternatives rejected:**
- **`@RequestParam` with `@Min`/`@Max` directly on method parameters.** This switches the method to built-in method validation. Out-of-range values then raise `HandlerMethodValidationException` (no `errors[]` today) and type errors raise `MethodArgumentTypeMismatchException` (also no `errors[]`). That gives two error shapes and needs two more handler overrides. **Never put `@Constraint` annotations directly on this controller's method parameters.**
- **Spring Data `Pageable` resolver with `spring.data.web.pageable.default-page-size=50` / `max-page-size=200`.** It is configuration only, but it silently clamps `size > 200` to 200 and resets `page < 0` or non-numeric values to defaults. That contradicts user decision 1. It would also expose a `sort` param, and sorting options are out of scope.

**Details:**
- **Dates.** `@DateTimeFormat(iso = ISO.DATE)` pins ISO parsing via `DateTimeFormatter.ISO_DATE`, which also accepts an offset suffix (`2026-01-31Z`, `2026-01-31+02:00`); that is harmless and not tested. Without it, Spring's default `LocalDate` formatter is the locale-dependent SHORT style (e.g. `1/31/26` for `en_US`, taken from `Accept-Language`), with an ISO fallback. The global alternative, `spring.mvc.format.date=iso`, was not chosen, to avoid an app-wide behaviour change for a single endpoint.
- **Comma splitting.** `categoryIds=1,4,7` is split by Spring's `StringToCollectionConverter`. `categoryIds=` gives an empty list. An empty entry such as `1,,4` becomes a `null` element, and the controller rejects it (step 3 above).
- **The compact constructor must never throw.** An exception there escapes data binding as a 500. So it must not use `List.copyOf(categoryIds)`, which throws a NullPointerException on `null` elements.
- **`Integer`, not `int`**, for `page` and `size`, so that a missing value can be told apart and defaulted. This follows the precedent of `ExpenseRequest`, which also normalises in its compact constructor.

### 2. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseTotals.java`
`public record ExpenseTotals(Long count, BigDecimal amount) {}`
- It is the target of the JPQL constructor expression in section 4.
- It is public and uses wrapper types so that Hibernate's constructor lookup matches exactly (`count` → `Long`, `sum(BigDecimal)` → `BigDecimal`).

### 3. New file: `backend/src/main/java/dev/katran/pet/expense/ExpenseListResponse.java`
```java
public record ExpenseListResponse(
        List<ExpenseResponse> items,
        int page,
        int size,
        long totalItems,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount) {}
```
- **Items (D4).** `items` reuses `ExpenseResponse`, the same shape as `GET /api/expenses/{id}`. The table in card 7 needs `id` (for edit/delete), date, category, amount and note, and all of them are there. A slimmer item type would be a second representation of the same resource.
- **`totalAmount`** is written with the same mechanism as `ExpenseResponse.amount`. The controller passes `totals.amount().setScale(2, RoundingMode.UNNECESSARY)`, so Postgres' `0` (scale 0) for an empty result becomes `"0.00"`. The sum of `numeric(12,2)` values always has scale 2, so `UNNECESSARY` never rounds.
- **No `totalPages`** and no other extra fields: the card fixes the body.

### 4. Change: `backend/src/main/java/dev/katran/pet/expense/ExpenseRepository.java`
Add a shared filter constant and two query methods. `findWithCategoryById` stays.
```java
// Shared by findPage and totals, so the page and the totals always filter the same rows.
String LIST_FILTER = """
        where e.spentOn between :from and :to
          and (:allCategories = true or e.category.id in :categoryIds)
        """;

@EntityGraph(attributePaths = "category")
@Query("select e from Expense e " + LIST_FILTER + " order by e.spentOn desc, e.createdAt desc, e.id desc")
List<Expense> findPage(LocalDate from, LocalDate to, boolean allCategories,
        Collection<Long> categoryIds, Pageable pageable);

@Query("select new dev.katran.pet.expense.ExpenseTotals(count(e), coalesce(sum(e.amount), 0)) from Expense e "
        + LIST_FILTER)
ExpenseTotals totals(LocalDate from, LocalDate to, boolean allCategories, Collection<Long> categoryIds);
```

**Page query:**
- The return type is `List<Expense>` with a `Pageable`, so Spring Data applies `offset`/`limit` and derives **no** count query.
- `@EntityGraph` fetches the category in the same SQL join, so there is no N+1 and no dependence on OSIV. Pagination with a to-one fetch is safe; there is no in-memory paging.
- `between` is inclusive at both ends (AC1).

**Sort order** is fixed in JPQL, not taken from the client (sorting options are out of scope). It is `spentOn desc, createdAt desc`, plus **`id desc` as a final tie-breaker**. Without a unique last key, rows with equal `spent_on` and `created_at` could appear on two pages or on none. That happens for rows inserted in one SQL transaction, where `now()` is the same for all of them (Q2).

**Totals query (D5):**
- One aggregate over **all** rows that match the same `LIST_FILTER`, with no limit or offset.
- `sum` happens in Postgres on `numeric`: exact, and it does not overflow at `numeric(12,2)`. `coalesce(..., 0)` makes an empty result `0` in SQL, and nothing is added up in Java.

**Filter without categories.** "All categories" is passed as `allCategories = true` with an empty collection. Hibernate 6+ renders an empty multi-valued `in` parameter as `1=0`, so the predicate becomes `true or 1=0` (verified by tests L4 and L9; fallback in Risk R1).

The named parameters need `-parameters`, which the Spring Boot Gradle plugin already sets.

`from` is an HQL reserved word. Hibernate's and Spring Data's grammars accept it as a named parameter, and Spring Data validates `@Query` at startup, so any problem fails every IT immediately. If it does, rename the parameters to `:fromDate`/`:toDate` with `@Param("fromDate")`/`@Param("toDate")` rather than restructuring the query.

**Index (D6): no change, no migration.**
- The page query is a range scan on `spent_on`, with the category filter checked inside the existing `(spent_on, category_id)` index. A backward scan gives `spent_on desc`, and Postgres 17's incremental sort orders `created_at, id` within each day before `limit`.
- The totals query has to read every matching row anyway.
- With one user, a month holds roughly 10²–10³ rows, so a dedicated `(spent_on desc, created_at desc, id desc)` index would not pay for itself.
- A migration would also take V4, which the backlog reserves for budget limits, and force the backlog to be renumbered.

### 5. Change: `backend/src/main/java/dev/katran/pet/expense/ExpenseController.java`
- Inject `java.time.Clock` through the constructor: `(ExpenseRepository, CategoryRepository, Clock)`.
- Add `list` (sketch):
```java
@GetMapping
public ExpenseListResponse list(@Valid @ModelAttribute ExpenseListQuery query) {
    if (query.categoryIds().stream().anyMatch(Objects::isNull)) {     // not contains(null): List.of().contains(null) throws NPE
        throw new InvalidFieldException("categoryIds", "invalid value");
    }
    YearMonth currentMonth = YearMonth.now(clock);                    // D1: zone from app.time-zone
    LocalDate from = query.from() != null ? query.from() : currentMonth.atDay(1);
    LocalDate to = query.to() != null ? query.to() : currentMonth.atEndOfMonth();
    if (from.isAfter(to)) {
        throw new InvalidFieldException("from", "must not be after to (" + from + " > " + to + ")");
    }
    Set<Long> categoryIds = new LinkedHashSet<>(query.categoryIds());
    requireExistingCategories(categoryIds);                           // D2: archived ids are known
    boolean allCategories = categoryIds.isEmpty();

    ExpenseTotals totals = expenses.totals(from, to, allCategories, categoryIds);
    long offset = (long) query.page() * query.size();
    List<ExpenseResponse> items = offset >= totals.count()
            ? List.of()
            : expenses.findPage(from, to, allCategories, categoryIds, PageRequest.of(query.page(), query.size()))
                    .stream().map(ExpenseResponse::from).toList();
    return new ExpenseListResponse(items, query.page(), query.size(), totals.count(),
            totals.amount().setScale(2, RoundingMode.UNNECESSARY));
}
```
- **`requireExistingCategories(Set<Long> ids)`:**
  - When `ids` is non-empty, it calls `categories.findAllById(ids)`, which is built in and runs one `where id in (...)`.
  - It collects the missing ids in request order. If there are any, it throws `new InvalidFieldException("categoryIds", "Category not found: " + joined with ", ")`.
  - It does **not** check `archived` (D2). Reading history of an archived category is legitimate. POST and PUT reject archived categories only because no *new* spending may go to them, and `GET /api/categories/{id}` returns archived categories too.
- **Why the totals query runs first and the page query is skipped when `offset >= totalItems`:**
  - It saves a query for pages past the end and for empty results.
  - It also avoids a 500. Spring Data JPA refuses offsets above `Integer.MAX_VALUE` (`InvalidDataAccessApiUsageException` in `PageableUtils.getOffsetAsInteger`), which `page=2147483647&size=200` would otherwise hit.
- **Why not a single query with window functions** (`count(*) over ()`, `sum(amount) over ()`): it returns no rows, and therefore no totals, for pages past the end.
- Keep the existing conventions: no service layer and no `@Transactional` (see Risk R2).
- **Only if test L16 fails:** add header-binding suppression for this model attribute (see Risk R1f):
```java
@InitBinder("expenseListQuery")
void disableHeaderBinding(WebDataBinder binder) {
    if (binder instanceof ExtendedServletRequestDataBinder extended) {
        extended.setHeaderPredicate(header -> false);   // verify the exact API name in spring-webmvc 7.0.9
    }
}
```

### 6. Change: `backend/src/main/java/dev/katran/pet/web/ApiExceptionHandler.java`
Change only the mapping in `handleMethodArgumentNotValid`:
```java
.map(e -> new InvalidField(e.getField(), e.isBindingFailure() ? "invalid value" : e.getDefaultMessage()))
```
- **Why:** for a type error, Spring's default message is the `TypeMismatchException` text, e.g. "Failed to convert value of type 'java.lang.String' to required type 'java.time.LocalDate'; ... @org.springframework.format.annotation.DateTimeFormat ...". That leaks Java type and annotation names and changes between framework versions. The catch-all handler already follows the rule of never echoing exception text.
- **Scope:** binding failures only happen with `@ModelAttribute` binding, and this is the first endpoint that uses it. Every existing endpoint binds a JSON `@RequestBody`, where type errors are `HttpMessageNotReadableException`. Existing behaviour and tests are therefore unaffected.
- **Alternative:** per-field messages through a `messages.properties` MessageSource (`typeMismatch.from=...`) (Q4).

### 7. Change: `README.md`
- **API table:** add after the `POST /api/expenses` row:
  `| GET | /api/expenses | Lists expenses: ?from=&to= (ISO dates, inclusive; default: current month in APP_TIME_ZONE), ?categoryIds=1,4,7 (empty = all; unknown id → 400), ?page= (0-based) and ?size= (1-200, default 50); sorted by spentOn desc, createdAt desc; returns {items, page, size, totalItems, totalAmount} with totalAmount summed over all matching rows |`
- **`APP_TIME_ZONE` paragraph:** extend it to say the zone also defines the "current month" that `GET /api/expenses` lists by default.

### 8. Not changed
- No migration and no index (D6).
- No change to `application.properties`.
- No changes to `Expense`, `ExpenseRequest`, `ExpenseResponse` or `CategoryRepository` (`findAllById` is inherited).
- **No new dependencies.** Everything used is already on the classpath: Spring MVC data binding, `@DateTimeFormat`, Bean Validation `@Min`/`@Max`, Spring Data `@Query`/`@EntityGraph`/`PageRequest`, JPQL constructor expressions, Jackson `@JsonFormat`. `backend/gradle.lockfile` stays unchanged, so `./gradlew dependencies --write-locks` is not needed.
- No frontend changes (cards 7 and 8).

## Tests
Done means `cd backend && ./gradlew test` is green. Existing tests (`ExpenseControllerIT`, `CategoryControllerIT`, `GreetingControllerIT`, `ApiExceptionHandlerIT`, `ApiExceptionHandlerTest`, `ClockConfigurationTest`, `PetApplicationTests`, the `db` tests) stay unchanged and must still pass.

### New file: `backend/src/test/java/dev/katran/pet/expense/ExpenseListIT.java`
**Setup.**
- It is a separate class because it needs its own clock and a database without rows from the CRUD tests. `ExpenseControllerIT` inserts rows in June 2026 and on the real `current_date`.
- It uses the same setup as `ExpenseControllerIT`: `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, a `RestTestClient` built in `@BeforeEach`, and autowired `CategoryRepository`, `ExpenseRepository` and `JdbcTemplate`.
- The fixed clock sits on a month boundary where UTC and Warsaw disagree:
```java
// 2026-07-01 00:30 CEST in Europe/Warsaw (current month: July) while it is still 2026-06-30 in UTC (June).
@TestBean
Clock clock;

static Clock clock() {
    return Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
}
```
**Conventions.**
- **Test data** goes in through `JdbcTemplate`: `insert into expense (category_id, amount, spent_on, note, created_at) values (?, ?, ?, ?, ?) returning id`. This sets `created_at` exactly, which the sort test needs, and allows future dates and archived categories. Categories are created through `CategoryRepository` with unique names.
- **Isolation.** The class shares one database, so every test uses its own date range (a distinct month in 2025, or 1990) and/or filters by its own `categoryIds`. Only L4 and L9 assert "all categories" totals, and each does so in a range no other test uses. The no-param request in L10 is checked with contains / does-not-contain.
- **Error tests** assert `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`, `$.status == 400`, `$.instance == "/api/expenses"`, `$.errors.length()`, every `$.errors[i].field`, and a non-blank or exact message.
- **Money** is always asserted as JSON strings (`jsonPath("$.totalAmount").isEqualTo("136.00")`).
- **Order** is asserted by comparing `$.items[*].id` with the expected id list.

| # | Test | Proves |
|---|---|---|
| L1 | `filtersByInclusiveDateRange`: category C, rows on 2025-03-09, 03-10, 03-15, 03-20 and 03-21 (10.00 … 50.00).<br>- `?from=2025-03-10&to=2025-03-20&categoryIds=C` → ids [03-20, 03-15, 03-10], `totalItems == 3`, `totalAmount == "90.00"`.<br>- `from=to=2025-03-10` → exactly the 03-10 row, `"20.00"`. | AC1, AC6 (inclusive bounds) |
| L2 | `filtersByMultipleCategoryIds`: A, B and C in 2025-04 (10.00, 20.00, 40.00). Every request passes `from=2025-04-01&to=2025-04-30` (the default month is July 2026).<br>- `categoryIds=A,B` → the B and A rows only, `"30.00"`, `totalItems == 2`.<br>- `categoryIds=A,B,A` → the same result (duplicates are harmless).<br>- `categoryIds=C` → only C, `"40.00"`. | AC1, AC3, AC6 (multiple ids) |
| L3 | `archivedCategoryIdIsKnown`: archived X with a row in 2025-05; every request passes `from=2025-05-01&to=2025-05-31`. `categoryIds=X` → 200 with that row (`$.items[0].category.id == X`); `categoryIds=X,A` (A active) → both rows. | D2 |
| L4 | `omittedOrEmptyCategoryIdsMeansAllCategories`: range 2025-06 (used by no other test), rows in active A and archived B. `?from=2025-06-01&to=2025-06-30` and the same with `&categoryIds=` → both return both rows, the same `totalItems` (2) and the same `totalAmount`. | AC3 (empty = all), D2, R1b |
| L5 | `unknownCategoryIdReturns400`:<br>- `categoryIds=A,9223372036854775807` → 400, `$.title == "Bad Request"`, `$.detail == "Invalid request content."`, one error `{categoryIds, "Category not found: 9223372036854775807"}`.<br>- `categoryIds=0` → `"Category not found: 0"`.<br>- `categoryIds=MAX,MAX-1,MAX` → `"Category not found: 9223372036854775807, 9223372036854775806"`. | AC3 (unknown → 400) |
| L6 | `sortsBySpentOnDescThenCreatedAtDesc`: C in 2025-07; rows inserted so that insertion (id) order differs from the expected order:<br>- r1 07-10 @08:00<br>- r2 07-12 @07:00<br>- r3 07-10 @12:00<br>- r4 07-08 created 2025-07-20 (latest `createdAt`, earliest day)<br>- r5 07-10 @10:00<br>- r6 and r7: 07-09 with the same `createdAt`<br>Expected: [r2, r3, r5, r1, r7, r6, r4]. With `size=3`, pages 0–2 put together give the same order with no duplicates. | AC4 (+ `id desc` tie-breaker, Q2) |
| L7 | `totalAmountCoversAllPages`: C in 2025-08, 5 rows: 10.00, 20.50, 0.01, 100.00, 5.49 (total 136.00, not equal to any single page's sum).<br>- `size=2`, pages 0, 1, 2 → 2, 2, 1 items. Each response has the right `page`, `size == 2`, `totalItems == 5` and `totalAmount == "136.00"`. The union of ids is all 5 rows.<br>- `page=3` → 200, `items` is empty, the same totals.<br>- `page=2147483647&size=200` → 200, empty `items`, the same totals (no 500). | AC5, AC6 (totalAmount across pages) |
| L8 | `appliesDefaultAndMaximumPageSize`: 51 rows in 2025-09 (`batchUpdate`).<br>- No `page`/`size` → `$.page == 0`, `$.size == 50`, 50 items, `totalItems == 51`.<br>- `page=1` → 1 item.<br>- `size=200` → 51 items, `$.size == 200`. `size=1` → 1 item.<br>- `page=&size=` → the defaults again. | AC1 (defaults, max), Q5 |
| L9 | `emptyResult`:<br>- Category C with no rows: `?from=2025-10-01&to=2025-10-31&categoryIds=C` → 200, `$.items.length() == 0`, `page 0`, `size 50`, `totalItems == 0`, `totalAmount == "0.00"` (a string).<br>- The same without `categoryIds` for `1990-01-01..1990-01-31`. | AC6 (empty result), D4, R1b |
| L10 | `defaultsToCurrentMonthInAppZone`: C with rows on 2026-06-30, 07-01, 07-31 and 08-01.<br>- `?categoryIds=C` → [07-31, 07-01]; totals match.<br>- `?from=2026-06-30&categoryIds=C` (`to` defaults to 07-31) → [07-31, 07-01, 06-30].<br>- `?to=2026-08-01&categoryIds=C` (`from` defaults to 07-01) → [08-01, 07-31, 07-01].<br>- `GET /api/expenses` with no params → contains the 07-01 and 07-31 ids, not the 06-30 and 08-01 ids.<br>- POST an expense in C dated `2026-07-01` through the API → it then appears in `?categoryIds=C` (`totalItems == 3`).<br>An implementation that uses UTC or the real clock fails this test. | AC2, D1 |
| L11 | `rejectsFromAfterTo`:<br>- `from=2025-03-20&to=2025-03-10` → one error `{from, "must not be after to (2025-03-20 > 2025-03-10)"}`.<br>- Only `to=2026-01-31` → `{from, "must not be after to (2026-07-01 > 2026-01-31)"}` (the user's example).<br>- Only `from=2026-08-01` → `{from, "must not be after to (2026-08-01 > 2026-07-31)"}`.<br>- `from=2025-03-20&to=2025-03-10&categoryIds=9223372036854775807` → only the `from` error (evaluation order). | User decision 2, Q1 |
| L12 | `rejectsOutOfRangePageOrSize` (parameterized): `size=201`, `size=0` and `size=-1` → [size]; `page=-1` → [page]; `page=-1&size=201` → [page, size] (sorted). Messages are non-blank. | User decision 1 |
| L13 | `rejectsMalformedParams` (parameterized), each giving one error with message exactly `"invalid value"` that contains neither `java.` nor `Failed to convert`:<br>- `from=2026-02-30`, `from=31.01.2026`, `from=1/31/26`, `to=2026/01/31`, `to=abc` → from / to<br>- `page=abc`, `page=1.5`, `page=2147483648` → page<br>- `size=abc` → size<br>- `categoryIds=abc`, `categoryIds=1,x`, `categoryIds=1.5`, `categoryIds=9223372036854775808` → categoryIds<br>- `categoryIds=1,,2` and `categoryIds=,` → categoryIds (controller check)<br>Also `from=abc&page=abc` → two errors [from, page], and `page=abc&size=500` → exactly one error [page `"invalid value"`] (Bean Validation is skipped when binding fails; see "Errors" step 2). | Malformed values → 400 Problem Details (D3, section 6), Q3 |
| L14 | `itemsUseExpenseResponseShape`: C with icon `cart`; one row, amount `12.3` with `note` null, in 2025-11.<br>- `$.items[0]` has `id`, `amount == "12.30"`, `currency == "PLN"`, `spentOn`, `note == null`, `createdAt`, and `category.id/name/icon`.<br>- `$.items[0].categoryId` and `$.items[0].category.archived` do not exist.<br>- `$.totalAmount == "12.30"`. | D4 |
| L15 | `totalAmountIsExact`: C in 2025-12 with 0.10 and 0.20 → `"0.30"`; D in 2025-12 with 9999999999.99 twice → `"19999999999.98"` (more than `numeric(12,2)` can hold; no overflow, no float). | Money rule, D5 |
| L16 | `ignoresFromRequestHeader`: C with a row on 2026-07-15. `GET ?categoryIds=C` with header `From: bot@example.com` → 200 and the row is included (the default month is used; the header is not bound to `from`). | Risk R1f |

### Criterion → tests
- AC1: L1, L2, L7, L8, L12, L13
- AC2: L10 (and L11 for defaults that combine into an invalid range)
- AC3: L2, L4, L5 (plus L3 for archived ids)
- AC4: L6 (and L7 for order across pages)
- AC5: L7, L8, L9, L14, L15
- AC6: inclusive bounds L1 and L10; multiple ids L2; empty result L9; totalAmount across pages L7
- User decisions: 1 → L12, L8 (boundaries 1 and 200); 2 → L11
- Settled points: D1 → L10; D2 → L3, L4; D3 and the handler change → L13; D4 → L14, L9; D5 → L7, L9, L15; D7 → every test (GET is now mapped)

## Risks and open questions

### Open questions
The user approved the plan with every proposal in bold, and explicitly confirmed Q2 and Q3. R4 stays an accepted risk.
1. **Q1. Field and message for `from > to`.** **Proposal: field `from`, message `"must not be after to (<from> > <to>)"` with the *effective* dates**, so a defaulted bound is visible. Alternatives: field `to`, an entry on both fields, or a plain message without dates.
2. **Q2. Tie-breaker `id desc`.** The card says "spentOn desc, then createdAt desc". **Proposal: add `id desc` as the last sort key.** It only affects rows whose `spentOn` and `createdAt` are equal, and it makes paging deterministic.
3. **Q3. Empty entries in `categoryIds`** (`1,,4`, a trailing comma, `,`). **Proposal: 400 `{categoryIds, "invalid value"}`**, in line with "no silent fixing". Alternative: drop empty entries, so `1,` becomes `[1]` and `,` means all categories.
4. **Q4. Message for type or format errors.** **Proposal: the fixed text `"invalid value"`, set once in `ApiExceptionHandler` for binding failures.** Alternatives: keep Spring's text (leaks Java type names), or per-field or per-type messages via `messages.properties`.
5. **Q5. Empty values** (`from=`, `to=`, `page=`, `size=`). **Proposal: treat them as omitted**, which is Spring's conversion behaviour and matches the card's rule for `categoryIds=`. Alternative: 400.
6. **Q6. Page past the end.** **Proposal: 200 with `items: []`** and the correct totals (not 404 or 400).
7. **Q7. Echo the effective `from`/`to` in the response?** **Proposal: no**, because the card fixes the body fields. Card 7 already knows it shows the current month.
8. **Q8. Archived categories** count as known ids, and "all categories" includes their expenses (D2). **Proposal: as described.** Confirm.
9. **Q9. New dependencies: none.** Nothing needs confirmation.

### Risks
1. **R1. Framework behaviour the plan relies on, confirmed by tests rather than assumed:**
   - **a) Comma-separated ids in constructor binding.** Constructor binding of a record unwraps a single-valued request param, so `categoryIds=1,4,7` goes through `StringToCollectionConverter` (L2).
     - Fallback: bind `@RequestParam(required = false) List<Long> categoryIds` next to the record. Its type errors then raise `MethodArgumentTypeMismatchException`, which needs an `errors[]` mapping.
   - **b) Empty `in` list.** Hibernate 7.4 renders an empty multi-valued `in` parameter as `1=0` (L4, L9).
     - Fallback: split each query into an "all categories" variant and an "in categories" variant, so four repository methods.
   - **c) Totals constructor expression.** `coalesce(sum(e.amount), 0)` takes the type of its first argument (`BigDecimal`), so the constructor expression resolves. A mismatch fails at startup, so every IT would catch it.
     - Fallback: `coalesce(sum(e.amount), 0bd)` or `cast(0 as BigDecimal)`.
   - **d) Date annotations reach binding.** `@DateTimeFormat` on a record component reaches constructor binding through the field-aware constructor parameter (L13 `from=1/31/26`).
   - **e) Error type for constructor-binding failures.** Constructor-binding type errors end up as a `MethodArgumentNotValidException` whose `FieldError`s have `isBindingFailure() == true` (L13).
   - **f) Header binding.** Spring 6.2+ can bind request **headers** to `@ModelAttribute` params. The standard `From` header would then fill `from` when the param is omitted, and an e-mail address fails to parse, so the request gets a 400. L16 decides: if it fails, add the `@InitBinder` from section 5; otherwise leave it out.
2. **R2. No shared snapshot.** The totals query and the page query are separate statements. A write between them can make `items` disagree with `totalItems` for that one response. This is accepted for a single-user app and follows the "no `@Transactional`" convention. The strict fix would be `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` on `list`.
3. **R3. An extra Spring context.** `ExpenseListIT`'s own `@TestBean` clock gives it its own cached context and Postgres container, which adds a few seconds per run. The same trade-off was accepted for `ExpenseControllerIT`.
4. **R4. Dates Postgres cannot store.** A date outside Postgres' `date` range (e.g. `to=+300000000-01-01`) parses in Java but fails in SQL and surfaces as the generic 500. This is the same class of issue as CRUD plan Risk 5 and is not addressed.
5. **R5. Repeated params.** `categoryIds=1&categoryIds=4` works by Spring default. Mixing forms (`categoryIds=1,4&categoryIds=7`) gives 400 `invalid value`. Only the comma form is documented and tested.
6. **R6. N+1 is prevented but not asserted.** The entity graph prevents it, and no test checks the SQL statement count. OSIV is on, so a regression would silently cause lazy loads rather than fail.
7. **R7. Performance** relies on the existing index and small data volumes (D6). If data grows, a later card can add `(spent_on desc, created_at desc, id desc)` in the next free migration version.

## Out of scope
- Full-text search in notes, sorting options and CSV export (from the card).
- Frontend work (cards 7 and 8).
- Extra response fields (`totalPages`, effective `from`/`to`) and Spring Data `PagedModel`/HATEOAS.
- New indexes or migrations.
- Changes to the expense CRUD endpoints, and mapping JSON body type errors to `errors[]` (CRUD plan Q5).
- A lower or upper bound on dates (R4), and snapshot consistency between the totals and the page (R2).
- Changing OSIV or introducing a service layer.
