# Plan: Expense filters and quick-template buttons

Trello card: https://trello.com/c/7i0hjF1f/16-expense-filters-and-quick-template-buttons
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Extend the existing "Expenses" page (card 7) in three ways:
- server-side filters (a date range and a category multi-select), mirrored in the URL query string;
- a row of quick-template buttons above the form, which apply a template and then refresh the table and the total;
- a pager driven by the API's `page`, `size` and `totalItems`.

Only the frontend changes. No dependency is added. The backend already provides every endpoint this needs.

## Acceptance criteria
- [ ] Filter controls: date range (from, to) and a multi-select of categories; every change issues a new GET /api/expenses request with from, to, categoryIds; no filtering in the browser
- [ ] Filter state is reflected in the URL query string so a filtered view can be bookmarked and reloaded
- [ ] A row of buttons above the form, one per quick template in sortOrder; clicking calls POST /api/quick-templates/{id}/apply and refreshes the table and total
- [ ] Pagination controls use page/size from the API and show totalItems

### How the criteria are interpreted
- **AC1, "with from, to, categoryIds":**
  - A filter that is set is always sent.
  - A filter that is not set (an empty date, or no category ticked) is **left out** of the query. The API treats a missing param as its default: the current month in `APP_TIME_ZONE`, and all categories (expense-list AC2/AC3; Q5 there: an empty value means the same as no value).
  - So the unfiltered view still sends exactly `GET /api/expenses?page=0&size=50`, as it does today (Q3).
- **AC1, "no filtering in the browser":** rows, the total and the pager are rendered exactly as the response returns them. The client never drops, sorts or sums rows.
- **AC3, "in sortOrder":** buttons are rendered in API order. `GET /api/quick-templates` already sorts by `sortOrder asc, id asc`, so the client does not sort again.
- **AC3, "refreshes the table and total":** this is card 7's D1. One `GET /api/expenses` is sent for the **current page with the current filters**, and the rows and `totalAmount` come from that response. The apply response is never inserted into the table.
- **AC4:** card 7 already computes the caption and the Next/Previous state from the response. This card also takes the **target page** from the response (`list.page ± 1`) and checks the pager when filters are set.

### Design decisions (one-line rationale each)
1. **No router. URL state uses `URLSearchParams` and `history.replaceState`, and is read once at mount** (lazy `useState` initialiser). `react-router` is not a dependency, there is one page, and built-in browser APIs cover both reading and writing. Card 9 decides navigation.
2. **`replaceState`, not `pushState` (Q4).** AC2 only needs bookmark and reload. `pushState` would add a history entry per checkbox click or date edit, and would need a `popstate` listener.
3. **URL params use the API's names and format:** `from`/`to` as `yyyy-MM-dd`, and `categoryIds` comma-separated, ascending and without duplicates. Unset values are left out. One serializer builds both the API query and the page URL, so there is one format and no mapping between two.
4. **Default when the URL has no params:** nothing is sent, the date inputs stay empty, and a hint says "Empty dates mean the current month." The client does no date arithmetic (card 7 decision 11), and the server's month cannot disagree with the client's.
5. **`page` and `size` are not in the URL (Q1).** AC2 asks for filter state. `size` is fixed at 50 (card 7 D2). A bookmark opens at page 0, so it never holds a stale page number past the end.
6. **Every filter change resets `page` to 0.** The filter and page updates are batched, so one request is sent. The old page number belongs to a different result set and may be past its end.
7. **A filter change clears the displayed list**, so "Loading…" shows until the response arrives (Q6). Rows, the total and the pager then always belong to the filter shown in the controls. In particular, no stale total stays on screen after a 400 for an invalid range.
8. **Invalid URL params are dropped silently when parsed, and the URL is normalised on mount.**
   - Values with valid syntax but invalid meaning are sent, and the server's 400 is shown. Examples: `from` after `to`, or an id with no category.
   - The server stays the only validator, and a malformed param never reaches the API.
   - This silent dropping is deliberately different from the API, which returns 400 for malformed params. Here it is cleanup of a bookmarked or hand-edited address, so that a broken link still opens a usable page. It is not validation.
9. **The multi-select is a checkbox group of all categories, archived ones included** (`GET /api/categories?includeArchived=true`, one request; the form gets the active subset) (Q2).
   - Reading history of an archived category is legitimate (expense-list D2), and a bookmark must keep working after its category is archived.
   - Checkboxes are native and accessible, and they add no `<option>` elements, which card 7's tests count.
10. **Every change sends a request at once, with no debounce. The latest request wins** through the existing `AbortController` pattern. AC1 says "every change", and the abort pattern already exists.
11. **The labels follow the filters:**
    - heading "This month" or "Selected period";
    - table name "Expenses this month" or "Expenses in the selected period";
    - empty text "No expenses this month." or "No expenses match the filters.".

    A fixed "This month" would be wrong for a custom range.
12. **The pager takes its target page from the response** (`onPageChange(list.page ± 1)`). The caption and the disabled states already come from the response's `page`, `size`, `items.length` and `totalItems`. This is AC4, and a double click during a load cannot skip a page.
    - If the target equals the page already requested (its load failed and the previous page is still shown), the same page is fetched again instead of being a no-op.
13. **Templates are loaded once on mount and rendered in API order.**
    - Each label is `"{name} {amount}"`, with the amount shown as is.
    - Templates of archived categories are disabled and get the suffix " (category archived)" (Q5).
    - Nothing is rendered while loading, when the list is empty, or when the load fails.
14. **Apply:**
    - It is a `POST` with no body.
    - It is serialised with the other mutations through the existing `mutating` flag.
    - On success it sets a status message and refetches the current page with the current filters.
    - The form draft and edit mode are not touched, and templates and categories are not fetched again.
15. **An applied expense outside the current filter stays hidden.** The filter is not changed and there is no client-side check. The status "Expense added from template "{name}"." confirms the save. The filter is the user's choice. A check would mean filtering in the browser, and it would need the server's "today".
16. **Errors reuse `expenseErrors.ts` and the Problem Details parsing in `client.ts`.**
    - Load errors and apply errors are shown at page level.
    - For a list 400, the `errors[]` messages are added to the message, because the generic `detail` ("Invalid request content.") says nothing useful.
17. **More user actions clear `pageError` and `status`** (card 7's one-slot rule): a filter change, "Clear filters" and a template click.

## Changes

### Existing state (verified in the code)
**Frontend:**
- **`package.json`:**
  - Dependencies: `react`/`react-dom` only. There is **no router**.
  - Dev dependencies: `vitest ^5.0.3`, `jsdom ^30.1.1`, `@testing-library/react ^16.3.3`, `@testing-library/dom ^10.4.2`, `@testing-library/user-event ^14.6.7`, `@testing-library/jest-dom ^7.0.1`.
  - `"test": "vitest run"`.
- **`ExpensesPage.tsx`:**
  - State: `categories`, `list`, `page`, `reloadKey`, `editing`, `formKey`, `mutating`, `pageError` and `status`.
  - The categories effect calls `listActiveCategories` → `GET /api/categories`.
  - The list effect on `[page, reloadKey]` calls `listExpenses(page, 50)` → `GET /api/expenses?page=0&size=50`. It uses an `AbortController`, ignores aborted results, and steps back one page when a page > 0 comes back empty.
  - `handlePrev`/`handleNext` change the local `page` (`current ± 1`).
- **`ExpenseTable.tsx`:**
  - The strings "Expenses this month" (`aria-label`) and "No expenses this month." are hardcoded.
  - The caption is `` `${page*size+1}–${page*size+items.length} of ${totalItems}` ``. Previous is disabled at page 0. Next is disabled when `(page+1)*size >= totalItems`. All values come from the response.
  - Props include `onPrev` and `onNext`.
- **`expenseErrors.ts`:** `classifyMutationError(error, 'create'|'update'|'delete')` and `describeLoadError(error, 'expenses'|'categories')`.
- **`client.ts`:** `request(method, path, {body?, signal?})`. Without a body it sends no `Content-Type`.
- **`tsconfig.app.json`:** `lib: ["ES2023", "DOM"]`, `verbatimModuleSyntax` and `erasableSyntaxOnly`. **`.oxlintrc.json`:** `react/rules-of-hooks` and `react/only-export-components`.
- **Tests:**
  - `setup.ts` stubs `fetch` before each test and fails any test that made a request without a route (card 7 decision 20).
  - `mockFetch` routes are exact `"METHOD path?query"` strings.
  - **Every** page test has the route `'GET /api/categories'`.
  - E1 asserts the exact request list `['GET /api/categories', 'GET /api/expenses?page=0&size=50']`.
  - E2 counts `entry.url === '/api/categories'`.
  - E15(a) asserts that no text "Old (archived)" exists after Cancel.
- **Serving:** the Vite dev server and nginx (`location / { try_files … /index.html }`) both serve `index.html` for `/?…`, so bookmarked query strings load the app in dev and in production.

**Backend (read only; verified in the code, tests and README):**
- **`GET /api/expenses`** binds `ExpenseListQuery` (`@ModelAttribute` record):
  - `from` and `to`: `@DateTimeFormat(iso = DATE)`;
  - `List<Long> categoryIds`;
  - `page`: `@Min(0)`, default 0;
  - `size`: `@Min(1) @Max(200)`, default 50.
- **`categoryIds` format:**
  - It is **comma-separated**, as in `categoryIds=1,4,7`, split by Spring's converter. `ExpenseListIT` sends only literal commas.
  - Repeated params (`categoryIds=1&categoryIds=4`) work by Spring default, but no test covers them (expense-list R5), and mixing the two forms returns 400.
  - An empty value means all categories. Archived ids count as known.
- **Defaults and empty values:** a missing `from` or `to` defaults to the first or last day of the current month (`YearMonth.now(clock)`, `APP_TIME_ZONE`). An empty value behaves as if the param were missing.
- **Errors:** all are 400 Problem Details with `detail "Invalid request content."` and `errors[]`:
  - `from` after `to` → `{from, "must not be after to (<from> > <to>)"}`;
  - an unknown id → `{categoryIds, "Category not found: <ids>"}`;
  - malformed values → `{field, "invalid value"}`.
- **Response:** `{items, page, size, totalItems, totalAmount: "0.00"-style string}`. A page past the end returns 200 with `items: []` and the totals.
- **`GET /api/categories?includeArchived=true`** returns all categories sorted by id (`findAllByOrderByIdAsc`). Without the param it returns only the active ones.
- **`GET /api/quick-templates`** returns a bare array sorted by `sortOrder asc, id asc`. Each item is `{id, name, amount: "12.50", sortOrder, category: {id, name, icon, archived}}`. Templates of archived categories are included with `archived: true`.
- **`POST /api/quick-templates/{id}/apply`:**
  - It works with no body and no Content-Type.
  - It returns 201 with the `ExpenseResponse`, using `spentOn = LocalDate.now(clock)` (`APP_TIME_ZONE`).
  - Errors: 409 `"Category is archived"` and 404 `"Quick template not found"`.
  - It is not idempotent.

### Backend: nothing is missing
Every request this card makes is already supported and covered by tests (`ExpenseListIT`, `QuickTemplateControllerIT`, `CategoryControllerIT`), so no backend file changes. One point is not covered by an automated test: the commas in `categoryIds` arrive percent-encoded (`%2C`), and the servlet container decodes them (R1).

### API calls made by the page
| When | Request | Fields used |
|---|---|---|
| Mount | `GET /api/categories?includeArchived=true` | `id`, `name`, `archived` |
| Mount | `GET /api/quick-templates` | `id`, `name`, `amount`, `category.archived` |
| Mount, a filter change, Previous/Next, and after every successful create, edit, delete or apply | `GET /api/expenses?[from=…&][to=…&][categoryIds=1%2C2&]page={p}&size=50`. Params appear in that order, and unset filters are left out. | `items`, `page`, `size`, `totalItems`, `totalAmount` |
| Template button | `POST /api/quick-templates/{id}/apply` (no body, no Content-Type) | status only; the body is ignored (D1) |
| Create, edit, delete | unchanged from card 7 | unchanged |

### Error placement (added to card 7's table; existing rows are unchanged)
| Outcome | List load (any trigger) | Templates load | Apply |
|---|---|---|---|
| `fetch` rejects | Page-level network message | Page-level network message | Page-level network message; no refetch |
| 4xx with non-empty `errors[]` | Page-level "Could not load expenses: {field}: {message}" (entries joined with "; "), for example `Could not load expenses: from: must not be after to (2026-09-30 > 2026-09-01)` | (same rule, label "Could not load quick templates") | — |
| Other 4xx (404, 409, 400 without `errors[]`) | Page-level "Could not load expenses: {detail}" (unchanged) | Page-level "Could not load quick templates: {detail}" | Page-level "Could not apply the template: {detail}", for example "…: Category is archived" or "…: Quick template not found"; no refetch |
| 5xx | "Server error ({status}): {detail}" (unchanged) | Same | Same; no refetch |
| Neither an `ApiError` nor an abort | "Could not load expenses: unexpected error." (unchanged) | "Could not load quick templates: unexpected error." | "Unexpected error. Please try again."; no refetch |
| Aborted | Ignored | Ignored | — (apply has no signal) |

- After a failed list load caused by a filter change, no table, total or pager is shown, because decision 7 already cleared the list. The filter controls stay, so the user can correct the filter.
- The one `pageError` slot rule is unchanged. A successful load never clears it. Only a user action does: those listed in card 7, plus a filter change, "Clear filters" and a template click.

### 1. Change: `frontend/src/api/categories.ts`
Replace `listActiveCategories` (its only caller is `ExpensesPage`) with:
```ts
// All categories, archived ones included: the filter offers them (expense-list D2);
// the expense form uses the active subset.
export const listAllCategories = (signal?: AbortSignal) =>
  request<Category[]>('GET', '/api/categories?includeArchived=true', { signal })
```

### 2. Change: `frontend/src/api/expenses.ts`
```ts
// Unset values are null / []: they are left out of the query, and the API applies its defaults
// (current month in APP_TIME_ZONE, all categories).
export type ExpenseFilters = { from: string | null; to: string | null; categoryIds: number[] }

// The one serializer for the API query and the page URL: from, to (yyyy-MM-dd),
// categoryIds comma-separated (URLSearchParams encodes "," as %2C; the servlet container decodes it).
export function expenseFilterParams(filters: ExpenseFilters): URLSearchParams  // appends from, to, categoryIds in this order, only when set

export function listExpenses(filters: ExpenseFilters, page: number, size: number, signal?: AbortSignal): Promise<ExpenseList>
// = expenseFilterParams(filters) + append('page') + append('size')
```
- With no filters, the URL stays `/api/expenses?page=0&size=50`, byte for byte.
- Use only `append`, `get` and `toString` on `URLSearchParams`. No iteration is needed.

### 3. New file: `frontend/src/api/quickTemplates.ts`
```ts
export type QuickTemplateCategory = { id: number; name: string; icon: string | null; archived: boolean }
export type QuickTemplate = { id: number; name: string; amount: string; sortOrder: number; category: QuickTemplateCategory }

export const listQuickTemplates = (signal?: AbortSignal) =>
  request<QuickTemplate[]>('GET', '/api/quick-templates', { signal })

// No body: the template's amount and category are used (overrides are out of scope).
export const applyQuickTemplate = (id: number) =>
  request<Expense>('POST', `/api/quick-templates/${id}/apply`)
```
Money stays a `string`, as in card 7 decision 12.

### 4. New file: `frontend/src/expense/expenseFilters.ts` (pure, no React)
```ts
export const NO_FILTERS: ExpenseFilters = { from: null, to: null, categoryIds: [] }
export function parseFilters(search: string): ExpenseFilters          // from window.location.search
export function filtersToSearch(filters: ExpenseFilters): string      // '' when nothing is set, else '?' + expenseFilterParams(...)
export function withCategory(filters: ExpenseFilters, id: number, selected: boolean): ExpenseFilters  // ascending, deduplicated, never mutates
export function hasDateFilter(filters: ExpenseFilters): boolean       // from or to set
export function hasAnyFilter(filters: ExpenseFilters): boolean        // dates or categoryIds set
```
**`parseFilters` rules.** These clean up the URL; they do not validate business rules:
- **`from` and `to`:**
  - Take the first value (`params.get`).
  - Keep it only if it matches `^\d{4}-\d{2}-\d{2}$` **and** is a real calendar date: `new Date(value + 'T00:00:00Z')` is valid and its `toISOString().slice(0, 10) === value`. Both sides are UTC, so there is no time-zone effect.
  - Anything else becomes `null`.
- **`categoryIds`:**
  - Split `params.get('categoryIds') ?? ''` on `,`.
  - Keep only entries that match `^[1-9]\d{0,15}$` and are `Number.isSafeInteger`.
  - Remove duplicates and sort ascending.
  - Invalid entries are dropped one by one, so `2,x,,0,1` becomes `[1, 2]`.
- `from` after `to` is **kept**. The server reports it (decision 8).
- Every other param (`page`, `size`, unknown ones) is ignored. The URL normalisation in section 9 removes them from the address bar.

### 5. Change: `frontend/src/expense/expenseErrors.ts`
- **`classifyMutationError`** gets the operation `'apply'`. A new `classifyApplyError(error, status)` returns:
  - 5xx → `serverErrorMessage`;
  - otherwise → `` `Could not apply the template: ${detailOrFallback(error, status)}` ``.

  Both use `target: 'page'`, `refresh: false` and `leaveEdit: false`. Network, unexpected and abort errors go through the existing shared branches.
- **`describeLoadError(error, what: 'expenses' | 'categories' | 'quickTemplates')`:**
  - The label for `'quickTemplates'` is "Could not load quick templates".
  - New branch, before the `detail` fallback: for an `ApiError` with `status < 500` and non-empty `fieldErrors`, return `` `${label}: ${entries.map(([field, message]) => `${field}: ${message}`).join('; ')}` ``. This uses the same `"{field}: {message}"` format as card 7's form-level unknown fields.
  - Card 7's existing outcomes do not change. No current test has a load error with `errors[]`.

### 6. New file: `frontend/src/expense/ExpenseFilterControls.tsx`
**Props:**
- `filters: ExpenseFilters`;
- `categories: Category[] | null` (all categories, or `null` while loading or after a failed load);
- `onChange(next: ExpenseFilters)`.

**Markup:** a `<div role="search" aria-label="Filter expenses" className="filters">`. It is deliberately **not** a `<form>`, so pressing Enter cannot submit and reload the page.
- **From and To:**
  - `<label htmlFor>` "From" and "To", each on an `<input type="date">`.
  - `value={filters.from ?? ''}`, and `onChange` emits `{...filters, from: value === '' ? null : value}` (`to` likewise).
  - Both inputs have `aria-describedby` pointing at the hint `<p className="hint">Empty dates mean the current month.</p>`.
  - There is no `min`, `max` or `required`. Card 7 decision 14 applies: no client-side validation.
- **Categories:**
  - A `<fieldset aria-describedby={hintId}>` with `<legend>Categories</legend>` and one `<label><input type="checkbox"/> {label}</label>` per entry, plus the hint "No category selected means all categories.".
  - Entries come first from `categories ?? []` in API order. The label is `name`, or `"{name} (archived)"` for an archived category.
  - Then each selected id that is missing from that list gets a checked entry labelled `"Category #{id}"`. This covers a hand-edited id, and categories that have not loaded or failed to load. The same idea is used in card 7 decision 8, so a filter in effect is never invisible.
  - `checked={filters.categoryIds.includes(id)}`, and `onChange` emits `withCategory(filters, id, event.target.checked)`.
- **"Clear filters":** a `type="button"`, `disabled={!hasAnyFilter(filters)}`, which emits `NO_FILTERS`.
- All ids come from `useId()`.

### 7. New file: `frontend/src/expense/QuickTemplateBar.tsx`
**Props:**
- `templates: QuickTemplate[] | null`;
- `busy: boolean` (the page's `mutating`);
- `onApply(template: QuickTemplate)`.

**Rendering:**
- When `templates` is `null` or empty, the component returns `null`.
- Otherwise it renders `<div role="group" aria-label="Quick templates" className="quick-templates">` with one `<button type="button">` per template, in API order.
  - Text: `` `${name} ${amount}` ``, plus `" (category archived)"` when `category.archived` is true.
  - `disabled={busy || category.archived}`.
  - The amount is shown exactly as the API sends it, never as a `Number` and without a currency. Templates have no currency field (card 7 decision 12 / Q6).

### 8. Change: `frontend/src/expense/ExpenseTable.tsx`
- New props: `label: string` (the table's `aria-label`) and `emptyText: string`. They replace the two hardcoded strings.
- Replace `onPrev`/`onNext` with `onPageChange(page: number)`. Previous calls `onPageChange(page - 1)` and Next calls `onPageChange(page + 1)`, where `page` is the **response's** `list.page` (decision 12).
- The caption and the disabled rules stay as they are; they already use the response's `page`, `size`, `items.length` and `totalItems`.

### 9. Change: `frontend/src/expense/ExpensesPage.tsx`
**New state and derived values:**
- `const [filters, setFilters] = useState<ExpenseFilters>(() => parseFilters(window.location.search))`.
- `templates: QuickTemplate[] | null`.
- `activeCategories = categories === null ? null : categories.filter((category) => !category.archived)`. It is passed to `ExpenseForm`, so the form behaves exactly as in card 7.

**Effects, in this declaration order** (fetch is called synchronously in each effect, so the request order is deterministic: categories, then templates, then the list):
1. **Categories:** `listAllCategories`. Otherwise unchanged.
2. **Templates** (new, once on mount): the same pattern as categories. Errors go through `describeLoadError(error, 'quickTemplates')` into `pageError`.
3. **List:**
   - Dependencies: `[filters, page, reloadKey]`.
   - Calls `listExpenses(filters, page, PAGE_SIZE, signal)`.
   - The abort handling and the step-back on an empty page > 0 are unchanged.
4. **URL sync** (new):
   - Dependencies: `[filters]`.
   - Builds `target = pathname + filtersToSearch(filters) + hash`. If it differs from the current URL, calls `window.history.replaceState(window.history.state, '', target)`.
   - It also runs on mount, which normalises garbage params (decision 8).

**Handlers:**
- **`handleFiltersChange(next)`:** clear `pageError` and `status`, then `setFilters(next)`, `setPage(0)` and `setList(null)` (decisions 6 and 7). React batches them, so one request is sent.
- **`handleApply(template)`:**
  - Clear `pageError` and `status`, then `setMutating(true)` and await `applyQuickTemplate(template.id)`.
  - On success: set the status `` `Expense added from template "${template.name}".` `` and increase `reloadKey`. This refetches the current page with the current filters (D1).
  - On error: `classifyMutationError(error, 'apply')`; a `page` outcome goes to `pageError`.
  - `finally`: `setMutating(false)`.
  - It does not touch `formKey` or `editing`.
- **`handlePageChange(target)`:** clear `pageError` and `status`, then `setPage(target)`. It replaces `handlePrev`/`handleNext`.
- Submit, delete, edit and cancel are unchanged.

**Markup order:**
1. `<h1>`.
2. The page alert.
3. The status line.
4. **`<QuickTemplateBar busy={mutating} …/>`**, above the form (AC3).
5. `<ExpenseForm categories={activeCategories} …/>`.
6. A `<section>` containing:
   - the heading `{hasDateFilter(filters) ? 'Selected period' : 'This month'}`;
   - **`<ExpenseFilterControls categories={categories} …/>`**;
   - "Loading…" (same condition as today);
   - the total;
   - `<ExpenseTable label={hasDateFilter ? 'Expenses in the selected period' : 'Expenses this month'} emptyText={hasAnyFilter ? 'No expenses match the filters.' : 'No expenses this month.'} onPageChange={handlePageChange} …/>`.

With no filters, every text is exactly what card 7 renders today.

### 10. Change: `frontend/src/App.css`
- `.quick-templates`: `display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 1rem`.
- `.filters`: a flex row with wrap, gap and `align-items: flex-start`.
- `.filters fieldset`: no border, no padding.
- Checkbox labels are inline.
- No mobile work.

### 11. Change: `frontend/src/test/setup.ts`
In `beforeEach`, add `window.history.replaceState(null, '', '/')`, with a comment saying that jsdom keeps the URL between tests and that the page reads it at mount.

### 12. Change: `frontend/src/test/fixtures.ts`
- `OLD: Category`: id 9, name "Old", `archived: true`. The id matches E15's archived row.
- `CATEGORIES_ROUTE = 'GET /api/categories?includeArchived=true'` and `TEMPLATES_ROUTE = 'GET /api/quick-templates'`.
- `baseRoutes(): MockRoutes` returns `{ [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]), [TEMPLATES_ROUTE]: json([]) }`.
  - It returns **exactly** `[FOOD, TRANSPORT]` and no templates, so card 7's assertions keep their meaning. For example, E15(a) asserts that no "Old (archived)" text exists.
  - New tests that need `OLD` or templates override the key after spreading.
- `quickTemplate(overrides)` builder. The default is `{id: 1, name: 'Coffee', amount: '12.50', sortOrder: 0, category: {FOOD…, archived: false}}`.
- It imports `json` and `MockRoutes` from `./fetchMock.ts`.

### 13. Change: existing tests (mechanical; `ExpensesPage.form.test.tsx`, `ExpensesPage.table.test.tsx`)
The page now always requests categories with `includeArchived=true` and also requests the templates. Because of card 7 decision 20, every existing `mockFetch` call needs these changes:
- **Replace each route:**
  - `'GET /api/categories': json([FOOD, TRANSPORT]),` becomes `...baseRoutes(),`.
  - `'GET /api/categories': problem(500, …)` (E10, E15(b), E30) becomes `...baseRoutes(), [CATEGORIES_ROUTE]: problem(500, …)`.
- **E1:** keep the existing mapping `` requests(mock).map((entry) => `${entry.method} ${entry.url}`) `` (`ExpensesPage.form.test.tsx`, lines 31–32). Only the expected array changes, to `[CATEGORIES_ROUTE, TEMPLATES_ROUTE, 'GET /api/expenses?page=0&size=50']`.
  - Both constants already start with `GET `, so this equals `['GET /api/categories?includeArchived=true', 'GET /api/quick-templates', 'GET /api/expenses?page=0&size=50']`.
- **E2:** `entry.url === '/api/categories'` becomes `entry.url === '/api/categories?includeArchived=true'`.
- **No other assertion changes.**
  - This rule applies from the first full `npm test` run, at the end of implementation step 3. Before that, the suite is expected to fail (see "Implementation order").
  - From then on, if any existing test fails for any other reason, stop and report the failure. Do not adapt the test.

### 14. Not changed
- **No new dependencies.** `package.json` and `package-lock.json` stay byte-identical. `react-router`, `nuqs`/`use-query-params` and multi-select component libraries were considered and rejected (decisions 1 and 9).
- **Other source files:** `ExpenseForm.tsx`, `client.ts`, `localDate.ts`, `App.tsx`, `main.tsx` and `index.css`.
- **Configuration and build:** `vite.config.ts`, tsconfig, oxlint config, `nginx.conf`, `Dockerfile` and CI. The existing `npm test` step runs the new tests.
- **Repository files:** `README.md` (no API or command change), `docs/wallet-backlog.md` and `CLAUDE.md`.
- **The whole backend.** `./gradlew test` is not required.
- **No git operations.**

### Implementation order
1. Sections 1–4, then `expenseFilters.test.ts` (U1–U4). Run **only** that file: `cd frontend && npm test -- src/expense/expenseFilters.test.ts`.
   - Sections 1 and 2 remove `listActiveCategories` and change the signature of `listExpenses`. `ExpensesPage.tsx` still calls the old functions until section 9.
   - So from this step until the end of step 3, the full `npm test` and `npm run build` are **expected to fail**. Do not use them as a gate in steps 1–2, and do not try to fix the failing page tests.
2. Sections 5–10. The full suite is still expected to fail, because the existing page tests lack the new routes until section 13.
3. Sections 11–13. Now run the full `npm test`. It must be green, with the existing tests changed only as described in section 13.
   - **From this run on**, section 13's "stop and report" rule applies to any failure of an existing test.
4. New page tests: `ExpensesPage.filters.test.tsx` and `ExpensesPage.templates.test.tsx`.
5. Final check: `cd frontend && npm test && npm run lint && npm run build`, all green. `tsc -b` type-checks the tests too.

**Forbidden at every step, for both the implementer and the test-writer:**
- `./gradlew bootRun`;
- `npm run dev` against a backend;
- any connection to the dev Postgres (`postgres:5432`: psql, JDBC, anything). Frontend tests mock `fetch` and need neither a backend nor a database;
- `npm install`/`uninstall` or any other change to dependencies;
- deleting `frontend/node_modules`;
- git operations.

A manual browser check against a running backend is for the user only (R1).

## Tests
Done means `cd frontend && npm test`, `npm run lint` and `npm run build` are all green.

**Conventions.** These are card 7's, already in place:
- **Tooling and `fetch`:**
  - Vitest 5 in jsdom, with Testing Library React, user-event and jest-dom matchers.
  - `fetch` is mocked with `mockFetch` exact routes, and `setup.ts` fails any request that has no route. There is no MSW.
- **Rendering and input:**
  - No `StrictMode` in tests, so request counts are exact.
  - Queries go by role or label.
  - Dates are set with `fireEvent.change(input, { target: { value } })`. Checkboxes are clicked with `user.click`.
  - Money is asserted as exact strings, and `list()` fixtures pass `totalAmount` explicitly.

**New conventions:**
- **URL state:**
  - `setup.ts` resets the URL to `/` before each test.
  - A test sets the starting URL with `window.history.replaceState(null, '', '/?…')` **before** `render`.
  - A **reload** is `unmount()` followed by a new `render()`. A fresh mount reads `window.location.search`, exactly as a page load does.
  - URL assertions use `window.location.search`.
- **Routes:**
  - Every page test spreads `baseRoutes()`.
  - Route keys containing several ids use `%2C`, for example `categoryIds=1%2C2`, because `URLSearchParams` encodes the comma.
- Distinct `totalAmount` strings per response (`'1.00'`, `'2.00'`, …) mark which response is on screen.

### New file: `frontend/src/expense/expenseFilters.test.ts`
| # | Test | Proves |
|---|---|---|
| U1 | `parsesValidParams`:<br>- `?from=2026-09-01&to=2026-09-30&categoryIds=2,1` → `{from: '2026-09-01', to: '2026-09-30', categoryIds: [1, 2]}`.<br>- `?categoryIds=1%2C2` and `?categoryIds=1,2` give equal results.<br>- `''` → `NO_FILTERS`.<br>- Leap day `2028-02-29` is kept. | AC2 (URL → state), decision 3 |
| U2 | `dropsInvalidParams` (table). Inputs are written exactly as they appear in the search string passed to `parseFilters`, so they are percent-encoded. In a query string, `+` decodes to a space.<br>- **`from`/`to` → `null`:** `2026-02-30`, `2026-02-29`, `2026-13-01`, `2026-9-1`, `20260901`, `2026-09-01T00:00`, `%202026-09-01` (decodes to a leading space), an empty value (`from=`), `abc`.<br>- **Repeated `from`:** the first value wins.<br>- **`categoryIds`:** `x`, an empty entry, `0`, `-1`, `1.5`, `01`, `%2B1` (a literal `+1`), `+1` (decodes to ` 1`) and `9007199254740993` are dropped one by one, so `2,x,,0,2,1` → `[1, 2]`. `categoryIds=` → `[]`.<br>- **Kept:** reversed `from=2026-09-30&to=2026-09-01`.<br>- **Ignored:** `page`, `size` and `foo`. | Decision 8 |
| U3 | `serialisesInApiFormat`:<br>- `filtersToSearch(NO_FILTERS) === ''`.<br>- Full filters → exactly `?from=2026-09-01&to=2026-09-30&categoryIds=1%2C2`. Only the categories → `?categoryIds=2`.<br>- `parseFilters(filtersToSearch(f))` deep-equals `f` for four sample filters. | AC2 (state → URL), decision 3 |
| U4 | `withCategoryAndPredicates`:<br>- `withCategory` adds an id in sorted position, removes one, ignores a duplicate add, and never mutates its input.<br>- `hasDateFilter` and `hasAnyFilter` give the right answer for each combination. | Decisions 3 and 11 |

### New file: `frontend/src/expense/ExpensesPage.filters.test.tsx`
In these tests the categories route returns `[FOOD, TRANSPORT, OLD]`, unless stated otherwise.

| # | Test | Proves |
|---|---|---|
| F1 | `defaultViewSendsNoFilterParams`:<br>- The URL is `/`.<br>- The requests are exactly `[categories?includeArchived=true, quick-templates, 'GET /api/expenses?page=0&size=50']`.<br>- From and To are empty.<br>- The checkboxes are "Food", "Transport" and "Old (archived)", in API order, none checked. "Clear filters" is disabled.<br>- The heading is "This month", and `window.location.search === ''`.<br>- The form's options are exactly ["Select a category", "Food", "Transport"], so the archived category is not offered for new expenses. | AC1 (controls, default = API default), decisions 4 and 9 |
| F2 | `everyChangeIssuesRequestWithFilters`. Steps:<br>1. Set From to 2026-09-01.<br>2. Set To to 2026-09-30.<br>3. Tick Transport.<br>4. Tick Food.<br>5. Untick Transport.<br>6. Clear To.<br>After each step, the response's total is shown and `window.location.search` matches.<br>The list GETs are **exactly**, in order:<br>- `?page=0&size=50`<br>- `?from=2026-09-01&page=0&size=50`<br>- `?from=2026-09-01&to=2026-09-30&page=0&size=50`<br>- `…&categoryIds=2&page=0&size=50`<br>- `…&categoryIds=1%2C2&page=0&size=50`<br>- `…&categoryIds=1&page=0&size=50`<br>- `?from=2026-09-01&categoryIds=1&page=0&size=50` | **AC1**, **AC2** (URL follows every change), decisions 3, 6 and 10 |
| F3 | `noFilteringInTheBrowser`:<br>- The URL is `/?categoryIds=1`.<br>- The response deliberately contains a Food row, a **Transport** row and a row dated `2025-01-01`, with `totalAmount '123.45'`, which is not their sum.<br>- All three rows are shown in API order, with "Total: 123.45" and "1–3 of 3". | **AC1** ("no filtering in the browser") |
| F4 | `bookmarkedViewLoadsAndSurvivesReload`:<br>- (a) The URL is `/?from=2026-09-01&to=2026-09-30&categoryIds=2,9`. The first list request is `GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=2%2C9&page=0&size=50`. From and To show the dates, Transport and "Old (archived)" are ticked, Food is not, and the heading is "Selected period". The URL is normalised to `?from=2026-09-01&to=2026-09-30&categoryIds=2%2C9`.<br>- (b) Starting from `/`, tick Food and set From through the UI, then unmount and render again. The first request after the remount carries exactly the same filter, and the controls show it. | **AC2** (bookmark and reload), decisions 1 and 9 |
| F5 | `garbageUrlParamsAreDroppedAndUrlNormalised`:<br>- (a) The URL is `/?from=2026-02-30&to=yesterday&categoryIds=2,x,,0,-1,1.5,2,1&page=3&size=500&foo=bar`. The request is `GET /api/expenses?categoryIds=1%2C2&page=0&size=50` (the page in the URL is ignored). `window.location.search === '?categoryIds=1%2C2'`. The dates are empty, and Food and Transport are ticked.<br>- (b) The URL is `/?from=2026-09-30&to=2026-09-01`. It is sent unchanged, and the 400 `errors[{from, "must not be after to (2026-09-30 > 2026-09-01)"}]` is shown as "Could not load expenses: from: must not be after to (2026-09-30 > 2026-09-01)". | Decision 8, AC2 |
| F6 | `unknownCategoryIdFromUrlIsVisibleAndRemovable`:<br>- The URL is `/?categoryIds=9,42`. The request uses `categoryIds=9%2C42`, which returns 400 `errors[{categoryIds, "Category not found: 42"}]`.<br>- Then: the alert "Could not load expenses: categoryIds: Category not found: 42", no table, and "Old (archived)" and "Category #42" both ticked.<br>- Untick "Category #42" → `GET /api/expenses?categoryIds=9&page=0&size=50` → the table renders, the alert is gone, "Category #42" is gone, and the URL is `?categoryIds=9`. | Decisions 8, 9 and 16 |
| F7 | `filterChangeResetsPageToZero`:<br>- The URL is `/`, with `totalItems 120`. Next → `page=1`.<br>- Tick Food → the next request is `GET /api/expenses?categoryIds=1&page=0&size=50`, and the caption starts at "1–". | Decision 6, AC4 |
| F8 | `invalidRangeShowsServerErrorAndNoStaleTotal`:<br>- From 2026-09-30 → OK, "Total: 5.00".<br>- To 2026-09-01 → 400 `errors[{from, …}]` → the page-level message with the field message. **No** table and **no** "Total:" are shown, and From and To keep their values.<br>- To 2026-10-15 → 200 → the table is back and the alert is gone. | Decisions 7, 14 (card 7) and 16 |
| F9 | `latestFilterWins`:<br>- Tick Food; the reply for `categoryIds=1` is deferred.<br>- Tick Transport; the reply for `categoryIds=1%2C2` gives total `'20.00'`.<br>- Then resolve the deferred reply with `'10.00'`. "20.00" stays, and "10.00" never appears, because the request was aborted. | Decision 10 |
| F10 | `clearFiltersRestoresDefault`:<br>- The URL is `/?from=2026-09-01&categoryIds=1`. Click "Clear filters".<br>- The request is `GET /api/expenses?page=0&size=50`, `window.location.search === ''`, the inputs are empty, nothing is ticked, the button is disabled, and the heading is "This month". | AC1, AC2, decision 4 |
| F11 | `labelsFollowFilters`:<br>- (a) `/?from=2026-09-01` with rows → the heading is "Selected period" and the table is named "Expenses in the selected period". With an empty result → "No expenses match the filters.".<br>- (b) `/?categoryIds=1` with an empty result → the heading is "This month" and the text is "No expenses match the filters.". | Decision 11 |
| F12 | `mutationsRefetchWithCurrentFiltersAndPage`:<br>- The URL is `/?from=2026-09-01&to=2026-09-30&categoryIds=1`, with `totalItems 51`. Next → page 1.<br>- Create, edit, delete (`confirm` stubbed) and a template click are each followed by `GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=1&page=1&size=50`. Modelled on E24.<br>- Each time the total is the refetch's `totalAmount`. | AC3 (refresh uses the current filters), card 7 D1 |
| P1 | `filteredPagination`:<br>- The URL is `/?categoryIds=2`, with `totalItems 120`. → "1–50 of 120", Previous disabled.<br>- Next → `GET /api/expenses?categoryIds=2&page=1&size=50` → "51–100 of 120".<br>- Previous → `…categoryIds=2&page=0&size=50`.<br>- Each page shows its own `totalAmount`. | **AC4** with filters |
| P2 | `pagerUsesPageAndSizeFromResponse`. The response's `size` is deliberately different from the requested 50, like E21's total that is not a sum:<br>- `{page: 0, size: 20, totalItems: 45, items: 20}` → "1–20 of 45", and Next is **enabled** (with 50 it would be disabled).<br>- Next → the request is `page=1&size=50` → `{page: 1, size: 20, totalItems: 45, items: 20}` → "21–40 of 45". | **AC4** ("use page/size from the API", shows `totalItems`), decision 12 |

### New file: `frontend/src/expense/ExpensesPage.templates.test.tsx`
| # | Test | Proves |
|---|---|---|
| QT1 | `rendersTemplateButtonsInApiOrderAboveForm`:<br>- (a) Templates in API order: "Bus" `3.40` (sortOrder −1), "Coffee" `12.50` (0), "Old pass" `100.00` (0, archived category), "Rent" `9999999999.99` (5).<br>- The group "Quick templates" has buttons named exactly ["Bus 3.40", "Coffee 12.50", "Old pass 100.00 (category archived)", "Rent 9999999999.99"]. "Old pass…" is disabled and the others are enabled.<br>- The group comes **before** the form "New expense" in DOM order (`compareDocumentPosition`).<br>- (b) `[]` → there is no "Quick templates" group. | **AC3** (row above the form, sortOrder, amounts shown as sent), decision 13 |
| QT2 | `applyPostsAndRefreshesTableAndTotal`:<br>- The URL is `/?categoryIds=1`. Type `5` into Amount (a draft), then click "Coffee 12.50".<br>- `POST /api/quick-templates/1/apply` has **no body and no Content-Type**.<br>- Then `GET /api/expenses?categoryIds=1&page=0&size=50`. Its new first row and `totalAmount '22.50'` are shown.<br>- The status is `Expense added from template "Coffee".`, and Amount still holds `5`.<br>- Categories and templates were each fetched exactly once. | **AC3**, decision 14 |
| QT3 | `appliedExpenseOutsideFilterStaysHidden`:<br>- The URL is `/?from=2026-09-01&to=2026-09-30`. The POST returns an expense dated `2026-10-01`. The refetched September list does not contain it and keeps `totalAmount '50.00'`.<br>- The status is shown, no cell reads "2026-10-01", the From and To values and `window.location.search` are unchanged, and there was exactly one POST and two list GETs. | Decision 15 |
| QT4 | `applyIsSerialisedWithOtherMutations`:<br>- (a) The POST is deferred. While it is pending, every template button, "Add expense" and every row's Edit and Delete are disabled. A second click sends no second POST. Resolve → everything is enabled again, and there was exactly one POST.<br>- (b) A deferred `POST /api/expenses` → the template buttons are disabled until it resolves. | Decision 14, card 7 decision 16, quick-templates R2 |
| QT5 | `applyErrorsArePageLevel`:<br>- (a) 409 "Category is archived" → "Could not apply the template: Category is archived".<br>- (b) A `TypeError` rejection → the network message.<br>- (c) A 500 problem → "Server error (500): Unexpected error".<br>- (d) `rawJson('not json', 201)` → "Unexpected error. Please try again.".<br>In each case there is no list refetch, the buttons are enabled again, and no message is inside the form. | Decision 16 |
| QT6 | `templatesLoadFailureKeepsPageUsable`:<br>- (a) Templates GET returns a 500 problem → "Server error (500): Unexpected error".<br>- (b) Templates GET returns `rawJson('not json')` → "Could not load quick templates: unexpected error.".<br>- (c) Templates GET rejects → the network message.<br>In each case there is no "Quick templates" group, and the form and the table still render. | Decision 13, error placement |

### Existing tests
Card 7's E1–E30, `newClientBehaviour…`, C1–C10, S1–S2 and L1 keep proving card 7's behaviour. The only changes are the mechanical ones in section 13. E23 and E25 still cover the pager without filters (AC4).

### Criterion → tests
- **AC1:**
  - controls and default: F1;
  - every change sends the filter: F2, F7, F10;
  - no filtering in the browser: F3;
  - latest wins: F9;
  - serializer: U3.
- **AC2:**
  - URL follows changes: F2, F10;
  - bookmark and reload: F4;
  - garbage params: F5, F6;
  - parse and serialize: U1–U4.
- **AC3:**
  - buttons, order and placement: QT1;
  - apply and refresh: QT2, F12;
  - outside the filter: QT3;
  - in flight: QT4;
  - errors: QT5, QT6.
- **AC4:**
  - response page and size: P2;
  - with filters: P1;
  - reset on filter change: F7;
  - unchanged pager: E23, E25.
- **Decisions:**
  - 3 → U1, U3, F2;
  - 4 → F1, F10;
  - 6 → F7;
  - 7 → F8;
  - 8 → U2, F5, F6;
  - 9 → F1, F4, F6;
  - 10 → F9;
  - 11 → F11;
  - 12 → P2;
  - 13 → QT1, QT6;
  - 14 → QT2, QT4;
  - 15 → QT3;
  - 16 → F5, F6, F8, QT5, QT6.

## Risks and open questions

### Open questions
No acceptance criterion is blocked. Each question below has a proposal (in bold) that the plan implements, and the alternative and its cost are stated.
1. **Q1. `page` in the URL.** **Proposal: no.** A bookmark or reload opens page 0 of the filtered view.
   - Alternative: add `page` to the URL, omitted when 0. That needs parsing and, for stale bookmarks past the end, a jump to the last page (`ceil(totalItems / size) − 1`) instead of the current one-step step-back.
2. **Q2. Archived categories in the filter.** **Proposal: yes,** with `includeArchived=true` (decision 9).
   - Alternative: only active categories. That keeps `GET /api/categories` unchanged, but history of archived categories can no longer be filtered from the UI, and a bookmarked archived id shows as "Category #9".
3. **Q3. Unset filters are left out of the request** (the AC1 interpretation). **Proposal: yes.**
   - Alternative: always send `from=&to=&categoryIds=`. The backend behaves the same, but every route in card 7's tests would change.
4. **Q4. `replaceState`.** **Proposal: yes.** Back does not undo filter changes.
   - Alternative: `pushState` plus a `popstate` listener that re-reads the filters. That is about 10 more lines and one history entry per change.
5. **Q5. Templates of archived categories.** **Proposal: shown disabled with " (category archived)".** Alternative: hide them.
6. **Q6. A filter change clears the table until the response arrives** (decision 7). **Proposal: yes.**
   - Alternative: keep the old rows during the load and clear them only on error. That avoids the brief "Loading…" but needs per-request bookkeeping.
7. **Q7. The "Clear filters" button** goes beyond the card's text. **Proposal: include it.** Native date inputs are not equally easy to clear in every browser, and it is the only way out of an invisible filter when categories fail to load.
8. **Q8. Texts:**
   - "Selected period";
   - "Expenses in the selected period";
   - "No expenses match the filters.";
   - `Expense added from template "{name}".`;
   - "Could not apply the template: …";
   - "Could not load quick templates";
   - "Category #{id}";
   - the two hints.

   **Proposal: as listed.**
9. **Q9. New dependencies: none.** Nothing needs confirmation.

### Risks
1. **R1. Comma encoding.**
   - The API receives `categoryIds=1%2C2`. The servlet container decodes it to `1,2` before Spring binds it, so the backend sees the documented comma form.
   - Backend ITs only send literal commas, and frontend tests mock `fetch`, so only the user's manual check proves it end to end: tick two categories with the backend running.
   - Fallback if it ever fails: build the query with literal commas. The values are digits and dashes only.
2. **R2. Changes to existing tests.** About 40 `mockFetch` calls change mechanically (section 13). The rule "no other assertion changes; otherwise stop and report" keeps card 7's coverage intact.
3. **R3. Typing a year into a date input** sends a request for each intermediate valid date (`0002-…`, `0020-…`, …). The latest request wins, and those intermediate values briefly appear in the URL. If this turns out to be noisy, the user can choose to commit dates on blur instead. That would no longer be "every change", so it is not done now.
4. **R4. Stale templates and categories.**
   - A template whose category was archived after the page loaded, or a template that was deleted, gives a page-level 409 or 404 message on click. The button stays until the page is reloaded.
   - Fetching templates or categories again after such errors is out of scope (as card 7 R7).
5. **R5. Apply is not idempotent.** A network error after the server committed means a retry creates a duplicate. Double clicks are prevented by `mutating` (QT4). A 2xx body that is not JSON shows "Unexpected error", although the expense exists (as card 7 R8).
6. **R6. Server "today".** The applied expense is dated in `APP_TIME_ZONE`, so it may fall outside a filter that seems to include "today" in the browser's zone near midnight. The status message confirms the save.
7. **R7. One `pageError` slot** (card 7 R12). A templates-load error can be replaced by a later error, or cleared by the next user action. The bar stays hidden until the page is reloaded.
8. **R8. jsdom date inputs.** Tests use `fireEvent.change`, as in card 7 R4. Real browser behaviour (R3) is not covered by automated tests.
9. **R9. Card 9 and navigation.** The URL code is local to the page and keeps `location.pathname` and `hash`. If card 9 adds a router, reading and writing move to its search-params API, and `parseFilters` and `filtersToSearch` stay as they are.
10. **R10. A brief "Loading…" on every filter change** (Q6). This is accepted.

## Out of scope
- **From the card:** managing templates from the UI (create, edit, delete), and saved filter presets.
- **Templates:**
  - overriding the amount or note on apply (the endpoint supports it, but the button sends no body);
  - fetching templates or categories again after a 404 or 409.
- **History and URL:**
  - Back/Forward through filter changes (Q4);
  - `page` or `size` in the URL (Q1);
  - a user-selectable page size.
- **Client behaviour:**
  - client-side checks such as `from` ≤ `to`, or date `min`/`max` hints;
  - debouncing;
  - sorting options;
  - field-level error display under the filter controls;
  - a retry button.
- **Other cards and areas:**
  - navigation, a router and the "Month" page (card 9);
  - mobile layout polish;
  - any backend change, including an IT for `%2C` (R1);
  - end-to-end tests, MSW and new dependencies.
