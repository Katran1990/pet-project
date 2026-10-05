# 0013. Thin controllers over Spring Data repositories, no service layer

Date: 2026-09-25
Status: accepted

## Context
The first endpoint saved a greeting with a single `JpaRepository.save` and needed no
service layer. The category card turned this into the project convention, and every
later backend card was planned against it. The application has a single user.

## Decision
- Packages are organised by feature (`category`, `expense`, `budgetlimit`,
  `quicktemplate`, `report`), plus cross-cutting `web`, `time` and `db`.
- Controllers call Spring Data repositories directly and write with `saveAndFlush`,
  whose inherited methods are already transactional. There is no service layer and no
  `@Transactional` in main code.
- Open Session in View stays at Boot's default (on), but nothing relies on it:
  - related entities are loaded with `@EntityGraph` or an explicit lookup (no N+1, no lazy
    loads during serialisation);
  - responses are built from the instance the controller holds, not from the return
    value of `saveAndFlush`;
  - every check that can throw runs before the first setter, so no half-modified managed
    entity can be flushed.
- Entity-to-response mapping uses static `from(...)` factories.
- Logic shared by several controllers becomes a small `@Component`, extracted when the
  third caller appears (`ActiveCategoryLookup`, the first `@Component`).
- Upserts are find-then-save, with the unique constraint as the backstop that yields 409.
- Races between separate statements are accepted for a single-user application.

## Alternatives considered
- A native `INSERT ... ON CONFLICT` upsert in a `@Modifying @Transactional` repository
  method: race-free, but it would be the first native SQL and the first `@Transactional`
  in the codebase; rejected for budget limits.
- `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` so list items and totals
  share a snapshot: not adopted.
- A `FOR SHARE` lock on the category row against a concurrent archive: not adopted.
- MapStruct: not justified for a few fields per response.
- Extracting the shared active-category check at the second caller: deferred to the
  third caller.
- Turning OSIV off: a separate project-wide decision, not made.

## Consequences
- A category archived between the check and the insert can still receive an expense.
- Totals and page items are separate statements and can disagree for one response.
- Two quick clicks create two records; the frontend prevents this by serialising
  mutations (ADR 0018).
- New controllers follow the same rules (entity graphs, checks before setters, response
  from the held instance), so turning OSIV off later should not break them.
- Read-only reporting SQL uses a `@Repository` with `JdbcClient` (ADR 0017), not a
  service.
