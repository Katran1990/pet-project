# 0014. Categories are archived, never deleted

Date: 2026-09-25
Status: accepted

## Context
Expenses, budget limits and quick templates reference categories. Deleting a category
would orphan or destroy history. The category card decided on an `archived` flag and
no DELETE endpoint, and the later cards defined what archiving means for the data that
references a category.

## Decision
- `category.archived boolean not null default false`. There is no DELETE endpoint (405).
  Archive and restore are a PATCH of `archived`.
- Name uniqueness includes archived rows: to reuse a name, restore the archived category.
- Foreign keys to `category` use `on delete restrict`.
- **New data may not go to an archived category**, checked by `ActiveCategoryLookup`
  (409 `"Category is archived"`; an unknown id is 400 `errors[{categoryId}]`):
  - creating an expense, or switching an expense to a different archived category;
  - any PUT of a budget limit, including an update of an existing one;
  - creating a quick template, switching it to another archived category, and every apply.
- **Keeping the current category is allowed** even if it was archived later (expense PUT,
  quick template PATCH), so old records stay editable.
- **Reads include archived categories:**
  - `GET /api/categories/{id}`, and `?includeArchived=true` on the list;
  - expense filters accept archived ids, and "all categories" includes their expenses;
  - the monthly report includes them;
  - budget limits of archived categories can be listed and deleted.
- The embedded category summary `{id, name, icon}` has no `archived` flag. Quick templates
  are the exception: their embedded category carries `archived`, so the UI can disable
  their buttons.

## Alternatives considered
- Excluding archived categories from the report: totals would either disagree with the
  expense list or shares would not sum to 100.
- An `archived` flag in the shared category summary: would change expense, budget-limit
  and report responses; rejected.
- Allowing updates of an existing budget limit of an archived category (mirroring expense
  PUT): rejected, because the category is part of the limit's key.
- A partial unique index that ignores archived rows: rejected.
- Hiding quick templates of archived categories: they are shown disabled instead.
- Deleting categories: not recorded beyond "out of scope" on the card.

## Consequences
- Every new entity that references a category uses `ActiveCategoryLookup` for writes and
  an FK with `on delete restrict`.
- Clients with stale category lists must handle the 409 and show it (field-level in
  forms).
- History of archived categories stays readable everywhere.
- The UI shows archived categories marked, offers them in filters, and leaves them out of
  the expense form's select.
