# 0017. Report SQL lives in one shared .sql file run through JdbcClient

Date: 2026-10-01
Status: accepted

## Context
The monthly report by category needs a `GROUP BY` of expenses, a full outer join with
budget limits, shares and remainders, and the month total, with no arithmetic in Java.
The first Grafana panel was planned to show the same numbers. All database access in
main code was Spring Data JPA until then.

## Decision
- The report is one SQL statement in `backend/src/main/resources/db/report/by-category.sql`:
  - CTEs, with expenses aggregated before the join;
  - a full outer join with the month's limits;
  - a window function for the total;
  - sorting done in SQL.
- It has exactly one named parameter, `:month`, written as `cast(:month as date)`.
- `CategoryReportRepository` (a `@Repository`) loads the file once at startup and runs it
  with `JdbcClient`, mapping rows to a flat record through `query(CategoryReportLine.class)`.
- Java only restructures the rows. The SQL guarantees the scales (2 for money, 1 for
  share), so there is no `setScale`.
- The Grafana "Wallet" dashboard uses the same text with `:month` replaced by its own
  month expression (ADR 0020).

## Alternatives considered
- A Java text block constant: no resource loading, but the Grafana author would copy SQL
  out of Java code.
- A Postgres function in a Flyway repeatable migration (`R__category_report.sql`): nothing
  copied, but it would be the first DB function and the first repeatable migration, and
  every query change would become a migration.
- The same function in a versioned migration: conflicted with the backlog's reserved
  version numbers.
- HQL (Hibernate 7 supports CTEs, full joins and window functions): a second dialect of
  the same query, while Grafana needs plain SQL anyway.
- A Spring Data JPA native query: needs a host repository of an unrelated entity, forces
  the SQL into an annotation constant, and goes through Hibernate's scalar type inference.
- `JdbcTemplate`: same engine, more code than `JdbcClient`.

## Consequences
- The SQL is not validated at startup; only the report integration tests catch syntax
  errors.
- Changing the report means changing the `.sql` file, and then regenerating the Grafana
  query from it. A test fails if the two drift (proposed in ADR 0020).
- The SQL avoids `::` casts next to named parameters and reserved words as aliases
  (`limit`, `period`).
- One statement means one snapshot, so the total always equals the sum of the rows.
