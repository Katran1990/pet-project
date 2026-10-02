# Plan: Monthly report page with limits

Trello card: https://trello.com/c/vKrf3QD1/17-monthly-report-page-with-limits
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add a "Month" page to the frontend and a simple Expenses / Month tab switcher in `App`. The page has:
- a month switcher (Previous month, Next month), starting at the current month;
- the month total, taken from `totalAmount`;
- a report table with the columns category, amount, share, limit and remaining, where rows with a negative remaining are highlighted;
- an inline form in each row that sets the limit (`PUT /api/budget-limits`) or clears it (`DELETE /api/budget-limits/{id}`), after which the report is loaded again.

Only the frontend changes. No dependency is added. The page calls only `/api/reports/by-category` and `/api/budget-limits`.

## Acceptance criteria
- [ ] Month switcher (previous/next, defaults to current month) reloads the report
- [ ] Table columns: category, amount, share, limit, remaining; rows with negative remaining are highlighted
- [ ] Month total displayed above the table from totalAmount
- [ ] Each row has an inline form to set or clear the limit, calling PUT / DELETE /api/budget-limits; the report reloads after a change
- [ ] No sums, shares or remainders are computed on the client

### User decisions (implemented as given)
- **D1. Navigation.** `App` gets a simple tab switcher (Expenses / Month) that uses component state. There is no router, no new dependency, and the URL does not change.
- **D2. Rows and forms.** Only rows returned by the report get the inline limit form. A category with neither expenses nor a limit in that month is not shown and gets no form; that would be a separate card. The page never calls `/api/categories`.

### How the criteria are interpreted
- **AC1, "defaults to current month":**
  - This is the browser's **local** calendar month at mount, from the existing `localIsoDate`.
  - `GET /api/reports/by-category` requires `month` and has no server default, unlike `GET /api/expenses`. So the client must name the month (decision 4).
- **AC1, "reloads the report":**
  - Every Previous or Next click moves the month by one calendar month, with year rollover, and sends one `GET /api/reports/by-category?month=YYYY-MM`.
  - The latest request wins.
  - The month is component state only. It is not in the URL (D1).
- **AC2:**
  - The five data columns come in the card's order.
  - A sixth column, "Change limit", holds the inline form (AC4). Card 7's table works the same way: it has an "Actions" column beyond the columns its criterion lists.
  - "Highlighted" means the class `over-limit` on the `<tr>`. It is set only when the server's `remaining` string starts with `-`. `"0.00"` and `null` are not highlighted.
- **AC3:** `totalAmount` is shown exactly as sent, above the table. It is also shown for an empty month (`"0.00"`).
- **AC4:**
  - **Set** is `PUT /api/budget-limits` with `{categoryId, month, amount}`.
  - **Clear** first sends `GET /api/budget-limits?month=` to find the limit id (report rows do not carry it), then `DELETE /api/budget-limits/{id}`.
  - After a successful change, one `GET /api/reports/by-category` is sent for the same month. Limit and remaining then come from that response, never from the PUT response.
- **AC5:**
  - `amount`, `share`, `limit`, `remaining` and `totalAmount` are rendered exactly as the API sends them. The share gets a `%` suffix, which is presentation, not arithmetic.
  - No `Number`, `parseFloat`, `toFixed` or `Intl` is used on money or share.
  - Rows stay in API order; the client does not sort.
  - The highlight looks only at the sign of the server's `remaining`, never at amount compared with limit.
  - The only arithmetic on the client is moving a `YYYY-MM` month by ±1 (decision 4). That is not a sum, a share or a remainder.

### Design decisions (one-line rationale each)
1. **The tab state lives in `App` (`useState<'expenses' | 'month'>`, default `'expenses'`), and only the active page is rendered.**
   - Switching tabs unmounts the other page, and coming back remounts it and loads its data again.
   - Reason: a hidden but mounted Month page would show a stale report after expenses change on the other tab.
   - Unmounting also aborts in-flight loads through the existing effect cleanups.
2. **Navigation markup:** `<nav aria-label="Pages">` with two `<button type="button">` elements, "Expenses" and "Month". The active one has `aria-current="page"`. No router and no URL change (D1). It is simpler than the full ARIA tabs pattern, which needs arrow-key handling (Q7).
3. **Folders:**
   - API code goes in `src/api/reports.ts` and `src/api/budgetLimits.ts`.
   - Page code goes in `src/report/`, which mirrors the backend package `report`, as `src/expense/` mirrors `expense`.
4. **Month handling** is a pure module, `src/report/yearMonth.ts`:
   - `currentYearMonth(now)` is `localIsoDate(now).slice(0, 7)`, which reuses the existing helper. Like card 7 decision 10, it is never `toISOString()`, which would give the UTC month.
   - `shiftMonth(month, delta)` uses integer arithmetic on year and month, not `Date`. This avoids time-zone and DST effects.
5. **A month change clears the report until the new one arrives**, so "Loading…" is shown. The previous load is aborted, and the latest wins. This follows card 8 decisions 7 and 10: rows, total and forms always belong to the month in the heading, and a form can never write into a month that is no longer shown.
6. **The month is shown as ISO `YYYY-MM`** (for example `2026-10`), in an `<h2>` between the two switcher buttons. The expense table shows ISO dates too, and no `Intl` locale differences reach the tests (Q6).
7. **The limit id is looked up when Clear is clicked:**
   - `GET /api/budget-limits?month={report.month}`, then the first entry whose `category.id` matches the row. The unique constraint allows at most one.
   - If no entry matches, a page-level message "This limit no longer exists. The report has been refreshed." is shown and the report is loaded again.
   - Reason: a report load stays one request, the id is always fresh, and there is no second state that could disagree with the report (Q3).
8. **The inline form** (`LimitForm`) is always visible:
   - a text input, prefilled with the row's `limit` exactly as sent, or empty;
   - a "Set limit" submit button;
   - a "Clear limit" button, disabled when the row has no limit.

   There is no confirmation before Clear, because a cleared limit can be set again at once (Q2).
9. **Inputs are sent as typed, with no client-side validation** (card 7 decisions 13 and 14):
   - `inputMode="decimal"`, `type="text"` and `noValidate`;
   - an empty input is sent as `amount: null`, so the server answers with `errors[{amount, "must not be null"}]`;
   - `"250"` is sent as `"250"`, and the server stores and returns `"250.00"`.
10. **After a successful set or clear:**
    - a status message is shown, and `reloadKey` is increased, which loads the report again for the same month;
    - the current report stays on screen during that reload, with no "Loading…" flash, as after card 7's mutations;
    - the PUT response body is ignored.
11. **Row forms are remounted after every successful report load.** Each form's `key` includes a `reportVersion` counter. So every input shows the server's normalised value (`"250.00"`, not the typed `"250"`), and stale row errors disappear. Unsaved drafts in other rows are discarded (R2).
12. **Limit changes run one at a time:**
    - A page-level `mutating` flag disables every row's Set and Clear button, and the two month buttons, while a PUT, or the GET+DELETE pair, is in flight.
    - This is the rationale of card 7 decision 16: one rule removes every interleaving, including a month switch during a change.
    - Inputs stay editable.
13. **Error handling:**
    - Errors are placed according to one table ("Error placement" below), implemented in the pure module `src/report/limitErrors.ts`. It reuses the message helpers in `expenseErrors.ts`, which are exported for this purpose.
    - `describeLoadError` gets the label `'report'`.
    - **Any outcome that loads the report again is page-level**, because the reload remounts the row forms and would wipe a row message.
14. **Null values:** a `null` limit is shown as "No limit", and a `null` remaining as "—". Clear is disabled when there is no limit.
15. **Highlight:**
    - `<tr className="over-limit">` when `row.remaining?.startsWith('-')`.
    - The minus sign in the cell is the cue that does not rely on colour.
    - The server sends `remaining` as a plain decimal with scale 2. For example, `ReportControllerIT` asserts `"-100.00"`.
16. **Changes are written to `report.month`**, the server's echo of the month the rows belong to. Decision 5 guarantees that it equals the selected month whenever rows are shown.
17. **The category cell is `<th scope="row">`.** Screen readers announce the category for every cell and button in the row, and tests find a row through its row header.
18. **One `pageError` slot and one status line, with card 7's rules:**
    - The month buttons, Set and Clear clear both.
    - A successful load never clears them.
    - Row messages are cleared when that row's Set or Clear is clicked, and when the forms remount (decision 11).

## Changes

### Existing state (verified in the code)
**Frontend:**
- **`package.json`:** the dependencies are `react`/`react-dom` only, so there is **no router**. The dev dependencies are Vitest 5, jsdom, Testing Library (react, dom, user-event, jest-dom).
- **`App.tsx`** renders `<main className="app"><ExpensesPage /></main>` and nothing else.
- **`api/client.ts`:**
  - `request(method: 'GET'|'POST'|'PUT'|'DELETE', path, {body?, signal?})`. Without a body it sends no `Content-Type`. A 204 resolves to `undefined`.
  - Errors are thrown as `ApiError {status | null, detail, fieldErrors}`. Several messages for one field are joined with `"; "`.
  - `isAbortError` exists.
- **`expense/expenseErrors.ts`:**
  - `NETWORK_MESSAGE`, `UNEXPECTED_MUTATION_MESSAGE`, `detailOrFallback` and `serverErrorMessage` are module-private.
  - `describeLoadError(error, 'expenses' | 'categories' | 'quickTemplates')` adds the `errors[]` entries for a 4xx.
  - `SubmitResult = { ok: true } | { ok: false; fieldErrors: FieldErrors; formError: string | null }` is exported. `ExpenseForm` stores `result.fieldErrors` in its state and reads `fieldErrors.amount ?? null` for the amount field.
- **`expense/localDate.ts`:** `localIsoDate(date)` returns the local `yyyy-MM-dd`.
- **`expense/ExpensesPage.tsx`** has the patterns this page copies:
  - load effects with an `AbortController` that ignore aborted results;
  - `reloadKey`, `mutating`, one `pageError` slot, and `<p role="status">`;
  - "Loading…" as `.hint`, and `.month-total` above the table.
  - It reads `window.location.search` at mount and normalises it with `replaceState` (card 8).
- **`App.css`:** `.app`, `.hint`, `.error`, `.field-error`, `.expense-table` (and its `th`/`td`), `td.amount`, `.month-total`, `.pager`, etc.
- **Tests:**
  - **`setup.ts`:**
    - stubs `fetch` before each test and fails any test that made a request without a route;
    - resets the URL to `/`;
    - restores real timers after each test.
  - **`fetchMock.ts`:** `mockFetch` with exact `"METHOD path?query"` routes, reply queues and the last reply repeating, plus `json`, `rawJson`, `problem`, `noContent`, `deferred` and `requests`.
  - **`fixtures.ts`:** `FOOD` (1), `TRANSPORT` (2), `OLD` (9), `expense`, `list` (with a required `totalAmount`) and `baseRoutes`.
  - **Vitest settings:** `TZ=Europe/Warsaw`, `unstubGlobals` and `restoreMocks`.
  - **No existing test renders `App`.**
- **`.oxlintrc.json`:** `react/rules-of-hooks` and `react/only-export-components` (`allowConstantExport`). **`tsconfig.app.json`:** `verbatimModuleSyntax`, `erasableSyntaxOnly`, no `noUncheckedIndexedAccess`.

**Backend (read only; nothing changes):**
- **`GET /api/reports/by-category?month=YYYY-MM`** (`ReportController`, `CategoryReportQuery(@NotNull YearMonth month)`):
  - A missing or empty `month` returns 400 `errors[{month, …}]`. A malformed one returns 400 `errors[{month, "invalid value"}]`. **There is no server default month.**
  - The response is `{month: "2026-10", totalAmount: "0.00", rows: [{category: {id, name, icon}, amount, share, limit, remaining}]}`:
    - money is a string with scale 2;
    - `share` is a string with scale 1, and `"0.0"` when the total is zero;
    - `limit` and `remaining` are `null` without a limit;
    - `remaining` is limit minus amount, and negative when the limit is exceeded (`"-100.00"`).
  - One row per category that has expenses and/or a limit in that month. Rows are sorted by amount desc, then category id. Archived categories are included.
  - **Rows carry no limit id and no `archived` flag.** `ReportControllerIT` R1 asserts that `limitId` and `category.archived` do not exist.
- **`PUT /api/budget-limits`** with `{categoryId, month: "uuuu-MM", amount}` is an upsert. It returns 200 with `{id, month, amount: "250.00", category: {id, name, icon}}`.
  - **`amount`:** `@NotNull @Positive @Digits(10,2) @DecimalMax("9999999999.99")`, sent as a string or a number.
  - **Errors:**
    - 400 `errors[{amount, …}]`. An 11-digit integer part gives two entries.
    - 400 `errors[{categoryId, "Category not found"}]`.
    - 400 **without** `errors[]` for an unreadable body, for example `"abc"` or a malformed month.
    - 409 `"Category is archived"`. This also applies to updating an existing limit of a category that was archived later.
- **`GET /api/budget-limits?month=YYYY-MM`** returns a bare array sorted by category id. There is at most one limit per category and month (`uq_budget_limit_category_month`).
- **`DELETE /api/budget-limits/{id}`** returns 204, or 404 `"Budget limit not found"`. It works for archived categories.
- `vite.config.ts` proxies `/api` in dev, and nginx forwards `/api/` in production. So relative URLs work in both.

### API calls made by the Month page
| When | Request | Fields used |
|---|---|---|
| Mount, Previous/Next month, after every successful set or clear, after a "limit gone" outcome | `GET /api/reports/by-category?month=YYYY-MM` | `month`, `totalAmount`, `rows[].category.id`, `rows[].category.name`, `amount`, `share`, `limit`, `remaining` |
| Set limit | `PUT /api/budget-limits` with `{"categoryId": 1, "month": "2026-10", "amount": "250"}` (the amount exactly as typed, or `null` when empty) | status only; the body is ignored (decision 10) |
| Clear limit, step 1 | `GET /api/budget-limits?month=YYYY-MM` | `id`, `category.id` |
| Clear limit, step 2 | `DELETE /api/budget-limits/{id}` (no body, no Content-Type) | status only |

The page never calls `/api/categories` (D2) or `/api/expenses`.

### Error placement
| Outcome | Report load (any trigger) | Set limit (PUT) | Clear limit (GET limits, then DELETE) |
|---|---|---|---|
| `fetch` rejects | Page-level: "Could not reach the server. Check your connection and try again." | Page-level, same text. The input keeps its value; no reload. | Page-level, same text; no reload |
| 400 with non-empty `errors[]` | Page-level: "Could not load the report: {field}: {message}" (entries joined with "; ") | `amount` → next to the row's input (`aria-invalid`, `aria-describedby`). Any other field → row-level `"{field}: {message}"`, joined with "; ". No reload. | Row-level "Could not clear the limit: {detail}" (not reachable in practice) |
| 409 | Page-level "Could not load the report: {detail}" | Row-level "Could not set the limit: {detail}", for example "…: Category is archived". No reload. | Row-level "Could not clear the limit: {detail}" |
| 404 from DELETE, or no matching entry in the month's limits | — | — | Page-level "This limit no longer exists. The report has been refreshed.", then a reload |
| Any other 4xx, including 400 without `errors[]` | Page-level "Could not load the report: {detail}" | Row-level "Could not set the limit: {detail}", for example "…: Failed to read request" for `12,50` | Row-level "Could not clear the limit: {detail}" |
| 5xx, with any body | "Server error ({status}): {detail}", or "Server error ({status})" without a detail | Same text, page-level; no reload | Same; no reload |
| Neither an `ApiError` nor an abort, for example a 2xx body that is not JSON | Page-level "Could not load the report: unexpected error." | Page-level "Unexpected error. Please try again."; no reload | Same |
| Aborted (unmount, or a newer month) | Ignored | — (mutations have no signal) | — |

- `{detail}` falls back to `request failed with status {status}`, as in card 7.
- **Row-level messages:**
  - The message for the amount is a `<p className="field-error">` that the input references.
  - Other row messages are a `<p role="alert" className="error">` inside that row's form.
- **One `pageError` slot** (decision 18). After a failed reload that follows a successful change, the previous report stays on screen with the alert, as in card 7.

### 1. New file: `frontend/src/api/reports.ts`
```ts
import { request } from './client.ts'
import type { ExpenseCategory } from './expenses.ts'

// Money and share are strings, rendered as sent (AC5). limit and remaining are null when the
// category has no limit in that month. Rows carry no limit id (see budgetLimits.ts).
export type CategoryReportRow = {
  category: ExpenseCategory // the backend's CategorySummary {id, name, icon}
  amount: string
  share: string
  limit: string | null
  remaining: string | null
}
export type CategoryReport = { month: string; totalAmount: string; rows: CategoryReportRow[] }

export function getCategoryReport(month: string, signal?: AbortSignal): Promise<CategoryReport> {
  return request<CategoryReport>('GET', `/api/reports/by-category?${new URLSearchParams({ month }).toString()}`, {
    signal,
  })
}
```
- `ExpenseCategory` is reused because it already has the shape of the backend's `CategorySummary`. No new type is added.
- Imports use explicit `.ts` extensions and `import type`.

### 2. New file: `frontend/src/api/budgetLimits.ts`
```ts
export type BudgetLimit = { id: number; month: string; amount: string; category: ExpenseCategory }
export type BudgetLimitInput = { categoryId: number; month: string; amount: string | null }

export const listBudgetLimits = (month: string, signal?: AbortSignal) =>
  request<BudgetLimit[]>('GET', `/api/budget-limits?${new URLSearchParams({ month }).toString()}`, { signal })
// Upsert: creates or updates the limit of (categoryId, month).
export const setBudgetLimit = (input: BudgetLimitInput) =>
  request<BudgetLimit>('PUT', '/api/budget-limits', { body: input })
export const deleteBudgetLimit = (id: number) => request<void>('DELETE', `/api/budget-limits/${id}`)
```

### 3. New file: `frontend/src/report/yearMonth.ts` (pure, no React)
```ts
// The browser's local month as YYYY-MM (reuses localIsoDate; never toISOString(), the UTC date).
export function currentYearMonth(now: Date): string   // localIsoDate(now).slice(0, 7)

// Moves a YYYY-MM month by delta calendar months. Integer arithmetic on year*12 + month - 1, no Date:
// no time-zone or DST effects. Year padded to 4 digits, month to 2.
export function shiftMonth(month: string, delta: number): string
```
- Inputs always come from `currentYearMonth` or `shiftMonth`, so `shiftMonth` does not validate. Its `Number(...)` calls work on the year and month parts, never on money.
- It imports `localIsoDate` from `../expense/localDate.ts`. The file is not moved, so the diff stays small.

### 4. Change: `frontend/src/expense/expenseErrors.ts`
- Add `export` to `NETWORK_MESSAGE`, `UNEXPECTED_MUTATION_MESSAGE`, `detailOrFallback` and `serverErrorMessage`. There is no other change to them, so the texts stay the same on both pages.
- `describeLoadError(error, what: 'expenses' | 'categories' | 'quickTemplates' | 'report')`. Add the label `report: 'Could not load the report'` to `LOAD_ERROR_LABELS`.
- `SubmitResult` is already exported. The Month page reuses it unchanged as the result type of its limit handlers (sections 6–8).
- The existing outcomes do not change, and no existing test changes.

### 5. New file: `frontend/src/report/limitErrors.ts` (pure)
```ts
import type { FieldErrors } from '../api/client.ts'

export type LimitErrorOutcome =
  | { target: 'row'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean }
  | { target: 'ignore' }

export const LIMIT_GONE_MESSAGE = 'This limit no longer exists. The report has been refreshed.'
export function classifyLimitError(error: unknown, operation: 'set' | 'clear'): LimitErrorOutcome
```
**Rules (the "Error placement" table):**
- An abort → `ignore`.
- Not an `ApiError` → page, `UNEXPECTED_MUTATION_MESSAGE`.
- `status === null` → page, `NETWORK_MESSAGE`.
- ≥ 500 → page, `serverErrorMessage`.
- **`set`:**
  - a 4xx with non-empty `fieldErrors` → row. Its `fieldErrors` holds only the `amount` entry (`{amount}` when present, otherwise `{}`). The other fields go to `formError` (`"{field}: {message}"`, joined with "; ", or `null`);
  - any other 4xx → row, with `fieldErrors: {}` and `` `Could not set the limit: ${detailOrFallback(…)}` ``.
- **`clear`:**
  - 404 → page, `LIMIT_GONE_MESSAGE`, `refresh: true`;
  - any other 4xx → row, with `fieldErrors: {}` and `` `Could not clear the limit: ${detailOrFallback(…)}` ``.
- `refresh` is `false` everywhere else.

**Result type and why the classifier is separate:**
- The page's handlers return the existing `SubmitResult` from `expenseErrors.ts` (`{ok: true} | {ok: false; fieldErrors; formError}`), so no new result type is added.
- `classifyLimitError` stays separate from `classifyMutationError`, because that one maps a 409 onto a `categoryId` field, and the limit form has no category field.

### 6. New file: `frontend/src/report/LimitForm.tsx`
**Props:**
- `categoryName: string`;
- `limit: string | null`;
- `busy: boolean`;
- `onSet(amount: string | null): Promise<SubmitResult>`;
- `onClear(): Promise<SubmitResult>`.

**State:**
- `amount`, initially `limit ?? ''`;
- `fieldErrors: FieldErrors`, initially `{}`;
- `formError`.

As in `ExpenseForm`, `amountError = fieldErrors.amount ?? null`.

**Markup** (inside the row's last cell):
```
<form noValidate className="limit-form" onSubmit=…>
  <input type="text" inputMode="decimal" autoComplete="off"
         aria-label={`Limit for ${categoryName}`} value onChange
         aria-invalid={amountError ? 'true' : undefined} aria-describedby={amountError ? errorId : undefined} />
  <button type="submit" disabled={busy}>Set limit</button>
  <button type="button" disabled={busy || limit === null} onClick=…>Clear limit</button>
  {amountError && <p id={errorId} className="field-error">{amountError}</p>}
  {formError && <p role="alert" className="error">{formError}</p>}
</form>
```
- The form has no accessible name of its own, so `getByLabelText('Limit for Food')` matches only the input.
- **Submit:**
  1. Call `preventDefault()`.
  2. Clear both messages (`setFieldErrors({})`, `setFormError(null)`).
  3. Call `onSet(amount === '' ? null : amount)`.
  4. On `ok: false`, store `result.fieldErrors` and `result.formError`.
- **Clear:** clear both messages, call `onClear()`, and on `ok: false` store the returned messages.
- Pressing Enter in the input submits that row's form only.
- `errorId` comes from `useId()`.

### 7. New file: `frontend/src/report/ReportTable.tsx`
**Props:**
- `report: CategoryReport`;
- `version: number` (decision 11);
- `busy: boolean`;
- `onSetLimit(row, amount): Promise<SubmitResult>`;
- `onClearLimit(row): Promise<SubmitResult>`.

**Rendering:**
- When `rows.length === 0`, it renders `<p>No expenses or limits in this month.</p>` and no table.
- Otherwise it renders `<table className="report-table" aria-label={`Report for ${report.month}`}>` with the headers `<th scope="col">` Category, Amount, Share, Limit, Remaining and Change limit. Rows come in API order, with `key={row.category.id}` and `className={row.remaining?.startsWith('-') ? 'over-limit' : undefined}`. The cells are:
  - `<th scope="row">{category.name}</th>`;
  - `<td className="amount">{amount}</td>`;
  - `<td className="amount">{`${share}%`}</td>`;
  - `<td className="amount">{limit ?? 'No limit'}</td>`;
  - `<td className="amount">{remaining ?? '—'}</td>`;
  - `<td><LimitForm key={`${version}:${category.id}`} categoryName limit busy onSet={(amount) => onSetLimit(row, amount)} onClear={() => onClearLimit(row)} /></td>`.
- The total is rendered by the page, above this component (section 8), as on the Expenses page.

### 8. New file: `frontend/src/report/MonthReportPage.tsx`
**State:**
- `month`, with a lazy initial value `() => currentYearMonth(new Date())`;
- `report: CategoryReport | null`;
- `reportVersion`;
- `reloadKey`;
- `mutating`;
- `pageError: string | null`;
- `status: string | null`.

**Effect** on `[month, reloadKey]`:
- Use the card 7 pattern: a fresh `AbortController` and `getCategoryReport(month, signal)`, and ignore the result or error when the signal is aborted.
- On success, call `setReport(result)` and `setReportVersion((v) => v + 1)`.
- On error, `describeLoadError(error, 'report')` → `pageError`, ignoring `null`.
- It never clears `pageError`.

**Handlers.** Both limit handlers return `Promise<SubmitResult>`.
- **`handleMonthChange(delta: -1 | 1)`:** clear `pageError` and `status`, then `setMonth((current) => shiftMonth(current, delta))` and `setReport(null)` (decision 5). The two updates are batched, so one request is sent.
- **`handleSetLimit(reportMonth, row, amount)`:**
  1. Clear `pageError` and `status`, then `setMutating(true)`.
  2. Await `setBudgetLimit({ categoryId: row.category.id, month: reportMonth, amount })`.
  3. On success, set the status `` `Limit set for ${row.category.name}.` ``, increase `reloadKey` and return `{ok: true}`.
  4. On error, apply `classifyLimitError(error, 'set')`:
     - `row` → return `{ok: false, fieldErrors: outcome.fieldErrors, formError: outcome.formError}`;
     - `page` → set `pageError`, increase `reloadKey` if `refresh`, and return `{ok: false, fieldErrors: {}, formError: null}`;
     - `ignore` → return `{ok: false, fieldErrors: {}, formError: null}`.
  5. `finally`: `setMutating(false)`.
- **`handleClearLimit(reportMonth, row)`:**
  1. The same start as `handleSetLimit`.
  2. Await `listBudgetLimits(reportMonth)` and find the entry with `category.id === row.category.id`.
  3. If there is none, set `pageError = LIMIT_GONE_MESSAGE`, increase `reloadKey` and return `{ok: false, fieldErrors: {}, formError: null}`.
  4. Otherwise await `deleteBudgetLimit(limit.id)`, set the status `` `Limit cleared for ${row.category.name}.` ``, increase `reloadKey` and return `{ok: true}`.
  5. Errors go through `classifyLimitError(error, 'clear')`, the same way as for set.
- `reportMonth` is always `report.month` (decision 16).

**Markup:**
1. `<h1>Month</h1>`.
2. The page alert `<p role="alert" className="error">`.
3. `<p role="status">{status}</p>`.
4. `<div role="group" aria-label="Select month" className="month-switcher">`, containing:
   - `<button type="button" disabled={mutating}>Previous month</button>`;
   - `<h2>{month}</h2>`;
   - `<button type="button" disabled={mutating}>Next month</button>`.
5. "Loading…" (`.hint`) while `report === null && pageError === null`.
6. When `report !== null`:
   - `<p className="month-total">Total: <strong>{report.totalAmount}</strong></p>`;
   - then `<ReportTable report version={reportVersion} busy={mutating} onSetLimit={(row, amount) => handleSetLimit(report.month, row, amount)} onClearLimit={(row) => handleClearLimit(report.month, row)} />`.

### 9. Change: `frontend/src/App.tsx`
```tsx
type Tab = 'expenses' | 'month'

function App() {
  const [tab, setTab] = useState<Tab>('expenses')
  return (
    <main className="app">
      <nav aria-label="Pages" className="tabs">
        <button type="button" aria-current={tab === 'expenses' ? 'page' : undefined} onClick={() => setTab('expenses')}>Expenses</button>
        <button type="button" aria-current={tab === 'month' ? 'page' : undefined} onClick={() => setTab('month')}>Month</button>
      </nav>
      {/* Only the active page is mounted, so the other one reloads its data when shown again (decision 1). */}
      {tab === 'expenses' ? <ExpensesPage /> : <MonthReportPage />}
    </main>
  )
}
```
- The `./App.css` import and the default export stay.
- `ExpensesPage` is not changed. When it remounts, it reads the URL query string that it left behind, so its filters come back. The Month page ignores the query string.

### 10. Change: `frontend/src/App.css`
- **`.tabs`:** a flex row with a gap and `margin-bottom: 1rem`. `.tabs button[aria-current='page']` is bold with an underline.
- **`.month-switcher`:** a flex row with `align-items: center` and a gap. Its `h2` has `margin: 0`.
- **The table:** add `.report-table` to the existing `.expense-table` selectors, so both tables look the same (`.expense-table, .report-table { … }` and the `th`/`td` rule).
- **The highlight:** `.report-table tr.over-limit th, .report-table tr.over-limit td { background: rgba(221, 51, 51, 0.15); }`.
- **The form:** `.limit-form` is a flex row with wrap and a gap, and its input has `width: 8rem`.
- No mobile work.

### 11. Change: `frontend/src/test/fixtures.ts`
- **Route helpers:**
  - `reportRoute(month) = `GET /api/reports/by-category?month=${month}``;
  - `limitsRoute(month) = `GET /api/budget-limits?month=${month}``;
  - `LIMIT_PUT_ROUTE = 'PUT /api/budget-limits'`;
  - `limitDeleteRoute(id) = `DELETE /api/budget-limits/${id}``.
- **`categorySummary(category: Category): ExpenseCategory`** returns exactly `{id, name, icon}`, the backend's `CategorySummary`.
  - Every new fixture or test value typed `ExpenseCategory` uses it, or a literal `{id, name, icon}` such as Gifts below. It never uses a full `Category` such as `FOOD`.
  - Reason: TypeScript accepts a full `Category` variable where `ExpenseCategory` is expected, because extra properties are allowed for a non-literal. The mocked payload would then silently gain `archived` and `createdAt`. The real endpoints never send them: `BudgetLimitControllerIT` and `ReportControllerIT` assert that `category.archived` is absent.
- **`reportRow(overrides)`:** the default is `{category: categorySummary(FOOD), amount: '10.00', share: '100.0', limit: null, remaining: null}`. It **never derives** `share` or `remaining` from other fields. A test that sets `limit` also sets `remaining` explicitly.
- **`categoryReport(month, rows, { totalAmount })`:** `totalAmount` is a **required** string and is never summed from the rows. This is the `list()` rule from card 7, applied to AC5.
- **`budgetLimit(overrides)`:** the default is `{id: 1, month: '2026-10', amount: '100.00', category: categorySummary(FOOD)}`.

### 12. Not changed
- **No new dependencies.** `package.json` and `package-lock.json` stay byte-identical. A router (`react-router` and similar) was rejected by D1. Table, form and date libraries are not needed: native elements and `localIsoDate` cover them.
- **Source files:** `ExpensesPage.tsx` and its components, `client.ts`, `expenses.ts`, `categories.ts`, `quickTemplates.ts`, `localDate.ts`, `expenseFilters.ts`, `main.tsx` and `index.css`.
- **Test infrastructure:** `setup.ts` and `fetchMock.ts`. **All existing test files** stay unchanged.
- **Configuration:** `vite.config.ts`, tsconfig, oxlint config, `nginx.conf`, `Dockerfile` and CI. The existing `npm test` step runs the new tests.
- **Repository files:** `README.md` (the endpoints are already documented, and no command changes), `docs/wallet-backlog.md` and `CLAUDE.md`.
- **The whole backend.** `./gradlew test` is not required.
- **No git operations.**

### Implementation order
1. Sections 1–3, then `yearMonth.test.ts` (Y1–Y2). Run only that file: `cd frontend && npm test -- src/report/yearMonth.test.ts`.
2. Sections 4–5. The full `cd frontend && npm test` must be green with **no change to existing tests**.
3. Sections 6–10. `npm test` must still be green; the existing tests do not render `App`.
4. Section 11, then the new tests: `MonthReportPage.report.test.tsx`, `MonthReportPage.limits.test.tsx` and `App.test.tsx`.
5. Final check: `cd frontend && npm test && npm run lint && npm run build`, all green. `tsc -b` type-checks the tests too.

If any existing test fails at any step, stop and report the failure. Do not adapt the test.

**Forbidden at every step, for both the implementer and the test writer:**
- `./gradlew bootRun`, and any other way of starting the backend;
- `npm run dev` against a backend;
- any connection to the dev Postgres (`postgres:5432`: psql, JDBC, anything). Frontend tests mock `fetch` and need neither a backend nor a database;
- `npm install`/`uninstall` or any other change to dependencies;
- deleting `frontend/node_modules`;
- any change under `backend/`;
- git operations.

A manual browser check against a running backend is for the user only.

## Tests
Done means `cd frontend && npm test`, `npm run lint` and `npm run build` are all green.

**Conventions.** These are the ones from cards 7 and 8:
- **`fetch` and rendering:**
  - Every test uses `mockFetch` with exact routes. `setup.ts` fails any request that has no route, so any call to `/api/categories` or `/api/expenses` from the Month page fails the test.
  - No `StrictMode`, so request counts are exact.
  - Request sequences are asserted with `requests(mock).map((e) => `${e.method} ${e.url}`)`.
- **The clock:** every Month page test and every `App` test pins it before `render`:
  ```ts
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))
  ```
  - In `TZ=Europe/Warsaw` this is 2026-10-01 00:30 local time, while UTC is still September. So the current month is `2026-10`, and a UTC-based month would request `2026-09` and fail on the missing route.
  - Only `Date` is faked, so user-event and `findBy*` keep working. `setup.ts` restores real timers after each test.
- **Queries and input:**
  - Queries go by role or label.
  - Row helper: `rowOf(name) = screen.getByRole('rowheader', { name }).closest('tr')!`.
  - Cell texts come from `within(row).getAllByRole('cell').map((c) => c.textContent)`.
  - Inputs are changed with `user.clear` and `user.type`.
- **Money:** it is asserted as exact strings. Fixtures pass `totalAmount`, `share` and `remaining` explicitly. Distinct `totalAmount` values per response (`'9.00'`, `'10.00'`, …) mark which response is on screen.
- **The total pattern.** The markup is `<p className="month-total">Total: <strong>{value}</strong></p>`.
  - "Total: " and the `<strong>` value are separate nodes, so `getByText('Total: 136.00')` does **not** match.
  - Tests use the pattern of E21 in `frontend/src/expense/ExpensesPage.table.test.tsx`:
    ```ts
    const total = screen.getByText('136.00')
    expect(total.closest('p')).toHaveTextContent('Total: 136.00')
    ```
  - Each test picks a total that no cell or heading shows, so `getByText(value)` finds only the `<strong>`.
  - **"No total"** is asserted as `screen.queryByText('Total:')` being `null`. The paragraph's own text node is "Total: ", and Testing Library trims it before matching.
  - In the tables below, "the total is X" means this pattern.

### New file: `frontend/src/report/yearMonth.test.ts`
| # | Test | Proves |
|---|---|---|
| Y1 | `currentYearMonthUsesLocalDate`:<br>- Sanity: `new Date(2026, 9, 1, 0, 30).toISOString()` starts with `2026-09-30`.<br>- `currentYearMonth` of that date is `'2026-10'`.<br>- `currentYearMonth(new Date(2026, 0, 5))` is `'2026-01'` (padding). | AC1 (default = local month), decision 4 |
| Y2 | `shiftMonthMovesOneCalendarMonth` (table):<br>- `('2026-10', -1)` → `'2026-09'`<br>- `('2026-10', 1)` → `'2026-11'`<br>- `('2026-09', 1)` → `'2026-10'`<br>- `('2026-01', -1)` → `'2025-12'`<br>- `('2026-12', 1)` → `'2027-01'` | AC1 (previous/next, year rollover), decision 4 |

### New file: `frontend/src/report/MonthReportPage.report.test.tsx`
| # | Test | Proves |
|---|---|---|
| MR1 | `loadsCurrentLocalMonthOnMount`:<br>- The requests are exactly `[reportRoute('2026-10')]`, with no categories and no budget-limits request.<br>- The headings are level 1 "Month" and level 2 "2026-10".<br>- The table "Report for 2026-10" renders. | **AC1** (default month), D2, decision 4 |
| MR2 | `switcherReloadsReportForEachMonth`. Routes: `2026-10` → total `'10.00'`, `2026-09` → `'9.00'`, `2026-11` → `'11.00'`, each with `rows: []`, so the total is the only money on screen.<br>- Previous month → heading "2026-09", and the total is 9.00.<br>- Next month → "2026-10", and the total is 10.00.<br>- Next month → "2026-11", and the total is 11.00.<br>- The requests are exactly the reports for `2026-10`, `2026-09`, `2026-10` and `2026-11`, in that order. | **AC1** |
| MR4 | `monthChangeClearsReportUntilLoaded`:<br>- The `2026-09` reply is deferred. After Previous month: "Loading…" is shown, with no table, no total and none of October's row headers.<br>- Resolve → September's table and total are shown, and "Loading…" is gone. | Decision 5 |
| MR5 | `latestMonthWins`:<br>- The `2026-09` reply is deferred. Previous month twice; `2026-08` replies with total `'8.00'`. Both replies have `rows: []`.<br>- Then resolve the September reply with `'9.00'`. The total stays 8.00, the heading stays "2026-08", and `queryByText('9.00')` stays `null`.<br>- The requests are the reports for `2026-10`, `2026-09` and `2026-08`. | Decision 5 |
| MR6 | `tableHasColumnsAndRowsInApiOrder`. Rows are served deliberately **not** sorted by amount:<br>- Transport (`categorySummary(TRANSPORT)`): `5.00` / `2.0` / `50.00` / `45.00`<br>- Food (`categorySummary(FOOD)`): `250.00` / `98.0` / `200.00` / `-50.00`<br>- Gifts (`{id: 3, name: 'Gifts', icon: null}`): `0.10` / `0.0` / `null` / `null`<br>Assertions:<br>- The column headers are exactly Category, Amount, Share, Limit, Remaining, Change limit.<br>- The row headers are in API order: Transport, Food, Gifts.<br>- The first four cells per row are `['5.00', '2.0%', '50.00', '45.00']`, `['250.00', '98.0%', '200.00', '-50.00']` and `['0.10', '0.0%', 'No limit', '—']`.<br>- Each row has an input "Limit for {name}" with the values `'50.00'`, `'200.00'` and `''`, and a "Set limit" button that is enabled.<br>- "Clear limit" is enabled for Transport and Food, and disabled for Gifts.<br>- There are exactly 3 "Set limit" buttons: one form per report row. | **AC2**, AC4 (a form per row), D2, decisions 8 and 14 |
| MR7 | `highlightsOnlyRowsWithNegativeRemaining`. The `remaining` values are `'-0.01'`, `'0.00'`, `'5.00'` and `null`. Only the first row `toHaveClass('over-limit')`; the other three do not. | **AC2** (highlight), decision 15 |
| MR8 | `monthTotalIsTotalAmountAboveTable`:<br>- (a) Rows `10.00` and `20.00` with `totalAmount '136.00'`, which is deliberately not their sum. The total is 136.00, asserted with exactly the two lines shown in the conventions. The total comes before the table in DOM order (`total.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING`), as in E21.<br>- (b) `rows: []` with `'0.00'` → the total is 0.00, the text "No expenses or limits in this month." is shown, and there is no table. | **AC3** |
| MR9 | `rendersServerValuesVerbatimWithoutArithmetic`. One response in which **nothing adds up**:<br>- A: amount `10.00`, share `87.6`, limit `100.00`, remaining `7.77` (not 90.00)<br>- B: amount `500.00`, share `0.1`, limit `100.00`, remaining `25.00` (over the limit by the numbers, but positive according to the server)<br>- C: amount `1.00`, share `50.0`, limit `100.00`, remaining `-3.00` (under the limit by the numbers, but negative according to the server)<br>- D: amount `9999999999.99`, share `100.0`, limit `null`, remaining `null`<br>- E: amount `0.10`, share `0.0`, limit `9999999999.99`, remaining `9999999999.89`<br>- `totalAmount '1234567890123456.78'`; `Number` would print it as `1234567890123456.8`.<br>Assertions:<br>- Every cell shows exactly the served string. The shares, which add up to 238.3, are shown as `87.6%`, `0.1%`, and so on.<br>- The total is 1234567890123456.78.<br>- Row A's input has the value `'100.00'`, not `'100'`.<br>- **Only row C** has `over-limit`, so the highlight follows the server's `remaining` and not amount compared with limit.<br>- Rows stay in API order. | **AC5**, AC2, AC3, decisions 14 and 15 |
| MR10 | `loadErrorsArePageLevelAndSwitcherStaysUsable`:<br>- (a) Report 500 problem "Unexpected error" → the alert "Server error (500): Unexpected error", no table and no total. Next month → 200 → the table is shown and the alert is gone.<br>- (b) A `TypeError` rejection → the network message.<br>- (c) 400 `errors[{month, "invalid value"}]` → "Could not load the report: month: invalid value".<br>- (d) `rawJson('not json')` → "Could not load the report: unexpected error.". | Error placement, decision 18 |

### New file: `frontend/src/report/MonthReportPage.limits.test.tsx`
Unless stated otherwise, the October report has Food (limit `null`) and Transport (limit `'50.00'`, remaining `'45.00'`). Both rows use `categorySummary(…)`.

| # | Test | Proves |
|---|---|---|
| LM1 | `setLimitPutsTypedAmountAndReloadsReport`:<br>- Type `250` into "Limit for Food" and click "Set limit" in Food's row.<br>- The PUT has `Content-Type: application/json` and a body exactly `{categoryId: 1, month: '2026-10', amount: '250'}`, where `typeof amount === 'string'`.<br>- The second report response has Food with limit `'250.00'` and remaining `'240.00'`. It is shown, and the input now holds `'250.00'`.<br>- The status is "Limit set for Food.".<br>- The requests are exactly the October report, `PUT /api/budget-limits`, and the October report again.<br>- Then Previous month clears the status. | **AC4** (set and reload), decisions 9, 10, 11 and 18 |
| LM2 | `changesApplyToTheDisplayedMonth`:<br>- Previous month, so the September report is shown, with `month: '2026-09'`.<br>- Set a Transport limit. The PUT body has `month: '2026-09'`, and the reload is `reportRoute('2026-09')`.<br>- Clear in a September row → `limitsRoute('2026-09')`. | AC1 + AC4, decision 16 |
| LM3 | `displayedLimitComesFromReloadNotFromPutResponse`:<br>- The PUT replies with `amount '111.11'`. The reload returns Food with limit `'222.22'` and remaining `'-5.00'`.<br>- The cells show `222.22` and `-5.00`, the row is highlighted, the input holds `'222.22'`, and `queryByText('111.11')` is `null`. | **AC5**, AC4, decision 10 |
| LM4 | `clearLimitResolvesIdAndDeletes`:<br>- (a) Click "Clear limit" in Transport's row. `limitsRoute('2026-10')` returns `[budgetLimit({id: 5, category: categorySummary(FOOD)}), budgetLimit({id: 7, category: categorySummary(TRANSPORT)})]`, so each category is exactly `{id, name, icon}`, as in the real payload.<br>  - Then `DELETE /api/budget-limits/7`, with no body and no Content-Type. Id 7 is the matching one, not the first entry.<br>  - The reload shows Transport with "No limit" and "—", an empty input, and "Clear limit" disabled.<br>  - The status is "Limit cleared for Transport.".<br>  - The requests are exactly the report, the limits GET, `DELETE /api/budget-limits/7`, and the report again.<br>- (b) A row with amount `0.00` and a limit is cleared. The reload no longer contains it, so its row is gone. | **AC4** (clear and reload), decision 7 |
| LM5 | `clearWhenLimitIsAlreadyGone`:<br>- (a) The limits GET returns `[]`. No DELETE is sent. The page alert is "This limit no longer exists. The report has been refreshed.", and the report is loaded again.<br>- (b) DELETE returns 404 "Budget limit not found". The same alert is shown, and the report is loaded again. | Decision 7, error placement |
| LM6 | `setValidationErrorsShownNextToRowInput`:<br>- (a) Type `0`. The PUT returns 400 `errors[{amount, "must be greater than 0"}]`.<br>  - Food's input is `aria-invalid="true"` and `toHaveAccessibleDescription('must be greater than 0')`, and it still holds `0`.<br>  - There is no page alert and no reload; the requests are the report and the PUT.<br>  - Transport's input is not invalid.<br>- (b) An empty input → the PUT body has `amount: null` → 400 `errors[{amount, "must not be null"}]`, shown next to the input.<br>- (c) Two `amount` entries → the description joins them with "; ".<br>- (d) `errors[{categoryId, "Category not found"}]` → `within(row).getByRole('alert')` reads "categoryId: Category not found", and the input is not invalid. | AC4 (Problem Details), decision 9, error placement |
| LM7 | `setConflictAndUnreadableBodyShownInRow`:<br>- (a) 409 "Category is archived" → the alert in Food's row reads "Could not set the limit: Category is archived". There is no page alert and no reload.<br>- (b) Type `12,50` → 400 "Failed to read request" without `errors[]` → "Could not set the limit: Failed to read request".<br>- (c) Click Set again after (b): the row's alert is cleared before the new reply arrives. | Error placement, risk R4 |
| LM8 | `networkServerAndUnexpectedErrorsArePageLevel`:<br>- **PUT:**<br>  - rejects with a `TypeError` → the network message;<br>  - 500 → "Server error (500): Unexpected error";<br>  - `rawJson('not json', 200)` → "Unexpected error. Please try again.".<br>- **Clear:**<br>  - the limits GET returns 500 → the server message, and no DELETE is sent;<br>  - the DELETE rejects → the network message.<br>- In every case there is no reload, the typed value is kept, the buttons are enabled again, and there is no row alert. | Error placement |
| LM9 | `limitChangesAreSerialised`:<br>- (a) The PUT is deferred. Every "Set limit" and "Clear limit" button, and Previous month and Next month, are disabled. A second click sends no second PUT.<br>  - Resolve → the reload runs, and everything is enabled again. Clear stays disabled where the limit is `null`.<br>  - There was exactly one PUT.<br>- (b) The DELETE is deferred → the same disabled set until it resolves. | Decision 12 |
| LM10 | `successfulReloadResetsEveryRowForm`:<br>- Transport: type `0` and Set → 400 with an amount error shown in its row.<br>- Type the draft `77` into Food's input, then type `250` into the Gifts row and Set → success → reload.<br>- After the reload, Transport's error is gone and its input shows `'50.00'`, and Food's input is back to `''`. | Decision 11 (documented behaviour, R2) |

### New file: `frontend/src/App.test.tsx`
| # | Test | Proves |
|---|---|---|
| N1 | `startsOnExpensesTab`:<br>- Routes: `baseRoutes()` and `GET /api/expenses?page=0&size=50`.<br>- `render(<App />)` → heading level 1 "Expenses". The navigation "Pages" has the buttons "Expenses" (`aria-current="page"`) and "Month" (no `aria-current`).<br>- The requests are exactly the Expenses page's three; there is no report request. | D1, decision 1 |
| N2 | `switchesToMonthAndBackWithoutChangingUrl`:<br>- Start at the URL `/?categoryIds=1`, and record `window.location.href` and `window.history.length`.<br>- Click "Month" → heading level 1 "Month". No "Expenses" heading is left, and "Month" has `aria-current="page"`.<br>- The requests made after the click are exactly `[reportRoute('2026-10')]`: no `/api/categories` and no `/api/expenses`.<br>- `href` and `history.length` are unchanged.<br>- Click "Expenses" → the Expenses page is shown again, and its list request is `GET /api/expenses?categoryIds=1&page=0&size=50`, so the filters come back from the URL.<br>- `href` and `history.length` are still unchanged. | **D1** (no URL change, no router), **D2** (no categories call), decision 1 |

### Existing tests
Card 7's and card 8's tests (E*, F*, P*, QT*, U*, C*, S*, L1) stay unchanged and must stay green. The only shared changes are new exports and one new label in `expenseErrors.ts`; existing outcomes do not change.

### Criterion → tests
- **AC1:**
  - default local month: MR1, Y1;
  - previous/next reloads, with year rollover: MR2, Y2;
  - load behaviour on switch: MR4, MR5;
  - changes apply to the shown month: LM2.
- **AC2:**
  - columns, order and null display: MR6;
  - highlight: MR7, MR9, LM3.
- **AC3:** MR8, MR9.
- **AC4:**
  - set: LM1, LM2;
  - clear and id lookup: LM4, LM5;
  - reload after a change: LM1, LM3, LM4;
  - Problem Details: LM6, LM7, LM8;
  - serialisation: LM9;
  - form reset: LM10;
  - one form per report row: MR6.
- **AC5:**
  - values shown as sent, including inconsistent ones: MR9;
  - the total is not a sum: MR8;
  - the reload, not the PUT response, is the source: LM3;
  - API order: MR6.

  Plus the fixture rule (section 11).
- **User decisions:** D1 → N1, N2. D2 → MR1, MR6, LM1, LM4, N2 (exact request lists).
- **Plan decisions:**
  - 4 → Y1, Y2;
  - 5 → MR4, MR5;
  - 7 → LM4, LM5;
  - 9 → LM1, LM6;
  - 10 → LM3;
  - 11 → LM1, LM10;
  - 12 → LM9;
  - 14 → MR6;
  - 15 → MR7, MR9;
  - 16 → LM2;
  - 18 → LM1, MR10.

## Risks and open questions

### Open questions
No acceptance criterion is blocked. Each question has a proposal (in bold) that the plan implements, with the alternative and its cost.
1. **Q1. Inline form layout.** **Proposal: an always-visible input prefilled with the current limit, "Set limit" and "Clear limit"** (decision 8).
   - Alternative: an "Edit limit" button that reveals the form. That is one more click and one more state per row.
2. **Q2. Confirmation before Clear.** **Proposal: none.** A limit can be set again at once, and the status message confirms the action.
   - Alternative: `window.confirm`, as for deleting an expense. That is one line, plus a stub in tests.
3. **Q3. Finding the limit id.** **Proposal: look it up when Clear is clicked** (decision 7). This costs one extra GET per clear.
   - Alternative A: load `GET /api/budget-limits?month=` together with every report and keep an id map. That means two requests per load, a second state that can disagree with the report, and a second route in every test.
   - Alternative B: the backend adds `limitId` to report rows. That is a backend change and a separate card. Afterwards Clear would need only the DELETE.
4. **Q4. The month after switching tabs.** **Proposal: the Month page opens at the current month every time it is shown**, because it is unmounted (decision 1).
   - Alternative: keep `month` in `App` and pass it down. That is about 5 lines, plus props on the page.
5. **Q5. Texts. Proposal: as listed:**
   - h1 "Month";
   - navigation "Pages", with the tabs "Expenses" and "Month";
   - "Previous month", "Next month" and the group "Select month";
   - the column "Change limit";
   - "No limit" and "—";
   - "Set limit", "Clear limit" and the input label "Limit for {name}";
   - "Limit set for {name}." and "Limit cleared for {name}.";
   - "No expenses or limits in this month.";
   - "Could not load the report", "Could not set the limit: …", "Could not clear the limit: …" and "This limit no longer exists. The report has been refreshed.";
   - the table name "Report for {month}";
   - the share with a `%` suffix.
6. **Q6. Month format.** **Proposal: ISO `2026-10`.**
   - Alternative: "October 2026" through `Intl.DateTimeFormat('en', {month: 'long', year: 'numeric'})`. That is a few lines, and the tests would assert the English text.
7. **Q7. Accessibility of the tabs.** **Proposal: buttons with `aria-current="page"`.**
   - Alternative: the full ARIA tabs pattern (`tablist`/`tab`/`tabpanel`, `aria-selected`, arrow-key focus). That is about 30 lines and two more tests.
8. **Q8. Upper bound for Next month.** **Proposal: none.** The backend accepts any month, so limits can be planned ahead.
9. **Q9. Archived categories.** The report includes them, but its rows have no `archived` flag. **Proposal:** Set stays enabled, and the server's 409 is shown in the row as "Could not set the limit: Category is archived". Clear works for them.
   - Alternative: the backend adds `archived` to report rows, so the client can disable Set. That is a backend change and a separate card.
10. **Q10. New dependencies: none.** Nothing needs confirmation.

### Risks
1. **R1. Browser month and `APP_TIME_ZONE`.** Near a month boundary, the browser's "current month" can differ from the server's zone. The report is still correct for the month shown, and the user can switch. No endpoint exposes the server's month, and adding one would be a backend change.
2. **R2. Drafts in other rows are discarded** when a successful change reloads the report (decision 11). Row errors in other rows disappear at the same time. This is accepted: changes are serialised and quick, and the inputs then show the server's values.
3. **R3. A stale report.**
   - Another tab or client can change limits between a load and a click. A Clear of a missing limit is handled (LM5). A Set always upserts.
   - A page left open does not refresh itself. Switching the month or the tab reloads it.
4. **R4. Comma decimals.** `12,50` gives a row-level "Could not set the limit: Failed to read request". This is the same accepted behaviour as card 7 Q7, and there is no client-side normalisation.
5. **R5. Unmounting during a change.** If the user switches tabs while a PUT or DELETE is in flight, the request still completes on the server. Its status message is lost, and the next visit to the Month page shows the result.
6. **R6. The URL keeps the Expenses filters while the Month tab is shown.** A browser reload opens the Expenses tab with those filters, because the tab is not in the URL (D1).
7. **R7. One `pageError` slot** (card 7 R12). A later message replaces an earlier one.
8. **R8. Highlight colour.** jsdom checks only the class, so the real colour contrast is checked manually. The minus sign is the cue that does not rely on colour.
9. **R9. Form inside a table cell.** This is valid HTML, because the form is entirely inside one `<td>`. A form spanning several cells would not be, so the input and the buttons stay in the last cell.

## Out of scope
- **From the card:** charts on the page (Grafana covers visualisation), exporting the report, and a multi-month view.
- **From D1 and D2:**
  - a router, and the tab or the month in the URL;
  - rows or forms for categories without expenses and without a limit in that month (a separate card if ever needed);
  - any call to `/api/categories`.
- **Client behaviour:**
  - client-side validation or normalisation of the limit amount;
  - a "Current month" button, a month picker, and a retry button;
  - keeping the selected month across tab switches (Q4);
  - confirmation before Clear (Q2);
  - category icons;
  - mobile layout polish.
- **Backend:** any change, including `limitId` or `archived` in report rows (Q3, Q9) and a server-side default month.
- **Testing extras:** end-to-end tests against a real backend, MSW, and visual tests.
- **Dependencies and files:** new dependencies, and changes to `README.md`, `docs/wallet-backlog.md`, CI, Docker or nginx.

## Changes after code review
Approved by the user after the first code review.
- **Decision 12, amended.** A row's Set and Clear buttons stay disabled from the start of a limit change until the follow-up report reload has settled (success or error). `MonthReportPage` sets `reloadPending` with every `reloadKey` increment (helper `reload()`), clears it in the load effect, and passes `busy={mutating || reloadPending}` to `ReportTable`. The month buttons are still disabled only by `mutating`.
  - Reason: `mutating` was cleared while the reload was in flight. A second Set could start, the reload then remounted every `LimitForm`, and the second change's row error was lost.
- **Tests.**
  - LM9 is updated to the amended decision 12. LM2 now waits for "Clear limit" to be enabled again after its reload before clicking it.
  - LM1 and LM4 wait for the status text instead of the always-present status element.
  - LM11 `rowButtonsStayDisabledUntilReloadSettles` is new: it covers the race fix, a failed reload, a month change while the reload is pending, the "limit gone" refresh, and the DELETE-404 refresh.
  - LM12 `clearRowErrorsAndMixedSetErrors` is new: it covers row-level Clear errors (a non-404 4xx from the limits GET or from the DELETE) and a PUT 400 with both an `amount` and a `categoryId` error.
