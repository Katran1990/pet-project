# 0016. Query parameters bound into validated @ModelAttribute records

Date: 2026-09-30
Status: accepted

## Context
`GET /api/expenses` was the first endpoint with query parameters: a date range, a list of
category ids, page and size. Type errors and range errors had to come back in the same
Problem Details shape with `errors[]` (ADR 0010), and out-of-range values had to be
rejected rather than silently adjusted.

## Decision
- Query parameters are bound into a record with `@Valid @ModelAttribute`
  (`ExpenseListQuery`, `BudgetLimitListQuery`, `CategoryReportQuery`).
- Constraint annotations go on the record components, never directly on controller method
  parameters.
- Conversion failures and Bean Validation failures both raise
  `MethodArgumentNotValidException`. Binding failures carry the message `"invalid value"`.
- Defaults are applied in the compact constructor, with wrapper types (`Integer`) so a
  missing value can be told apart. The compact constructor must never throw.
- Values are never clamped: `size` outside `1..200`, `page < 0` or a malformed date gives
  400.
- An empty value (`from=`, `categoryIds=`) means the same as an omitted one.
- Dates use `@DateTimeFormat(iso = DATE)`; id lists are comma-separated.
- Checks that need several fields or the database run in the controller and throw
  `InvalidFieldException` (`from` after `to`, unknown category ids).
- Sorting is fixed in the query, with `id` as the final tie-breaker, so paging is
  deterministic.

## Alternatives considered
- `@RequestParam` with `@Min`/`@Max` on method parameters: switches to method validation,
  so range errors become `HandlerMethodValidationException` and type errors
  `MethodArgumentTypeMismatchException`, both without `errors[]`. That gives two error
  shapes and needs two more handler overrides.
- Spring Data's `Pageable` resolver with `default-page-size`/`max-page-size`: it is only
  configuration, but it silently clamps `size` and resets invalid pages, and exposes a
  `sort` parameter.
- `spring.mvc.format.date=iso` globally: an app-wide change for one endpoint.
- Per-field messages through `messages.properties`: not chosen.
- Dropping empty entries in `categoryIds` (`1,,4`): rejected, they give 400.

## Consequences
- If any parameter fails to convert, Bean Validation does not run for that request, so
  clients see one class of error at a time.
- New list endpoints copy the record pattern, and the existing handler needs no change.
- Spring can bind request headers to `@ModelAttribute` parameters (for example the
  standard `From` header to `from`); a test guards against it.
