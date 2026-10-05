# 0018. Frontend architecture: server-driven pages, native fetch, no router

Date: 2026-10-01
Status: accepted

## Context
The expense form and table card replaced the greeting demo with the first real page and
added the frontend test setup. The filter, month-report and category-management cards
built on the same patterns. The backend already validates everything and returns Problem
Details (ADR 0010), totals (ADR 0009) and server-side defaults (ADR 0015).

## Decision
- **Tests:**
  - Vitest in run mode (`npm test`), `@testing-library/react`, `user-event`, `jest-dom`
    and jsdom; `TZ=Europe/Warsaw` in the Vitest config.
  - Test files live next to the code under `src/`, so `tsc -b` type-checks them.
  - `fetch` is replaced in every test: `setup.ts` installs a rejecting stub, `mockFetch`
    routes exact `"METHOD url"` keys, and any request without a route fails the test.
  - Tests render without `StrictMode`, so request counts are exact.
  - CI runs `npm test` in the frontend job.
- **API access:** a small typed wrapper `src/api/client.ts`. It throws one
  `ApiError {status | null, detail, fieldErrors}` and detects aborts by `name`. API
  modules sit in `src/api/`, and pages in `src/<feature>/`, mirroring the backend packages.
- **The server is the only validator and the source of truth:**
  - forms use `noValidate` and have no `required`, `min` or `maxLength`;
  - inputs are sent as typed; empty required inputs are sent as `null`, and money stays a
    string;
  - after every successful mutation the page fetches the list again and renders the
    response; a POST/PUT/PATCH response is never inserted into the UI;
  - mutations are serialised through a page-level `mutating` flag (plus `reloadPending`
    where a reload remounts forms);
  - for loads, the latest request wins (`AbortController`).
- **Error placement:**
  - next to the field (`aria-invalid`, `aria-describedby`) for `errors[]` entries of known
    fields and for a 409 that belongs to a field;
  - a form-level alert for other 4xx and for unknown fields;
  - page level for network errors, 5xx, unexpected errors and load errors.
  - There is one `pageError` slot, cleared only by user actions. The rules live in a pure
    classifier module per page.
- **Navigation:**
  - no router: `App` keeps the tab (Expenses, Month, Categories) in state, and only the
    active page is mounted, so a page loads fresh data whenever it is shown;
  - expense filters are mirrored in the URL with `URLSearchParams` and
    `history.replaceState`, read once at mount and normalised;
  - `page` and `size` are not in the URL.

## Alternatives considered
- Jest: a second toolchain and duplicated transform configuration.
- MSW: not used; component tests mock `fetch` directly.
- axios or react-query: dependencies and features the pages do not need.
- `type="number"` inputs: browsers clean up or localise the value.
- Client-side validation or `maxLength` attributes: would duplicate server rules.
- Optimistic updates: excluded by the card.
- `react-router` or URL-state libraries: there were one to three pages and built-in
  browser APIs suffice.
- `pushState` with a `popstate` listener: one history entry per filter change; not
  needed for bookmark and reload.
- Keeping inactive pages mounted: they would show stale data.
- The full ARIA tabs pattern: more code; buttons with `aria-current="page"` chosen.
- A separate `tsconfig.test.json`: deferred.
- Keeping old rows on screen while a new filter loads: needs per-request bookkeeping.
- End-to-end browser tests: out of scope.

## Consequences
- Every page test must route every request the page makes, including the requests other
  components on the page make on mount.
- Test-only types (Vitest, jest-dom, possibly Node) are visible to application code in
  the app tsconfig program.
- Stale lists are corrected by the server: a 404 or 409 is shown and the list is reloaded
  where that helps.
- On reload, forms that remount discard drafts in other rows (Month page).
- The active tab is not in the URL, so a browser reload opens Expenses.
- Messages can mix languages: Bean Validation follows the browser locale, custom
  messages are English.
- New pages copy these patterns (classifier module, `mutating`, one `pageError` slot,
  refetch).
