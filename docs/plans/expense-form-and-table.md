# Plan: Expense form and current-month table

Trello card: https://trello.com/c/QmnDLDb9/15-expense-form-and-current-month-table
Part of the "Wallet" feature: expenses by category with monthly limits.

> **Revision 2 (2026-10-01).** The user confirmed Q1–Q9 as proposed. The user also adopted the plan review's suggestions 1–14. Suggestion 15 was not adopted, so E3(b), E12 and E26 stay.
>
> Changes from Revision 1:
> - **E1** pins the clock and asserts the literal date `2026-10-01`. Only `Date` is faked.
> - **The `list()` fixture** takes `totalAmount` as a required string and never sums item amounts. This applies AC5 to the test code too.
> - **Abort handling:**
>   - `mockFetch` honours `init.signal`.
>   - `isAbortError` checks `name === 'AbortError'`, not `instanceof`.
>   - The list effect ignores the results of an aborted request.
>
>   New test: S2. Updated test: C9.
> - **Decision 20 by construction:** `setup.ts` stubs `fetch` before every test with a stub that rejects. After every test it fails the test if any request had no route. New test: S1.
> - **Errors that are neither `ApiError` nor aborts**, such as a `SyntaxError` from a 2xx body that is not JSON, get a generic page-level message. See "Error placement" and decision 4. New tests: E27 and C10.
> - **R1 rewritten.** Vitest depends on vite as a regular dependency, so a version mismatch nests a second vite silently instead of failing with ERESOLVE.
>   - Vitest is installed with an explicit major, chosen with `npm view`.
>   - `npm ls vite` is the gate, and its output goes into the report.
> - **R10** notes that the test files bring test-only types into the app tsconfig program.
> - **Edit mode focuses the amount input** (decision 7, E14).
> - **The "(archived)" suffix** is added only when the categories loaded successfully (decision 8, E15(b)).
> - **Mutations are serialised.** A page-level `mutating` flag disables every row action and the form buttons while any mutation is in flight. It replaces `deletingId` (decision 16). New test: E28. E20 is trimmed accordingly.
> - **One `pageError` slot rule:** a successful load never clears the slot (see "Error placement"). New test: E30. New risk: R12.
> - **New test E29** covers field names the form does not know, which are shown at form level.
> - **E21** also asserts that the total comes before the table in DOM order.
> - **Decision 13** states that amounts are sent as typed, and that CLAUDE.md's two-decimal rule applies to API output.

## Goal
Replace the greeting demo in `frontend/` with an "Expenses" page. The page has:
- a form that creates expenses through `POST /api/expenses`;
- a table of the current month's expenses from `GET /api/expenses`, with edit (`PUT`) and delete (`DELETE`) actions;
- the month total taken from `totalAmount`;
- Prev/Next paging.

The card also adds the frontend test setup (Vitest, Testing Library, jsdom), an `npm test` script and a CI step that runs it. The backend does not change.

## Acceptance criteria
- [ ] Form fields: amount, category (select of active categories), date (defaults to today), note; submit calls POST /api/expenses and prepends the new row to the table without a full reload
- [ ] Backend validation errors (400/409) are shown next to the corresponding field; network errors are shown as a page-level message
- [ ] Table columns: date, category, amount, note, with edit and delete actions calling the API
- [ ] The month total shown above the table is totalAmount from the list response
- [ ] Amounts are rendered from the API strings, never re-parsed into floats for display

### How AC1 is interpreted: "prepends the new row" (user decision D1)
- After a successful `POST`, the page fetches the current page of `GET /api/expenses` again and renders `items` exactly as returned. This is a `fetch` call, not a page reload:
  - the component stays mounted;
  - the form's `submit` event calls `preventDefault`.
- The client never inserts the POST response into the table itself.
- The server sorts by `spentOn desc, createdAt desc, id desc`. As a result:
  - an expense dated today becomes the first row of page 0, so it is "prepended";
  - a back-dated expense shows up at its sorted position;
  - an expense outside the current month does not show up. The status message "Expense added." confirms that it was saved (decision 15).
- `totalAmount` and `totalItems` always come from the same response as the rows.

### User decisions (implemented as given)
- **D1.** After every successful create, edit or delete, fetch the current page of `GET /api/expenses` again. There is no page reload. The rows and `totalAmount` always come from the server.
- **D2.** Paging uses Prev/Next buttons, a page size of 50 and a caption like "N–M of totalItems". `totalAmount` always covers the whole month.
- **D3.** Frontend tests use Vitest, `@testing-library/react`, `@testing-library/user-event` and jsdom. `@testing-library/jest-dom` is added with the justification in section 1 (confirmed in Q1). They are component tests with a mocked `fetch`, without MSW. An `npm test` script runs them once, without watch mode. `package-lock.json` is updated with `npm install`. `frontend/node_modules` is never deleted.
- **D4.** CI gets an `npm test` step in the existing frontend job. The Node setup, the npm cache and the Trivy dependency scan were checked; see section 13 and R2.

### Design decisions made in this plan (one-line rationale each)
1. **No router.** `App` renders `ExpensesPage` directly. There is one page today. Card 8 extends this same page, and card 9 adds the second page, so navigation can be decided then.
2. **Folders.** API code goes in `src/api/` and the page in `src/expense/`, the same split as the backend packages (`expense`, `category`). Card 8 extends these same files.
3. **A small typed `fetch` wrapper** (`src/api/client.ts`) instead of a library.
   - It throws one `ApiError { status: number | null, detail, fieldErrors }`.
   - `isAbortError` checks `name === 'AbortError'` on any object, because jsdom and Node each have their own `DOMException`, so `instanceof` is unreliable.
   - Native `fetch` plus about 60 lines covers four verbs. axios or react-query would add dependencies and features this page does not need.
4. **Where errors are shown** is set in one table ("Error placement" below) and implemented in one pure module, `expenseErrors.ts`.
   - Any error that is neither an `ApiError` nor an abort gets a generic page-level message, so nothing is swallowed.
   - The form and the table stay presentational.
5. **A 409 from the expense form is shown next to the category field**, with the Problem Details `detail` text ("Category is archived"). It is the only 409 that `POST`/`PUT /api/expenses` can return. The other 409s are constraint-based ones for category names and budget limits.
6. **A 404 on edit or delete** shows a page-level message: "This expense no longer exists. The list has been refreshed." The page then fetches the list again and leaves edit mode. The row was stale, so the server's current state is the only useful thing to show.
7. **Edit reuses `ExpenseForm` in edit mode instead of inline row editing.**
   - The form is remounted with a `key`, prefilled from the row, and sends a `PUT` with all four fields.
   - When edit mode starts, focus moves to the amount input, so a keyboard user lands in the form (it sits above the table).
   - This gives one component and one way of showing field errors, and PUT needs every field anyway.
   - Any unsent draft in the create form is dropped.
8. **Archived category on edit.**
   - If the edited expense's category is not among the active categories, the select gets one extra option, built from `expense.category` and preselected.
   - It is labelled `"<name> (archived)"` only when the categories loaded successfully. If they failed to load, absence proves nothing, so the label is just `"<name>"`.
   - The backend allows a PUT that keeps the current category even when it is archived (`ExpenseControllerIT.putKeepsCurrentArchivedCategory`).
9. **Delete asks for confirmation with `window.confirm`.** There is no undo. It is one line, and tests can stub it with `vi.spyOn(window, 'confirm')`.
10. **"Today" is the browser's local date**, from `localIsoDate(new Date())`. It is computed each time the form mounts, never with `toISOString()`, which gives the UTC date.
    - No endpoint exposes `APP_TIME_ZONE`, and copying the zone into the client would duplicate configuration.
    - The consequence near midnight is described in R6.
    - E1 pins the clock, so the test does not depend on the real date.
11. **The current month comes from the server defaults.** The page sends `GET /api/expenses?page={p}&size=50` without `from`/`to`, and the section is labelled "This month". There is no date arithmetic on the client, and a fixed label cannot disagree with the server's month (Q3).
12. **Amounts are rendered exactly as the API sends them.**
    - Row amounts are shown as `{amount} {currency}`, for example `200.00 PLN`.
    - The total is shown as `{totalAmount}` with no currency. The list response has no currency field, and the client must not assume one (Q6).
    - No `Number`, `parseFloat` or `Intl.NumberFormat` is used on money. In TypeScript, money is typed as `string`.
    - Test fixtures never compute money either (section 4).
13. **Inputs are sent as typed.**
    - The amount input is `type="text" inputMode="decimal"`. The typed string is sent unchanged (`"12.5"` stays `"12.5"`).
    - An empty amount, an empty category and an empty date are sent as JSON `null`.
    - The note is sent as typed, including `""`. The backend normalises `""` to `null`, and on PUT that clears the note.
    - **The money rule:** CLAUDE.md's "string with two decimals" governs API **output**. In requests the backend accepts a string with at most two decimals and normalises it: `"12.5"` is stored as 12.50 and returned as `"12.50"`. So the client sends the user's string as is.
    - `type="number"` is not used: browsers clean up or localise its value, so what reaches the server could differ from what was typed.
    - Sending `null` for empty fields gives the deterministic field-level `errors[{field, "must not be null"}]`, whatever Jackson does with `""` for a `LocalDate`.
14. **No client-side validation.** The form has `noValidate` and no `required`, `min`, `max` or `maxLength`. The server is the only validator ("no business logic on the client"), and every rule is shown through AC2.
15. **After a successful create** the form remounts with its defaults: amount empty, the category placeholder, today's date and an empty note. A status message (`role="status"`) shows "Expense added.", "Expense updated." or "Expense deleted." D1 means a saved expense may not be visible, so the user needs this confirmation (Q5).
16. **Loading, busy states and one mutation at a time.**
    - "Loading…" is shown until the first list response arrives.
    - "No expenses this month." is shown when `totalItems` is 0.
    - **Mutations are serialised.** A page-level `mutating` flag is set while any create, update or delete is in flight. While it is set, every row's Edit and Delete buttons are disabled, and so are the form's submit and Cancel buttons.
    - **Why serialise instead of checking ids** (for example, clearing `editing` after a PUT only if its id still matches):
      - one rule removes every interleaving: switching the edited row during a PUT, deleting a row that is being saved, two deletes at once, or a create during a delete;
      - a successful PUT can then always clear `editing`;
      - it also prevents double creates, because POST is not idempotent;
      - it replaces `deletingId`;
      - the requests take milliseconds, so the cost is negligible.
    - For the list, the latest request wins: the previous one is aborted with an `AbortController`. The effect also ignores any result or error that arrives after its signal was aborted.
17. **Paging (D2):**
    - `PAGE_SIZE = 50` is sent explicitly.
    - The caption is computed from the response's `page`, `size`, `items.length` and `totalItems`.
    - Prev is disabled on page 0. Next is disabled when `(page + 1) * size >= totalItems`.
    - If a page re-fetched after a mutation comes back empty while `page > 0`, the page steps back one page. This happens, for example, after deleting the last row of the last page.
18. **Accessibility basics:**
    - Every input has a `<label htmlFor>`, with ids from `useId()`.
    - A field with an error gets `aria-invalid="true"` and `aria-describedby` pointing at its error text.
    - Page-level and form-level errors use `role="alert"`.
    - Each form has `aria-labelledby` pointing at its heading.
    - Table headers use `<th scope="col">`.

    Tests query by role and label, which keeps them robust.
19. **Where tests live.** Test files sit next to the code under `src/`, so `tsc -b` (part of `npm run build`) type-checks them, which Vitest itself does not do. The Vitest config goes in the existing `vite.config.ts`. Tests import `describe`/`it`/`expect`/`vi` explicitly (no `globals`), and a setup file runs `cleanup`.
20. **`fetch` is replaced with a stub in every test, by construction.**
    - `setup.ts` stubs `fetch` before every test with a stub that records the request and rejects.
    - A test's `mockFetch` replaces that stub. Any request that matches no route is recorded the same way.
    - After every test, `setup.ts` fails the test if anything was recorded.
    - So no test can reach a backend or the dev Postgres, and a forgotten or missing route fails loudly. It cannot hide behind the page's network-error message.

## Changes

### Existing state (verified in the code)
**Frontend:**
- **`package.json`:**
  - Dependencies: `react`/`react-dom` `^19.2.8`.
  - Dev dependencies: `@types/node ^24.13.3`, `@types/react`, `@types/react-dom`, `@vitejs/plugin-react ^6.1.1`, `oxlint ^1.81.0`, `typescript ~6.0.2`, `vite ^8.3.0`.
  - Scripts: `dev`, `build` (`tsc -b && vite build`), `lint` (`oxlint`), `preview`. There is no `test` script and no test library.
  - The lockfile (`lockfileVersion: 3`) resolves vite 8.3.0 (engines `^20.19.0 || >=22.12.0`), plugin-react 6.1.1, typescript 6.0.3 and oxlint 1.83.0.
- **`src/`:**
  - `main.tsx` uses `StrictMode` and imports `./App.tsx` with its extension.
  - `App.tsx` is the greeting demo: it calls `GET /api/greeting` with an `AbortController` and has loading/loaded/failed states.
  - `App.css` has `.app` (max-width 40rem, centred), `.greeting`, `.hint` and `.error`.
  - `index.css` is the Vite skeleton theme, with `#root` 1126px wide and `text-align: center`.
  - There is no router, no API module and no test.
- **`vite.config.ts`** proxies `/api` to `http://localhost:8080` in dev. **`nginx.conf`** forwards `location /api/` to `http://backend:8080`. So relative `/api/...` URLs work in both, and neither file changes.
- **`tsconfig.app.json`:**
  - Includes `src` with `types: ["vite/client"]`, `verbatimModuleSyntax`, `erasableSyntaxOnly`, `noUnusedLocals`/`noUnusedParameters` and `allowImportingTsExtensions`.
  - `erasableSyntaxOnly` rules out enums and constructor parameter properties.
  - `tsconfig.node.json` covers only `vite.config.ts`, with `types: ["node"]`.
- **`.oxlintrc.json`:** plugins `react`, `typescript` and `oxc`; `react/rules-of-hooks` is an error and `react/only-export-components` a warning.
- **`Dockerfile`:** the build stage (`node:24-alpine`) runs `npm ci` and `npm run build`. The final stage is nginx with `dist` only.
- **CI (`ci.yml`):**
  - The frontend job uses Node 24 with `cache: npm` and `cache-dependency-path: frontend/package-lock.json`, then runs `npm ci`, `npm run lint` and `npm run build`.
  - The `dependency-scan` job runs a Trivy fs scan that reads `frontend/package-lock.json` directly, with `TRIVY_INCLUDE_DEV_DEPS: true`. Its gate fails on fixed HIGH/CRITICAL findings.
  - `build-images.yml` scans the built images. The frontend image contains only nginx and `dist`.
- **Dev container:** Node feature `lts`.

**Backend (read only; nothing changes):**
- **`GET /api/categories`** returns the active categories as `[{id, name, icon, archived, createdAt}]`, sorted by id.
- **`GET /api/expenses`** returns `{items, page, size, totalItems, totalAmount}`.
  - Omitted `from`/`to` mean the current month (`YearMonth.now(clock)`, `APP_TIME_ZONE`).
  - Sort order: `spentOn desc, createdAt desc, id desc`.
  - A page past the end is a 200 with `items: []` and the totals.
  - Item: `{id, amount: "12.50", currency: "PLN", spentOn: "2026-10-01", note | null, createdAt, category: {id, name, icon}}`.
- **`POST /api/expenses`** takes an `ExpenseRequest` and returns 201:
  - `amount`: `@NotNull @Positive @Digits(10,2) @DecimalMax`;
  - `categoryId`: `@NotNull`;
  - `spentOn`: `@NotNull @PastOrPresent`, checked against the Clock in `APP_TIME_ZONE`;
  - `note`: `@Size(max = 255)`, with `""` turned into `null`.

  The response serialises `amount` with `setScale(2)`, so the request value `"12.5"` comes back as `"12.50"`.
- **`PUT /api/expenses/{id}`** takes the same request.
  - Bean Validation runs first, then the 404 check (`detail "Expense not found"`, plus `id`).
  - The category is checked only when `categoryId` differs from the current one, so keeping an archived category is allowed. Switching to a different archived category returns 409.
- **`DELETE /api/expenses/{id}`** returns 204, or 404 `"Expense not found"`.
- **`ApiExceptionHandler`:**
  - Bean Validation errors → 400 with `detail "Invalid request content."` and `errors[]` sorted by field, then message. One field can appear twice, for example `amount` failing both `@Digits` and `@DecimalMax`.
  - Unknown category → 400 `errors[{categoryId, "Category not found"}]`.
  - Archived category → 409 with `detail "Category is archived"` and no `errors[]`.
  - Unreadable JSON → 400 **without** `errors[]`. Examples: `amount: "abc"` or `"12,50"`, or a date that cannot be parsed. Spring's default detail is expected to be "Failed to read request"; `ExpenseControllerIT.rejectsUnreadableBody` asserts only that `errors` is absent.
  - Anything else → 500 `"Unexpected error"`.
- **`""` handling:**
  - `""` in `amount` is read as `null` (README), which gives `errors[{amount, "must not be null"}]`.
  - `""` for `spentOn` is not covered by any backend test. It is expected to be read as `null` too, but it could be a 400 without `errors[]`. The page does not depend on either outcome: it sends `null` for an empty date (decision 13).

### API calls made by the page
| When | Request | Fields used |
|---|---|---|
| Page mount | `GET /api/categories` (active only, no query string) | `id`, `name` |
| Page mount, Prev/Next, and after every successful mutation (D1) | `GET /api/expenses?page={page}&size=50` (no `from`/`to`) | `items`, `page`, `size`, `totalItems`, `totalAmount` |
| Create | `POST /api/expenses` with `{"amount": "12.5", "categoryId": 1, "spentOn": "2026-10-01", "note": "Lunch"}` (the amount exactly as typed, decision 13) | status only; the body is ignored (D1) |
| Edit | `PUT /api/expenses/{id}` with the same body shape | status only |
| Delete | `DELETE /api/expenses/{id}` | status only |

### Error placement
| Outcome | Create / edit form | Delete | List or categories load |
|---|---|---|---|
| `fetch` rejects (network) | Page-level: "Could not reach the server. Check your connection and try again." The form keeps its values. | Page-level, same text | Page-level, same text |
| 400 with non-empty `errors[]` | Next to each field (`amount`, `categoryId`, `spentOn`, `note`). Several messages for one field are joined with `"; "`. Entries for any other field name are shown at form level (`role="alert"` inside the form) as `"{field}: {message}"`, joined with `"; "`. | — | Page-level: "Could not load expenses: {detail}" or "Could not load categories: {detail}" |
| 409 | Next to the category field: `{detail}` | Page-level: "Could not delete the expense: {detail}" | Page-level, as above |
| 404 | Edit: page-level "This expense no longer exists. The list has been refreshed.", then a list refetch and leaving edit mode. Create: form-level, as in the next row. | Page-level, same text, then a list refetch (and leaving edit mode if that expense was being edited) | Page-level, as above |
| Any other 4xx, including 400 without `errors[]` | Form-level (`role="alert"` inside the form): "Could not save the expense: {detail}" | Page-level: "Could not delete the expense: {detail}" | Page-level, as above |
| 5xx, with any body (Problem Details, nginx HTML, empty) | Page-level: "Server error ({status}): {detail}", or "Server error ({status})" when there is no detail | Same | Same |
| Neither an `ApiError` nor an abort, for example a `SyntaxError` from a 2xx body that is not JSON, or a programming error | Page-level: "Unexpected error. Please try again." The form keeps its values; no refetch. | Page-level, same text; no refetch | Page-level: "Could not load expenses: unexpected error." or "Could not load categories: unexpected error." |
| Request aborted (unmount, or a newer list request) | Ignored | — | Ignored |

- When `detail` is missing, `{detail}` becomes `request failed with status {status}`.
- **There is one `pageError` slot.**
  - It is set by load errors and by page-level mutation outcomes, and the last message wins.
  - A successful list or categories load **never** clears it.
  - Only the start of a user action clears it: submit, Edit, Cancel, Delete, Prev or Next.
  - The status message is cleared at the same moments.
  - So a categories-load error stays visible after the list loads (E30).
- Field and form errors are cleared each time the form is submitted.

### 1. Change: `frontend/package.json` and `frontend/package-lock.json`
Run from `frontend/`:
1. Pick the Vitest major. Run `npm view vitest version dependencies.vite` (read-only registry query) and check that the `vite` range of the latest release includes `8.3.x`. If it does not, stop and report (R1). The major found here is expected to be 4; it is called `<major>` below.
2. Install with that explicit major:
   ```bash
   npm install --save-dev vitest@^<major> jsdom @testing-library/react @testing-library/dom @testing-library/user-event @testing-library/jest-dom
   ```
   - In `package.json` every new entry must be a caret range of the resolved version (`^x.y.z`), like the existing entries.
   - If npm writes the bare `^<major>` for vitest, change it to `^<resolved>` and run `npm install` once more, so that the root entry in `package-lock.json` matches.
   - **Never** run `rm -rf node_modules`, delete `package-lock.json`, or use `--force` / `--legacy-peer-deps`.
3. Add the script `"test": "vitest run"`. It runs the tests once without watching, which is what CI needs.

| Package | Expected range (confirm what npm resolves) | Why | Cost of doing it by hand |
|---|---|---|---|
| `vitest` | `^4.x`: the major picked in step 1 | Test runner that reuses `vite.config.ts` and the React plugin's transform (D3) | Jest plus a separate TS/JSX transform: a second toolchain and duplicated config |
| `jsdom` | latest major (`^27` or newer) | DOM for component tests (D3) | Not feasible |
| `@testing-library/react` | `^16` (React 19 support) | `render`, queries by role/label, `act` handling for React 19 | Your own `createRoot` + `act` helpers and DOM queries |
| `@testing-library/dom` | `^10` | Required peer of RTL 16 and user-event 14. Listing it keeps a single copy, so user-event events are wrapped in `act`. | Not optional |
| `@testing-library/user-event` | `^14` | Realistic typing, selecting and clicking (D3) | `fireEvent` only, without focus and keyboard behaviour |
| `@testing-library/jest-dom` | `^6` | See the justification below | About 20 lines of helpers and weaker failure messages |

**Why `@testing-library/jest-dom` (confirmed in Q1).**
- AC2's "next to the corresponding field" is best asserted as: the field's accessible description is the error text. That is `expect(input).toHaveAccessibleDescription('…')`, computed from `aria-describedby` the way screen readers do it.
- The tests also use `toHaveAttribute('aria-invalid', 'true')`, `toBeDisabled`, `toHaveValue`, `toHaveDisplayValue` and `toHaveFocus`.
- Its `@testing-library/jest-dom/vitest` entry adds the matchers and their TypeScript types to Vitest with one import.

**Checks after the install. The implementer pastes the command output into the report.**
- `npm ls vite` must show one `vite@8.3.x` at the top level. The vitest branch must end in `vite@8.3.x deduped`, and the `@vitejs/plugin-react` branch, if listed, too. A nested `vite` under `vitest` fails this gate (R1).
- `npm ls @testing-library/dom react` shows one copy of each.
- The `engines` fields of the new packages in `package-lock.json` accept Node 24 (CI, Docker build stage) and the dev container's LTS Node.

### 2. Change: `frontend/vite.config.ts`
- Add `/// <reference types="vitest/config" />` as the first line. This adds the `test` key to Vite's `defineConfig` without importing vitest at runtime during `vite build`.
- Add the block below; `plugins` and `server` stay unchanged:
```ts
test: {
  environment: 'jsdom',
  setupFiles: ['./src/test/setup.ts'],
  // East of UTC on purpose: a "today" computed with toISOString() (the UTC date) fails localDate.test.ts and E1.
  env: { TZ: 'Europe/Warsaw' },
  unstubGlobals: true, // undo vi.stubGlobal('fetch', …) after each test
  restoreMocks: true,  // undo vi.spyOn(window, 'confirm') after each test
},
```
- If oxlint flags the triple-slash directive, use `import { defineConfig } from 'vitest/config'` instead.

### 3. New file: `frontend/src/test/setup.ts`
```ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, expect, vi } from 'vitest'
import { rejectUnexpected, unexpectedRequests } from './fetchMock.ts'

// Decision 20 by construction: a test that never calls mockFetch gets this stub, never Node's real fetch.
beforeEach(() => {
  unexpectedRequests.length = 0
  vi.stubGlobal('fetch', vi.fn(rejectUnexpected))
})

// Vitest runs without `globals`, so Testing Library cannot register its automatic cleanup.
afterEach(() => {
  cleanup()
  vi.useRealTimers() // E1 fakes Date
  expect(unexpectedRequests, 'requests without a mockFetch route').toEqual([])
})
```

### 4. New files: `frontend/src/test/fetchMock.ts` and `frontend/src/test/fixtures.ts`
**`fetchMock.ts`:**
- `unexpectedRequests: string[]` is shared with `setup.ts`.
- `rejectUnexpected(input, init)` records `"METHOD url"` and returns `Promise.reject(new Error('Unexpected request: METHOD url'))`.
- `mockFetch(routes)` calls `vi.stubGlobal('fetch', vi.fn(...))` and returns the mock.
  - Routes are keyed by `"METHOD /path?query"`, for example `"GET /api/expenses?page=0&size=50"`.
  - Each route holds a queue of replies, used in order; the last reply repeats. A reply is one of:
    - a function that returns a new `Response`. It must be a factory, because a body can only be read once;
    - an `Error`, which makes `fetch` reject with it;
    - a `Promise<Response>`, for in-flight tests.
  - A request that matches no route goes to `rejectUnexpected`, so `setup.ts` fails the test.
- **`mockFetch` honours `init.signal`:**
  - If the signal is already aborted, the call rejects with `new DOMException('This operation was aborted', 'AbortError')`.
  - Otherwise an `abort` event rejects the pending call the same way, even when its reply is a deferred promise that has not settled.
- **Helpers:**
  - `json(body, status = 200)`;
  - `rawJson(text, status = 200)`, a body with `Content-Type: application/json` that is not valid JSON;
  - `problem(status, detail, errors?)`, which uses `Content-Type: application/problem+json`;
  - `html(status)`;
  - `noContent()`;
  - `requests(mock)`, which returns `[{ method, url, headers, body }]`, with `body` parsed from JSON;
  - `deferred()`.

**`fixtures.ts`:**
- Categories `FOOD` (id 1) and `TRANSPORT` (id 2).
- `expense({...})` builder.
- `list(items, { totalAmount, page = 0, size = 50, totalItems = items.length })`:
  - **`totalAmount` is a required `string` parameter.** The builder never derives it from the item amounts, so the test code never does money arithmetic either (AC5).
  - `totalItems` defaults to a count, which is not money.

### 5. New file: `frontend/src/api/client.ts`
```ts
export type FieldErrors = Record<string, string>   // field -> messages joined with "; "

export class ApiError extends Error {
  readonly status: number | null   // null: no response (fetch rejected)
  readonly detail: string | null   // Problem Details "detail", when the body was JSON with a string detail
  readonly fieldErrors: FieldErrors // from "errors": [{field, message}], in response order
  // explicit fields assigned in the constructor (erasableSyntaxOnly forbids parameter properties)
}

// name-based, not instanceof DOMException: jsdom and Node each have their own DOMException
export function isAbortError(error: unknown): boolean   // typeof error === 'object' && error !== null && name === 'AbortError'
export async function request<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string,
    options?: { body?: unknown; signal?: AbortSignal }): Promise<T>
```
**Request:**
- When there is a body: `Content-Type: application/json` and `JSON.stringify(body)`.
- `signal` is passed through to `fetch`.
- `fetch` is looked up when the call is made, never captured at module load, so `vi.stubGlobal` works.

**When `fetch` rejects:**
- An abort is rethrown unchanged.
- Anything else throws `ApiError(status: null)` with the message "Network error".

**When the response is not `ok`:**
- The body is read as text. It is parsed as JSON only when `Content-Type` contains `json`, inside a try/catch.
- `detail` is taken only if it is a string.
- `errors` entries are taken only if both `field` and `message` are strings.
- Then an `ApiError(status, detail, fieldErrors)` is thrown. An HTML or empty body never causes a crash.

**When the response is `ok`:**
- A 204 (or an empty body) resolves to `undefined`.
- Otherwise the result of `response.json()` is returned. A 2xx body that is not valid JSON rejects with that `SyntaxError`, unwrapped. `expenseErrors.ts` turns it into the generic message.

### 6. New files: `frontend/src/api/categories.ts` and `frontend/src/api/expenses.ts`
```ts
// categories.ts
export type Category = { id: number; name: string; icon: string | null; archived: boolean; createdAt: string }
export const listActiveCategories = (signal?: AbortSignal) => request<Category[]>('GET', '/api/categories', { signal })

// expenses.ts: money is always a string
export type ExpenseCategory = { id: number; name: string; icon: string | null }
export type Expense = { id: number; amount: string; currency: string; spentOn: string; note: string | null;
    createdAt: string; category: ExpenseCategory }
export type ExpenseList = { items: Expense[]; page: number; size: number; totalItems: number; totalAmount: string }
export type ExpenseInput = { amount: string | null; categoryId: number | null; spentOn: string | null; note: string }
export function listExpenses(page: number, size: number, signal?: AbortSignal): Promise<ExpenseList>  // URLSearchParams {page, size}
export function createExpense(input: ExpenseInput): Promise<Expense>
export function updateExpense(id: number, input: ExpenseInput): Promise<Expense>
export function deleteExpense(id: number): Promise<void>
```
- Imports use explicit `.ts` extensions, as in `main.tsx`.
- Type-only imports use `import type`, because of `verbatimModuleSyntax`.

### 7. New file: `frontend/src/expense/localDate.ts`
```ts
// The browser's local calendar date as yyyy-MM-dd. Not toISOString(): that is the UTC date,
// which is still "yesterday" in Warsaw between 00:00 and 01:00/02:00 local time.
export function localIsoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
```

### 8. New file: `frontend/src/expense/expenseErrors.ts`
Pure functions that implement the "Error placement" table, plus the result type the form gets back:
```ts
export type SubmitResult = { ok: true } | { ok: false; fieldErrors: FieldErrors; formError: string | null }
export type ErrorOutcome =
  | { target: 'form'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean; leaveEdit: boolean }
  | { target: 'ignore' }
export function classifyMutationError(error: unknown, operation: 'create' | 'update' | 'delete'): ErrorOutcome
export function describeLoadError(error: unknown, what: 'expenses' | 'categories'): string | null  // null when aborted
```
- `FORM_FIELDS = ['amount', 'categoryId', 'spentOn', 'note']`.
  - Entries for these fields go to `fieldErrors`.
  - Entries for any other field go to `formError` as `"{field}: {message}"`, joined with `"; "`.
- A 409 on create or update maps to `{ categoryId: detail }`.
- **An error that is neither an `ApiError` nor an abort:**
  - `classifyMutationError` returns `{ target: 'page', message: 'Unexpected error. Please try again.', refresh: false, leaveEdit: false }`;
  - `describeLoadError` returns `'Could not load expenses: unexpected error.'` or `'Could not load categories: unexpected error.'`.
- The file is kept separate from the `.tsx` files so that `react/only-export-components` stays quiet.

### 9. New file: `frontend/src/expense/ExpenseForm.tsx`
**Props:**
- `categories: Category[] | null`, where `null` means the categories did not load (yet);
- `expense?: Expense` (edit mode);
- `busy: boolean`, the page's `mutating` flag;
- `onSubmit(input: ExpenseInput): Promise<SubmitResult>`;
- `onCancel?: () => void`.

**State:**
- The four field values as strings.
- `fieldErrors` and `formError`.
- Initial values:
  - create mode: `''`, `''`, `localIsoDate(new Date())`, `''`;
  - edit mode: `expense.amount` (the string as is), `String(expense.category.id)`, `expense.spentOn`, `expense.note ?? ''`.

**Markup:**
- `<form noValidate aria-labelledby={headingId} onSubmit={…}>` with the heading "New expense" or "Edit expense".
- Amount: label "Amount", `type="text" inputMode="decimal" autoComplete="off" placeholder="12.50"`.
  - It has a ref.
  - In edit mode, a mount effect calls `amountRef.current?.focus()` (decision 7). The `key` remount makes it run once per edit.
- Category: label "Category", a `<select>` whose first option is `<option value="">Select a category</option>`, followed by `categories ?? []` in API order.
  - In edit mode, if the expense's category is missing from that list, an extra option is added and preselected (decision 8).
  - Its label is `"<name> (archived)"` when `categories !== null`, and `"<name>"` otherwise.
- Date: label "Date", `type="date"`.
- Note: label "Note", `type="text"`.
- A field with an error renders `<p id={errorId} className="field-error">{message}</p>` and sets `aria-invalid` and `aria-describedby` on the input.
- The form-level error is `<p role="alert" className="error">` above the buttons.
- Buttons:
  - create mode: "Add expense";
  - edit mode: "Save changes" and "Cancel" (`type="button"`).

  Each button is `disabled={busy}`.

**Submit:**
1. Call `preventDefault()`.
2. Clear the errors.
3. Build the `ExpenseInput` as in decision 13 (`categoryId: Number(value)` for a non-empty option value).
4. Await `onSubmit`. If it fails, show its errors.

Use controlled inputs and `onSubmit`, not React 19 form actions, which reset forms automatically and make it harder to keep values after an error.

### 10. New file: `frontend/src/expense/ExpenseTable.tsx`
**Props:** `list: ExpenseList`, `actionsDisabled: boolean`, `onEdit`, `onDelete`, `onPrev` and `onNext`.

**Rendering:**
- When `totalItems === 0`: "No expenses this month." and no pager.
- Otherwise, `<table aria-label="Expenses this month">` with the columns:
  - **Date:** `spentOn` as is (ISO).
  - **Category:** `category.name`.
  - **Amount:** `` `${amount} ${currency}` ``, class `amount`, right-aligned with tabular numbers.
  - **Note:** `note ?? ''`.
  - **Actions:** "Edit" and "Delete" buttons, both `disabled={actionsDisabled}`.
- **Pager:**
  - The caption is `` `${page * size + 1}–${page * size + items.length} of ${totalItems}` `` (en dash).
  - "Previous" and "Next" buttons, disabled at the bounds (decision 17).

### 11. New file: `frontend/src/expense/ExpensesPage.tsx`
**State:**
- `categories: Category[] | null`. It is `null` until the categories load succeeds, and stays `null` if the load fails.
- `list: ExpenseList | null`.
- `page`.
- `reloadKey`, increased after each successful mutation (D1).
- `editing: Expense | null`.
- `formKey`, increased to reset the create form.
- `mutating: boolean` (decision 16).
- `pageError: string | null`, the single slot.
- `status`.

**Effects:**
- Categories are loaded once on mount. An error goes to `pageError` through `describeLoadError`.
- The list is loaded on `[page, reloadKey]`, each time with a fresh `AbortController`, and aborted in the effect's cleanup.
- In both the success and the error branch, the effect returns early when `controller.signal.aborted`.
- On success:
  - if the result has `items.length === 0 && result.page > 0`, the effect calls `setPage(result.page - 1)`;
  - otherwise it calls `setList(result)`.

  It **does not** touch `pageError`.
- On error, `pageError` is set through `describeLoadError`; `null`, which means an abort, is ignored.

**Handlers:**

Each handler first clears `pageError` and `status` (a user action), then sets `mutating = true` and resets it in `finally`.
- **Submit:**
  - Create calls `createExpense`, then increases `formKey`, sets the status "Expense added." and increases `reloadKey`.
  - Update calls `updateExpense(editing.id, …)`, then calls `setEditing(null)`, sets the status "Expense updated." and increases `reloadKey`. Clearing `editing` is safe because no other row can be selected while the PUT is in flight (decision 16).
  - Errors go through `classifyMutationError`:
    - `form`: the errors are returned to the form;
    - `page`: `pageError` is set, plus a refresh and leaving edit mode where the outcome says so, and `{ ok: false, fieldErrors: {}, formError: null }` is returned so the form keeps its values.
- **Delete:**
  - Calls `window.confirm(\`Delete the expense of ${amount} ${currency} (${category.name}, ${spentOn})?\`)`. If the user declines, nothing happens and nothing is cleared.
  - Otherwise it calls `deleteExpense`. On success it shows the status "Expense deleted.", leaves edit mode if `editing?.id` is that row, and increases `reloadKey`.
  - Errors are handled as for submit; a 404 also refreshes the list.
- **Edit, Cancel, Prev and Next** clear `pageError` and `status`, then set `editing` or `page`.

**Markup:**
- `<h1>Expenses</h1>`.
- The page-level `<p role="alert" className="error">`.
- The status line `<p role="status">`.
- The form, rendered as `<ExpenseForm key={editing ? \`edit-${editing.id}\` : \`new-${formKey}\`} busy={mutating} …/>`.
- `<section aria-labelledby>` with:
  - the heading `<h2>This month</h2>`;
  - `<p className="month-total">Total: <strong>{list.totalAmount}</strong></p>` **above** the table;
  - then `ExpenseTable` with `actionsDisabled={mutating}`.
- "Loading…" is shown while `list === null && pageError === null`.

### 12. Change: `frontend/src/App.tsx` and `frontend/src/App.css`
- **`App.tsx`:** replace the greeting demo with `<main className="app"><ExpensesPage /></main>`, keeping the `./App.css` import and the default export (Q4).
  - The UI no longer calls `GET /api/greeting`.
  - The backend endpoint and `GreetingControllerIT` are unaffected.
- **`App.css`:**
  - `.app`: max-width 60rem and `text-align: left`, which overrides `#root`'s centring.
  - Remove `.greeting`. Keep `.hint` and `.error`.
  - Add `.field-error` (the same colour as `.error`, smaller).
  - Add a simple form layout.
  - Add `.expense-table` (`width: 100%`, `border-collapse: collapse`).
  - Add `td.amount` (`text-align: right`, `font-variant-numeric: tabular-nums`, `white-space: nowrap`).
  - Add `.pager` and `.month-total`.
  - No mobile work; that is out of scope.

### 13. Change: `.github/workflows/ci.yml`
In the `frontend` job, add a step after "Type-check and build":
```yaml
      # Vitest in run mode (no watch): component tests in jsdom with a mocked fetch,
      # so no backend or database is needed.
      - name: Test
        run: npm test
```
- **Node and cache:**
  - `actions/setup-node@v6` with Node 24 and `cache: npm` keyed on `frontend/package-lock.json` works unchanged. The new lockfile changes the cache key, so the first run is a cold install.
  - `npm ci` installs devDependencies, because there is no `--omit=dev` and no `NODE_ENV=production`.
  - The job's `working-directory: frontend` applies to the new step.
- **Trivy:**
  - The `dependency-scan` job needs no change, but it **does** cover the new packages: it reads `frontend/package-lock.json` with `TRIVY_INCLUDE_DEV_DEPS: true`. A fixed HIGH/CRITICAL finding in Vitest, jsdom or any of their transitive dependencies would fail the PR's gate (R2).
  - The image scan in `build-images.yml` is unaffected: dev dependencies exist only in the Docker build stage, and the frontend image contains only nginx and `dist`.
- The header comment ("Five independent jobs") stays correct. actionlint checks the change.

### 14. Change: `README.md`
- **Stack:** extend the "Tests" bullet: "…; Vitest with Testing Library (jsdom) for the frontend."
- **"Tests" section:** add `cd frontend && npm test   # Vitest, jsdom, fetch is mocked: no backend needed`.
- **"CI and Docker images":** change "frontend lint and build" to "frontend lint, build and tests (`npm test`)".
- The Trivy section already says that npm devDependencies are scanned, so it does not change.

### 15. Not changed
- **Backend:** nothing changes, so `backend/gradle.lockfile` and the backend tests stay as they are.
- **Other files:**
  - `nginx.conf`, `Dockerfile` and `.dockerignore`. The build stage now also type-checks the test files; its `npm ci` installs the dev dependencies they need.
  - `tsconfig*.json` (see R10), `.oxlintrc.json`, `index.html` and `index.css`.
  - `.trivyignore`, unless R2 occurs, and then only after the user decides.
  - `docs/wallet-backlog.md`, Helm values and `CLAUDE.md` (see Q8).
- **No git operations.**

### Implementation order
1. **Test tooling:**
   - Pick the Vitest major and install (section 1, steps 1–3). Paste the `npm ls` outputs into the report.
   - Add the `vite.config.ts` block, `src/test/setup.ts`, `src/test/fetchMock.ts`, `localDate.ts`, `localDate.test.ts` and `fetchMock.test.ts`.
   - `npm test`, `npm run lint` and `npm run build` must all be green. Vitest exits with code 1 when there is no test file, so the tests are needed in this step.
2. Add `src/test/fixtures.ts`, `src/api/client.ts` and `client.test.ts`. Run `npm test`.
3. Add `src/api/categories.ts`, `src/api/expenses.ts` and `src/expense/expenseErrors.ts`.
4. Add `ExpenseForm.tsx`, `ExpenseTable.tsx` and `ExpensesPage.tsx`, then make the `App.tsx` and `App.css` changes.
5. Write `ExpensesPage.form.test.tsx` and `ExpensesPage.table.test.tsx`.
6. Add the CI step, then run actionlint locally from the repository root with `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color`.
7. Update the README.
8. Run the README's local Trivy fs command (`aquasec/trivy:0.70.0@sha256:…`, `--severity HIGH,CRITICAL --exit-code 1`) from the repository root. If it fails, stop and report (R2).
9. Final check: `cd frontend && npm test && npm run lint && npm run build`, all green.

**Forbidden at every step:**
- `./gradlew bootRun`, `npm run dev` against a backend, and any use of the dev Postgres.
- Deleting `frontend/node_modules`.
- git operations.

A manual browser check is for the user only.

## Tests
Done means `cd frontend && npm test`, `npm run lint` and `npm run build` are green. `./gradlew test` is not required, because no backend file changes.

Conventions:
- **`fetch`:**
  - Every test that renders the page calls `mockFetch` with exactly the routes it expects.
  - `setup.ts` fails any test that made a request with no route, including a test with no `mockFetch` at all (decision 20).
- **Rendering and queries:**
  - Pages are rendered without `StrictMode`, so that request counts are exact.
  - Queries go by role or label, with `within(row)` for row buttons.
  - Every assertion that follows a load uses `await screen.findBy…`.
- **Stubs and fixtures:**
  - Dates are changed with `fireEvent.change(dateInput, { target: { value } })`, because user-event typing into `type="date"` in jsdom is unreliable (R4).
  - `window.confirm` is stubbed with `vi.spyOn(window, 'confirm')`.
  - Money is always asserted as exact strings, and fixtures pass `totalAmount` as an explicit string (section 4).
- **The clock in E1:**
  - Only `Date` is faked: `vi.useFakeTimers({ toFake: ['Date'] })` and `vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))`. In `TZ=Europe/Warsaw` that is 2026-10-01 00:30 CEST, while UTC is still 2026-09-30.
  - Timers are not faked, so user-event and `findBy*` keep working.
  - `setup.ts` restores real timers after each test.

### New file: `frontend/src/test/fetchMock.test.ts`
| # | Test | Proves |
|---|---|---|
| S1 | `unexpectedRequestsAreRecorded`:<br>- Without `mockFetch`, `fetch('/api/x')` rejects with "Unexpected request: GET /api/x" and appears in `unexpectedRequests`.<br>- With `mockFetch({})`, a POST to an unknown route is recorded too.<br>- The test empties `unexpectedRequests` at its end, so its own `afterEach` check passes. | Decision 20 by construction |
| S2 | `honoursAbortSignal`:<br>- A call with an already aborted signal rejects with `name === 'AbortError'`.<br>- A call whose reply is a pending `deferred()` rejects with `AbortError` as soon as its controller aborts. | Decision 16 (abort), suggestion 3 |

### New file: `frontend/src/expense/localDate.test.ts`
| # | Test | Proves |
|---|---|---|
| L1 | Sanity: `new Date(2026, 9, 1, 0, 30).toISOString()` starts with `2026-09-30`, which proves the tests run east of UTC. `localIsoDate` of that date is `2026-10-01`. `localIsoDate(new Date(2026, 0, 5, 23, 59))` is `2026-01-05` (zero padding). | AC1 (default date is the local today), decision 10 |

### New file: `frontend/src/api/client.test.ts`
| # | Test | Proves |
|---|---|---|
| C1 | GET with a 200 JSON body returns the parsed body. The request has method GET, no body and no `Content-Type`. | Decision 3 |
| C2 | POST sends `Content-Type: application/json` and a JSON body. A 201 returns the parsed body. | Decision 3 |
| C3 | DELETE with a 204 resolves to `undefined`; no JSON parse is attempted. | Decision 3 |
| C4 | A 400 `application/problem+json` with `errors` `[amount a, amount b, note c]` → `ApiError` with status 400, `detail`, and `fieldErrors == {amount: "a; b", note: "c"}`. | AC2 |
| C5 | A 400 without `errors` → `fieldErrors == {}` and `detail` set. An empty `errors: []` gives the same result. | AC2, the unreadable-body case |
| C6 | A 409 → status 409 and `detail "Category is archived"`. | AC2 |
| C7 | A 502 `text/html` and a 500 problem body that is not valid JSON → `ApiError` with that status and `detail == null`, without throwing a parse error. | Decision 4 (5xx) |
| C8 | `fetch` rejects with `TypeError('Failed to fetch')` → `ApiError` with `status == null`. | AC2 (network) |
| C9 | Aborting the controller while a `request` with that `signal` is pending (deferred reply) rejects with an error whose `name` is `AbortError`. That error is rethrown unchanged, not as an `ApiError`. `isAbortError` is true for it and for a plain `Error` with `name = 'AbortError'`, and false for a `TypeError` and an `ApiError`. | Decisions 3 and 16 |
| C10 | A 200 response with `Content-Type: application/json` and a body that is not JSON → `request` rejects with a `SyntaxError`, not an `ApiError`. | Decision 4 (unexpected errors) |

### New file: `frontend/src/expense/ExpensesPage.form.test.tsx`
| # | Test | Proves |
|---|---|---|
| E1 | `rendersFormWithActiveCategoriesAndTodayAsDefaultDate`:<br>- The clock is pinned as in the conventions, before the render.<br>- The Amount, Category, Date and Note fields exist (by label).<br>- The category options are exactly ["Select a category", "Food", "Transport"], in API order.<br>- Date `toHaveValue('2026-10-01')`, a literal; a UTC-based "today" would give `2026-09-30`. Amount and note are empty.<br>- The requests are exactly `GET /api/categories` (no query) and `GET /api/expenses?page=0&size=50` (no `from`/`to`). | AC1, decisions 10 and 11 |
| E2 | `createPostsTypedValuesAndRefetchesCurrentPage`:<br>- Type amount `12.5`, select Food, keep the default date, type note `Lunch`, click "Add expense".<br>- The POST has `Content-Type: application/json` and a body equal to `{amount: "12.5", categoryId: 1, spentOn: localIsoDate(new Date()), note: "Lunch"}`, where `typeof amount === 'string'`.<br>- Then `GET …page=0&size=50` again. The second list response puts the new row first, and the first body row shows it.<br>- The total now shows the second response's `totalAmount`.<br>- The form is back to its defaults, and the status is "Expense added.".<br>- `GET /api/categories` was called exactly once. | **AC1**, AC4, AC5 (typed string sent), D1, decision 13 |
| E3 | `rendersRefetchedRowsNotThePostResponse`:<br>- (a) The POST returns an expense dated in the previous month, and the refetched list does not contain it. The table shows exactly the refetched rows, and the total comes from the refetch.<br>- (b) The refetched list puts the back-dated new row third, and it is shown third. | AC1 interpretation, D1 |
| E4 | `emptyFieldsAreSentAsNullWithoutClientValidation`:<br>- Clear the date and leave everything empty.<br>- `fireEvent.submit(form)` returns `false` (default prevented, so no browser navigation or full reload).<br>- The POST body is `{amount: null, categoryId: null, spentOn: null, note: ""}`.<br>- A 400 `errors[{amount,…},{categoryId,…},{spentOn,…}]` is shown next to those three fields. | AC1 ("without a full reload"), AC2, decisions 13 and 14 |
| E5 | `showsValidationErrorsNextToFields`:<br>- 400 with errors: amount "must be greater than 0" and amount "numeric value out of bounds", categoryId "Category not found", spentOn "must be a date in the past or in the present", note "size must be between 0 and 255".<br>- Each field is `aria-invalid="true"` and `toHaveAccessibleDescription` with its message. Amount shows both messages joined with "; ".<br>- There is no page-level alert, the inputs keep their typed values, and the list is **not** fetched again. | **AC2** (400) |
| E6 | `showsArchivedCategoryConflictNextToCategory`: 409 `detail "Category is archived"` → the select is invalid with description "Category is archived". The other fields are not invalid, and there is no page-level alert. | **AC2** (409), decision 5 |
| E7 | `showsUnreadableBodyErrorAtFormLevel`: amount `12,50` → 400 `detail "Failed to read request"` with no `errors` → `within(form).getByRole('alert')` reads "Could not save the expense: Failed to read request". No field is invalid, and there is no page-level alert. | Decision 4, Q7 |
| E8 | `showsNetworkErrorAsPageLevelMessage`: the POST rejects with `TypeError` → a page-level alert outside the form says "Could not reach the server. Check your connection and try again.". The form keeps its values, submit is enabled again, and no field is invalid. | **AC2** (network) |
| E9 | `showsServerErrorsAsPageLevelMessage`: a 500 problem with "Unexpected error" → "Server error (500): Unexpected error". A 502 `text/html` → "Server error (502)", with no crash. | Decision 4 |
| E10 | `showsPageLevelErrorWhenLoadsFail`:<br>- The first expenses GET rejects → page-level network alert, no table, and the form still renders.<br>- Separately, categories GET 500 → page-level "Server error (500): …". | AC2 (network), decision 4 |
| E11 | `disablesSubmitWhileRequestIsInFlight`: the POST reply is deferred. After the click the button is disabled, and a second click sends no second POST. After resolving, exactly one POST was sent. | Decision 16 |
| E12 | `clearsErrorsOnResubmit`: first a 400 amount error, then a successful submit → no `aria-invalid` and no error text are left, and the status is "Expense added.". | Decisions 4 and 15 |
| E27 | `showsGenericMessageForUnexpectedErrors`:<br>- (a) The expenses GET returns `rawJson('not json')` with status 200 → page-level "Could not load expenses: unexpected error.".<br>- (b) With a normal list, the POST returns `rawJson('not json', 201)` → page-level "Unexpected error. Please try again.". The form keeps its values, no field is invalid, and the list is not fetched again. | Decision 4, suggestion 5 |
| E29 | `showsErrorsForUnknownFieldsAtFormLevel`: 400 with errors `[{amount, "must be greater than 0"}, {currency, "must be null"}]` → amount is invalid with its description. `within(form).getByRole('alert')` reads "currency: must be null". There is no page-level alert. | AC2, "Error placement" (unknown field) |
| E30 | `listLoadDoesNotClearCategoriesError`:<br>- Categories GET → 500 problem "Unexpected error". The expenses GET is deferred.<br>- Wait for the alert "Server error (500): Unexpected error", then resolve the list.<br>- The table renders, and the same alert is **still** shown. | "Error placement" (one `pageError` slot), suggestion 11 |

### New file: `frontend/src/expense/ExpensesPage.table.test.tsx`
| # | Test | Proves |
|---|---|---|
| E13 | `tableHasColumnsAndRowsFromApi`:<br>- The column headers are exactly Date, Category, Amount, Note, Actions.<br>- One row reads "2026-10-01", "Food", "200.00 PLN", "Lunch". A `null` note gives an empty cell.<br>- Every row has Edit and Delete buttons. | **AC3** |
| E14 | `editPrefillsFormAndPutsAllFields`:<br>- Edit on row 2 → heading "Edit expense", fields prefilled (amount `"12.50"`, the category selected, the date, the note), and **the amount input `toHaveFocus()`**.<br>- Change the amount to `15`, then "Save changes" → `PUT /api/expenses/{id}` with all four fields.<br>- Then the current page is fetched again, the form is back in create mode with its defaults, and the status is "Expense updated.". | **AC3**, D1, decision 7 |
| E15 | `editKeepsArchivedCategory`:<br>- (a) A row's category `{id: 9, name: "Old"}` is not in the active list. Edit → an option "Old (archived)" exists and is selected (value `9`). Saving unchanged sends a PUT body with `categoryId: 9`. After Cancel, the create form has no "Old (archived)" option.<br>- (b) Categories GET → 500. Edit on a Food row → the options are exactly ["Select a category", "Food"], "Food" is selected, and no option contains "(archived)". | AC3, decision 8 |
| E16 | `editShowsConflictAndValidationErrorsNextToFields`: PUT 409 → the error is next to the category. PUT 400 `errors[amount]` → the error is next to the amount. The form stays in edit mode with the edited values. | AC2, AC3 |
| E17 | `editOfDeletedExpenseShowsPageMessageAndRefreshes`: PUT 404 "Expense not found" → the page-level message "This expense no longer exists. The list has been refreshed.", the list is fetched again, and the form is back in create mode. | Decision 6 |
| E18 | `cancelEditMakesNoRequest`: Edit, then Cancel → create mode with defaults, and no PUT. | Decision 7 |
| E19 | `deleteAsksForConfirmation`:<br>- `confirm` returns `false` → no DELETE.<br>- `confirm` returns `true` → `DELETE /api/expenses/{id}` with no body, then the current page is fetched again. The row is gone (per the refetch), the total updates, and the status is "Expense deleted.". | **AC3**, D1, decision 9 |
| E20 | `deleteFailuresAndEditedRow`:<br>- DELETE 404 → page-level message and a refetch.<br>- DELETE rejects → page-level network message and no refetch.<br>- Deleting the row that is being edited → the form is back in create mode. | Decision 6 |
| E21 | `monthTotalIsTotalAmountFromResponse`:<br>- The rows "10.00" and "20.00" come with `totalAmount "136.00"` (deliberately not their sum) → "Total: 136.00".<br>- The total paragraph comes **before** the table in DOM order: `total.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING`.<br>- An empty month → "Total: 0.00" and "No expenses this month." | **AC4** |
| E22 | `amountsAreRenderedVerbatim`:<br>- The item amounts "200.00", "0.10" and "9999999999.99" appear exactly as "200.00 PLN", "0.10 PLN" and "9999999999.99 PLN".<br>- `totalAmount "1234567890123456.78"` appears exactly. `Number()` would turn these into "200", "0.1" and "1234567890123456.8".<br>- Edit on the "200.00" row → the amount input has the value "200.00". | **AC5** |
| E23 | `paginatesWithPrevNextAndCaption`:<br>- `totalItems 120`: page 0 → "1–50 of 120", Previous disabled, Next enabled.<br>- Next → `GET …page=1&size=50` → "51–100 of 120".<br>- Next → page 2 with 20 items → "101–120 of 120", Next disabled.<br>- Previous → `page=1`.<br>- On every page the total shows that response's `totalAmount`. | D2, AC4 |
| E24 | `mutationsRefetchTheCurrentPage`: on page 1, each of create, edit and delete is followed by `GET …page=1&size=50`. | D1, D2, Q2 |
| E25 | `stepsBackWhenRefetchedPageIsEmpty`: page 1 has one row. Delete → the refetch of `page=1` returns `items: []` with `totalItems 50` → `GET …page=0` follows → "1–50 of 50", Previous disabled. | Decision 17 |
| E26 | `showsLoadingState`: the first expenses GET is deferred → "Loading…". After it resolves, the table appears and "Loading…" is gone. | Decision 16 |
| E28 | `disablesAllActionsWhileAnyMutationIsInFlight`:<br>- (a) Edit row A and save with a deferred PUT → every Edit and Delete button is disabled, and so are "Save changes" and "Cancel". Resolve → the list is fetched again, create mode, buttons enabled.<br>- (b) Delete row B with a deferred DELETE → every Edit and Delete button and "Add expense" are disabled. Resolve → enabled.<br>- (c) A deferred POST → every Edit and Delete button is disabled. Resolve → enabled. | Decision 16 (serialised mutations), suggestion 10 |

### Criterion → tests
- **AC1:** E1, E2, E3, E4, L1
- **AC2:**
  - next to a field: E4, E5, E6, E16, E29;
  - network at page level: E8, E10;
  - client parsing: C4–C8;
  - other placements: E7, E9, E27, E30.
- **AC3:** E13, E14, E15, E16, E17, E18, E19, E20, E28
- **AC4:** E2, E21, E23
- **AC5:** E2 (the typed string is sent), E14, E22, plus the `list()` fixture rule
- **User decisions:**
  - D1 → E2, E3, E14, E19, E24;
  - D2 → E23, E24, E25;
  - D3 → the whole setup and every test;
  - D4 → the CI step, checked by actionlint and the PR run.
- **Plan decisions:**
  - 3 → C1–C10;
  - 4 → E7, E9, E27, C10;
  - 7 → E14, E18;
  - 8 → E15;
  - 16 → E11, E26, E28, S2, C9;
  - 20 → S1.

## Risks and open questions

### Open questions
No acceptance criterion is blocked. **All questions were confirmed by the user on 2026-10-01, as proposed.**
1. **Q1. New devDependencies. Confirmed:** `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/dom`, `@testing-library/user-event` and `@testing-library/jest-dom` (section 1).
   - `@testing-library/jest-dom` is included.
   - The exact resolved versions and the `npm ls` outputs go into the implementer's report.
2. **Q2. Creating while on page > 0. Confirmed:** keep D1 literally. The page fetches the current page again, and the status "Expense added." confirms the save.
3. **Q3. Month label. Confirmed:** "This month".
4. **Q4. Remove the greeting demo from the UI. Confirmed.** The backend endpoint stays.
5. **Q5. Status messages** ("Expense added/updated/deleted."). **Confirmed.**
6. **Q6. Currency. Confirmed:** rows show `200.00 PLN`, using each item's `currency`; the total is shown without a currency.
7. **Q7. A comma decimal (`12,50`)** gets a form-level "Could not save the expense: Failed to read request". **Confirmed.**
   - The follow-up is backend card expense-crud Q5: map Jackson type errors to `errors[{field}]`.
   - The page would then show them next to the field with no frontend change.
8. **Q8. `CLAUDE.md` "Commands"** gets `Frontend tests: cd frontend && npm test`. **Confirmed that the user adds it.** This plan does not edit `CLAUDE.md`.
9. **Q9. Delete confirmation with `window.confirm`. Confirmed.**

### Risks
1. **R1. Vitest and Vite 8 compatibility.**
   - Vitest depends on `vite` as a regular dependency, not a peer. A Vitest release whose range does not include 8.3 therefore usually does **not** fail with ERESOLVE: npm silently nests a second vite under `vitest`. The `test` key's types and the plugin types then belong to different vite copies.
   - The versions were not checked against the registry while planning.
   - Mitigations:
     - pick the major with `npm view` first and install it explicitly (section 1);
     - the real gate is `npm ls vite`, which must show `vitest@… └── vite@8.3.x deduped`, and its output is pasted into the report.
   - If the gate fails or npm reports ERESOLVE: stop and report. Do not use `--force` or `--legacy-peer-deps`.
2. **R2. Trivy covers the new dev dependencies** (`TRIVY_INCLUDE_DEV_DEPS: true`). jsdom has a fairly large transitive tree, and a fixed HIGH/CRITICAL finding would fail the PR.
   - Mitigation: run the local Trivy command (implementation order step 8).
   - If it fails, the user chooses between a version bump, an npm `overrides` entry, and a `.trivyignore` entry following that file's convention.
3. **R3. The `TZ` setting.** It is assumed that Vitest's `test.env.TZ` reaches Node before `Date` is used.
   - L1's sanity assertion and E1's literal date fail if it does not.
   - Fallback: `"test": "TZ=Europe/Warsaw vitest run"`, which is fine on Linux, the dev container and CI.
4. **R4. jsdom and `type="date"`.** Typing into a date input with user-event is unreliable in jsdom, so the tests use `fireEvent.change` for dates. Real browsers are not covered: there are no end-to-end tests.
5. **R5. Testing Library cleanup without Vitest globals.** If `setup.ts` is missing, DOM from one test leaks into the next and the unexpected-request guard does not run. The setup file handles both, and S1 pins the guard.
6. **R6. Browser "today" and `APP_TIME_ZONE`.**
   - Normally they are the same zone.
   - If the browser is ahead of the server's zone near midnight, the default date is the server's "tomorrow". The server returns a 400 `spentOn` error, shown next to the date field, and the user corrects the date.
   - If the browser is behind, the expense is dated the server's "yesterday". On the 1st of a month that date is outside the current month, so the expense is saved but not listed (the status message mitigates this).
   - A page left open past midnight keeps the old default until the form resets.
7. **R7. Stale categories.** A category archived after the page loaded stays in the select until the page is reloaded. Submitting with it gives 409 next to the category field. Fetching the categories again after a 409 is out of scope.
8. **R8. Non-idempotent POST.** If the server commits and the connection then drops, the user sees a network error, and a retry creates a duplicate. This is accepted for a single-user app. Double clicks are prevented (decision 16).
   - The same applies to a 2xx whose body is not JSON (E27(b)): the expense may already exist although the page shows "Unexpected error". It is visible after the next list refetch.
9. **R9. Message language.** Bean Validation messages are interpolated in the request locale (Accept-Language), while custom messages ("Category not found", "Category is archived") are English. The page shows them verbatim, so a non-English browser may see mixed languages.
10. **R10. `tsc -b` now type-checks the tests.**
    - A type error in a test fails `npm run build`, in CI and in the Docker build stage. This is intended.
    - Because the tests live under `src/`, the `tsconfig.app.json` program also loads Vitest's types, the jest-dom augmentation and possibly `@types/node` through them. Node globals such as `process` would then type-check in application code too, where they do not exist at runtime.
    - This is accepted for now: oxlint and review cover it. A separate `tsconfig.test.json`, with app sources excluding `*.test.ts(x)` and `src/test/`, can come later.
    - The production image size is unchanged (multi-stage build).
11. **R11. More CI time.** The first run after this change installs cold, and the job gets a test step that takes a few seconds.
12. **R12. One `pageError` slot.**
    - If the categories load and the list load both fail, the later message replaces the earlier one.
    - The next user action (for example Next) clears a categories-load error, although the select stays empty until the page is reloaded.
    - This is accepted: the empty select is visible, and a reload retries both loads.

## Out of scope
- **Card 8:** filters (date range, categories), quick-template buttons, and syncing filters with the URL query string.
- **Card 9:** the "Month" page, navigation, and a router.
- **Explicitly excluded by the card:** mobile layout polish and optimistic updates.
- **Client behaviour:**
  - Client-side validation or input normalisation (commas, trimming).
  - Thousands separators or `Intl` number formatting.
  - Sorting options.
  - Fetching categories again after a 409, or after a failed load.
  - A retry button.
  - Category management UI.
- **Backend:** any change, including mapping Jackson type errors to `errors[]` (Q7) and adding a currency to the list response (Q6).
- **Testing extras:** end-to-end tests against a real backend (Playwright or similar), MSW, coverage thresholds, visual tests, and a separate `tsconfig.test.json` (R10).
- **Files outside the plan:** `nginx.conf`, `Dockerfile`, `.dockerignore`, tsconfig, oxlint config, Helm, `docs/wallet-backlog.md`, `CLAUDE.md` (Q8) and `.trivyignore` (unless R2 occurs and the user decides).
