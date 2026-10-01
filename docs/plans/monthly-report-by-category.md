# Plan: Monthly report by category

Trello card: https://trello.com/c/JsXWwY6W/13-monthly-report-by-category
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add `GET /api/reports/by-category?month=YYYY-MM`. One SQL statement does all the work in Postgres. It sums the month's expenses per category (`GROUP BY`), full-outer-joins that result with the month's budget limits, and computes `amount`, `share`, `limit`, `remaining` and the month's `totalAmount`. Java only maps the columns to JSON. The SQL is kept in a single file so that the first Grafana panel can use the same query.

## Acceptance criteria
- [ ] Returns month, totalAmount and rows; each row has category (id, name, icon), amount, share (percent of totalAmount with one decimal, 0.0 when total is zero), limit (nullable), remaining (limit minus amount, nullable when there is no limit, negative when exceeded)
- [ ] Categories that have a limit but no expenses in that month appear with amount 0
- [ ] Categories with neither expenses nor a limit are absent
- [ ] Rows are sorted by amount desc
- [ ] Integration tests cover: a month without expenses, a category over its limit (negative remaining), a category with a limit and no expenses, and share summing to 100 within rounding

### Decisions not stated on the card (confirmed by the user on 2026-09-30)
No acceptance criterion is blocked. The plan implements the proposed default shown in bold for each point below. The alternatives are listed under "Open questions". The user approved every proposal (Q1–Q9) as written, together with the four plan-review suggestions listed under "Plan review changes".

1. **`month` is required and must be strict `YYYY-MM`.** It is bound exactly like `GET /api/budget-limits?month=`:
   - Missing, or `month=`: 400 with `errors: [{field: "month", message: "must not be null"}]`.
   - Malformed (`2026-7`, `26-07`, `2026-13`, `2026-00`, `2026-07-01`, `abc`): 400 with `errors: [{field: "month", message: "invalid value"}]`.
   - Any month is accepted, past or future. Nothing depends on "today", so no `Clock` is needed. See Q6.
2. **Response shape:** `{month, totalAmount, rows}`. Each row is `{category: {id, name, icon}, amount, share, limit, remaining}`.
   - `month` echoes the request as `"2026-07"`.
   - `limit` and `remaining` are always present. Both are `null` when the category has no limit in that month.
   - There are no extra fields: no `limitId`, no `category.archived`, no `categoryId` (Q3, Q5).
3. **JSON types:**
   - `totalAmount`, `amount`, `limit` and `remaining` are strings with two decimals (`"800.00"`, `"0.00"`, `"-100.00"`), per the CLAUDE.md money rule.
   - **`share` is a string with exactly one decimal** (`"64.8"`, `"0.0"`, `"100.0"`), for two reasons:
     - "With one decimal" is part of the AC, and only a string keeps it through a JSON parser. JavaScript reads the number `0.0` as `0` and `100.0` as `100`, so card 9 would have to format it.
     - It uses the same mechanism as every other decimal in the API (`@JsonFormat(shape = STRING)`).

     See Q1.
4. **One SQL statement computes everything.** The card asks for "one SQL query with GROUP BY, no per-row arithmetic in Java".
   - The month's expenses are grouped per category. The month's limits are selected. The two sets are combined with a `FULL OUTER JOIN` on `category_id`, then joined to `category` for the name and icon.
   - `amount = coalesce(sum, 0.00)`. `remaining = limit - amount`, which is `null` when there is no limit.
   - **`totalAmount` comes from the same statement** as the window function `sum(amount) over ()`, repeated on every row. Java reads it from the first row.
     - For a month without rows it is the constant `0.00`: the sum over an empty set is zero by definition, so no second query and no Java addition are needed.
   - `share = round(100 * amount / nullif(total, 0), 1)`, and `0.0` when the total is zero.
   - Sorting happens in SQL.
5. **Rounding uses Postgres `round(numeric, 1)`, which rounds ties away from zero.** Shares are never negative, so this is the same as Java's `HALF_UP` (`12.25` → `12.3`).
   - Each share is rounded on its own. The plan does **not** force the sum to exactly `100.0`.
   - The sum can therefore differ from 100 by at most 0.05 per row with a non-zero amount (`33.3 × 3 = 99.9`; `87.8 + 12.3 = 100.1`). This is what "summing to 100 within rounding" allows. See Q7.
6. **Ties are broken by `category id asc`**, after `amount desc` (Q2). Rows with a limit but no expenses (amount `0.00`) end up at the bottom, ordered by category id.
7. **Archived categories are included** when they have expenses or a limit in that month (Q3).
   - `totalAmount` therefore always equals the sum of all expenses of the month. That is the same number `GET /api/expenses?from=<first>&to=<last>` returns as `totalAmount`.
   - Reading the history of an archived category is legitimate (expense-list D2).
8. **Empty months:**
   - A month with no expenses and no limits returns 200 `{"month": "…", "totalAmount": "0.00", "rows": []}`, not 404.
   - A month with limits but no expenses returns one row per limit: amount `"0.00"`, share `"0.0"`, remaining equal to the limit, and totalAmount `"0.00"`.
9. **The query runs through `JdbcClient`**, inside a small `@Repository` class.
   - `JdbcClient` is part of spring-jdbc 7.0.9, which is already on the classpath, and Spring Boot auto-configures it.
   - JdbcClient's built-in row mapping fills a flat record.
   - A JPA native query is not used (reasons in section 3).
10. **The SQL lives in one file, `backend/src/main/resources/db/report/by-category.sql`.** The repository loads it at startup.
    - It has exactly one named parameter, `:month` (the first day of the month).
    - The Grafana card can paste the file into the panel and replace `:month` with a Grafana expression. No view or DB function is created (Q4).
11. **No migration and no index.**
    - The expense month filter is a range on `spent_on`, which the existing `ix_expense_spent_on_category_id` covers.
    - `budget_limit` rows of one month are a tiny scan (budget-limits Q5).
12. **Names:**
    - Package `dev.katran.pet.report`.
    - `ReportController`, mapped to `/api/reports` with `GET /by-category`, so future reports live next to it.
    - `CategoryReportQuery`, `CategoryReportRepository`, `CategoryReportLine`, `CategoryReportRow` and `CategoryReportResponse`.
    - The SQL file `db/report/by-category.sql`.
13. **Currency is ignored when summing.** Every expense is `PLN`, because `currency` has a DB default and the API cannot set it. Multi-currency is out of scope project-wide.

## Changes

### Existing state (verified in the code)
- **Migrations:** V1–V4 exist in `backend/src/main/resources/db/migration`.
  - `category (id, name, icon nullable, archived, created_at)`.
  - `expense (id, category_id, amount numeric(12,2), currency char(3) not null default 'PLN', spent_on date, note, created_at)`, with `ck_expense_amount_positive` and `ix_expense_spent_on_category_id on expense (spent_on, category_id)`.
  - `budget_limit (id, category_id, month date, amount numeric(12,2))`, with:
    - `ck_budget_limit_month_first_day`, so `month = <first day>` is a safe equality match;
    - `ck_budget_limit_amount_positive`;
    - `uq_budget_limit_category_month unique (category_id, month)`. `month` is the second column, so the index does not serve month-only lookups (budget-limits Q5).
  - Both amount checks are `> 0`. A category that appears in the report therefore always has an amount above 0, or a limit above 0, or both.
  - `docs/wallet-backlog.md` reserves `V5__create_quick_templates_table.sql` for card 6. This card needs no migration.
- **`Expense.currency`** is `@Generated` with `insertable = false, updatable = false`, so it is always the DB default `PLN`.
- **Packages** are organised by feature: `category`, `expense`, `budgetlimit`, `web`, `time`, `db`, `greeting`.
  - There is no `report` package and nothing is mapped under `/api/reports`.
  - There is no service layer: controllers call repositories directly.
  - Main code has no `@Transactional`.
- **Database access in main code is only Spring Data JPA.** No main class uses `JdbcTemplate` or `JdbcClient`.
  - `ExpenseRepository.totals` is a JPQL aggregate with `coalesce(sum(e.amount), 0)`.
  - `ExpenseController.list` calls `setScale(2, RoundingMode.UNNECESSARY)` on the result, because Postgres returns the literal `0` with scale 0.
- **JDBC on the classpath** (`backend/gradle.lockfile`, pulled in by the data-jpa starter):
  - `org.springframework:spring-jdbc:7.0.9` contains `JdbcClient`, `SimplePropertyRowMapper` and `DataClassRowMapper`.
  - `org.springframework.boot:spring-boot-jdbc:4.1.1` contains `JdbcClientAutoConfiguration`.
  - The ITs already autowire `JdbcTemplate`, which shows that JDBC auto-configuration is active next to JPA.
- **Month binding precedent:**
  - `BudgetLimitListQuery(@NotNull YearMonth month)` is bound with `@Valid @ModelAttribute` in `BudgetLimitController.list`.
  - `ApiExceptionHandler.handleMethodArgumentNotValid` returns `errors[]` sorted by field, then message, with `"invalid value"` for binding failures (`FieldError.isBindingFailure()`).
  - `BudgetLimitControllerIT` B12 shows the result: a missing month or `month=` gives `errors[{month, <Bean Validation message>}]`; a malformed month gives `errors[{month, "invalid value"}]`.
- **Serialisation:**
  - `BudgetLimitResponse.month` is a `YearMonth` and is written as `"2026-07"` (asserted by B1).
  - Money is `@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal`, imported from `com.fasterxml.jackson.annotation.JsonFormat`, in `ExpenseResponse`, `ExpenseListResponse` and `BudgetLimitResponse`.
  - There is no `spring.jackson.*` configuration, so `null` fields are written. `ExpenseListIT` L14 asserts `note` with `isEqualTo(null)`.
- **`CategorySummary(Long id, String name, String icon)`** is a public record in `dev.katran.pet.category`, with `from(Category)`.
- **Tests:**
  - These classes share one cached context, and so one Postgres container: `CategoryControllerIT`, `GreetingControllerIT`, `ApiExceptionHandlerIT` and `BudgetLimitControllerIT`. All use `@SpringBootTest(RANDOM_PORT)` without bean overrides.
  - In that context no test writes `expense`. Only `BudgetLimitControllerIT` writes `budget_limit`, in months 2020-01, 2030-01…2030-09, 2031-01…2031-05 and 2039-01…2039-06.
  - `ExpenseControllerIT` and `ExpenseListIT` each have their own context and container (`@TestBean Clock`).
  - There is no `src/test/resources`.
- **Grafana** is mentioned only in `docs/wallet-backlog.md`. There is no dashboard, datasource or config in the repository.
- **Frontend:** no report or budget-limit code (`frontend/src` has no match for `reports` or `budget-limits`).
- **README:** the API table ends with the budget-limit rows and `/actuator/health`. It has a sentence on money as strings with two decimals.
- **Versions:** Spring Boot 4.1.1, Spring Framework 7.0.9, Hibernate ORM 7.4.5, jackson-databind 3.1.7, PgJDBC 42.7.13, Flyway 12.4.0. Testcontainers runs `postgres:17`.

### API contract

| Method | Path | Request | Success | Errors (all `application/problem+json`) |
|---|---|---|---|---|
| `GET` | `/api/reports/by-category?month=2026-07` | `month` required, `YYYY-MM` | `200`, `CategoryReportResponse`; `rows: []` and `totalAmount: "0.00"` if the month has neither expenses nor limits | `400` missing, empty or malformed `month` (`errors[month]`) |

- Other methods on this path are not mapped. Spring MVC answers them with 405 Problem Details.
- `GET /api/reports` alone returns 404 Problem Details (`ApiExceptionHandlerIT.unknownRouteIsProblemDetails`).

**Response example:**
```json
{
  "month": "2026-07",
  "totalAmount": "1234.50",
  "rows": [
    {"category": {"id": 7, "name": "Food", "icon": "cart"}, "amount": "800.00", "share": "64.8", "limit": "700.00", "remaining": "-100.00"},
    {"category": {"id": 12, "name": "Transport", "icon": null}, "amount": "434.50", "share": "35.2", "limit": null, "remaining": null},
    {"category": {"id": 3, "name": "Fun", "icon": "star"}, "amount": "0.00", "share": "0.0", "limit": "200.00", "remaining": "200.00"}
  ]
}
```

**Error example:**
```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Invalid request content.",
 "instance": "/api/reports/by-category", "errors": [{"field": "month", "message": "invalid value"}]}
```

**Evaluation order** (the same as `GET /api/budget-limits`):
1. A malformed `month` is a binding failure: `errors[{month, "invalid value"}]`.
2. A missing or empty `month` is a Bean Validation error: `errors[{month, "must not be null"}]`.
3. Otherwise the SQL runs and the endpoint returns 200.

### 1. New file: `backend/src/main/resources/db/report/by-category.sql`
```sql
-- Monthly report by category.
-- Used by GET /api/reports/by-category (CategoryReportRepository) and as the source of the
-- first Grafana panel, which substitutes its own month expression for the month parameter.
-- Parameter month: the first day of the month (a date).
-- One row per category that has expenses and/or a limit in that month, sorted by amount desc,
-- then category id. Money keeps scale 2; share is the percent of the month total with scale 1,
-- 0.0 when the total is zero.
with params as (
    select cast(:month as date) as first_day
),
spent as (
    select e.category_id, sum(e.amount) as amount
    from expense e
    cross join params p
    where e.spent_on >= p.first_day
      and e.spent_on < cast(p.first_day + interval '1 month' as date)
    group by e.category_id
),
month_limit as (
    select l.category_id, l.amount
    from budget_limit l
    cross join params p
    where l.month = p.first_day
),
combined as (
    select coalesce(s.category_id, ml.category_id) as category_id,
           coalesce(s.amount, 0.00)                as amount,
           ml.amount                               as limit_amount
    from spent s
    full outer join month_limit ml on ml.category_id = s.category_id
)
select c.id                      as category_id,
       c.name                    as category_name,
       c.icon                    as category_icon,
       x.amount,
       coalesce(round(100 * x.amount / nullif(sum(x.amount) over w, 0), 1), 0.0) as share,
       x.limit_amount,
       x.limit_amount - x.amount as remaining,
       sum(x.amount) over w      as total_amount
from combined x
join category c on c.id = x.category_id
window w as ()
order by x.amount desc, c.id
```
**How each AC is met:**
- **AC2 and AC3.** The row set is the full outer join of "categories with expenses in the month" and "categories with a limit in the month".
  - A category appears only if it has at least one expense or a limit in that month.
  - The inner join to `category` adds only the name and icon. The foreign keys guarantee a match, so no row is lost.
  - Starting from `category left join … where … is not null` would be equivalent. The full join expresses the ACs directly and does not scan every category.
- **Aggregate first, then join.** Expenses are summed per category *before* the limit is joined. A limit is therefore never multiplied by the number of expenses (the join-then-sum fan-out bug).
- **Month filter.**
  - The filter is a half-open range `[first_day, next month's first day)` on the bare `spent_on` column, so it can use `ix_expense_spent_on_category_id`. `date_trunc('month', spent_on) = …` would defeat that index.
  - `budget_limit.month = first_day` is correct because of `ck_budget_limit_month_first_day`.
- **Money scale.**
  - `sum` of `numeric(12,2)` keeps scale 2.
  - The literal `0.00`, not `0`, keeps scale 2 for rows that have only a limit.
  - `remaining` keeps scale 2.
  - There is deliberately no cast to `numeric(12,2)`, because sums can exceed 12 digits (test R9: `20000000000.28`).
- **Total.**
  - `sum(x.amount) over w`, with the empty window `window w as ()`, covers all result rows.
  - Every expense belongs to exactly one category, and every category with expenses is in the result. The total is therefore the month's expense sum, archived categories included.
- **Share.**
  - The expression multiplies before it divides.
  - `nullif` prevents division by zero.
  - `coalesce(…, 0.0)` returns `0.0` (scale 1) when the total is zero. `round(…, 1)` also returns scale 1.
- **Sort order** is set in SQL (`amount desc, category id asc`). Java keeps the list order.
- **Syntax choices:**
  - The column alias is `limit_amount`, because `LIMIT` is a reserved word in Postgres.
  - `cast(:month as date)` is used instead of `:month::date`. It gives the JDBC parameter an explicit type, and it keeps `::` away from a named parameter.
  - `:month` appears once, so Grafana has a single substitution. Example for the Grafana card: `cast(date_trunc('month', $__timeFrom()) as date)`. Not implemented here.
- **One statement, one snapshot.** One `WITH … SELECT` statement with one `GROUP BY` and window functions is a single query. Its rows and total always come from the same snapshot.

### 2. New file: `backend/src/main/java/dev/katran/pet/report/CategoryReportLine.java`
```java
// One result row of db/report/by-category.sql; columns map by name (category_id -> categoryId).
public record CategoryReportLine(
		Long categoryId, String categoryName, String categoryIcon,
		BigDecimal amount, BigDecimal share, BigDecimal limitAmount, BigDecimal remaining,
		BigDecimal totalAmount) {
}
```
- This is a flat record so that JdbcClient's built-in mapping can fill it without a hand-written `RowMapper`. It is never serialised.
- It is public, like `ExpenseTotals`, so reflective instantiation has no accessibility concerns.

### 3. New file: `backend/src/main/java/dev/katran/pet/report/CategoryReportRepository.java`
```java
@Repository
public class CategoryReportRepository {

	private final JdbcClient jdbc;
	private final String sql;

	public CategoryReportRepository(JdbcClient jdbc,
			@Value("classpath:db/report/by-category.sql") Resource sql) throws IOException {
		this.jdbc = jdbc;
		this.sql = sql.getContentAsString(StandardCharsets.UTF_8);
	}

	public List<CategoryReportLine> findByMonth(YearMonth month) {
		return jdbc.sql(sql)
				.param("month", month.atDay(1))
				.query(CategoryReportLine.class)
				.list();
	}
}
```
**How it works:**
- **The SQL is read once at startup.** A missing file fails context startup, and therefore every IT.
- **`query(Class)`** uses Spring's `SimplePropertyRowMapper`. It binds record constructor arguments by column label and matches `snake_case` to `camelCase`. There is no custom mapping code.
- **No `@Transactional`.** The single statement runs in its own autocommit snapshot, which follows the project convention.

**Why `JdbcClient` and not a Spring Data JPA native query (`@Query(nativeQuery = true)`):**
- The report is not an entity, so a native query would need a host repository of some unrelated entity.
- The SQL would have to be an annotation constant, so it could not live in the shared `.sql` file (decision 10).
- The result would go through Hibernate's native scalar type inference and an interface projection. JdbcClient maps plain JDBC types directly.

**Why not HQL:** Hibernate 7 HQL does support CTEs, full joins and window functions. But the Grafana panel needs plain SQL anyway, and HQL would be a second dialect of the same query.

**Why not `JdbcTemplate`:** `JdbcClient` is Spring's fluent front-end over the same `NamedParameterJdbcTemplate`, with named parameters, and it needs less code.

**No new dependency.**

### 4. New file: `backend/src/main/java/dev/katran/pet/report/CategoryReportQuery.java`
```java
public record CategoryReportQuery(@NotNull YearMonth month) {
}
```
- It is bound with `@Valid @ModelAttribute`, exactly like `BudgetLimitListQuery`. The error shapes are therefore identical (decision 1), and the existing handler needs no change.
- `BudgetLimitListQuery` is not reused, because it belongs to the budget-limit resource. The two-line record is copied. A shared `MonthQuery` can be extracted if a third endpoint needs it.
- Do **not** put constraint annotations on the controller method parameters (expense-list section 1).

### 5. New file: `backend/src/main/java/dev/katran/pet/report/CategoryReportRow.java`
```java
public record CategoryReportRow(
		CategorySummary category,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal share,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remaining) {

	static CategoryReportRow from(CategoryReportLine line) {
		return new CategoryReportRow(
				new CategorySummary(line.categoryId(), line.categoryName(), line.categoryIcon()),
				line.amount(), line.share(), line.limitAmount(), line.remaining());
	}
}
```
- **Only restructuring, no arithmetic** (card rule).
- **No `setScale`.** This differs from `ExpenseResponse`, because the SQL already guarantees scale 2 for money and scale 1 for share. The Grafana panel shows the SQL values directly, so the SQL has to be right on its own; Java would add nothing. Tests pin `"0.00"` and `"0.0"`. See Risk R1c for the fallback.
- **Null fields.** A `null` `limit` or `remaining` is written as JSON `null`, because the STRING shape does not apply to nulls.
- **`CategorySummary`** is reused unchanged: `{id, name, icon}` without `archived`, as everywhere.

### 6. New file: `backend/src/main/java/dev/katran/pet/report/CategoryReportResponse.java`
```java
public record CategoryReportResponse(
		YearMonth month,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
		List<CategoryReportRow> rows) {

	// Every SQL row carries the same month total; a month without rows sums to zero.
	private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

	public static CategoryReportResponse of(YearMonth month, List<CategoryReportLine> lines) {
		BigDecimal total = lines.isEmpty() ? ZERO_AMOUNT : lines.getFirst().totalAmount();
		return new CategoryReportResponse(month, total, lines.stream().map(CategoryReportRow::from).toList());
	}
}
```
- `month` relies on the same default `YearMonth` output (`"2026-07"`) that `BudgetLimitResponse` already relies on.

### 7. New file: `backend/src/main/java/dev/katran/pet/report/ReportController.java`
```java
@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final CategoryReportRepository categoryReports;

	public ReportController(CategoryReportRepository categoryReports) {
		this.categoryReports = categoryReports;
	}

	@GetMapping("/by-category")
	public CategoryReportResponse byCategory(@Valid @ModelAttribute CategoryReportQuery query) {
		return CategoryReportResponse.of(query.month(), categoryReports.findByMonth(query.month()));
	}
}
```
- There is no service layer and no `@Transactional`, following project convention.

### 8. Change: `README.md`
- **API table:** add this row after the budget-limit rows:
  `| GET | /api/reports/by-category?month=YYYY-MM | Monthly report (month is required): {month, totalAmount, rows}; one row per category with expenses and/or a limit in that month: {category {id, name, icon}, amount, share (percent of totalAmount, one decimal, "0.0" when the total is zero), limit (null if none), remaining (limit minus amount, null if no limit, negative when exceeded)}; sorted by amount desc, then category id; archived categories included |`
- **Sentence after "Money amounts are JSON strings with two decimals":** add "Percentages (`share`) are JSON strings with one decimal (`"64.8"`)."

### 9. Not changed
- **No new dependencies.** Everything used is already on the classpath:
  - spring-jdbc `JdbcClient` and its auto-configuration;
  - Spring `Resource`;
  - Bean Validation;
  - Jackson `@JsonFormat`;
  - Spring's built-in `YearMonth` formatter.

  `backend/gradle.lockfile` is unchanged, so `./gradlew dependencies --write-locks` is not needed. It becomes mandatory only if the implementer touches dependencies anyway.
- No migration, no index, and no change to `application.properties`.
- No change to `ApiExceptionHandler`, `BudgetLimit*`, `Expense*`, `Category*` or `CategorySummary`.
- No frontend change (card 9), no Grafana, no Helm, no `CLAUDE.md` change, no change to `docs/wallet-backlog.md`.

### Implementation order
1. Write `db/report/by-category.sql`. Optionally run it against the dev database (`postgres:5432`), with `:month` replaced by `date '2026-07-01'`, to check the syntax.
2. Add `CategoryReportLine` and `CategoryReportRepository`.
3. Add `CategoryReportQuery`, `CategoryReportRow`, `CategoryReportResponse` and `ReportController`.
4. Write `ReportControllerIT`.
5. Update `README.md`.
6. Run `cd backend && ./gradlew test`. It must be green with the new tests and all existing ones.

## Tests
Done means `cd backend && ./gradlew test` is green. The existing tests stay unchanged and must still pass: `CategoryControllerIT`, `ExpenseControllerIT`, `ExpenseListIT`, `BudgetLimitControllerIT`, `GreetingControllerIT`, `ApiExceptionHandlerIT`, `ApiExceptionHandlerTest`, `ClockConfigurationTest`, `PetApplicationTests` and the `db` tests. There are no unit tests: the logic is the SQL, so only integration tests against Postgres 17 prove it.

### New file: `backend/src/test/java/dev/katran/pet/report/ReportControllerIT.java`
**Setup.** Copy `BudgetLimitControllerIT`:
- `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, and a `RestTestClient` built in `@BeforeEach`.
- Autowired `CategoryRepository` and `JdbcTemplate`.
- **No `@TestBean`.** The class shares the cached context and container of `BudgetLimitControllerIT`, so there is no extra startup cost.

**Conventions:**
- **Categories** are created through `CategoryRepository` with unique names. Use `createActiveCategory(prefix, icon)` and `createArchivedCategory(prefix)`; the icon may be `null`.
- **Expenses** are inserted through `JdbcTemplate`, as in `ExpenseListIT`: `insert into expense (category_id, amount, spent_on) values (?, ?, ?) returning id`.
- **Limits** are inserted through `JdbcTemplate`: `insert into budget_limit (category_id, month, amount) values (?, ?, ?) returning id`, with `YearMonth.parse(m).atDay(1)`. This bypasses the API's archived-category check, so R8 can set a limit on an archived category.
- **Isolation.** Each test uses months that no other test in the shared database uses: 2016-01…2016-03, 2017-01…2017-12 and 2099-12. `BudgetLimitControllerIT` uses none of them, and nothing in this context writes `expense`. Exact assertions on `rows` are therefore safe.
  - Record these months in a class-level comment of `ReportControllerIT`, so that authors of later ITs sharing this context see which months are taken.
- **Money and share** are always asserted as JSON strings.
- **Ids** are compared as ints, with the `ids(...)` helper from `ExpenseListIT`, on `$.rows[*].category.id`.
- **Nulls** are asserted with `isEqualTo(null)`. That asserts the field is present and null; `exists()` fails on null values.
- **Share-sum helper** `assertSharesSumTo100(month)`:
  - Read `$.rows[*].share` and `$.rows[*].amount` as string lists and sum the shares as `BigDecimal`.
  - Let n be the number of rows whose amount is not `"0.00"`.
  - If n > 0, assert that the absolute difference between the sum and 100 is at most 0.05 × n.
  - If n = 0, assert that the sum is 0.
- **Error tests** assert:
  - `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`;
  - `$.status`;
  - `$.instance == "/api/reports/by-category"`;
  - `$.errors.length()`, the field, and the message.

| # | Test | Proves |
|---|---|---|
| R1 | `returnsMonthTotalAndRowsWithAllFields`, month 2017-01.<br>- Create categories in the order C (icon `star`), B (icon `null`), A (icon `cart`), D, E, so ids ascend C < B < A.<br>- A: expenses 500.00 on 01-01 and 300.00 on 01-31, limit 700.00.<br>- B: 434.50, no limit.<br>- C: limit 200.00, no expenses.<br>- D: expense on 2017-02-01 and a limit in 2017-02 only.<br>- E: no data.<br>- `GET ?month=2017-01` → 200.<br>- Top level: `$.month == "2017-01"`, `$.totalAmount == "1234.50"`, `$.rows.length() == 3`, `$.rows[*].category.id == [A, B, C]` (sorted by amount, not id).<br>- A: `amount "800.00"`, `share "64.8"`, `limit "700.00"`, `remaining "-100.00"`, `category.name/icon` match.<br>- B: `"434.50"`, `"35.2"`, `limit == null`, `remaining == null`, `category.icon == null`.<br>- C: `"0.00"`, `"0.0"`, `"200.00"`, `"200.00"`.<br>- Absent fields: `$.rows[0].categoryId`, `limitId`, `limitAmount`, `totalAmount` and `category.archived`.<br>- D and E are not in the rows. `?month=2017-02` → exactly [D].<br>- `assertSharesSumTo100("2017-01")`. | AC1 (shape, all fields, nullable limit/remaining), AC2, AC3, AC4, AC5 (over limit, limit without expenses), decisions 2, 3 |
| R2 | `countsOnlyExpensesAndLimitsOfTheRequestedMonth`. Categories F and G.<br>- F: 10.00 on 2016-01-31, 1.00 on 2016-02-01, 2.00 on 2016-02-29 (leap day), 100.00 on 2016-03-01.<br>- G: 5.00 on 2016-01-15, limit in 2016-03 of 30.00.<br>- `?month=2016-02` → [F `"3.00"`, share `"100.0"`, limit `null`], total `"3.00"`. G is absent.<br>- `?month=2016-01` → [F `"10.00"` `"66.7"`, G `"5.00"` `"33.3"`], total `"15.00"`.<br>- `?month=2016-03` → [F `"100.00"`, G `"0.00"` with limit `"30.00"` and remaining `"30.00"`], total `"100.00"`. | Month bounds inclusive/exclusive (half-open range), AC2 (limit while expenses exist only in other months), AC3 |
| R3 | `monthWithoutExpensesOrLimitsIsEmpty`: `?month=2017-03` and `?month=2099-12` (a future month) → 200, `$.month` echoed, `$.totalAmount == "0.00"` (a string), `$.rows` empty. | **AC5 (month without expenses)**, decisions 1 (any month), 4 (empty total), 8 |
| R4 | `limitsWithoutExpensesAppearWithZeroAmount`, month 2017-04.<br>- Categories H then I (H.id < I.id), with limits H 50.00 and I 300.00, and no expenses.<br>- Result: `$.totalAmount == "0.00"`; rows [H, I] (tie at `"0.00"`, broken by id, not by limit).<br>- Each row: `amount "0.00"`, `share "0.0"`, `limit` equal to `remaining` (`"50.00"`, `"300.00"`).<br>- `assertSharesSumTo100` passes with n = 0. | **AC2**, **AC5 (month without expenses; limit and no expenses)**, AC1 (share 0.0 when the total is zero), decision 6 |
| R5 | `remainingIsLimitMinusAmountAndNegativeWhenExceeded`, month 2017-05:<br>- J: 120.00 + 30.50, limit 100.00 → `"150.50"`, `"-50.50"`, share `"53.7"`.<br>- K: 100.00, limit 100.00 → remaining `"0.00"` (exactly at the limit is not negative), share `"35.7"`.<br>- L: 20.00, limit 25.00 → `"5.00"`, share `"7.1"`.<br>- M: 10.00, no limit → `limit null`, `remaining null`, share `"3.6"`.<br>- Rows [J, K, L, M]; total `"280.50"`; `assertSharesSumTo100` (sum 100.1). | **AC5 (over limit, negative remaining)**, AC1 (remaining semantics), AC4 |
| R6 | `sortsByAmountDescThenCategoryId`, month 2017-06. Categories N1…N6 are created in that order.<br>- N1: 10.00.<br>- N3: 20.00 + 30.00, inserted before N2's expense.<br>- N2: 50.00.<br>- N4: limit 5.00 only.<br>- N5: limit 500.00 only.<br>- N6: 0.01.<br>- Expected `$.rows[*].category.id == [N2, N3, N1, N6, N4, N5]`. | **AC4**, decision 6 (id tie-break, not insertion order and not limit) |
| R7 | `sharesSumTo100WithinRounding`, three months:<br>- **2017-07:** P, Q, R with 10.00 each, and S with limit 40.00 only → `"33.3"` × 3 and `"0.0"`, sum 99.9.<br>- **2017-08:** T 3.51 and U 0.49, total `"4.00"` → `"87.8"` (87.75) and `"12.3"` (12.25), sum 100.1. This proves ties round away from zero, not half-even.<br>- **2017-09:** V 42.00 alone → `"100.0"`.<br>- Each month: exact share strings, plus `assertSharesSumTo100`. | **AC5 (share sums to 100 within rounding)**, AC1 (one decimal), decisions 3, 5 |
| R8 | `includesArchivedCategories`, month 2017-10:<br>- W: 60.00, archived after the insert.<br>- X: archived, limit 80.00 only (JDBC insert).<br>- Y: active, 40.00.<br>- Result: `$.totalAmount == "100.00"` (includes W); rows [W `"60.00"` `"60.0"`, Y `"40.00"` `"40.0"`, X `"0.00"` `"0.0"` `"80.00"` `"80.00"`].<br>- `$.rows[0].category.archived` does not exist. | Decision 7, AC2 |
| R9 | `totalAmountIsExactAndMatchesExpenseList`, month 2017-11:<br>- Z1: 9999999999.99 twice, limit 9999999999.99.<br>- Z2: 0.10 + 0.20.<br>- Result: `$.totalAmount == "20000000000.28"`.<br>- Z1: `"19999999999.98"`, share `"100.0"`, remaining `"-9999999999.99"`.<br>- Z2: `"0.30"`, share `"0.0"` (a non-zero amount can round to 0.0).<br>- `GET /api/expenses?from=2017-11-01&to=2017-11-30` → `$.totalAmount` equals the report's `totalAmount`. | Money rule (exact, no overflow, no float), decisions 4, 7, AC5 (over limit) |
| R10 | `rejectsMissingOrMalformedMonth` (parameterized). Each returns 400 with exactly one error, `field == "month"`:<br>- `/api/reports/by-category` and `?month=` → non-blank Bean Validation message.<br>- `?month=2026-7`, `26-07`, `2026-13`, `2026-00`, `2026-07-01`, `07-2026`, `2026/07`, `abc` → message exactly `"invalid value"`, containing neither `java.` nor `Failed to convert`. | Decision 1, error format (CLAUDE.md) |
| R11 | `reflectsLimitAndExpenseChanges`, month 2017-12, active category AA:<br>- `GET` → `rows: []`.<br>- `PUT /api/budget-limits {AA, "2017-12", "150.00"}` → the row has `"0.00"`, limit `"150.00"` and remaining `"150.00"`.<br>- Insert a 200.00 expense on 2017-12-24 through `JdbcTemplate` with `returning id` (the id is used by the final `DELETE`) → `"200.00"`, `"100.0"`, `"-50.00"`, total `"200.00"`.<br>- `DELETE /api/budget-limits/{id}` → the row stays, with `limit` and `remaining` both `null`.<br>- `DELETE /api/expenses/{id}` → `rows: []` again. | AC2, AC3 (a category disappears once it has neither), AC5; the card-9 flow (the report reflects changes, no caching) |

### Criterion → tests
- **AC1:**
  - shape and fields: R1
  - limit and remaining: R5
  - share 0.0 when the total is zero: R3, R4
  - one decimal: R7
  - exactness: R9
- **AC2:** R1 (C), R2 (G in 2016-03), R4, R8 (X), R11
- **AC3:** R1 (D, E), R2 (month bounds, G in 2016-02), R11 (last step)
- **AC4:** R6 (including ties), R1, R5, R8
- **AC5:**
  - month without expenses: R3 (no limits either) and R4 (limits only)
  - over limit, negative remaining: R5 (and R1, R9, R11)
  - limit and no expenses: R4 (and R1, R2, R8, R11)
  - share sums to 100 within rounding: R7 (and the helper in R1 and R5)
- **Decisions:**
  - 1 → R10, R3
  - 2 → R1
  - 3 → R1, R4, R7
  - 4 → R3, R9
  - 5 → R7
  - 6 → R4, R6
  - 7 → R8, R9
  - 8 → R3, R4
  - 9 and 10 → every test (all of them execute the SQL through `JdbcClient`)

## Risks and open questions

### Open questions
Each proposal below is in bold and is already implemented by the plan. The user confirms it or picks the alternative.
1. **Q1. JSON type of `share` (decision 3).**
   - **Proposal: a string with one decimal (`"64.8"`, `"0.0"`).**
   - Alternative: a JSON number (`64.8`). Jackson would write `0.0` and `100.0`, but JavaScript reads them as `0` and `100`, so card 9 would have to format with `toFixed(1)`. It would also be the only decimal in the API that is not a string.
2. **Q2. Tie-break (decision 6).**
   - **Proposal: `category id asc`.** This is the same order as `GET /api/categories` and `GET /api/budget-limits`, and it raises no collation question.
   - Alternatives:
     - category name, which needs a collation decision (category-crud Risk 6);
     - `limit desc` for rows with amount 0.
3. **Q3. Archived categories (decision 7).**
   - **Proposal: include them when they have expenses or a limit in the month, with no `archived` flag.**
   - Alternatives:
     - (a) Exclude them. Then `totalAmount` would either drop their expenses, and disagree with `GET /api/expenses`, or keep them, and the shares would no longer sum to 100.
     - (b) Include them and add `category.archived` to the row, so card 9 can hide the inline limit form. A `PUT` for an archived category returns 409.
4. **Q4. Where the SQL lives and how Grafana reuses it (decision 10).**
   - **Proposal: the classpath file `db/report/by-category.sql`, loaded by the repository.** The Grafana card pastes it into the panel and replaces `:month`.
   - Alternatives:
     - (a) A Java text block constant in `CategoryReportRepository`, like `ExpenseRepository.LIST_FILTER`. It needs no resource loading, but the Grafana author copies the SQL from Java code.
     - (b) A Postgres function `category_report(month date)` in a Flyway **repeatable** migration `R__category_report.sql`. The backend runs `select * from category_report(:month)` and Grafana calls the same function, so nothing is copied. This would be the first DB function and the first repeatable migration, and every change to the query becomes a migration.
     - (c) The same as (b) in a versioned `V5`. This conflicts with the backlog, which reserves V5 for quick templates (card 6).
5. **Q5. Limit id in the row (decision 2).**
   - **Proposal: no `limitId`.** The card lists the row fields, and card 9 names `/api/budget-limits` as its second data source. `GET /api/budget-limits?month=` returns the ids, keyed by category.
   - Alternative: add a nullable `limitId`, so card 9 can call `DELETE /api/budget-limits/{id}` straight from a report row without a second request.
6. **Q6. Is `month` required? (decision 1)**
   - **Proposal: required.** This matches `GET /api/budget-limits`, which card 9 calls with the same month.
   - Alternative: optional, defaulting to the current month in `APP_TIME_ZONE` through the `Clock` bean, like `GET /api/expenses`.
7. **Q7. Shares that do not add up to exactly 100.0 (decision 5).**
   - **Proposal: round each share on its own. The sum stays within 0.05 per non-zero row.**
   - Alternative: the largest-remainder method, so the sum is always exactly `100.0`. It needs extra window steps in SQL (floor, rank by remainder, distribute the missing tenths), makes the Grafana query harder to read, and the card only asks for "within rounding".
8. **Q8. Names (decision 12):** package `report`, `ReportController` under `/api/reports`, the `CategoryReport*` classes, and `db/report/by-category.sql`.
9. **Q9. New dependencies: none.** Nothing needs confirmation.

### Risks
1. **R1. Framework behaviour, confirmed by tests rather than assumed:**
   - **a) `JdbcClient` bean.** Spring Boot's `JdbcClientAutoConfiguration` (present in spring-boot-jdbc 4.1.1) provides a `JdbcClient` next to JPA. `JdbcTemplate` is already autowired in the ITs. Fallback: `JdbcClient.create(dataSource)` in the repository constructor.
   - **b) Row mapping.** `query(CategoryReportLine.class)` maps `category_id` to `categoryId` and `limit_amount` to `limitAmount` through `SimplePropertyRowMapper`'s constructor binding for records. Fallback: `new DataClassRowMapper<>(CategoryReportLine.class)` or a lambda `RowMapper`.
   - **c) Scale.** Postgres keeps scale 2 for `sum` over `numeric(12,2)` and for `0.00`, and scale 1 for `round(…, 1)` and `0.0`. PgJDBC returns `BigDecimal` with that scale, and Jackson's STRING shape writes `"0.00"`, `"0.0"` and `"100.0"`. A regression such as `coalesce(…, 0)` would render `"0"`, and R1, R3 and R4 catch it. Fallback: `setScale(2, RoundingMode.UNNECESSARY)` in `CategoryReportRow.from`, as the other responses do.
   - **d) Named parameters.** Spring's `NamedParameterUtils` skips `--` comments and quoted text, so `:month` is the only parameter. The SQL deliberately uses `cast(… as date)` instead of `::`. Unlike Spring Data `@Query`, this SQL is **not** validated at startup, so a syntax error fails the report ITs, not the context.
   - **e) Rounding.** Postgres `round(numeric, int)` rounds ties away from zero. R7 pins this with 87.75 → 87.8 and 12.25 → 12.3.
2. **R2. Consistency.** A single statement means a single snapshot, so `totalAmount` always equals the sum of the rows' `amount`. No `@Transactional` is needed. This is an advantage of the one-query design over "rows query plus totals query".
3. **R3. "One query" is not asserted by a test.** It holds by construction: the repository makes exactly one `jdbc.sql(...)` call. Counting statements would need a DataSource proxy dependency, which is not worth it.
4. **R4. Extreme years.** `?month=+999999999-12` parses as a `YearMonth` but is outside Postgres' `date` range, so it surfaces as the generic 500. This is the same class of issue as budget-limits R3 and expense-list R4, and it is not addressed.
5. **R5. Shared test database.** `ReportControllerIT` owns the months 2016-01…2016-03, 2017-01…2017-12 and 2099-12 in the shared context.
   - Later cards whose ITs share this context must avoid those months, or assert with contains / does-not-contain. Card 6's `apply` creates expenses dated "today", which is safe.
   - The report ITs rely on `BudgetLimitControllerIT` staying out of these months.
6. **R6. Currency.** Amounts are summed regardless of `currency` (decision 13). This is correct while everything is `PLN`. Multi-currency would need grouping by currency, which is out of scope project-wide.
7. **R7. Follow-ups for card 9, with no change here:**
   - (a) By AC3, a category with neither expenses nor a limit is absent. The report alone therefore cannot offer "set a first limit" for it, so card 9 will also need `GET /api/categories`, although it names only two endpoints.
   - (b) Limit ids come from `GET /api/budget-limits` (Q5).
   - (c) Rows of archived categories will get 409 from the limit form (Q3).

   Raise these when card 9 is planned.
8. **R8. Precision of the share.**
   - Postgres numeric division keeps at least 16 significant digits before the one-decimal `round`.
   - Amounts have two decimals, so a share that is not exactly on a `x.x5` boundary is at least 1/(20 × total in grosze) away from it.
   - The rounding is therefore exact for any month total below 10^13 PLN.
9. **R9. Performance.** No new index is added.
   - The expense part is a range scan on the existing `(spent_on, category_id)` index.
   - The `budget_limit` part scans a table that holds a few rows per month (budget-limits Q5).

   This is enough at single-user volume.

## Plan review changes
Accepted by the user together with the plan:
1. Q1 (`share` as a one-decimal string) and Q3 (archived categories included, no `archived` flag) are explicitly confirmed, because they fix the contract card 9 consumes.
2. The first CTE is named `params`, not `period`. `PERIOD` is reserved in SQL:2011 and is a keyword in PG18 temporal syntax.
3. `ReportControllerIT` lists the months it owns in a class-level comment.
4. R11 inserts its expense through `JdbcTemplate` with `returning id`, so the id for `DELETE /api/expenses/{id}` is captured directly.

## Implementation notes
Deviations from the test table above, made after code review with the user's approval:
- **R4** uses three categories K, H, J (created in that order, limits 200.00, 50.00, 300.00) instead of H, I. With three tied rows, id order differs from name order and from limit order in both directions, so the test pins the `category id asc` tie-break (decision 6).
- **R6** names the tied categories so that ascending name order disagrees with id order (N2 `R6-Y2`, N3 `R6-A3`, N4 `R6-Z4`, N5 `R6-B5`) and swaps the N4/N5 limits (500.00 and 5.00). The expected id order is unchanged. The descending name and limit variants are ruled out by R4.

## Out of scope
- From the card: daily breakdown, month-over-month comparison, and the Grafana dashboard itself. This card only keeps the SQL in one file, ready for the first panel.
- The frontend "Month" page (card 9), and the card-9 follow-ups in R7.
- Filtering the report by category, multi-currency, extra row fields (`limitId`, `archived`, expense count), and forcing shares to sum to exactly 100 (Q7).
- Database views or functions, new migrations and new indexes.
- Caching, a service layer, `@Transactional`, and OpenAPI documentation.
- Bounding years (R4).
- Helm values, `CLAUDE.md`, `gradle.lockfile` and `docs/wallet-backlog.md` changes.
