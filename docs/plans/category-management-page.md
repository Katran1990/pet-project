# Plan: Category management page

Trello card: https://trello.com/c/OG85Cbj1/19-category-management-page
Part of the "Wallet" feature: expenses by category with monthly limits.

## Goal
Add a third tab, "Categories", to the frontend. The page lists every category (archived ones included) and lets the user do four things: create a category, rename it or change its icon inline, archive it and restore it. It uses only the existing `GET`/`POST`/`PATCH /api/categories` endpoints. There are no backend changes and no new dependencies. Only the active page is mounted, so the expense form loads the changed categories when the user goes back to Expenses (AC7). That needs no change to `ExpensesPage`.

## Acceptance criteria
- [ ] 1. New "Categories" tab in the page navigation next to Expenses and Month
- [ ] 2. Table lists all categories from GET /api/categories?includeArchived=true (icon, name, status); archived rows are visually marked
- [ ] 3. A form above the table creates a category (name required, icon optional) via POST /api/categories; the list reloads after success
- [ ] 4. Each row can be edited inline (name, icon) via PATCH /api/categories/{id}; an empty icon clears it
- [ ] 5. Each row has an Archive / Restore button that toggles archived via PATCH
- [ ] 6. Server errors are shown next to the form: 409 for a duplicate name (case-insensitive) and 400 validation errors per field from errors[]
- [ ] 7. After switching back to Expenses, the expense form offers new and restored categories and no longer offers archived ones, without a browser reload
- [ ] 8. Frontend tests with mocked fetch cover create, the duplicate-name error, rename, archive and restore

### How the criteria are interpreted
- **AC1:**
  - A third `<button type="button">` "Categories" goes after "Month" in the existing `<nav aria-label="Pages">`. It uses the same `aria-current="page"` markup as the other two.
  - The tab lives in `App` state only. There is no router, and the URL does not change (card 9, D1).
  - As today, only the active page is mounted.
- **AC2:**
  - **Requests:** exactly one `GET /api/categories?includeArchived=true` on mount, and one after every successful mutation.
  - **Columns,** in the card's order: Icon, Name, Status, plus a fourth column, Actions. Card 7 and card 9 also add an action column beyond the ones their criteria list.
  - **Cells:**
    - Name is the row header (`<th scope="row">`).
    - A `null` icon is shown as "—".
    - Status is "Active" or "Archived".
  - **Archived marking:** the row gets `<tr className="archived">`, and CSS mutes its text. The word "Archived" is the cue that does not rely on colour.
  - **Order:** rows come in API order (the server sorts by id). The client does not sort, because ordering is out of scope.
- **AC3:**
  - **Form:** the form "New category" comes before the table. It has the inputs Name and Icon and the button "Add category".
  - **"Name required" is enforced by the server.** There is no client-side validation (card 7, decisions 13 and 14). An empty name is sent as `""`, and the server answers 400 `errors[{name, "must not be blank"}]`, shown next to Name.
  - **"Icon optional":** an empty icon is sent as `""`, and the server stores `null` (CLAUDE.md rule, `CreateCategoryRequest`).
  - **On success:**
    - the form is reset;
    - the status "Category added." is shown;
    - the list is loaded again.
  - The POST response body is ignored. The reload is the source of what is shown (card 7 E3, card 9 LM3).
- **AC4:**
  - **Edit mode:** "Edit" turns that row into an inline form with the inputs Icon and Name (prefilled) and the buttons Save and Cancel.
  - **Request:** Save sends `PATCH /api/categories/{id}` with `{name, icon}`. Both values are sent as typed, both keys are always present, and `null` is never sent.
  - **Empty icon:** it is sent as `""`. Per CLAUDE.md that means "clear", and `CategoryController` stores `null`.
  - **On success:**
    - edit mode closes;
    - the status "Category updated." is shown;
    - the list is loaded again.
- **AC5:**
  - **Button:** one per row, "Archive" for an active category and "Restore" for an archived one.
  - **Request:** `PATCH /api/categories/{id}` with exactly `{archived: true}` or `{archived: false}`. The other keys are left out, so the server leaves them unchanged.
  - There is no confirmation, because the action can be undone.
  - **On success:** the status `Category "{name}" archived.` or `Category "{name}" restored.` is shown, and the list is loaded again.
- **AC6: "next to the form" means the form that sent the request.** For POST that is the create form; for PATCH it is the edit form inside that row.
  - **409:** the server's `detail` ("Category name already exists") is shown under that form's **Name** input, with `aria-invalid` and `aria-describedby`. The only 409 these endpoints produce is the name constraint.
  - **400 with `errors[]`:** each entry goes to its field. `name` goes under the Name input and `icon` under the Icon input. Any other field becomes an alert inside that form, as `"{field}: {message}"`.
  - **400 without `errors[]`:** a form-level alert, "Could not add the category: …" or "Could not update the category: …".
  - **Page level instead:** network errors, 5xx responses and unexpected errors stay page-level, as on every other page.
  - **Archive and Restore have no form,** so their errors are page-level. The only failures they can really have are network errors, 5xx and 404.
- **AC7:**
  - No code change is needed.
  - `App` unmounts the inactive page. `ExpensesPage` calls `listAllCategories` once on mount, and gives `ExpenseForm` only the active subset (`ExpensesPage.tsx`, lines 33–55 and 252).
  - So going back to the Expenses tab remounts the page, which loads the categories again inside the same React tree, without a browser reload. Test N4 proves this.
- **AC8:** these tests use `mockFetch`:
  - CP4 (create);
  - CP7 and CR4 (duplicate name, on POST and on PATCH);
  - CR1 (rename);
  - CR8 (archive);
  - CR9 (restore).

### Design decisions (one-line rationale each)
1. **Folder `src/category/`.** It mirrors the backend package `category`, as `src/expense/` and `src/report/` mirror theirs. API functions stay in `src/api/categories.ts`.
2. **The tab state stays in `App`, and only the active page is rendered** (card 9, decision 1). This is what makes AC7 work without extra code.
3. **The client needs `'PATCH'`.** `request()` only accepts `'GET' | 'POST' | 'PUT' | 'DELETE'`, so `'PATCH'` is added to the union. Fetch does not upper-case `patch`, which is why the method is passed as the literal `'PATCH'` (C13 pins it).
4. **Loading the list:**
   - The list loads on mount and after each successful mutation, through `reloadKey`. The latest request wins: the previous one is aborted, and late results are ignored.
   - The current list stays on screen during a reload, as after card 7's mutations.
   - The bodies of POST and PATCH responses are ignored.
5. **Inline edit layout:** in edit mode, the Icon and Name cells become one `<td colSpan={2}>` that holds a `<form aria-label="Edit {name}">`.
   - Reason: a `<form>` cannot span several table cells (card 9, R9).
   - The Status cell and the Archive/Restore button stay visible in that row. Its Edit button is hidden.
6. **Only one row is in edit mode at a time** (`editingId` in the page).
   - Opening another row, or Cancel, drops the draft (as in card 7, decision 7).
   - Focus moves to the row's Name input (`autoFocus`), as with card 7's Amount input.
7. **Reloads do not remount the edit form.** Rows are keyed by id, and the form is rendered only for `editingId`. So an open draft and its errors survive a reload caused by another row. On the Month page, by contrast, a reload remounts every form.
8. **Mutations run one at a time.** The `mutating` + `reloadPending` pattern is reused from `MonthReportPage` (card 9, decision 12 as amended after review).
   - While any request is in flight, every button except the tabs is disabled.
   - Row buttons (Edit, Archive/Restore, Save, Cancel) also stay disabled until the reload that follows a mutation has settled. Without this, Edit right after a rename would prefill the old name from the stale list, and Save would write it back.
   - "Add category" depends on `mutating` only, because the create form never reads the list.
   - Inputs stay editable.
9. **Errors are placed by one table** ("Error placement" below), implemented in a pure module, `src/category/categoryErrors.ts`.
   - It reuses `NETWORK_MESSAGE`, `UNEXPECTED_MUTATION_MESSAGE`, `detailOrFallback` and `serverErrorMessage` from `expenseErrors.ts`, which are already exported.
   - It is a module of its own, like `limitErrors.ts`, because `classifyMutationError` maps a 409 onto `categoryId`.
   - Load errors use the existing `describeLoadError(error, 'categories')`.
10. **One `pageError` slot and one status line,** with card 7's rules:
    - Add category, Save, Archive, Restore, Edit and Cancel clear both when clicked.
    - A successful load never clears them.
11. **No client-side validation, normalisation or duplicate detection.** There is no `required` and no `maxLength`, and nothing is trimmed. The server is the only validator (card 7, decision 14).
12. **Styling reuses the existing table rules** by adding `.category-table` to the shared selectors.
    - The row's Save button is secondary, as "Set limit" is inside the report table (jetbrains-style Q5). "Add category" is primary, like "Add expense".
    - The create form gets the existing card style, because it is a direct child of `.app`.
13. **No new dependency.**

## Changes

### Existing state (verified in the code)
**Frontend:**
- **`App.tsx`:**
  - `type Tab = 'expenses' | 'month'`;
  - `<nav aria-label="Pages" className="tabs">` with two buttons;
  - `{tab === 'expenses' ? <ExpensesPage /> : <MonthReportPage />}`, so only the active page is mounted.
- **`api/client.ts`:**
  - `request(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path, {body?, signal?})`, which has **no PATCH**;
  - errors become `ApiError {status | null, detail, fieldErrors}`, and several messages for one field are joined with "; ".
- **`api/categories.ts`** has only:
  - `Category = {id, name, icon: string | null, archived, createdAt}`;
  - `listAllCategories(signal)` → `GET /api/categories?includeArchived=true`. That is exactly the URL AC2 names.
- **`expense/ExpensesPage.tsx`:**
  - The categories effect runs once on mount (`[]`).
  - `activeCategories = categories.filter(!archived)` goes to `ExpenseForm`. All categories go to `ExpenseFilterControls`, which labels archived ones "(archived)".
- **`expense/expenseErrors.ts`:**
  - exports `SubmitResult`, `NETWORK_MESSAGE`, `UNEXPECTED_MUTATION_MESSAGE`, `detailOrFallback` and `serverErrorMessage`;
  - `describeLoadError(error, 'categories')` gives "Could not load categories: …".
- **`report/MonthReportPage.tsx`** has the patterns this page copies:
  - the `reload()` helper with `reloadPending`;
  - a `NOTHING` result;
  - a page-level alert and `<p role="status">`.
- **`report/LimitForm.tsx`** has the form pattern: `run(action)` clears the messages, awaits, and stores them on `ok: false`.
- **`App.css`:**
  - `.app > form` is the form card. Its comment says it is only the expense form.
  - Table, `.hint`, `.error`, `.field-error`, `.field` and `.form-actions` rules exist.
  - `.report-table button[type='submit']` makes "Set limit" secondary.
- **Tests:**
  - **`setup.ts`** fails any request without a route.
  - **`fetchMock.ts`:** `mockFetch` with exact `"METHOD url"` keys, reply queues where the last reply repeats, plus `json`, `rawJson`, `problem`, `noContent`, `deferred` and `requests`. `requests` upper-cases the method.
  - **`fixtures.ts`:** `FOOD` (1), `TRANSPORT` (2), `OLD` (9, archived), `CATEGORIES_ROUTE`, `TEMPLATES_ROUTE` and `baseRoutes()`.
  - **`App.test.tsx` N1** asserts that the nav buttons are exactly `['Expenses', 'Month']`.
- **`tsconfig.app.json`:** `verbatimModuleSyntax` and `erasableSyntaxOnly`. **`.oxlintrc.json`:** `react/rules-of-hooks` and `react/only-export-components`.
- **`nginx.conf`** proxies `/api/` with no method restriction, and the backend has no CORS or security filter. So PATCH works in dev (Vite proxy) and in production.

### Backend contract (read only; nothing changes)
These facts were checked in `CategoryController`, `CreateCategoryRequest`, `UpdateCategoryRequest`, `CategoryResponse`, `ApiExceptionHandler`, `V2__create_categories_table.sql` and `CategoryControllerIT`.

| Request | Body | Success | Errors (all `application/problem+json`) |
|---|---|---|---|
| `GET /api/categories?includeArchived=true` | — | 200, a bare array of `{id, name, icon, archived, createdAt}`, archived ones included, sorted by id asc (`findAllByOrderByIdAsc`) | 400 for a non-boolean `includeArchived`; the client never sends one |
| `POST /api/categories` | `{name, icon}` | 201 with `Location`, and the created category | 400 `errors[]` (`name`: "must not be blank" or "size must be between 0 and 64"; `icon`: "size must be between 0 and 32"); 400 without `errors[]` for an unreadable body; 409 "Category name already exists" |
| `PATCH /api/categories/{id}` | any subset of `{name, icon, archived}` | 200, the updated category | 400 `errors[]` (`name`: "size must be between 1 and 64"; `icon`: "size must be between 0 and 32"); 400 without `errors[]` for an unreadable body; 404 "Category not found" (plus an `id` property); 409 "Category name already exists" |
| `DELETE` | — | — | 405, as intended |

- **`name`:**
  - It is stripped in both request records' compact constructors, before validation.
  - POST: `@NotBlank @Size(max = 64)`.
  - PATCH: `@Size(min = 1, max = 64)`. `null` or absent means unchanged, and `""` or blank gives 400 for `name`.
- **`icon`:**
  - It is not stripped. Max length 32.
  - POST: `""` is stored as `null`.
  - PATCH: `""` clears it (`setIcon(null)`), and `null` or absent leaves it unchanged (controller lines 61–63).
- **`archived` is accepted in PATCH** (`Boolean`, controller lines 64–66). `null` or absent means unchanged. Archiving has no other effect, and archived categories can still be renamed.
- **Duplicates:**
  - The table has the unique index `uq_category_name_lower` on `lower(name)`, and archived rows count too.
  - `ApiExceptionHandler.handleDataIntegrityViolation` maps that index to **409 "Category name already exists" for both POST and PATCH**.
  - `rejectsDuplicateNameCaseInsensitive` asserts the detail for POST. `patchToExistingNameReturns409` asserts status 409 and `instance` for PATCH, and the same map yields the same detail.
  - Renaming a category to another case of its own name is allowed (`patchSameNameDifferentCaseOfItself`).
  - `ConflictException` (409 "Category is archived") is thrown only by `ActiveCategoryLookup`, which the category endpoints do not use.
- **Problem Details shapes:**
  - 409: `{"type":"about:blank","title":"Conflict","status":409,"detail":"Category name already exists","instance":"/api/categories"}`. For PATCH, `instance` is `/api/categories/{id}`.
  - 400 validation: `{…,"title":"Bad Request","status":400,"detail":"Invalid request content.","instance":…,"errors":[{"field":"icon","message":…},{"field":"name","message":…}]}`. Entries are sorted by field, then by message.
  - 404: `{…,"status":404,"detail":"Category not found","id":7}`.
- **Messages** are Bean Validation defaults in the request locale (card 7, R9).

**Blockers: none.** Every criterion can be met with the existing endpoints.

### API calls made by the Categories page
| When | Request | Fields used |
|---|---|---|
| Mount, after every successful mutation, after a "category gone" outcome | `GET /api/categories?includeArchived=true` | `id`, `name`, `icon`, `archived` |
| Add category | `POST /api/categories` with `{"name": "<as typed>", "icon": "<as typed, may be "">"}` | status only |
| Save (inline edit) | `PATCH /api/categories/{id}` with `{"name": "<as typed>", "icon": "<as typed, may be "">"}` | status only |
| Archive / Restore | `PATCH /api/categories/{id}` with `{"archived": true}` or `{"archived": false}` | status only |

The page never calls `/api/expenses`, `/api/quick-templates`, `/api/reports` or `/api/budget-limits`. `setup.ts` enforces this in every test.

### Error placement
| Outcome | List load | Create (POST) | Edit (PATCH `{name, icon}`) | Archive / Restore (PATCH `{archived}`) |
|---|---|---|---|---|
| `fetch` rejects | Page: "Could not reach the server. Check your connection and try again." | Page, same text. The inputs keep their values; no reload | Page, same text. Edit mode and the draft are kept; no reload | Page, same text; no reload |
| 409 | Page: "Could not load categories: {detail}" | Under the create form's Name input: `{detail}` ("Category name already exists"); no reload | Under that row's Name input: `{detail}`; no reload | Page: "Could not archive the category: {detail}" or "Could not restore the category: {detail}" (not reachable in practice) |
| 400 with non-empty `errors[]` | Page: "Could not load categories: {field}: {message}" (the existing `describeLoadError`) | `name` → under Name, `icon` → under Icon. Any other field → an alert inside the create form, `"{field}: {message}"` joined with "; "; no reload | The same mapping inside the row's edit form; no reload | Page: "Could not archive/restore the category: {detail}" (not reachable) |
| 404 | Page: "Could not load categories: {detail}" | Alert in the create form: "Could not add the category: {detail}" (not reachable) | Page: "This category no longer exists. The list has been refreshed." Edit mode closes and the list is reloaded | Page: the same "gone" message, and the list is reloaded |
| Any other 4xx, including 400 without `errors[]` | Page: "Could not load categories: {detail}" | Alert in the create form: "Could not add the category: {detail}" | Alert in the row's form: "Could not update the category: {detail}" | Page: "Could not archive the category: {detail}" or "Could not restore the category: {detail}" |
| 5xx, with any body | "Server error ({status}): {detail}", or "Server error ({status})" without a detail | Page, same; no reload | Page, same; edit mode is kept | Page, same |
| Neither an `ApiError` nor an abort, for example a 2xx body that is not JSON | Page: "Could not load categories: unexpected error." | Page: "Unexpected error. Please try again." | Page, same | Page, same |
| Aborted (unmount, or a newer load) | Ignored | — (mutations have no signal) | — | — |

- `{detail}` falls back to `request failed with status {status}` (`detailOrFallback`).
- **Field messages** are `<p className="field-error">` elements, referenced by the input's `aria-describedby`, with `aria-invalid="true"` on the input.
- **Form-level messages** are `<p role="alert" className="error">` inside that form.

### 1. Change: `frontend/src/api/client.ts`
- The method union becomes `'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'`. Nothing else changes.

### 2. Change: `frontend/src/api/categories.ts`
```ts
// POST body. Both values are sent as typed: the server strips the name and stores an empty icon as null.
export type CategoryInput = { name: string; icon: string }

// PATCH body. An omitted key leaves that field unchanged; icon "" clears the icon (CLAUDE.md).
export type CategoryPatch = { name?: string; icon?: string; archived?: boolean }

export const createCategory = (input: CategoryInput) =>
  request<Category>('POST', '/api/categories', { body: input })

export const updateCategory = (id: number, patch: CategoryPatch) =>
  request<Category>('PATCH', `/api/categories/${id}`, { body: patch })
```
- `Category` and `listAllCategories` are unchanged. Only the comment above `listAllCategories` is extended: "…the expense filter and the Categories page show them; the expense form uses the active subset."

### 3. New file: `frontend/src/category/categoryErrors.ts` (pure)
```ts
export type CategoryOperation = 'create' | 'update' | 'archive' | 'restore'
export type CategoryErrorOutcome =
  | { target: 'form'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean }
  | { target: 'ignore' }

export const CATEGORY_GONE_MESSAGE = 'This category no longer exists. The list has been refreshed.'
export function classifyCategoryError(error: unknown, operation: CategoryOperation): CategoryErrorOutcome
```
- The labels are a private map:
  - `create` → "Could not add the category";
  - `update` → "Could not update the category";
  - `archive` → "Could not archive the category";
  - `restore` → "Could not restore the category".
- The form fields are `['name', 'icon']`.
- **Rules, in this order:**
  1. An abort → `ignore`.
  2. Not an `ApiError` → page, `UNEXPECTED_MUTATION_MESSAGE`.
  3. `status === null` → page, `NETWORK_MESSAGE`.
  4. Status ≥ 500 → page, `serverErrorMessage`.
  5. 404 with an operation other than `create` → page, `CATEGORY_GONE_MESSAGE`, `refresh: true`.
  6. `archive` or `restore` → page, `` `${label}: ${detailOrFallback(…)}` ``.
  7. 409 → form, `fieldErrors: { name: detailOrFallback(…) }`, `formError: null`.
  8. Non-empty `fieldErrors` → form. The `name` and `icon` entries become `fieldErrors`. Every other entry goes into `formError` as `"{field}: {message}"` joined with "; ", or `null` if there are none.
  9. Anything else → form, `fieldErrors: {}`, `` `${label}: ${detailOrFallback(…)}` ``.
- `refresh` is `false` everywhere except rule 5.

### 4. New file: `frontend/src/category/CategoryForm.tsx` (create form)
- **Props:** `busy: boolean` and `onSubmit(input: CategoryInput): Promise<SubmitResult>`. `SubmitResult` comes from `../expense/expenseErrors.ts`.
- **State:** `name`, `icon`, `fieldErrors` and `formError`. Ids come from `useId()`.
- **Markup:**
  ```
  <form noValidate aria-labelledby={headingId} onSubmit=…>
    <h2 id={headingId}>New category</h2>
    <div className="field"><label htmlFor>Name</label><input type="text" autoComplete="off" … aria-invalid aria-describedby />{nameError && <p id className="field-error">}</div>
    <div className="field"><label htmlFor>Icon</label><input type="text" autoComplete="off" … />{iconError && …}</div>
    {formError && <p role="alert" className="error">{formError}</p>}
    <div className="form-actions"><button type="submit" disabled={busy}>Add category</button></div>
  </form>
  ```
- **Submit:**
  1. Call `preventDefault()`.
  2. Clear both messages.
  3. Call `onSubmit({ name, icon })` with the values exactly as typed.
  4. On `ok: false`, store the returned messages.
- **On success** the page remounts the form through `key={formKey}`, which empties it. That is card 7, decision 15.

### 5. New file: `frontend/src/category/CategoryEditForm.tsx` (inline row form)
- **Props:**
  - `category: Category`;
  - `busy: boolean`;
  - `onSave(input: CategoryInput): Promise<SubmitResult>`;
  - `onCancel(): void`.
- **State:** `name = category.name`, `icon = category.icon ?? ''`, `fieldErrors` and `formError`. They are initialised once at mount (decision 7).
- **Markup:**
  ```
  <form noValidate aria-label={`Edit ${category.name}`} className="category-edit-form" onSubmit=…>
    <input type="text" autoComplete="off" aria-label="Icon" … aria-invalid aria-describedby />
    <input type="text" autoComplete="off" aria-label="Name" autoFocus … aria-invalid aria-describedby />
    <button type="submit" disabled={busy}>Save</button>
    <button type="button" disabled={busy} onClick={onCancel}>Cancel</button>
    {iconError && <p id className="field-error">…</p>}
    {nameError && <p id className="field-error">…</p>}
    {formError && <p role="alert" className="error">…</p>}
  </form>
  ```
- **Submit:** the same steps as section 4, calling `onSave({ name, icon })`. An empty icon is sent as `""` (AC4).
- **Order:** Icon comes before Name so the inputs line up with the columns. Focus starts in Name.

### 6. New file: `frontend/src/category/CategoryTable.tsx`
- **Props:**
  - `categories: Category[]`;
  - `editingId: number | null`;
  - `busy: boolean`;
  - `onEdit(category)`;
  - `onCancelEdit()`;
  - `onSave(category, input): Promise<SubmitResult>`;
  - `onToggleArchived(category)`.
- **Empty list:** `categories.length === 0` renders `<p>No categories yet.</p>` and no table.
- **Table:** `<table className="category-table" aria-label="Categories">` with the headers `<th scope="col">` Icon, Name, Status and Actions. Rows come in API order, with `key={category.id}` and `className={category.archived ? 'archived' : undefined}`.
  - **View mode:**
    - `<td>{icon ?? '—'}</td>`;
    - `<th scope="row">{name}</th>`;
    - `<td>{archived ? 'Archived' : 'Active'}</td>`;
    - `<td>` with Edit and then Archive or Restore, both `disabled={busy}`.
  - **Edit mode** (`category.id === editingId`):
    - `<td colSpan={2}><CategoryEditForm category busy onSave={(input) => onSave(category, input)} onCancel={onCancelEdit} /></td>`;
    - the same Status cell;
    - the actions cell with Archive or Restore only.

### 7. New file: `frontend/src/category/CategoriesPage.tsx`
**State:**
- `categories: Category[] | null`;
- `reloadKey`, `reloadPending`, `formKey`;
- `editingId: number | null`;
- `mutating`;
- `pageError` and `status`.

**Load effect** on `[reloadKey]`:
- This is `MonthReportPage`'s effect with `listAllCategories(controller.signal)`.
- On success: `setReloadPending(false)` and `setCategories(result)`.
- On error: `setReloadPending(false)`, then `describeLoadError(error, 'categories')` → `pageError`, ignoring `null`.
- It never clears `pageError`.

**Helpers:**
- `reload()` sets `reloadPending` to `true` and increases `reloadKey`.
- `start()` clears `pageError` and `status`, and sets `mutating` to `true`.
- `NOTHING = {ok: false, fieldErrors: {}, formError: null}`.
- `handleError(error, operation): SubmitResult` applies `classifyCategoryError`:
  - `form` → returns the outcome's messages.
  - `page` → sets `pageError`. If `refresh` is set, it calls `reload()`, and for `update` it also calls `setEditingId(null)`. Then it returns `NOTHING`.
  - `ignore` → returns `NOTHING`.

**Handlers** (each one calls `setMutating(false)` in `finally`):
- **`handleCreate(input)`:**
  - `start()`, then `await createCategory(input)`;
  - `setFormKey(k + 1)`, the status "Category added.", `reload()`, and return `{ok: true}`.
- **`handleSave(category, input)`:**
  - `start()`, then `await updateCategory(category.id, input)`;
  - `setEditingId(null)`, the status "Category updated.", `reload()`, and return `{ok: true}`.
- **`handleToggleArchived(category)`:**
  - `const archived = !category.archived`, `start()`, then `await updateCategory(category.id, { archived })`;
  - the status `` `Category "${category.name}" ${archived ? 'archived' : 'restored'}.` `` and `reload()`;
  - errors go to `handleError(error, archived ? 'archive' : 'restore')`.
- **`handleEdit(category)`:** clear both messages and `setEditingId(category.id)`.
- **`handleCancelEdit()`:** clear both messages and `setEditingId(null)`.

**Markup** (a fragment, so the create form is a direct child of `.app` and gets the card style):
1. `<h1>Categories</h1>`.
2. The page alert `<p role="alert" className="error">`.
3. `<p role="status">{status}</p>`.
4. `<CategoryForm key={formKey} busy={mutating} onSubmit={handleCreate} />`.
5. "Loading…" (`.hint`) while `categories === null && loading`. `loading` is a state that starts at `true`, is set to `true` by `reload()`, and is set to `false` in both the success and the error branch of the load effect when the request was not aborted. (Amended after code review: the earlier condition `pageError === null` showed "Loading…" forever when the first load failed and a create then returned 409 or 400, because `start()` clears `pageError`.)
6. When `categories !== null`: `<CategoryTable categories editingId busy={mutating || reloadPending} onEdit={handleEdit} onCancelEdit={handleCancelEdit} onSave={handleSave} onToggleArchived={(c) => void handleToggleArchived(c)} />`.

### 8. Change: `frontend/src/App.tsx`
- `type Tab = 'expenses' | 'month' | 'categories'`.
- A third nav button, "Categories", goes after "Month", with `aria-current={tab === 'categories' ? 'page' : undefined}`.
- The pages are rendered as `{tab === 'expenses' && <ExpensesPage />}`, `{tab === 'month' && <MonthReportPage />}` and `{tab === 'categories' && <CategoriesPage />}`.
- The comment is extended: "Only the active page is mounted, so the others load their data again when shown. This is also how category changes reach the expense form."

### 9. Change: `frontend/src/App.css`
- **`.category-table` joins the shared rules:**
  - the table base (`.expense-table, .report-table`);
  - the `th`/`td` rule;
  - `thead th`;
  - the `tbody th` rule of `.report-table`;
  - the size S buttons list;
  - the two "secondary submit inside a table" rules (`.category-table button[type='submit']…`);
  - `td > button + button` (the gap);
  - `td:last-child { white-space: nowrap }`;
  - the `@media (max-width: 1000px)` block/overflow rule.
- **Archived rows:** `.category-table tr.archived th, .category-table tr.archived td { color: var(--color-text-muted); }`.
  - Specificity (0,2,2) beats `.category-table tbody th`.
  - t70 on `#000` is 10.02:1 (jetbrains-style contrast table).
- **The edit form:**
  - `.category-edit-form` is `display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2)`.
  - `.category-edit-form input` is `width: 10rem; padding-block: 3px`, the height of an S button.
  - `.category-edit-form p` is `flex-basis: 100%`.
- **Comment only:** the `.app > form` comment becomes "The expense form and the new-category form are the only forms that are direct children of .app."
- `index.css` is not changed.

### 10. Change: `frontend/src/test/fixtures.ts`
```ts
export const CATEGORY_POST_ROUTE = 'POST /api/categories'
export const categoryPatchRoute = (id: number) => `PATCH /api/categories/${id}`

// A full CategoryResponse {id, name, icon, archived, createdAt}, as every /api/categories endpoint returns it.
export function category(overrides: Partial<Category> = {}): Category {
  return { ...FOOD, ...overrides }
}
```
`FOOD`, `TRANSPORT`, `OLD`, `CATEGORIES_ROUTE` and `baseRoutes()` are unchanged.

### 11. Not changed
- **The whole backend.** `./gradlew test` is not required.
- **Expenses and Month code:** `ExpensesPage.tsx` and its components, `expenseErrors.ts` (only imported), `report/*`, `localDate.ts` and `expenseFilters.ts`.
- **Other source and test files:** `main.tsx`, `index.css`, `setup.ts` and `fetchMock.ts`.
- **Dependencies:** `package.json` and `package-lock.json` stay byte-identical.
- **Config:** `vite.config.ts`, tsconfig, oxlint, `nginx.conf`, `Dockerfile` and CI.
- **Repository files:** `README.md` (the endpoints are already documented, and frontend pages are not), `docs/wallet-backlog.md` and `CLAUDE.md`.
- **Existing tests:** all stay as they are, **except N1 in `App.test.tsx`**. Its expected nav list gains "Categories" (see Tests).
- **No git operations.**

### Implementation order
1. Sections 1 and 2, and C13 in `client.test.ts`. Run `cd frontend && npm test -- src/api/client.test.ts`.
2. Section 3 and `categoryErrors.test.ts`.
3. Sections 4–7, then 8 and 9. In the same step, update N1 as described under Tests. That is the only change allowed to an existing test.
4. Section 10, then `CategoriesPage.list.test.tsx`, `CategoriesPage.rows.test.tsx` and N3/N4 in `App.test.tsx`.
5. Final check: `cd frontend && npm test && npm run build && npm run lint`. All must be green; `tsc -b` type-checks the tests too.

If any other existing test fails, stop and report it. Do not adapt that test.

**Forbidden at every step, for the implementer and the test writer:**
- `./gradlew bootRun`, or any other way of starting the backend;
- `npm run dev` against a backend;
- any connection to the dev Postgres (`postgres:5432`: psql, JDBC, anything). The frontend tests mock `fetch` and need neither;
- `npm install`/`uninstall`, or any other change to dependencies;
- deleting `frontend/node_modules`;
- any change under `backend/`;
- git operations.

A manual browser check against a running backend is for the user only.

## Tests
Done means `cd frontend && npm test`, `npm run build` and `npm run lint` are all green.

**Conventions.** These follow cards 7–9.
- **Mocking and rendering:**
  - Every test uses `mockFetch` with exact routes. `setup.ts` fails any request without a route, so a call to any other endpoint fails the test.
  - There is no `StrictMode`, so request counts are exact.
  - Request sequences are asserted with `sent(mock) = requests(mock).map((e) => `${e.method} ${e.url}`)`.
  - Bodies are asserted with `requests(mock)[i].body` and `toEqual`. Because that is an exact key match, an unexpected `archived` or `null` key fails the test.
- **Helpers:**
  - `rowOf(name) = screen.getByRole('rowheader', { name }).closest('tr')!`;
  - `cellTexts(row)` gives the `td` texts, so `[icon, status, actions]` in view mode;
  - `createForm() = screen.getByRole('form', { name: 'New category' })`;
  - `editForm(name) = screen.getByRole('form', { name: `Edit ${name}` })`;
  - `typeInto(user, input, text)` = `user.clear` plus `user.type`.
- **Labels are always queried inside a form** (`within(createForm()).getByLabelText('Name')`), because the create form and an open edit form both have "Name" and "Icon".
- **Fixtures:** `GIFTS = category({ id: 10, name: 'Gifts', icon: 'gift' })` is defined locally where it is needed.
- **The clock:** the Categories page uses no dates, so its tests do not fake the clock. `App.test.tsx` keeps its existing `beforeEach` with a fake `Date`.

### Change: `frontend/src/api/client.test.ts`
| # | Test | Proves |
|---|---|---|
| C13 | `PATCH sends method PATCH, Content-Type and a JSON body; a 200 returns the parsed body`:<br>- Route `'PATCH /api/things/1': json({ id: 1 })`; `request('PATCH', '/api/things/1', { body: { icon: '' } })` resolves to `{id: 1}`.<br>- The raw `mock.mock.calls[0][1]?.method` is exactly `'PATCH'`. Fetch does not upper-case PATCH, and `requests()` would hide the case.<br>- The Content-Type is `application/json`, and the body is `{icon: ''}`. | Decision 3 (AC4, AC5) |

### New file: `frontend/src/category/categoryErrors.test.ts`
| # | Test | Proves |
|---|---|---|
| CE1 | `transportAndServerErrorsArePageLevel`. For each of create, update, archive and restore:<br>- an `AbortError` → `ignore`;<br>- `new Error('x')` → page, "Unexpected error. Please try again.";<br>- `ApiError(null, null)` → page, the network message;<br>- `ApiError(503, 'Down')` → page, "Server error (503): Down".<br>`refresh` is `false` in every case. | Error placement |
| CE2 | `createAndUpdateErrorsGoToTheForm`. For create and for update:<br>- 409 "Category name already exists" → form `{name: 'Category name already exists'}`, `formError: null`;<br>- 409 with no detail → `{name: 'request failed with status 409'}`;<br>- 400 with `fieldErrors {name: 'a', icon: 'b', other: 'c'}` → form `{name: 'a', icon: 'b'}`, `formError: 'other: c'`;<br>- 400 "Failed to read request" with no field errors → `{}`, with "Could not add the category: Failed to read request" (create) or "Could not update the category: Failed to read request" (update).<br>Plus: a create 404 → form, "Could not add the category: Not Found". | AC6, error placement |
| CE3 | `goneAndArchiveRestoreErrors`:<br>- 404 for update, archive and restore → page, `CATEGORY_GONE_MESSAGE`, `refresh: true`;<br>- archive 400 "Failed to read request" → page, "Could not archive the category: Failed to read request", `refresh: false`;<br>- restore 409 with no detail → page, "Could not restore the category: request failed with status 409". | Error placement |

### New file: `frontend/src/category/CategoriesPage.list.test.tsx` (list and create)
| # | Test | Mocked routes | Asserts | Proves |
|---|---|---|---|---|
| CP1 | `listsAllCategoriesWithIconNameAndStatus` | `CATEGORIES_ROUTE`: `json([TRANSPORT, category({ icon: 'cart' }), OLD])`, deliberately not in id order | - h1 "Categories" and the table "Categories".<br>- The column headers are exactly Icon, Name, Status, Actions.<br>- The row headers are in API order: Transport, Food, Old.<br>- `cellTexts(...).slice(0, 2)`: Transport `['—', 'Active']`, Food `['cart', 'Active']`, Old `['—', 'Archived']`.<br>- Only Old `toHaveClass('archived')`.<br>- Food and Transport each have Edit and Archive, and no Restore. Old has Edit and Restore, and no Archive. All are enabled.<br>- There is no button matching `/delete/i`.<br>- The requests are exactly `[CATEGORIES_ROUTE]`. | **AC2**, decisions 4 and 5 |
| CP2 | `emptyDatabaseShowsHintAndCreateForm` | `json([])` | "No categories yet." is shown, with no table. The form "New category" has the inputs Name and Icon and an enabled "Add category". | AC2, AC3 (the card's fresh database) |
| CP3 | `loadingAndLoadErrorsArePageLevel`: five independent tests (or `it.each` for (b)–(d)), each with its own `mockFetch` and `render`, never a shared render | (a) a deferred reply; (b) `problem(500, 'Unexpected error')`; (c) `new TypeError('Failed to fetch')`; (d) `rawJson('not json')` | (a) "Loading…" is shown with no table; after resolving, the table is shown and "Loading…" is gone.<br>(b) The alert reads "Server error (500): Unexpected error". There is no table and no "Loading…", and the create form is still there.<br>(c) The network message.<br>(d) "Could not load categories: unexpected error."<br>(e) Added after code review: GET `problem(500, 'Unexpected error')`, then POST `problem(409, 'Category name already exists')`. After the 409 field error is shown, there is no "Loading…", and the requests are exactly `[GET, POST]`. | Error placement, markup item 5 |
| CP4 | `createPostsTypedValuesAndReloadsList` | `CATEGORIES_ROUTE`: `[json([FOOD]), json([FOOD, GIFTS])]`; `CATEGORY_POST_ROUTE`: `json(category({ id: 10, name: 'Gifts', icon: 'from-post' }), 201)` | - Type `'  Gifts '` into Name and `gift` into Icon, then click "Add category".<br>- The status is "Category added.".<br>- The POST has `Content-Type: application/json` and a body exactly `{name: '  Gifts ', icon: 'gift'}`, as typed (the server strips).<br>- The requests are exactly `[CATEGORIES_ROUTE, CATEGORY_POST_ROUTE, CATEGORIES_ROUTE]`.<br>- The Gifts row shows `gift`, and `queryByText('from-post')` is `null`, so the row comes from the reload.<br>- Both create inputs are empty again and not invalid. | **AC3**, **AC8 (create)**, decision 4 |
| CP5 | `createWithEmptyIconSendsEmptyString` | GET `[json([FOOD]), json([FOOD, category({ id: 10, name: 'Gifts', icon: null })])]`; POST 201 | The body is exactly `{name: 'Gifts', icon: ''}`. The reloaded Gifts row's icon cell is "—". | AC3 (icon optional) |
| CP6 | `createValidationErrorsShownPerField` | POST: `[problem(400, 'Invalid request content.', [{name, 'must not be blank'}]), problem(400, …, [{icon, 'size must be between 0 and 32'}, {name, 'size must be between 0 and 64'}]), problem(400, 'Failed to read request'), deferred]` | (a) Click Add with empty inputs. The body is `{name: '', icon: ''}`. The create Name input is `aria-invalid="true"` with the accessible description "must not be blank". Icon is not invalid. There is no alert anywhere, and the requests are `[GET, POST]` (no reload).<br>(b) Type values. Both inputs are invalid with their own descriptions, and the typed values are kept.<br>(c) `within(createForm()).getByRole('alert')` reads "Could not add the category: Failed to read request", and neither input is invalid.<br>(d) Click Add again (deferred): the alert is gone before the reply arrives. | **AC6 (400 per field)**, AC3 (name required by the server) |
| CP7 | `createDuplicateNameShows409NextToName` | GET `json([FOOD])`; POST `problem(409, 'Category name already exists')` | - Type `FOOD` and click Add.<br>- The create Name input is `aria-invalid` with the description "Category name already exists", and it still holds `FOOD`. Icon is not invalid.<br>- `screen.queryByRole('alert')` is `null`: no page alert and no form alert.<br>- The status is empty, and the requests are `[GET, POST]`. | **AC6 (409)**, **AC8 (duplicate)** |
| CP8 | `createNetworkServerAndUnexpectedErrorsArePageLevel` | POST: `[new TypeError('Failed to fetch'), problem(500, 'Unexpected error'), rawJson('not json', 201)]` | The page alert reads, in turn:<br>- the network message;<br>- "Server error (500): Unexpected error";<br>- "Unexpected error. Please try again.".<br>Each time, the typed Name is kept, "Add category" is enabled again, the create form has no alert, and only one GET was sent. | Error placement |
| CP9 | `createIsSerialisedWithRowActions` | GET `[json([FOOD, OLD]), reload deferred]`; POST deferred | - While the POST is pending, "Add category" and every Edit, Archive and Restore button are disabled. A second click on Add sends no second POST.<br>- Resolve the POST: "Add category" is enabled again, while the row buttons stay disabled until the reload resolves (`reloadPending`).<br>- Resolve the reload: everything is enabled.<br>- Exactly one POST was sent. | Decision 8 |

### New file: `frontend/src/category/CategoriesPage.rows.test.tsx` (inline edit, archive, restore)
| # | Test | Mocked routes | Asserts | Proves |
|---|---|---|---|---|
| CR1 | `renameAndIconChangeArePatchedInline` | GET `[json([category({ icon: 'cart' }), TRANSPORT]), json([category({ name: 'Groceries', icon: 'basket' }), TRANSPORT])]`; `categoryPatchRoute(1)`: `json(category({ name: 'Groceries', icon: 'basket' }))` | - Click Edit in Food's row. The form "Edit Food" appears. Its Name input holds `Food` and `toHaveFocus()`, and its Icon input holds `cart`.<br>- The rowheader "Food" is gone. That row still shows "Active" and an Archive button, but no Edit. Transport is still in view mode.<br>- Type `Groceries` and `basket`, then click Save.<br>- The status is "Category updated.". The PATCH has `Content-Type: application/json` and a body exactly `{name: 'Groceries', icon: 'basket'}`, with no `archived` key.<br>- The requests are exactly `[GET, 'PATCH /api/categories/1', GET]`.<br>- The form "Edit Food" is gone. The rowheader "Groceries" shows `basket`. | **AC4**, **AC8 (rename)**, decisions 5 and 6 |
| CR2 | `emptyIconClearsTheIcon` | GET `[json([category({ icon: 'cart' })]), json([category({ icon: null })])]`; PATCH 1 200 | Edit, clear Icon, Save → the body is exactly `{name: 'Food', icon: ''}`. The reloaded icon cell is "—". | **AC4 (empty icon clears)** |
| CR3 | `cancelAndSwitchingRowsMakeNoRequest` | GET `json([FOOD, TRANSPORT])` | - Edit Food, type `X`, Cancel → the rowheader "Food" is back.<br>- Edit Food again → Name holds `Food`, so the draft was dropped.<br>- Click Edit in Transport's row → "Edit Food" is gone and "Edit Transport" is shown: one row at a time.<br>- The requests are exactly `[GET]`. | Decision 6 |
| CR4 | `renameDuplicateShows409NextToRowName` | GET `json([FOOD, TRANSPORT])`; `categoryPatchRoute(2)`: `problem(409, 'Category name already exists')` | - Edit Transport, set Name to `food`, Save.<br>- Within "Edit Transport", Name is `aria-invalid` with the description "Category name already exists", and it still holds `food`. The form stays open.<br>- There is no alert anywhere. The create form's Name is not invalid. The status is empty.<br>- The requests are `[GET, 'PATCH /api/categories/2']`. | **AC6 (409 on PATCH)**, **AC8 (duplicate)** |
| CR5 | `editValidationErrorsShownPerFieldInRow` | PATCH 1: `[problem(400, …, [{name, 'size must be between 1 and 64'}]), problem(400, …, [{icon, 'size must be between 0 and 32'}]), problem(400, 'Failed to read request')]` | (a) Clear Name and Save. The body is `{name: '', icon: ''}`. The row's Name is invalid with that description, and Icon is not. No reload.<br>(b) The row's Icon is invalid with its description. Name is no longer invalid.<br>(c) `within(editForm('Food')).getByRole('alert')` reads "Could not update the category: Failed to read request".<br>The create form's inputs are never invalid. | **AC6 (400 per field)** |
| CR6 | `editOfMissingCategoryLeavesEditAndRefreshes` | GET `[json([FOOD, TRANSPORT]), json([TRANSPORT])]`; PATCH 1 `problem(404, 'Category not found')` | - The page alert reads "This category no longer exists. The list has been refreshed.".<br>- The form "Edit Food" is gone, and so is the Food row after the reload.<br>- The requests are `[GET, PATCH 1, GET]`. | Error placement |
| CR7 | `editNetworkServerUnexpectedErrorsArePageLevelAndKeepDraft` | PATCH 1: `[new TypeError(…), problem(500, 'Unexpected error'), rawJson('not json', 200)]` | The page alerts are, in turn, the network message, "Server error (500): Unexpected error" and "Unexpected error. Please try again.". Each time:<br>- "Edit Food" stays open with the typed draft;<br>- Save is enabled again;<br>- there is no alert inside the edit form;<br>- only one GET was sent. | Error placement |
| CR8 | `archiveSendsArchivedTrueAndReloads` | GET `[json([FOOD, TRANSPORT]), json([category({ archived: true }), TRANSPORT])]`; PATCH 1 `json(category({ archived: true }))` | - Click Archive in Food's row. The status is `Category "Food" archived.`.<br>- The body is exactly `{archived: true}`, and the requests are `[GET, 'PATCH /api/categories/1', GET]`.<br>- Food's row has the class `archived`, the status "Archived" and a Restore button, and no Archive button. Transport is unchanged. | **AC5**, **AC2 (marking)**, **AC8 (archive)** |
| CR9 | `restoreSendsArchivedFalseAndReloads` | GET `[json([FOOD, OLD]), json([FOOD, category({ ...OLD, archived: false })])]`; `categoryPatchRoute(9)` 200 | - Click Restore in Old's row. The status is `Category "Old" restored.`.<br>- The body is exactly `{archived: false}`, and the requests are `[GET, 'PATCH /api/categories/9', GET]`.<br>- Old's row has no `archived` class, the status "Active" and an Archive button. | **AC5**, **AC8 (restore)** |
| CR10 | `archiveAndRestoreErrorsArePageLevel` | (a) PATCH 1 404 "Category not found", with a second GET; (b) PATCH 1 500; (c) PATCH 9 `TypeError`; (d) PATCH 1 400 "Failed to read request" | (a) The "gone" alert, and the list is reloaded (2 GETs).<br>(b) "Server error (500): Unexpected error", and no reload.<br>(c) The network message, and no reload.<br>(d) "Could not archive the category: Failed to read request".<br>In (b)–(d) the row is unchanged and no alert appears inside any form. | Error placement, Q4 |
| CR11 | `archiveIsSerialisedWithOtherActions` | Own `mockFetch` and `render`. PATCH 1 (archive) deferred, then a deferred reload | While the PATCH is pending, "Add category" and every Edit, Archive and Restore button are disabled. A second click sends nothing. After the PATCH resolves, "Add category" is enabled and the row buttons stay disabled until the reload resolves; after the reload resolves, they are enabled. Exactly one PATCH was sent. | Decision 8 |
| CR13 | `saveIsSerialisedWithOtherActions` | Own `mockFetch` and `render` (separate from CR11: in one render, Save could not be clicked until CR11's reload settled, because `reloadPending` keeps the row buttons disabled). PATCH 2 (Save) deferred | Open "Edit Transport" and Save (deferred). Save and Cancel in that form are disabled, and so are "Add category" and every other row button. The inputs stay editable. Exactly one PATCH was sent. | Decision 8 |
| CR12 | `openEditSurvivesOtherRowChanges` | GET `[json([FOOD, TRANSPORT]), json([category({ archived: true }), TRANSPORT])]`; PATCH 1 200 | Open "Edit Transport" and type the draft `Trains`. Archive Food and wait for the reload. "Edit Transport" is still open and still holds `Trains`, and Food is archived. | Decision 7 |

### Change: `frontend/src/App.test.tsx`
| # | Test | Asserts | Proves |
|---|---|---|---|
| N1 (updated) | `startsOnExpensesTab` | The only edits: the expected nav button texts become `['Expenses', 'Month', 'Categories']`, and one line is added asserting that "Categories" has no `aria-current`. Everything else stays. | **AC1** |
| N3 (new) | `categoriesTabMountsCategoriesPage` | - Routes: `baseRoutes()` and `GET /api/expenses?page=0&size=50`. Start on Expenses, and record `href` and `history.length`.<br>- Click "Categories". The h1 is "Categories" and the table "Categories" is shown. "Categories" has `aria-current="page"`, and no "Expenses" heading is left.<br>- The requests made after the click are exactly `[CATEGORIES_ROUTE]`.<br>- `href` and `history.length` are unchanged. | **AC1**, decision 2 |
| N4 (new) | `categoryChangesReachTheExpenseFormWithoutReload` | **Local fixtures:** `GIFTS = category({ id: 10, name: 'Gifts', icon: null })`, `FOOD_ARCHIVED = category({ archived: true })` and `OLD_RESTORED = { ...OLD, archived: false }`.<br>**Routes:**<br>- `...baseRoutes()`;<br>- `CATEGORIES_ROUTE`: `[json([FOOD, TRANSPORT, OLD])` (Expenses mount), `json([FOOD, TRANSPORT, OLD])` (Categories mount), `json([FOOD, TRANSPORT, OLD, GIFTS])` (after create), `json([FOOD_ARCHIVED, TRANSPORT, OLD, GIFTS])` (after archive), `json([FOOD_ARCHIVED, TRANSPORT, OLD_RESTORED, GIFTS])]` (after restore; it repeats for the Expenses remount);<br>- the expenses list;<br>- POST 201 `GIFTS` (id 10, icon `null`);<br>- `categoryPatchRoute(1)` → `FOOD_ARCHIVED`;<br>- `categoryPatchRoute(9)` → `OLD_RESTORED`.<br>**Steps:**<br>1. The Expenses form's options are `['Select a category', 'Food', 'Transport']`. Record `href` and `history.length`.<br>2. Click "Categories", create "Gifts", archive Food, restore Old. Wait for each reload: the rowheader "Gifts", Food's Restore enabled, Old's Archive enabled.<br>3. Click "Expenses". Once the option "Gifts" appears, the options are exactly `['Select a category', 'Transport', 'Old', 'Gifts']`.<br>4. The filter still offers the checkbox "Food (archived)".<br>5. `CATEGORIES_ROUTE` was requested 6 times, the last time after the final tab click.<br>6. `href` and `history.length` are unchanged.<br>All of this happens in one `render`. | **AC7** |

N2 and all other existing tests (E*, F*, P*, QT*, U*, L1, Y*, MR*, LM*, C1–C12, S1–S2) stay unchanged and must stay green.

### Acceptance criteria → how verified
| AC | Code | Tests |
|---|---|---|
| 1. Categories tab | `App.tsx` (section 8) | N1 (updated), N3 |
| 2. Table from `includeArchived=true`, archived marked | `CategoriesPage` load effect, `CategoryTable`, the `tr.archived` CSS | CP1 (exact request, columns, order, cells, class), CP2, CP3, CR8, CR9 |
| 3. Create form, reload after success | `CategoryForm`, `handleCreate`, `createCategory` | CP4, CP5, CP6(a) (name required by the server), CP9 |
| 4. Inline edit, empty icon clears | `CategoryEditForm`, `handleSave`, `updateCategory`, PATCH in `client.ts` | CR1, CR2, CR3, CR12, C13 |
| 5. Archive / Restore toggles via PATCH | `CategoryTable` actions, `handleToggleArchived` | CR8, CR9, CR10, CR11, CR13 |
| 6. 409 and per-field 400 next to the form | `categoryErrors.ts`, the field and form messages in both forms | CP6, CP7, CR4, CR5, CE2; page-level rules CP8, CR7, CR10, CE1, CE3 |
| 7. Expense form follows category changes without a reload | Existing: `App` mounts only the active page, and `ExpensesPage` loads categories on mount | N4 |
| 8. Tests with mocked fetch | All of the above use `mockFetch`, and `setup.ts` forbids unrouted requests | Create CP4, duplicate CP7 and CR4, rename CR1, archive CR8, restore CR9 |

## Risks and open questions

### Blockers / open questions
**No acceptance criterion is blocked, and no backend change is needed** (see "Backend contract"). Each question below has a proposal in bold, which the plan implements, plus the alternative and its cost.
1. **Q1. Inline edit layout.** **Proposal: an "Edit" button that turns the row into a form (Icon, Name, Save, Cancel)** (decisions 5 and 6).
   - Alternative: always-visible inputs in every row, like the Month page's `LimitForm`.
   - Then the table no longer "lists" names as text, every row needs a Save button, and archived rows look like editable forms.
2. **Q2. One row in edit mode at a time.** **Proposal: yes,** and switching rows drops the draft.
   - Alternative: per-row edit state, so several drafts can exist. That is about 10 lines, and the page can no longer close the edit after a 404.
3. **Q3. Confirmation before Archive.** **Proposal: none.** Archiving is undone with one click on Restore, and the status line confirms it.
   - Alternative: `window.confirm`, as for deleting an expense. That is one line, plus a stub in tests.
4. **Q4. Where archive/restore errors go.** **Proposal: page-level.** There is no form to put them in, and the only failures they can really have are network errors, 5xx and 404, which are page-level on every page.
   - Alternative: an alert inside the row, like the Month page's Clear errors.
5. **Q5. Where a 409 goes.** **Proposal: under the Name input of the form that sent the request,** with the server's detail.
   - Alternative: a form-level alert. Then nothing marks the field.
6. **Q6. What "icon" means.** **Proposal: free text up to 32 characters,** for example an emoji or a short name. It is rendered verbatim, and "—" is shown when there is none.
   - There is no picker and no icon library.
   - The card does not define the format, and the backend accepts any string.
7. **Q7. Texts. Proposal: as listed:**
   - the tab "Categories" and the h1 "Categories";
   - the form "New category" with Name, Icon and "Add category";
   - the table "Categories" with the columns Icon, Name, Status, Actions;
   - "Active" and "Archived"; "—";
   - "Edit", "Archive", "Restore", "Save" and "Cancel"; the form name "Edit {name}";
   - "No categories yet.";
   - "Category added.", "Category updated.", `Category "{name}" archived.` and `Category "{name}" restored.`;
   - "Could not add/update/archive/restore the category: …";
   - "This category no longer exists. The list has been refreshed.".
8. **Q8. Tab position.** **Proposal: after Month** (Expenses, Month, Categories).
9. **Q9. Column order.** **Proposal: the card's order, Icon, Name, Status, then Actions.**
10. **Q10. Client-side checks** (`required`, `maxLength={64}`/`{32}`, trimming). **Proposal: none,** following card 7, decision 14. The server answers with field errors.
    - Alternative: add `maxLength` attributes. That is two lines per form, but it duplicates server rules.
11. **Q11. Row Save style.** **Proposal: secondary inside the table,** like "Set limit" (jetbrains-style Q5). "Add category" is primary.
12. **Q12. Duplicate of an archived name.** Creating "Food" while an archived "food" exists gives "Category name already exists", and nothing says the existing one is archived.
    - **Proposal: show the server's detail verbatim.**
    - Alternative: the client looks up an archived match in the loaded list and adds "Restore it instead". That is client-side business logic, and it would go stale.
13. **Q13. New dependencies: none.** `package.json` is unchanged, so nothing needs confirmation.

### Risks / notes
1. **R1. The single change to an existing test.** N1 hard-codes the nav buttons, so it must gain "Categories". The implementer may change only those lines; any other failing existing test is reported, not adapted.
2. **R2. Stale list within the page.** A Categories page left open does not refresh itself, so another client's changes show only after the next mutation or a tab switch.
   - Edit sends both `name` and `icon`, so it can overwrite another client's concurrent icon change. This is accepted for a single-user app.
3. **R3. Archiving a category in use** is allowed by the backend. The Categories page shows no warning, but the effects appear on other pages:
   - its quick templates become disabled on Expenses ("(category archived)");
   - its expenses can still be edited (card 7, decision 8);
   - setting its limit on the Month page gives 409 "Category is archived".
4. **R4. A non-idempotent POST.** If the server commits and the connection then drops, the user sees the network message. A retry gives 409 "Category name already exists", which explains itself, so no duplicate is created.
5. **R5. Focus after Save or Cancel.** The edit form unmounts, so focus falls back to `body`. This matches card 7 after a save. Restoring focus to the row's Edit button needs refs and is left out.
6. **R6. Input sanitisation.** `<input type="text">` drops line breaks, so a name with an internal line break (which the backend accepts) cannot be typed. Editing such a name and saving it removes the line break.
   - A whitespace-only icon is stored as is (category-crud, Risk 4) and shows as a blank cell.
7. **R7. Mixed languages.** Validation messages are Bean Validation defaults in the browser's locale (card 7, R9).
8. **R8. Unmounting during a mutation.** If the user switches tabs while a POST or PATCH is in flight, the request still completes on the server. Its status message is lost, and the next visit shows the result (card 9, R5).
9. **R9. One `pageError` slot** (card 7, R12). A later message replaces an earlier one.
10. **R10. A stale list after a failed reload.** If the reload after a mutation fails, the alert is shown, the previous list stays on screen, and the row buttons are enabled again. An Edit started from that stale list prefills old values. This is the same accepted behaviour as the Month page.
11. **R11. CSS is not tested.** jsdom does not render it, so only the `archived` class is asserted. The look of muted archived rows and of the inline form in a narrow table cell is checked manually (at 375px the table scrolls inside itself).
12. **R12. "Categories" appears in several roles:** the tab button, the h1, the table name, and the legend of the Expenses filter. The Expenses page is unmounted while the Categories tab is open, and tests query by role, so they do not collide.

## Out of scope
- **From the card:** deleting categories (they are archived, never deleted), category ordering, nested categories, and seeding default categories.
- **Backend:** any change.
- **Client behaviour:**
  - client-side validation, trimming, `maxLength` and duplicate detection (Q10, Q12);
  - an icon picker, an emoji or icon library, and rendering icons as images (Q6);
  - confirmation or undo for Archive (Q3);
  - showing `createdAt`;
  - sorting, searching or paging the category list;
  - restoring focus after an inline edit (R5);
  - a hint or link on the Expenses page when no categories exist;
  - keeping the tab in the URL, and a router.
- **Other pages:** any change to `ExpensesPage`, `ExpenseForm`, `MonthReportPage` or their tests, apart from the N1 line change.
- **Testing extras:** end-to-end tests against a real backend, MSW, and visual tests.
- **Files and dependencies:** new dependencies, and changes to `README.md`, `docs/wallet-backlog.md`, `CLAUDE.md`, CI, Docker or nginx.
