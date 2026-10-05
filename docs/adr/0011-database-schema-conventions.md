# 0011. Database schema conventions

Date: 2026-09-25
Status: accepted

## Context
The category card added the first business table and set the patterns that every later
table (`expense`, `budget_limit`, `quick_template`) followed. The first migration had
created a plural table, `greetings`.

## Decision
- Flyway owns the schema (`spring.jpa.hibernate.ddl-auto=none`). Migrations are lowercase
  SQL in `backend/src/main/resources/db/migration`.
- Table names are singular (rule in `CLAUDE.md`). A migration file may keep the plural
  name from its card (`V3__create_expenses_table.sql` creates `expense`).
- Primary keys are `bigint generated always as identity primary key`.
- Constraints and indexes are named `<kind>_<table>_<what>`: `uq_category_name_lower`,
  `fk_expense_category`, `ck_expense_amount_positive`, `ix_expense_spent_on_category_id`.
- Columns are `not null` wherever the intent requires it, even when the card does not say
  so.
- Foreign keys use `on delete restrict`.
- Invariants are enforced by the database: case-insensitive uniqueness through a unique
  index on `lower(name)`, positive amounts and first-of-month dates through checks, one
  limit per category and month through a unique constraint.
  - There are no "exists" pre-check queries; the constraint is the single source of truth,
    and a reachable violation is mapped to 409 (ADR 0010).
- Bean Validation annotations live only on request records, never on entities.
- No index is added without a measured or obvious need; volumes are single-user.

## Alternatives considered
- A plain `UNIQUE` on `name`: only case-sensitive.
- A partial unique index that ignores archived categories: rejected, archived names still
  count (unarchive instead).
- `citext` or ICU nondeterministic collations for full case folding: more complexity than
  needed.
- An `existsByNameIgnoreCase` pre-check: not race-safe and redundant with the index.
- Bean Validation on entities: Hibernate's pre-insert validation builds its own
  `ValidatorFactory` without the application clock.
- Extra indexes (on `expense.category_id`, on `budget_limit.month`): not worth it at this
  volume.
- Alternatives to singular table names: not recorded.

## Consequences
- `greetings` stays the only plural table; renaming it was out of scope.
- Every new constraint the API can violate needs an entry in the handler's
  constraint-name map; others surface as 500.
- Migrations must not contain `${`, because Flyway treats it as a placeholder.
- Next migration numbers are coordinated with `docs/wallet-backlog.md`.
