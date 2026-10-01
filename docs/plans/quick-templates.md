# Plan: Quick templates

Trello card: https://trello.com/c/AP82ipCY/14-quick-templates
Part of the "Wallet" feature: expenses by category with monthly limits.

> **Revision 3 (2026-10-01).** Revision 2 did the following: it settled Q5 with alternative (b) (`QuickTemplateCategory` with `archived`), confirmed Q1–Q4 and Q6–Q9, corrected the transaction wording, documented and tested 415 on apply, added the `@Digits` overflow inputs, fixed the `createdAt` example, and added two Jackson input rules.
>
> Changes from Revision 2:
> - **The empty-string rule is dropped.** Revision 2's decision 15, its section 9 (`JacksonInputConfiguration`) and Q11 are removed, and Jackson's default applies: `""` in a numeric JSON field is read as `null`.
>   - The new decision 15 documents what follows from that: POST returns 400 with field-level `errors[]`, PATCH leaves the field unchanged, and apply does not override.
>   - T4, T11 and A5 pin it, and the README states it.
> - **Decision 16 is kept** (`spring.jackson.deserialization.accept-float-as-int=false`, now section 9). Its wording is corrected: every floating-point token is rejected for integer fields, including whole values such as `7.0` and `1e1`.
> - **New cases in T4 and T11:** blank strings (`"   "`) for amount, sortOrder and categoryId, and whole-valued floats (`1.0`, `<id>.0`) for sortOrder and categoryId. T4, T11 and A5 are renamed, because they now also contain cases that are not errors.
> - **New "behaviour pinning rule" in Tests:** if a test observes something other than the documented outcome, the result is reported, not silently adapted.
> - **"Effect on existing endpoints"** now lists only the float-as-int rows. No existing test changes.

## Goal
Add the `quick_template` table (Flyway `V5`) and a `/api/quick-templates` resource with `POST`, `GET` (the list and one by id), `PATCH`, `DELETE` and `POST /{id}/apply`.
- Apply creates exactly one expense dated "today" (the `Clock` bean in `APP_TIME_ZONE`) with the template's amount and category. The amount and the note can optionally be overridden.
- Archived categories return 409 on create, on a category switch and on apply.
- The active-category check, which now has three callers, is extracted from `ExpenseController` and `BudgetLimitController`.

## Acceptance criteria
- [ ] POST, GET, PATCH, DELETE on /api/quick-templates; GET returns templates sorted by sortOrder with embedded category
- [ ] PATCH can change name, amount, categoryId and sortOrder
- [ ] POST /api/quick-templates/{id}/apply creates an expense dated today with the template's amount and category; an optional body may override amount and note; returns 201 with the created expense
- [ ] Creating or applying a template for an archived category returns 409
- [ ] Integration tests: apply creates exactly one expense, override works, archived category is rejected

### Decisions not stated on the card (confirmed by the user on 2026-10-01)
No acceptance criterion is blocked. Every decision below is confirmed: Q1–Q4 and Q6–Q9 as proposed, Q5 with alternative (b), and decisions 15 and 16 as decided by the user in revisions 2 and 3.

1. **Table `quick_template`.** The table name is singular, per CLAUDE.md. The migration file keeps the card's name, `V5__create_quick_templates_table.sql`, as V2–V4 did. `docs/wallet-backlog.md` names both the file and the table.
2. **`sortOrder` is required on POST (`@NotNull Integer`).**
   - Any 32-bit integer is accepted, including negative ones.
   - Any floating-point JSON token (`1.5`, `1.0`, `1e1`) returns 400 (decision 16). `""` is read as `null` (decision 15).
   - The column is `int not null` with no default and is **not unique**. GET breaks ties by `id asc`.
   - Rationale:
     - The card says sortOrder "is set explicitly". So there is no hidden `max + 1` query, and no race between concurrent creates.
     - Not unique, so swapping two templates is just two PATCHes.
     - Negative values let a client put a template first without renumbering the others.
3. **`name` is required and not unique.**
   - It is stripped in the record's compact constructor and must be 1–64 characters after stripping (`@NotBlank @Size(max = 64)`). These are the category-name rules.
   - Not unique: the card and the backlog list `name varchar(64)` without "unique", while they do say "unique" for `category.name`.
4. **`amount` follows the expense amount rules.**
   - Constraints: `@Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99")`, plus `@NotNull` on POST.
   - Java type: `BigDecimal`.
   - Input: a JSON string or a JSON number. `""` is read as `null` (decision 15).
   - Output: a JSON string with two decimals (`@JsonFormat(shape = STRING)` plus `setScale(2, UNNECESSARY)`).
5. **Category on POST:**
   - An unknown `categoryId` returns 400 `errors[{categoryId, "Category not found"}]`.
   - An archived category returns 409 `"Category is archived"` (AC4).
   - The texts are the same as in expense-crud decisions 3 and 4.
6. **PATCH is a partial update of `name`, `amount`, `categoryId` and `sortOrder`.**
   - An omitted or `null` field means "unchanged". `{}` returns 200 and changes nothing.
   - **`""` for `amount`, `sortOrder` or `categoryId` is read as `null` by Jackson (decision 15), so it also means "unchanged" (200).**
     - CLAUDE.md's PATCH rule ("`""` means clear") is about optional string fields. These are numeric fields, and they are required, so they could not be cleared anyway.
   - `name` is stripped. `""` or a blank name returns 400, because a required string field cannot be cleared (as in `PATCH /api/categories`).
   - Changing `categoryId` to a **different** category runs the full check: unknown → 400, archived → 409.
   - Sending the **current** `categoryId` is accepted without a check, even if that category has been archived since. This matches expense-crud decision 3.
7. **`GET /api/quick-templates/{id}` is added, and POST returns `Location: …/api/quick-templates/{id}`.** Category-crud decision 4 requires `Location` to point at a resource that exists.
8. **(Q5, alternative (b).) Templates embed their category through a dedicated record, `QuickTemplateCategory(Long id, String name, String icon, boolean archived)`.**
   - Every template response uses it: POST, GET list, GET by id and PATCH.
   - `GET /api/quick-templates` returns a bare JSON array of **all** templates, sorted by `sortOrder asc, id asc`, with no paging or filter.
   - **Templates whose category is archived are included, with `category.archived: true`.** Card 8 can then disable their buttons.
   - `CategorySummary` is **not** changed: expenses, budget limits and the report use it, and their tests assert that `category.archived` is absent.
   - The apply response is the unchanged `ExpenseResponse`.
9. **Apply:**
   - **The body is optional.** All of these mean "no override":
     - no body;
     - an empty JSON body;
     - `{}`;
     - `null` fields;
     - **`"amount": ""`**, which Jackson reads as `null` (decision 15). This is consistent with CLAUDE.md's normalisation of `""` to `null` in POST.
   - **Content type:** a body must be sent as `Content-Type: application/json`. Any other Content-Type, or a non-empty body without one, returns **415** (Spring MVC default, documented and tested).
   - **Fields:**
     - `amount` follows decision 4.
     - `note` follows the expense note rules: `@Size(max = 255)`, `""` → `null` (CLAUDE.md), not stripped.
     - Other properties (`spentOn`, `categoryId`, `currency`, …) are ignored.
   - **The expense gets:**
     - `spentOn = LocalDate.now(clock)`;
     - the template's category;
     - the override amount, or else the template's amount;
     - the override note, or else `null`;
     - `currency` from the DB default (`PLN`).
   - **Response:** 201 with the same `ExpenseResponse` that `POST /api/expenses` returns, plus `Location: …/api/expenses/{expenseId}`.
   - The template is never modified.
10. **Applying a template whose category is archived returns 409 `"Category is archived"`.**
    - This is checked on every apply, against the category's current state.
    - Such templates can still be listed (with `category.archived: true`), read, patched (decision 6) and deleted.
11. **Each apply makes exactly one write:** one `expenses.saveAndFlush(new Expense(...))`, with no `@Transactional` (project convention).
    - Apply is not idempotent: two calls create two expenses.
    - Expenses do not reference the template. Deleting or editing a template never changes existing expenses.
12. **The active-category check is extracted into a new `@Component` `dev.katran.pet.category.ActiveCategoryLookup`** (scheduled by budget-limits decision 14 / Q6). Messages and status codes do not change.
13. **Schema beyond the card text:**
    - `not null` on every column.
    - FK with `on delete restrict`.
    - No unique constraint, no extra index, no `created_at`.
14. **Names:**
    - Package `dev.katran.pet.quicktemplate`.
    - Classes `QuickTemplate*` (including `QuickTemplateCategory`) and `ActiveCategoryLookup`.
    - Constraints `fk_quick_template_category` and `ck_quick_template_amount_positive`.
    - 404 detail `"Quick template not found"`.
    - Action path `/{id}/apply`.
15. **(Revision 3.) `""` in a numeric JSON field keeps Jackson's default: it is read as `null`.** No configuration is added.
    - Resulting semantics:
      - **POST `/api/quick-templates`:** `"amount": ""`, `"categoryId": ""` and `"sortOrder": ""` return 400 with the field-level error `errors[{field, "must not be null"}]`. This is the same body as sending `null`, and the same as on `POST /api/expenses` today.
      - **PATCH:** `""` for `amount`, `categoryId` or `sortOrder` means "unchanged" (200, nothing changes).
      - **Apply:** `"amount": ""` means "no override".
    - Rationale:
      - It keeps the field-level `errors[]` that existing endpoints return today, which card 7 needs to show errors next to fields.
      - It matches CLAUDE.md's normalisation of `""` to `null` in POST.
      - It needs no code.
    - **Blank strings (`"   "`)** are expected, but not verified for jackson-databind 3.1.7, to be coerced the same way. The alternative outcome is a 400 without `errors[]`. T4 and T11 pin whichever is observed, and the implementer reports it (Tests, "Behaviour pinning rule").
16. **Integer JSON fields reject every floating-point number token.** This covers `categoryId` and `sortOrder`, and `categoryId` on existing endpoints.
    - Both fractional (`1.5`, `7.9`) and whole-valued (`1.0`, `7.0`, `1e1`) tokens return 400 without `errors[]`. Without the rule, Jackson truncates (`7.9` → 7) or converts (`7.0` → 7, `1e1` → 10).
    - Not affected: integer tokens (`7`) and JSON strings (`"7"`).
    - Implemented with the Boot property `spring.jackson.deserialization.accept-float-as-int=false` (section 9).
    - Rationale:
      - Configuration only, and it breaks no existing test (see "Effect of section 9 on existing endpoints").
      - Silent truncation can store a value the client did not send.
      - It resolves expense-crud Risk 7.
    - Cost: a client that sends a whole number as `7.0` gets a 400. Ids and sortOrder are integers by contract, so this is accepted.

## Changes

### Existing state (verified in the code)
- **Migrations:** V1–V4 exist, so **V5 is free**. `docs/wallet-backlog.md` reserves `V5__create_quick_templates_table.sql` and the table `quick_template`.
- **Active-category check:** `ExpenseController.activeCategory(Long)` and `BudgetLimitController.activeCategory(Long)` are identical private copies.
  - An unknown id → `InvalidFieldException("categoryId", "Category not found")`.
  - An archived category → `ConflictException("Category is archived")`.
  - `BudgetLimitController` uses `CategoryRepository` only in that helper.
  - No test constructs either controller directly.
- **Expense creation path:**
  - `Expense(Category, BigDecimal, LocalDate, String)` is a public constructor, `ExpenseResponse.from(Expense)` is public static, and `ExpenseRepository` is a public interface.
  - `Expense.currency` is `@Generated`.
  - `Expense.createdAt` is Hibernate's `@CreationTimestamp`, which uses JVM time, not the `Clock` bean.
- **"Today":** `ClockConfiguration` provides `Clock clock(@Value("${app.time-zone}") ZoneId)`. `ExpenseController.list` uses `YearMonth.now(clock)`.
- **`CategorySummary(Long id, String name, String icon)`** has no `archived`. These tests assert that `$.category.archived` does not exist: expense test 2, budget-limit B1 and B11, report R1 and R8.
- **Request conventions:**
  - `CreateCategoryRequest` and `UpdateCategoryRequest`: the name is stripped, with `@Size(min = 1)` in PATCH.
  - `ExpenseRequest`: amount constraints, and a `note` with `""` → `null`.
- **JSON request fields of non-string types today:**
  - `ExpenseRequest`: `BigDecimal amount`, `Long categoryId`, `LocalDate spentOn`.
  - `BudgetLimitRequest`: `Long categoryId`, `YearMonth month`, `BigDecimal amount`.
  - `UpdateCategoryRequest`: `Boolean archived`.
  - Query-parameter records (`ExpenseListQuery`, `BudgetLimitListQuery`, `CategoryReportQuery`) are bound by Spring's `WebDataBinder`, not by Jackson.
- **Jackson configuration:**
  - `application.properties` has no `spring.jackson.*` property, and main code has no Jackson customizer.
  - On the classpath: `org.springframework.boot:spring-boot-jackson:4.1.1` and `tools.jackson.core:jackson-databind:3.1.7`.
- **Existing tests near decisions 15 and 16:**
  - `ExpenseControllerIT.rejectsUnparseableAmount` sends `"amount": ""` and asserts only status 400, the Problem Details content type and `instance`. It passes whether Jackson reads `""` as `null` or rejects it, and it is not changed.
  - No test sends a floating-point token for `categoryId`.
  - `ExpenseListIT` `categoryIds=1.5` is a query parameter, converted by Spring, not by Jackson.
- **`ApiExceptionHandler`:**
  - `MethodArgumentNotValidException` → 400 with sorted `errors[]`.
  - `InvalidFieldException` → 400 with one error.
  - `ConflictException` → 409.
  - `NotFoundException` → 404 with `id`.
  - Known constraints → 409.
  - Catch-all → 500.
  - Because it extends `ResponseEntityExceptionHandler`: `HttpMessageNotReadableException` → 400 without `errors[]`, and `HttpMediaTypeNotSupportedException` → 415.
- **Architecture:**
  - No service layer.
  - Main code has no `@Transactional` and no `@Component`/`@Service`.
  - OSIV is on (Boot default) and is not relied upon.
- **Tests:**
  - `TestcontainersConfiguration` declares `postgres:17` as a bean, so each distinct context starts its own container.
  - `ExpenseControllerIT` and `ExpenseListIT` each have their own context (`@TestBean Clock`).
- **Versions:** Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Data JPA 4.1.1, Hibernate ORM 7.4.5, jackson-databind 3.1.7.
- **Frontend:** no quick-template code.

### API contract

| Method | Path | Request | Success | Errors (all `application/problem+json`) |
|---|---|---|---|---|
| `POST` | `/api/quick-templates` | `{"name": "Coffee", "categoryId": 7, "amount": "12.50", "sortOrder": 0}` (all required) | `201`, `QuickTemplateResponse`, `Location: …/api/quick-templates/{id}` | `400` validation (`errors[]`), including `""` for `amount`/`categoryId`/`sortOrder`, which is read as `null` and gives `errors[{field, "must not be null"}]`; `400` unreadable body, including any floating-point token for `sortOrder`/`categoryId` (`1.5`, `1.0`, `1e1`) (no `errors[]`); `400` unknown category (`errors[categoryId]`); `409` archived category |
| `GET` | `/api/quick-templates` | | `200`, JSON array of `QuickTemplateResponse`, sorted by `sortOrder`, then `id`; `[]` if none | |
| `GET` | `/api/quick-templates/{id}` | | `200`, `QuickTemplateResponse` | `404` unknown id; `400` non-numeric id |
| `PATCH` | `/api/quick-templates/{id}` | any subset of `{"name", "amount", "categoryId", "sortOrder"}`; `null`, or `""` for the numeric fields, means unchanged | `200`, updated `QuickTemplateResponse` | `400` validation; `400` unreadable, including any floating-point token for `sortOrder`/`categoryId`; `404` unknown id; `400` unknown category; `409` switching to a different archived category |
| `DELETE` | `/api/quick-templates/{id}` | | `204`, empty body | `404` unknown id; `400` non-numeric id |
| `POST` | `/api/quick-templates/{id}/apply` | no body, or `Content-Type: application/json` with optional `{"amount": "15.00", "note": "Latte"}`; `null` or `""` amount means no override | `201`, `ExpenseResponse` (same as `POST /api/expenses`), `Location: …/api/expenses/{expenseId}` | `400` validation; `400` unreadable; `415` a body with another Content-Type, or a non-empty body without one; `404` unknown template; `400` non-numeric id; `409` the template's category is archived |

- Other methods on these paths are not mapped, so Spring MVC answers with 405 Problem Details.
- `POST` and `PATCH` also return 415 for a non-JSON Content-Type. This is the Spring MVC default, not specific to this card.

**Template response** (POST, PATCH, GET by id, and each GET list item):
```json
{"id": 4, "name": "Coffee", "amount": "12.50", "sortOrder": 0,
 "category": {"id": 7, "name": "Food", "icon": "cart", "archived": false}}
```
- There is no `categoryId`.
- `category.icon` may be `null`.
- `category.archived: true` means apply returns 409. Card 8 disables such buttons.

**Apply response** (the unchanged `ExpenseResponse`, whose category has no `archived`):
```json
{"id": 42, "amount": "12.50", "currency": "PLN", "spentOn": "2026-07-01", "note": null,
 "createdAt": "2026-10-01T08:15:30.123456Z", "category": {"id": 7, "name": "Food", "icon": "cart"}}
```
- `spentOn` is "today" from the `Clock` bean in `APP_TIME_ZONE`.
- `createdAt` is set by Hibernate's `@CreationTimestamp` from JVM time and has nothing to do with the `Clock` bean. With the fixed test clock the two dates differ, as in this example.

**Error examples:**
```json
{"type": "about:blank", "title": "Conflict", "status": 409, "detail": "Category is archived", "instance": "/api/quick-templates/4/apply"}
```
```json
{"type": "about:blank", "title": "Not Found", "status": 404, "detail": "Quick template not found", "instance": "/api/quick-templates/99", "id": 99}
```
```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Invalid request content.",
 "instance": "/api/quick-templates", "errors": [{"field": "amount", "message": "must not be null"}]}
```
(This is POST with `"amount": ""`. The message is Hibernate Validator's default English text for `@NotNull`.)
```json
{"type": "about:blank", "title": "Unsupported Media Type", "status": 415, "detail": "Content-Type 'text/plain;charset=UTF-8' is not supported.", "instance": "/api/quick-templates/4/apply"}
```
(The 415 `detail` text comes from Spring and is not asserted.)

**Evaluation order** (this also documents precedence):
- **POST:**
  1. Non-JSON Content-Type → 415.
  2. Unreadable body → 400 without `errors[]`.
  3. Bean Validation → 400 with all field errors, sorted.
  4. Unknown `categoryId` → 400 `errors[categoryId]`.
  5. Archived → 409.
  6. Insert → 201.

  So an archived category together with `amount: 0` returns 400.
- **PATCH:**
  1. Non-numeric id → 400.
  2. Non-JSON Content-Type → 415.
  3. Unreadable body → 400.
  4. Bean Validation → 400. So `{"amount": 0}` on an unknown id returns 400, not 404.
  5. Unknown template → 404.
  6. Only if `categoryId` is present and differs: unknown → 400, archived → 409.
  7. Setters, then 200.
- **Apply:**
  1. Non-numeric id → 400.
  2. A body with a non-JSON Content-Type, or a non-empty body without one → 415.
  3. Unreadable body → 400.
  4. Bean Validation, only when a body is present → 400.
  5. Unknown template → 404.
  6. Category archived → 409.
  7. Insert expense → 201.

### Transaction boundaries
This follows the project convention: no `@Transactional` and no service layer.
- **Methods inherited from `SimpleJpaRepository`** run in their own transaction, because that class is annotated: `findById` read-only, `saveAndFlush` and `delete` read-write.
- **Query methods declared on a repository interface get no transaction by default.**
  - This applies to `QuickTemplateRepository.findWithCategoryById` and `findAllByOrderBySortOrderAscIdAsc`, as it already does to `ExpenseRepository.findWithCategoryById` and the `BudgetLimitRepository` finders.
  - With OSIV (on), they run on the request's `EntityManager`, outside a transaction, in autocommit mode: one `SELECT` each.
  - Without OSIV, a shared `EntityManager` would be created for the call. A single select behaves the same either way.
- **`ActiveCategoryLookup.getActive`** calls the inherited `categories.findById`, so it runs in a read-only transaction.

Per endpoint:
- **POST:**
  1. `getActive` (read-only transaction).
  2. `templates.saveAndFlush`: one transaction, one `INSERT`.
- **GET list / GET by id:** a single autocommit `SELECT … JOIN category` (entity graph).
- **PATCH:**
  1. `findWithCategoryById`: an autocommit `SELECT`. The entity stays managed on the OSIV `EntityManager`.
  2. `getActive`, only for a category switch (read-only transaction).
  3. **Every check that can throw runs before the first setter.**
  4. `saveAndFlush`: one transaction, one `UPDATE`. On failure it rolls back.
- **DELETE:**
  1. `findById` (read-only transaction). It is needed for the 404.
  2. `delete`: one transaction.
- **Apply:**
  1. `findWithCategoryById`: an autocommit `SELECT … JOIN category`.
  2. The archived check needs no I/O.
  3. **Exactly one write:** `expenses.saveAndFlush(new Expense(...))`, one transaction with one `INSERT`.
  4. The template and category are not modified, so with OSIV the flush emits no other statement.
- **Concurrency:** a category archived between the read and the insert can still receive the expense. This race is accepted, as in expense-crud Risk 2 and Q7.

### 1. New file: `backend/src/main/resources/db/migration/V5__create_quick_templates_table.sql`
```sql
-- One-tap expense presets. Templates are deleted, not archived: expenses created from a
-- template copy its values and do not reference it, so deleting a template loses no history.
create table quick_template (
    id          bigint generated always as identity primary key,
    name        varchar(64)    not null,
    category_id bigint         not null,
    amount      numeric(12, 2) not null,
    sort_order  int            not null,
    constraint fk_quick_template_category foreign key (category_id) references category (id) on delete restrict,
    constraint ck_quick_template_amount_positive check (amount > 0)
);
```
- **No `ApiExceptionHandler` mapping for these constraints.** The API cannot violate them:
  - `@Positive` covers the amount check.
  - The category lookup covers the FK.
  - Categories are never deleted.

  If one is ever hit, the result is the generic 500.

### 2. New file: `backend/src/main/java/dev/katran/pet/category/ActiveCategoryLookup.java`
```java
// Resolves a categoryId from a request to a category that may receive new data
// (expenses, budget limits, quick templates).
@Component
public class ActiveCategoryLookup {

	private final CategoryRepository categories;

	public ActiveCategoryLookup(CategoryRepository categories) {
		this.categories = categories;
	}

	// 400 errors[categoryId] for an unknown id; 409 for an archived category.
	public Category getActive(Long categoryId) {
		Category category = categories.findById(categoryId)
				.orElseThrow(() -> new InvalidFieldException("categoryId", "Category not found"));
		return requireNotArchived(category);
	}

	public Category requireNotArchived(Category category) {
		if (category.isArchived()) {
			throw new ConflictException("Category is archived");
		}
		return category;
	}
}
```
- The method bodies and messages are copied from the existing helper.
- `requireNotArchived` serves apply, where the category is already loaded with the template.
- It is the first `@Component` in main code (Q6, confirmed).

### 3. Change: `backend/src/main/java/dev/katran/pet/expense/ExpenseController.java`
- Inject `ActiveCategoryLookup activeCategories` as an extra constructor parameter. Keep `CategoryRepository`, which `requireExistingCategories` still uses.
- `create` and `update` call `activeCategories.getActive(request.categoryId())`.
- Delete the private `activeCategory` and the unused `ConflictException` import.
- Nothing else changes.

### 4. Change: `backend/src/main/java/dev/katran/pet/budgetlimit/BudgetLimitController.java`
- Replace the `CategoryRepository` dependency with `ActiveCategoryLookup`.
- `upsert` calls `activeCategories.getActive(request.categoryId())`.
- Delete the private `activeCategory` and the unused imports.

### 5. New file: `backend/src/main/java/dev/katran/pet/quicktemplate/QuickTemplate.java`
A JPA entity in the style of `Expense`:
- `@Entity @Table(name = "quick_template")` with `@Id @GeneratedValue(strategy = IDENTITY) Long id`.
- `@Column(nullable = false, length = 64) String name`.
- `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false) Category category`. It is updatable, because PATCH can change it.
- `@Column(nullable = false, precision = 12, scale = 2) BigDecimal amount`.
- `@Column(name = "sort_order", nullable = false) int sortOrder`.
- A protected no-arg constructor, and `public QuickTemplate(String name, Category category, BigDecimal amount, int sortOrder)`.
- Getters for all fields. Setters for `name`, `category`, `amount` and `sortOrder`.
- No Bean Validation annotations on the entity.

### 6. New file: `backend/src/main/java/dev/katran/pet/quicktemplate/QuickTemplateRepository.java`
```java
public interface QuickTemplateRepository extends JpaRepository<QuickTemplate, Long> {

	@EntityGraph(attributePaths = "category")
	Optional<QuickTemplate> findWithCategoryById(Long id);

	@EntityGraph(attributePaths = "category")
	List<QuickTemplate> findAllByOrderBySortOrderAscIdAsc();
}
```
- These are derived queries. As declared query methods, they are not transactional (see "Transaction boundaries").
- The entity graph loads the category in the same join, so there is no N+1 and no dependency on OSIV.
- `findById` and `delete` are inherited.
- See R1b for the parsing risk of a property named `sortOrder`.

### 7. New files: request and response records (`dev.katran.pet.quicktemplate`)
```java
public record CreateQuickTemplateRequest(
		@NotBlank @Size(max = 64) String name,
		@NotNull Long categoryId,
		@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		@NotNull Integer sortOrder) {

	public CreateQuickTemplateRequest {
		name = name == null ? null : name.strip();
	}
}

public record UpdateQuickTemplateRequest(
		@Size(min = 1, max = 64) String name,
		Long categoryId,
		@Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		Integer sortOrder) {

	public UpdateQuickTemplateRequest {
		name = name == null ? null : name.strip();
	}
}

public record ApplyQuickTemplateRequest(
		@Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		@Size(max = 255) String note) {

	public ApplyQuickTemplateRequest {
		note = note == null || note.isEmpty() ? null : note;
	}
}

// Embedded category of a template. Unlike CategorySummary it carries "archived", because
// applying a template of an archived category returns 409 and clients disable it (card 8).
public record QuickTemplateCategory(Long id, String name, String icon, boolean archived) {

	public static QuickTemplateCategory from(Category category) {
		return new QuickTemplateCategory(category.getId(), category.getName(), category.getIcon(),
				category.isArchived());
	}
}

public record QuickTemplateResponse(
		Long id,
		String name,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		int sortOrder,
		QuickTemplateCategory category) {

	public static QuickTemplateResponse from(QuickTemplate template) {
		return new QuickTemplateResponse(template.getId(), template.getName(),
				template.getAmount().setScale(2, RoundingMode.UNNECESSARY), template.getSortOrder(),
				QuickTemplateCategory.from(template.getCategory()));
	}
}
```
- Create and Update are separate records, following the `category` pair.
- Name stripping runs during deserialization, before validation.
- The amount is not rescaled in the request.
- `ApplyQuickTemplateRequest` has no other components, so unknown properties are ignored.
- `QuickTemplateCategory` lives in `quicktemplate`, because only this resource exposes `archived` in an embedded category (decision 8).
- Static factories are used, not MapStruct.
- **`""` handling needs no code:** Jackson reads `""` in a numeric component as `null` (decision 15). Then:
  - `@NotNull` reports it on POST;
  - PATCH treats it as "unchanged";
  - apply treats it as "no override".

### 8. New file: `backend/src/main/java/dev/katran/pet/quicktemplate/QuickTemplateController.java`
`@RestController @RequestMapping("/api/quick-templates")`. The constructor takes `QuickTemplateRepository templates`, `ActiveCategoryLookup activeCategories`, `ExpenseRepository expenses` and `Clock clock`.
```java
@PostMapping
public ResponseEntity<QuickTemplateResponse> create(@Valid @RequestBody CreateQuickTemplateRequest request) {
	Category category = activeCategories.getActive(request.categoryId());
	QuickTemplate saved = templates.saveAndFlush(
			new QuickTemplate(request.name(), category, request.amount(), request.sortOrder()));
	URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}").buildAndExpand(saved.getId()).toUri();
	return ResponseEntity.created(location).body(QuickTemplateResponse.from(saved));
}

@GetMapping
public List<QuickTemplateResponse> list() {
	return templates.findAllByOrderBySortOrderAscIdAsc().stream().map(QuickTemplateResponse::from).toList();
}

@GetMapping("/{id}")
public QuickTemplateResponse get(@PathVariable Long id) {
	return templates.findWithCategoryById(id).map(QuickTemplateResponse::from)
			.orElseThrow(() -> new NotFoundException("Quick template", id));
}

@PatchMapping("/{id}")
public QuickTemplateResponse update(@PathVariable Long id, @Valid @RequestBody UpdateQuickTemplateRequest request) {
	QuickTemplate template = templates.findWithCategoryById(id)
			.orElseThrow(() -> new NotFoundException("Quick template", id));
	// Every check that can throw runs before the first setter (OSIV, expense-crud section 7).
	if (request.categoryId() != null && !request.categoryId().equals(template.getCategory().getId())) {
		template.setCategory(activeCategories.getActive(request.categoryId()));
	}
	if (request.name() != null) template.setName(request.name());
	if (request.amount() != null) template.setAmount(request.amount());
	if (request.sortOrder() != null) template.setSortOrder(request.sortOrder());
	templates.saveAndFlush(template);
	return QuickTemplateResponse.from(template);
}

@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable Long id) {
	QuickTemplate template = templates.findById(id).orElseThrow(() -> new NotFoundException("Quick template", id));
	templates.delete(template);
}

@PostMapping("/{id}/apply")
public ResponseEntity<ExpenseResponse> apply(@PathVariable Long id,
		@Valid @RequestBody(required = false) ApplyQuickTemplateRequest request) {
	QuickTemplate template = templates.findWithCategoryById(id)
			.orElseThrow(() -> new NotFoundException("Quick template", id));
	Category category = activeCategories.requireNotArchived(template.getCategory());
	BigDecimal amount = request != null && request.amount() != null ? request.amount() : template.getAmount();
	String note = request != null ? request.note() : null;
	Expense saved = expenses.saveAndFlush(new Expense(category, amount, LocalDate.now(clock), note));
	URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
			.path("/api/expenses/{id}").buildAndExpand(saved.getId()).toUri();
	return ResponseEntity.created(location).body(ExpenseResponse.from(saved));
}
```
- **PATCH:** the response is built from the instance the controller holds (expense decision 7.2). The `if` uses braces in real code.
- **Apply:**
  - **Missing body:** `@RequestBody(required = false)` turns a missing body, or an empty body with a JSON Content-Type, into `null`. `@Valid` runs only on a non-null argument (R1a).
  - **415:** a body with a non-JSON Content-Type finds no message converter. A non-empty body without a Content-Type is treated as `application/octet-stream`. Both cases raise `HttpMediaTypeNotSupportedException`, which becomes the 415 Problem Detail.
  - **Response:** `Location` points at the created expense.

### 9. Change: `backend/src/main/resources/application.properties`
Add:
```properties
# JSON integer fields (categoryId, sortOrder) reject every floating-point number token,
# including whole values such as 7.0 or 1e1, instead of truncating or converting them
spring.jackson.deserialization.accept-float-as-int=false
```
- This is Boot's standard binding of Jackson's `DeserializationFeature` map.
- With the feature disabled, Jackson's coercion from a float token to an integer type fails. Spring turns that into `HttpMessageNotReadableException`, which becomes the existing 400 Problem Detail without `errors[]`.
- It affects only JSON request bodies with `Integer`/`Long` targets. `BigDecimal` amounts, query parameters and serialization are unaffected.
- If Jackson 3.1.7 already defaults this feature to `false`, the line only makes the choice explicit. T4 and T11 pin the behaviour either way.

### Effect of section 9 on existing endpoints
This is the only behaviour change outside `/api/quick-templates`:

| Endpoint | Input | Before | After |
|---|---|---|---|
| `POST`/`PUT /api/expenses`, `PUT /api/budget-limits` | `"categoryId": 7.9` (fractional float token) | Truncated to 7, then used for the lookup and stored (expense-crud Risk 7) | 400 without `errors[]` |
| same | `"categoryId": 7.0` or `7e0` (whole-valued float token; `1e1` → 10 likewise) | Accepted as 7 | 400 without `errors[]` |

**Not affected:**
- `""` in any field (Jackson's default stays).
- Integer tokens (`7`) and numeric strings (`"7"`).
- `BigDecimal` amounts.
- Dates.
- `Boolean`.
- Strings.
- Query parameters (`GET /api/expenses?categoryIds=1.5` keeps its `errors[{categoryIds}]` 400).
- Serialization.

**Existing tests:** none needs a change, and none is edited. No test sends a float token for `categoryId`. Step 2 of the implementation order runs the full suite right after this change to prove it.

### 10. Change: `README.md`
**API table:** add these rows after the report row:
- `| POST | /api/quick-templates | Creates a quick template ({"name": "Coffee", "categoryId": 1, "amount": "12.50", "sortOrder": 0}); all fields required; name is stripped (1-64 chars, not unique); amount > 0 with at most two decimals; sortOrder is an integer; 400 on unknown category, 409 on archived category |`
- `| GET | /api/quick-templates | Lists all quick templates sorted by sortOrder, then id, with the category embedded as {id, name, icon, archived}; templates of archived categories are included with archived: true (applying them returns 409) |`
- `| GET | /api/quick-templates/{id} | Returns one quick template; 404 if unknown |`
- `| PATCH | /api/quick-templates/{id} | Updates name, amount, categoryId and/or sortOrder; omitted or null fields, and "" for amount, categoryId or sortOrder, are left unchanged; 409 when switching to an archived category |`
- `| DELETE | /api/quick-templates/{id} | Deletes a template; expenses created from it are kept; 204, or 404 if unknown |`
- `| POST | /api/quick-templates/{id}/apply | Creates an expense dated today (APP_TIME_ZONE) with the template's amount and category; optional JSON body {"amount": "...", "note": "..."} overrides amount and note (a null or "" amount means no override; a body without Content-Type application/json is 415); 201 with the created expense (same body as POST /api/expenses); 409 if the template's category is archived |`

**`APP_TIME_ZONE` paragraph:** extend the last sentence to "…lists by default, and the date of expenses created by `POST /api/quick-templates/{id}/apply`."

**Money paragraph:** add after the `share` sentence:

"In JSON request bodies, an empty string (`""`) in a numeric field is read as `null`: a required field then fails with `errors[{field, "must not be null"}]`, a PATCH field stays unchanged, and an apply override is not applied. Integer fields such as `categoryId` and `sortOrder` reject any JSON number with a fraction or an exponent (`1.5`, `7.0`, `1e1`) with 400 without `errors[]`."

### 11. Not changed
- **No new dependencies.** Everything used is already on the classpath:
  - JPA and Bean Validation;
  - Jackson annotations and Boot's `spring.jackson.*` properties;
  - `ServletUriComponentsBuilder`, `java.time.Clock` and `@TestBean`.

  `backend/gradle.lockfile` is unchanged, so `./gradlew dependencies --write-locks` is not needed.
- **No Jackson customizer or configuration class** (the Revision 2 section 9 is dropped).
- No change to `ApiExceptionHandler`, `CategorySummary`, `Expense*` (apart from the controller refactor), `CategoryRepository` or `ClockConfiguration`.
- No change to any existing test, including `ExpenseControllerIT`.
- No frontend change (card 8), no Helm change, no `CLAUDE.md` change, no change to `docs/wallet-backlog.md`.

### Implementation order
1. Add the V5 migration. Run `cd backend && ./gradlew test`; the existing tests stay green.
2. Add the property (section 9). Run `./gradlew test`: every existing test must stay green unchanged, which confirms the "Effect" table.
3. Add `ActiveCategoryLookup` and switch `ExpenseController` and `BudgetLimitController` to it. Run `./gradlew test`.
4. Add `QuickTemplate` and `QuickTemplateRepository`.
5. Add the five records.
6. Add `QuickTemplateController`.
7. Write `QuickTemplateControllerIT`.
8. Update `README.md`.
9. `cd backend && ./gradlew test` must be green.

## Tests
Done means `cd backend && ./gradlew test` is green.

The existing tests stay unchanged and must still pass, including after section 9: `CategoryControllerIT`, `ExpenseControllerIT`, `ExpenseListIT`, `BudgetLimitControllerIT`, `ReportControllerIT`, `GreetingControllerIT`, `ApiExceptionHandlerIT`, `ApiExceptionHandlerTest`, `ClockConfigurationTest`, `PetApplicationTests` and the `db` tests.

These existing tests guard the extraction in sections 2–4, because they assert the exact texts:
- `ExpenseControllerIT`: `rejectsUnknownCategoryOnCreate`, `rejectsArchivedCategoryOnCreate`, `putKeepsCurrentArchivedCategory`, `putToDifferentArchivedCategoryReturns409` and `putUnknownCategoryReturns400`.
- `BudgetLimitControllerIT`: `rejectsUnknownCategory` and `archivedCategoryReturns409`.

No unit tests are added.

**Behaviour pinning rule.** Several cases pin framework behaviour that this plan documents rather than implements:
- decision 15 (`""` read as `null`);
- decision 16 (float tokens rejected);
- the optional body (A3);
- the 415 (A12).

Each such test asserts the documented outcome. **If the observed behaviour differs, the test-writer must not change the assertion, the README or this plan to match it. Instead, stop and report the observed response (status and body) to the user, who decides.**

The only exception is the blank-string cases (`"   "`) in T4 and T11, whose expected result is not certain:
- They assert one of the two allowed outcomes listed there, pinned to whichever is observed.
- The test-writer reports which one it was.
- Any third outcome (for example a 500) is a mismatch and is reported as above.

### New file: `backend/src/test/java/dev/katran/pet/quicktemplate/QuickTemplateControllerIT.java`
**Setup.** Copy `ExpenseControllerIT`:
- `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest(RANDOM_PORT)`, and a `RestTestClient` built in `@BeforeEach`.
- Autowired `QuickTemplateRepository`, `ExpenseRepository`, `CategoryRepository` and `JdbcTemplate`.
- A fixed clock:
  ```java
  // "Today" is 2026-07-01 in Europe/Warsaw (00:30 CEST) while it is still 2026-06-30 in UTC
  // (another day and another month), and far from the real system date.
  @TestBean
  Clock clock;

  static Clock clock() {
  	return Clock.fixed(Instant.parse("2026-06-30T22:30:00Z"), ZoneId.of("Europe/Warsaw"));
  }
  ```
  - `TODAY = "2026-07-01"`.
  - The class gets its own context and container (R5).

**Conventions:**
- **Setup data:**
  - Categories are created through `CategoryRepository` with unique names.
  - Setup templates are created through `QuickTemplateRepository`.
  - The behaviour under test always goes through the API.
- **Isolation:**
  - Lists are checked only through the ids a test created, plus whole-list ordering properties.
  - Expense counts are taken per category, or as `expenses.count()` before and after a single request.
- **Request bodies:** raw JSON strings or `HashMap`, never `Map.of`. Float tokens such as `<C.id>.0` are written into raw JSON strings.
- **Error tests:**
  - Assert `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`, `$.status` and `$.instance`.
  - 400 validation tests also assert `$.errors.length()`, every field, and a non-blank message. The message is never compared with a locale-dependent literal; where the plan says "must not be null", the test compares with the response to the same body with JSON `null`.
  - Jackson-level 400s assert that `$.errors` does not exist.
- **Values:**
  - Money is always asserted as a JSON string.
  - `null` values are asserted with `isEqualTo(null)`.
  - `createdAt` is only asserted to exist.
  - The unknown id is `Long.MAX_VALUE`.

| # | Test | Proves |
|---|---|---|
| T1 | `createsTemplateAndReturns201`:<br>- POST `{"name":"  Coffee-<uuid>  ","categoryId":C,"amount":"12.5","sortOrder":3}` → 201.<br>- Body: `$.id`; the stripped name; `$.amount == "12.50"`; `$.sortOrder == 3`; `$.category.id/name/icon` match C; `$.category.archived == false`; no `$.categoryId`.<br>- `Location` ends with `/api/quick-templates/{id}`, and a GET of it → 200 with the same body.<br>- DB row is correct.<br>- Also: `sortOrder` `-5` and `0` → 201. A 64-character name with surrounding spaces → 201. | AC1 (POST, GET by id), decisions 2, 3, 7, 8 |
| T2 | `acceptsAmountFormats` (parameterized): `"200"` → `"200.00"`, `"0.01"`, `"9999999999.99"`, JSON `200` → `"200.00"`, `19.99` → `"19.99"`. The DB value is `compareTo`-equal. | Decision 4, money rule |
| T3 | `rejectsInvalidCreate` (parameterized, raw JSON). Each → 400 with exactly these sorted `errors[].field`, and nothing is stored:<br>- name missing, `null`, `""`, `"   "` or `"\t\n"`, or 65 characters after strip → [name]<br>- categoryId missing or `null` → [categoryId]<br>- amount `0`, `"0.00"`, `-1`, `"1.234"`, `0.30000000000000004`, missing or `null` → [amount]<br>- amount `"1e2147483647"` and `1e2147483647` → [amount], **exactly one** error (`@DecimalMax`; `@Digits` overflows, as in expense test 6)<br>- `"10000000000"` → [amount, amount]<br>- sortOrder missing or `null` → [sortOrder]<br>- `{}` → [amount, categoryId, name, sortOrder] | Decisions 2, 3, 4; error format |
| T4 | `numericEdgeInputsOnCreate` (parameterized). Each body is otherwise valid, with active category C. Nothing is stored in any case.<br>**`""` (decision 15):** `"amount": ""`, `"categoryId": ""` and `"sortOrder": ""` → 400 with exactly one error for that field, and `$.errors` equal to the response for the same body with JSON `null` in that field (`@NotNull`, "must not be null" in the default locale).<br>**Blank strings (decision 15, outcome not certain):** `"amount": "   "`, `"categoryId": "   "` and `"sortOrder": "   "` → 400. Expected: the same as `""` (`errors[{field}]`). Allowed alternative: `$.errors` absent. Pin the observed outcome and report it.<br>**Float tokens (decision 16):** `"sortOrder": 1.5`, `1.0` and `1e1`; `"categoryId": <C.id>.0` and `<C.id>.5` → 400 with `$.errors` absent. `<C.id>.0` proves that only the token type is rejected, because the same integer value would be valid.<br>**Other unreadable input:** malformed `{"name":` → `$.errors` absent. amount `"abc"` or `true`, sortOrder `"abc"` or `3000000000`, categoryId `"abc"` → 400; `errors[]` is not asserted. | Decisions 15, 16; error format |
| T5 | `rejectsUnknownCategoryOnCreate`: `categoryId = Long.MAX_VALUE` → 400 with `$.detail == "Invalid request content."`, `$.errors == [{categoryId, "Category not found"}]` and `$.instance == "/api/quick-templates"`. Nothing is stored. | Decision 5 |
| T6 | `rejectsArchivedCategoryOnCreate`:<br>- Archived X → 409, `$.title == "Conflict"`, `$.detail == "Category is archived"`, `$.instance == "/api/quick-templates"`. No template references X.<br>- Precedence: X with `amount: 0` → 400 [amount]. | **AC4 (creating)**, decision 5 |
| T7 | `listIsSortedBySortOrderThenIdWithEmbeddedCategory`. Categories P (icon `cart`) and Q (icon `null`).<br>- Templates in creation order: A (P, 2), B (Q, 0), C (P, 1), D (Q, 1), E (P, 0). Then archive Q.<br>- `GET` → 200, a JSON array. The created ids appear in the relative order [B, E, C, D, A].<br>- Over the whole array, consecutive `(sortOrder, id)` pairs are strictly ascending.<br>- Item B has `category == {Q.id, Q.name, null, archived: true}`. Item A has `category.archived == false`. No item has `categoryId`.<br>- Rename P → GET shows the new name for A, C and E. | **AC1 (GET sorted, embedded category)**, decisions 2, 8 |
| T8 | `getUnknownOrNonNumericId`:<br>- `GET /{Long.MAX_VALUE}` → 404, `$.detail == "Quick template not found"`, `$.id`, `$.instance`.<br>- `GET /abc` → 400. | Decision 7 |
| T9 | `patchChangesEachFieldIndividually`. Template (P, `"Old-<uuid>"`, `"10.00"`, 5), and active R. After each step, the response, a following GET and the DB row show exactly the expected values:<br>- name only (stripped)<br>- `{"amount":"20.5"}` → `"20.50"`<br>- `{"categoryId":R}` → embedded R, `archived == false`<br>- `{"sortOrder":-1}`<br>- all four at once | **AC2** |
| T10 | `patchWithEmptyOrNullFieldsChangesNothing`: `{}` and `{"name":null,"amount":null,"categoryId":null,"sortOrder":null}` → 200; the response and the DB are unchanged. | Decision 6, CLAUDE.md PATCH rule |
| T11 | `patchValidationAndNumericEdgeInputs` (parameterized). Template (P, `"10.00"`, 5) and active R. `$.instance == "/api/quick-templates/{id}"` on every error. **The DB row is unchanged in every case.**<br>**Rejections (400):**<br>- name `""`, `"   "` or 65 characters → [name]<br>- amount `0`, `"-1"` or `"1.234"` → [amount]; `"10000000000"` → [amount, amount]<br>- malformed JSON → `$.errors` absent<br>- sortOrder `"abc"` → 400<br>**`""` (decision 15):** `{"amount":""}`, `{"categoryId":""}` and `{"sortOrder":""}` → **200**, with a response equal to the one before the PATCH (same as the `null` case in T10).<br>**Blank strings (decision 15, outcome not certain):** `{"amount":"   "}`, `{"categoryId":"   "}` and `{"sortOrder":"   "}`. Expected: 200 unchanged (like `""`). Allowed alternative: 400 with `$.errors` absent. Pin the observed outcome and report it.<br>**Float tokens (decision 16):** `{"sortOrder":1.5}`, `{"sortOrder":1.0}` and `{"categoryId":<R.id>.0}` → 400 with `$.errors` absent. sortOrder and category are unchanged. | AC2 (rules on PATCH), decisions 6, 15, 16 |
| T12 | `patchCategoryRules`:<br>- (a) An unknown `categoryId` → 400 `errors[{categoryId, "Category not found"}]`; row unchanged.<br>- (b) Archived X → 409; row unchanged.<br>- (c) `{"name":"Renamed-<uuid>","categoryId":X}` → 409, and the name is unchanged in the DB.<br>- (d) Archive P. `{"categoryId":P,"name":"Still-<uuid>","sortOrder":9}` → 200 with `$.category.archived == true`. Then `{"amount":"3"}` → 200.<br>- (e) `{"categoryId":R}` → 200 with `archived == false`. | AC4 (consistency on PATCH), decisions 6, 8 |
| T13 | `patchUnknownTemplate`:<br>- `PATCH /{Long.MAX_VALUE}` `{"name":"x"}` → 404.<br>- The same id with `{"amount":0}` → 400.<br>- `PATCH /abc` → 400. | Evaluation order |
| T14 | `deleteReturns204AndKeepsExpenses`. Template on C, applied once (expense E).<br>- `DELETE` → 204 with an empty body.<br>- GET → 404; the id is not in the list; a second DELETE → 404.<br>- `GET /api/expenses/{E}` → 200, unchanged. C still exists. | **AC1 (DELETE)**, decision 11 |
| T15 | `deleteEdgeCases`:<br>- A template of an archived category → 204.<br>- `Long.MAX_VALUE` → 404 with `$.id`.<br>- `abc` → 400. | Decisions 8, 10 |
| A1 | `applyWithoutBodyCreatesExactlyOneExpense`. Template (C, `"12.50"`).<br>- Apply with no body and no Content-Type → 201.<br>- Body: `$.amount == "12.50"`, `$.currency == "PLN"`, `$.spentOn == "2026-07-01"`, `$.note == null`, `$.createdAt` exists (JVM time, not compared), `$.category == {C.id, C.name, "cart"}`. `$.category.archived` and `$.categoryId` do not exist.<br>- `Location` ends with `/api/expenses/{expenseId}`, and a GET of it → 200 with the same values.<br>- **Exactly one:** `expenses.count() == before + 1`, and C's count is 1 with the expected column values.<br>- The template row is unchanged. | **AC3**, **AC5 (exactly one)**, decision 9 |
| A2 | `applyUsesTodayFromClockInConfiguredZone`:<br>- Sanity: `LocalDate.now(clock)` is 2026-07-01, and the UTC date is 2026-06-30.<br>- Apply → `$.spentOn == "2026-07-01"`.<br>- `GET /api/expenses?categoryIds=C` → `$.totalItems == 1`, the expense id, and `$.totalAmount` equal to the template amount. | AC3 ("dated today"), decision 9 |
| A3 | `applyWithEmptyBodiesUsesTemplateValues` (parameterized). Each → 201 with the template amount and `note == null`; each adds exactly one expense:<br>- (i) no body and no Content-Type<br>- (ii) JSON Content-Type with an empty body<br>- (iii) `{}`<br>- (iv) `{"amount":null,"note":null}`<br>- (v) `{"note":""}` | AC3 (optional body), AC5, CLAUDE.md empty-string rule, R1a |
| A4 | `applyOverridesAmountAndNote`:<br>- `{"amount":"7.5"}` → `"7.50"`<br>- `{"note":"Latte"}`<br>- `{"amount":99,"note":"Big"}` → `"99.00"`<br>- amount bounds `"0.01"` and `"9999999999.99"`<br>- a 255-character note<br>- `"  spaced  "` → stored unstripped<br>Each creates one expense with exactly these values. The template row is unchanged. | **AC3 (override)**, **AC5 (override works)**, decision 9 |
| A5 | `applyOverrideValidationAndEdgeInputs` (parameterized). Template amount `"12.50"`.<br>**Rejections:** each → 400 with `$.instance == "/api/quick-templates/{id}/apply"`, and `expenses.count()` unchanged:<br>- amount `0`, `"0.00"`, `-1`, `"1.234"` or `0.30000000000000004` → [amount]<br>- amount `"1e2147483647"` and `1e2147483647` → [amount], **exactly one** error<br>- `"10000000000"` → [amount, amount]<br>- a 256-character note → [note]<br>- `{"amount":0,"note":<256 chars>}` → [amount, note]<br>**`""` (decision 15):** `{"amount":""}` → **201** with `$.amount == "12.50"` (no override) and `note == null`, exactly one new expense. This is the same result as `{"amount":null}` in A3. | AC5 (override validation), decisions 9, 15 |
| A6 | `applyRejectsUnreadableBody`:<br>- `{"amount":` → 400 with `$.errors` absent.<br>- amount `"abc"` or `true` → 400.<br>- The count is unchanged. | Error format |
| A7 | `applyIgnoresOtherFields`: `{"spentOn":"2020-01-01","categoryId":D,"currency":"EUR","name":"x"}` → 201 with spentOn `"2026-07-01"`, category C and `"PLN"`. D has no expenses. | Decision 9 |
| A8 | `applyForArchivedCategoryReturns409`. Template on C, then archive C.<br>- Apply → 409 `"Category is archived"`, `$.instance == "/api/quick-templates/{id}/apply"`; no expense is created.<br>- `{"amount":"5"}` → 409.<br>- `{"amount":0}` → 400.<br>- GET list and GET by id show `category.archived == true`.<br>- Unarchive C → `archived == false`, and apply → 201. | **AC4 (applying)**, **AC5 (archived rejected)**, decisions 8, 10 |
| A9 | `applyUnknownTemplate`:<br>- `/{Long.MAX_VALUE}/apply` → 404 with `$.id`.<br>- The same id with `{"amount":0}` → 400.<br>- `/abc/apply` → 400.<br>- The count is unchanged. | Evaluation order |
| A10 | `eachApplyCreatesOneNewExpense`: apply twice → two 201s with different ids; C's count is 2. | AC5, decision 11 |
| A11 | `applyUsesCurrentTemplateValuesAndCopiesThem`:<br>- Apply → `"12.50"` in C.<br>- PATCH to `{"amount":"15","categoryId":D}`, then apply → `"15.00"` in D.<br>- The first expense is unchanged. | AC3, decision 11 |
| A12 | `applyRejectsNonJsonContentType`: `Content-Type: text/plain` with body `{"amount":"5"}` → **415**, Problem Details content type, `$.status == 415`, `$.instance`. The count is unchanged. | Decision 9 (415) |
| S1 | `schemaConstraints` (`JdbcTemplate`):<br>- A valid insert succeeds, and a duplicate name and sort_order succeed too.<br>- amount 0 or -1 → `ck_quick_template_amount_positive`.<br>- An unknown `category_id` → `fk_quick_template_category`.<br>- Deleting a category that has a template → `fk_quick_template_category`, and the category still exists.<br>- `null` in each column → `DataIntegrityViolationException`.<br>- `information_schema.columns`: `varchar(64)`, `numeric(12,2)`, `integer`, all `NOT NULL`.<br>- `expense` has no column containing `template`. | Card migration, decisions 1, 2, 3, 13 |

### Criterion → tests
- **AC1:**
  - POST: T1–T6
  - GET list: T7
  - GET by id: T1, T8
  - PATCH: T9
  - DELETE: T14, T15
- **AC2:** T9, T10, T11, T12, T13
- **AC3:**
  - dated today: A1, A2
  - template values: A1, A3, A5 (`""`), A11
  - optional body: A3
  - override: A4, A7
  - 201: A1
- **AC4:**
  - creating: T6
  - applying: A8
  - PATCH switch: T12
- **AC5:**
  - exactly one expense: A1, A3, A10
  - override works: A4 (and A5, A6)
  - archived rejected: A8 (and T6, T12)
- **Decisions:**
  - 1 → S1
  - 2 → T1, T3, T4, T7, S1
  - 3 → T1, T3, S1
  - 4 → T2, T3, T11, A5
  - 5 → T5, T6
  - 6 → T9–T13
  - 7 → T1, T8
  - 8 → T1, T7, T12, T15, A8
  - 9 → A1–A7, A12
  - 10 → A8, T15
  - 11 → T14, A10, A11
  - 12 → the existing expense and budget-limit tests listed above, plus T5, T6, T12, A8
  - 13 → S1
  - 15 → T4, T11, A5
  - 16 → T4, T11, plus the unchanged existing suite (implementation order step 2)

## Risks and open questions

### Open questions
All questions are settled. None is open.
1. **Q1. `sortOrder` on POST (decision 2). Confirmed:** required, any integer, not unique, ties broken by id.
2. **Q2. Template name uniqueness (decision 3). Confirmed:** not unique.
3. **Q3. PATCH and archived categories (decision 6). Confirmed:** switching to a different archived category returns 409; keeping the current one is allowed.
4. **Q4. `GET /api/quick-templates/{id}` (decision 7). Confirmed.**
5. **Q5. Templates of archived categories (decision 8). Decided, alternative (b):** a dedicated `QuickTemplateCategory` with `archived`. The list still includes these templates. `CategorySummary` is unchanged.
6. **Q6. Shared active-category check (decision 12). Confirmed:** `@Component ActiveCategoryLookup`.
7. **Q7. Transactions in apply (decision 11). Confirmed:** no `@Transactional`.
8. **Q8. Apply response and `Location` (decision 9). Confirmed.**
9. **Q9. Names (decision 14). Confirmed.**
10. **Q10. New dependencies: none.**

(Revision 2's Q11, the scope of the empty-string rule, was removed together with the rule in revision 3.)

### Risks
1. **R1. Framework behaviour, confirmed by tests rather than assumed** (Tests, "Behaviour pinning rule"):
   - **a) Optional body.** With `@Valid @RequestBody(required = false)`:
     - no body without a Content-Type, or an empty body with a JSON Content-Type, gives `null`, and validation is skipped (A3);
     - a body with another Content-Type gives 415 (A12).
   - **b) Derived query.** `findAllByOrderBySortOrderAscIdAsc` must parse with a property whose name contains `Order`. Spring Data validates this at startup. Fallback: `@Query("select t from QuickTemplate t order by t.sortOrder, t.id")` with the same `@EntityGraph`.
   - **c) Property binding.** `spring.jackson.deserialization.accept-float-as-int` must bind to Jackson 3's `DeserializationFeature` in Boot 4.1.1. Otherwise T4 and T11 fail.
     - Fallback: a `JsonMapperBuilderCustomizer` bean that calls `builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)`.
     - The fallback is still configuration, not a custom deserializer, but it adds a configuration class, so it needs the user's approval.
   - **d) Jackson 3.1.7 handling of `""` and blank strings in numeric fields.**
     - `""` → `null` for `BigDecimal`/`Integer`/`Long` is expected: it is Jackson's long-standing scalar coercion default. Decision 15 documents it, and T4, T11 and A5 pin it. A mismatch is reported, not adapted.
     - Blank strings are expected, but not verified, to behave the same way. The tests pin the observed outcome and the implementer reports it.
     - `ExpenseControllerIT.rejectsUnparseableAmount` (case `""`) passes under either outcome and is not changed.
2. **R2. Concurrency.**
   - A category archived between the read and the insert can still receive an expense (Q7).
   - A template patched or deleted while an apply runs: the expense uses the values that were read.
   - A double click creates two expenses; card 8 should disable the button while the request is in flight.

   All of this is accepted for a single-user app.
3. **R3. Refactoring two existing controllers.** Their constructors change, and no test constructs them directly. The existing tests listed under "Tests" pin the behaviour.
4. **R4. Templates whose category is archived** are listed with `category.archived: true`, and card 8 disables their buttons. A category archived after the list was loaded still yields a 409 on click, which card 8 must show as an error.
5. **R5. An extra Spring context.** `@TestBean Clock` gives `QuickTemplateControllerIT` its own context and Postgres container (a few seconds).
6. **R6. OSIV stays on**, and the plan does not rely on it:
   - Declared query methods run in autocommit on the request's `EntityManager`; without OSIV, on a per-call one, with the same result.
   - Categories come from entity graphs or explicit lookups.
   - Responses are built from instances the controller holds.
   - In PATCH, checks run before setters.
   - Apply does not modify the template.
7. **R7. A NUL character (`\u0000`) in `name` or `note`** surfaces as the generic 500, as it does today for category names and expense notes.
8. **R8. The float-as-int rule is project-wide** (section 9). It also applies to future JSON endpoints with integer fields, which is intended. Query parameters are not affected.

## Out of scope
- From the card: a reordering endpoint with drag-and-drop semantics, and template usage statistics.
- The frontend quick-template buttons (card 8) and any UI to manage templates.
- A note, currency or date on the template itself. `spentOn` cannot be overridden on apply.
- Idempotency keys for apply, locking against concurrent archiving, and a link from an expense back to its template.
- Paging or filtering `GET /api/quick-templates`, `PUT /api/quick-templates/{id}`, and bulk operations.
- An `archived` flag in `CategorySummary` (expense, budget-limit and report responses).
- Rejecting `""` in numeric JSON fields: dropped in revision 3, and Jackson's default applies.
- Mapping Jackson type errors to field-level `errors[]` project-wide (expense-crud Q5).
- Editing existing tests, including the `ExpenseControllerIT` comment about `""`.
- Name uniqueness, `created_at` / `updated_at` columns, and extra indexes.
- Changing OSIV, adding a service layer, Helm values, `CLAUDE.md`, `gradle.lockfile` and `docs/wallet-backlog.md`.
