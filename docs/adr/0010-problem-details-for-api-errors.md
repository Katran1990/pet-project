# 0010. RFC 9457 Problem Details for all API errors

Date: 2026-09-25
Status: accepted

## Context
The first endpoint kept Spring Boot's default error body (`timestamp`, `status`, `error`,
`path`), which has no field-level details. The category card needed validation errors per
field, 404 and 409 responses, and one format for every endpoint that the frontend could
show next to form fields.

## Decision
- `spring.mvc.problemdetails.enabled=true`, and one `@RestControllerAdvice`,
  `dev.katran.pet.web.ApiExceptionHandler`, which extends `ResponseEntityExceptionHandler`
  (Boot's own handler backs off).
- All errors are `application/problem+json`. Validation errors add
  `errors: [{field, message}]`, sorted by field, then message. The rule is in `CLAUDE.md`.
- Binding failures (type errors in query parameters) get the fixed message
  `"invalid value"`, so no Java type names leak.
- Controllers throw domain exceptions from the `web` package, and the handler maps them:
  - `NotFoundException(entity, id)` → 404, `"<Entity> not found"` plus an `id` property;
  - `InvalidFieldException(field, message)` → 400 with one `errors[]` entry, used for
    checks Bean Validation cannot make (an unknown `categoryId` → `"Category not found"`);
  - `ConflictException(detail)` → 409 for conflicts decided by the application.
- `DataIntegrityViolationException` is mapped by constraint name through
  `CONSTRAINT_CONFLICT_DETAILS` to a 409. Only constraints the API can actually violate are
  mapped (`uq_category_name_lower`, `uq_budget_limit_category_month`).
- A catch-all handler returns a generic 500 `"Unexpected error"`, logs the exception, and
  never echoes its message.
- An unreadable JSON body is a 400 without `errors[]`.

## Alternatives considered
- Spring Boot's default error body: used by the first endpoint, replaced project-wide.
- A `@RestControllerAdvice` with its own error shape: not chosen in favour of the standard.
- `ResponseStatusException` thrown from controllers: replaced by domain exceptions.
- Rethrowing unknown integrity violations from the handler: in Spring MVC 7 the exception
  escapes as a non-Problem-Details error, so the handler delegates to the catch-all.
- Mapping Jackson type errors to field-level `errors[]`: changes every endpoint and is
  left for its own card.
- Per-field messages through `messages.properties`: not chosen.

## Consequences
- The frontend relies on `errors[]` to place messages next to fields (ADR 0018).
- An application 409 carries no field; the client knows which field from the endpoint
  (for example "Category is archived" → the category field).
- Constraints the API cannot violate stay unmapped and surface as the generic 500.
- The `web` package knows constraint names of feature packages (accepted trade-off).
- Errors raised before Spring MVC dispatch (servlet filters) would still get Boot's body;
  there are no custom filters.
- Validation messages are Bean Validation defaults in the request locale.
