# Wallet: backlog

Expenses by category with monthly limits. One user, no authentication yet.
All business logic lives in the backend; the web client and the future mobile
client only display data from the API.

The cards below mirror the "To Do" list of the Pet Project Trello board, in
implementation order.

## Data model

Four tables. `user_id` is intentionally absent; it will be added by a single
migration when login arrives.

| Table | Columns |
|---|---|
| `category` | id, name varchar(64) unique, icon varchar(32) nullable, archived boolean default false, created_at timestamptz |
| `expense` | id, category_id FK (on delete restrict), amount numeric(12,2) check > 0, currency char(3) with default, spent_on date not null, note varchar(255) nullable, created_at timestamptz; index (spent_on, category_id) |
| `budget_limit` | id, category_id FK, month date (first day of month, check), amount numeric(12,2) check > 0; unique (category_id, month) |
| `quick_template` | id, name varchar(64), category_id FK, amount numeric(12,2) check > 0, sort_order int |

Aggregations are SQL queries, not tables. Grafana reads the same Postgres
database directly.

## Cards

| # | Card | Trello |
|---|---|---|
| 1 | Category CRUD | https://trello.com/c/pGEvCuca |
| 2 | Expense create, read, update, delete | https://trello.com/c/FhFglXQj |
| 3 | Expense list with filters and pagination | https://trello.com/c/wiwEnIHA |
| 4 | Monthly budget limits | https://trello.com/c/24bBhnVQ |
| 5 | Monthly report by category | https://trello.com/c/JsXWwY6W |
| 6 | Quick templates | https://trello.com/c/AP82ipCY |
| 7 | Expense form and current-month table | https://trello.com/c/QmnDLDb9 |
| 8 | Expense filters and quick-template buttons | https://trello.com/c/7i0hjF1f |
| 9 | Monthly report page with limits | https://trello.com/c/vKrf3QD1 |

---

### 1. Category CRUD

Flyway migration `V2__create_categories_table.sql`, entity, repository, REST
controller. Categories are archived, never deleted, because expenses reference
them.

Table `category`: id, name varchar(64) unique, icon varchar(32) nullable,
archived boolean default false, created_at timestamptz.

**Acceptance Criteria**

- [ ] `POST /api/categories` creates a category; a duplicate name (case-insensitive) returns 409
- [ ] `GET /api/categories` returns active categories; `?includeArchived=true` includes archived ones
- [ ] `PATCH /api/categories/{id}` updates name, icon and the archived flag
- [ ] No `DELETE` endpoint; the table has `archived boolean default false`
- [ ] Testcontainers integration test covers create, duplicate name, archive and the includeArchived filter

**Out of scope**

- User ownership (`user_id`), category ordering, nested categories.

---

### 2. Expense create, read, update, delete

Migration `V3__create_expenses_table.sql`: id, category_id FK to category with
`on delete restrict`, `amount numeric(12,2) check (amount > 0)`, currency
char(3) with a default, `spent_on date` not null, note varchar(255) nullable,
created_at timestamptz, index on `(spent_on, category_id)`.

**Acceptance Criteria**

- [ ] `POST /api/expenses` accepts amount, categoryId, spentOn, note and returns 201 with the created expense
- [ ] 400 when amount <= 0, spentOn is in the future, or categoryId is missing; 409 when the category is archived
- [ ] `GET /api/expenses/{id}` returns the expense with an embedded category object (id, name, icon), not a bare categoryId
- [ ] `PUT /api/expenses/{id}` replaces the expense; `DELETE /api/expenses/{id}` returns 204
- [ ] Amounts are serialized as strings with two decimals ("200.00"); input with more than two decimals is rejected with 400
- [ ] Integration tests cover each validation rule and the create/read/update/delete round trip

**Out of scope**

- List endpoint and filters (next card), multi-currency, attachments or receipts.

---

### 3. Expense list with filters and pagination

`GET /api/expenses` with date range, category filter and paging. The response
includes the total of the filtered expenses so clients never sum on their side.

**Acceptance Criteria**

- [ ] Query params: `from`, `to` (ISO dates, inclusive), `categoryIds` (comma-separated list of ids, e.g. `categoryIds=1,4,7`), `page` (0-based), `size` (default 50, max 200)
- [ ] Omitted `from`/`to` default to the first and last day of the current month
- [ ] An unknown id in `categoryIds` returns 400; an empty `categoryIds` param is treated as "all categories"
- [ ] Sorted by spentOn desc, then createdAt desc
- [ ] Response body: `items`, `page`, `size`, `totalItems`, `totalAmount` (sum over all matching rows, not only the current page)
- [ ] Integration tests cover inclusive date bounds, multiple category ids, an empty result and `totalAmount` across pages

**Out of scope**

- Full-text search in notes, sorting options, CSV export.

---

### 4. Monthly budget limits

Migration `V4__create_budget_limits_table.sql`: id, category_id FK to category,
`month date` (always the first day of the month, enforced by a check
constraint), `amount numeric(12,2) check (amount > 0)`, unique
`(category_id, month)`.

**Acceptance Criteria**

- [ ] `PUT /api/budget-limits` with categoryId, month (`YYYY-MM`), amount creates or updates the limit for that pair (upsert) and returns 200 with the stored row
- [ ] `GET /api/budget-limits?month=YYYY-MM` returns limits of that month with embedded category; `month` is required
- [ ] `DELETE /api/budget-limits/{id}` returns 204
- [ ] 400 on malformed month or amount <= 0; 409 when the category is archived
- [ ] Integration tests cover upsert (second PUT updates, does not duplicate) and the unique constraint

**Out of scope**

- A default limit that applies to every month, limit history, notifications on overspend.

---

### 5. Monthly report by category

`GET /api/reports/by-category?month=YYYY-MM`, one SQL query with `GROUP BY`, no
per-row arithmetic in Java. This query is also the source for the first
Grafana panel.

**Acceptance Criteria**

- [ ] Returns `month`, `totalAmount` and `rows`; each row has category (id, name, icon), `amount`, `share` (percent of totalAmount with one decimal, 0.0 when total is zero), `limit` (nullable), `remaining` (limit minus amount, nullable when there is no limit, negative when exceeded)
- [ ] Categories that have a limit but no expenses in that month appear with amount 0
- [ ] Categories with neither expenses nor a limit are absent
- [ ] Rows are sorted by amount desc
- [ ] Integration tests cover: a month without expenses, a category over its limit (negative remaining), a category with a limit and no expenses, and share summing to 100 within rounding

**Out of scope**

- Daily breakdown, month-over-month comparison, the Grafana dashboard itself.

---

### 6. Quick templates

Migration `V5__create_quick_templates_table.sql`: id, name varchar(64),
category_id FK to category, `amount numeric(12,2) check (amount > 0)`,
sort_order int. Templates are deleted, not archived: expenses created from a
template do not reference it, so deletion loses no history.

**Acceptance Criteria**

- [ ] `POST`, `GET`, `PATCH`, `DELETE` on `/api/quick-templates`; `GET` returns templates sorted by sortOrder with embedded category
- [ ] `PATCH` can change name, amount, categoryId and sortOrder
- [ ] `POST /api/quick-templates/{id}/apply` creates an expense dated today with the template's amount and category; an optional body may override `amount` and `note`; returns 201 with the created expense
- [ ] Creating or applying a template for an archived category returns 409
- [ ] Integration tests: apply creates exactly one expense, override works, archived category is rejected

**Out of scope**

- Reordering endpoint with drag-and-drop semantics (sortOrder is set explicitly via PATCH), template usage statistics.

---

### 7. Expense form and current-month table

Frontend page "Expenses": a form to add an expense and a table of the current
month's expenses. No business logic on the client; every number shown comes
from the API.

**Acceptance Criteria**

- [ ] Form fields: amount, category (select of active categories), date (defaults to today), note; submit calls `POST /api/expenses` and prepends the new row to the table without a full reload
- [ ] Backend validation errors (400/409) are shown next to the corresponding field; network errors are shown as a page-level message
- [ ] Table columns: date, category, amount, note, with edit and delete actions calling the API
- [ ] The month total shown above the table is `totalAmount` from the list response
- [ ] Amounts are rendered from the API strings, never re-parsed into floats for display

**Out of scope**

- Filters and quick-template buttons (next card), mobile layout polish, optimistic updates.

---

### 8. Expense filters and quick-template buttons

Extends the "Expenses" page with server-side filters and one-tap templates.

**Acceptance Criteria**

- [ ] Filter controls: date range (from, to) and a multi-select of categories; every change issues a new `GET /api/expenses` request with `from`, `to`, `categoryIds`; no filtering in the browser
- [ ] Filter state is reflected in the URL query string so a filtered view can be bookmarked and reloaded
- [ ] A row of buttons above the form, one per quick template in sortOrder; clicking calls `POST /api/quick-templates/{id}/apply` and refreshes the table and total
- [ ] Pagination controls use `page`/`size` from the API and show `totalItems`

**Out of scope**

- Managing templates from the UI (create/edit/delete), saved filter presets.

---

### 9. Monthly report page with limits

Frontend page "Month": report by category with limits, driven only by
`/api/reports/by-category` and `/api/budget-limits`.

**Acceptance Criteria**

- [ ] Month switcher (previous/next, defaults to current month) reloads the report
- [ ] Table columns: category, amount, share, limit, remaining; rows with negative remaining are highlighted
- [ ] Month total displayed above the table from `totalAmount`
- [ ] Each row has an inline form to set or clear the limit, calling `PUT` / `DELETE /api/budget-limits`; the report reloads after a change
- [ ] No sums, shares or remainders are computed on the client

**Out of scope**

- Charts on the page (Grafana covers visualisation), exporting the report, multi-month view.
