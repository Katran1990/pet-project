# Plan: Add endpoint to create a greeting

Trello card: https://trello.com/c/fRWVlYBW/1-add-endpoint-to-create-a-greeting

## Goal
Add `POST /api/greetings`. It validates `{"message": "..."}`, saves a new `Greeting` and returns 201 with the created resource. It reuses the existing `Greeting` entity, `GreetingRepository` and `GreetingController`.

## Acceptance criteria
- [ ] POST /api/greetings accepts {"message": "..."} and returns 201
- [ ] Empty or blank message returns 400
- [ ] Message longer than 200 chars returns 400
- [ ] Covered by integration tests

## Changes

### Existing state (for context)
- `GreetingController` is mapped at `/api` and has `GET /greeting`, which returns the first greeting or throws `ResponseStatusException(404)`.
- `Greeting` is a JPA entity: `id` is an IDENTITY `Long`, `message` is a `String` and has a public constructor `Greeting(String message)`.
- `GreetingResponse` is `record GreetingResponse(String message)`.
- `V1__create_greetings_table.sql` creates `greetings(id bigint identity, message varchar(255) not null)` and seeds `'Hello from Postgres'`.
- `spring-boot-starter-validation` is already a dependency.
- The only error-handling convention is `ResponseStatusException` plus Spring Boot's default error body. There is no `@ControllerAdvice` and ProblemDetail is not enabled.

### 1. New file: `backend/src/main/java/dev/katran/pet/greeting/CreateGreetingRequest.java`
This is the request DTO, a record in the same package, following the `GreetingResponse` style:
```java
public record CreateGreetingRequest(
        @NotBlank @Size(max = 200) String message) {
}
```
- `@NotBlank` rejects a missing field, `null`, `""` and whitespace-only strings.
- `@Size(max = 200)` rejects anything longer than 200 characters.
- The message is stored exactly as sent, with no trimming.

### 2. Change: `backend/src/main/java/dev/katran/pet/greeting/GreetingResponse.java`
Change it to `record GreetingResponse(Long id, String message)` so the 201 body can include the new id.
- This adds a field to the JSON of `GET /api/greeting`. The frontend (`frontend/src/App.tsx`) reads only `message`, so it is not affected.
- Update the existing `GET` mapping to `new GreetingResponse(greeting.getId(), greeting.getMessage())`.

### 3. Change: `backend/src/main/java/dev/katran/pet/greeting/GreetingController.java`
Add a method. The class mapping stays `/api`, and the existing `GET /api/greeting` is not changed.
```java
@PostMapping("/greetings")
public ResponseEntity<GreetingResponse> create(@Valid @RequestBody CreateGreetingRequest request) {
    Greeting saved = greetings.save(new Greeting(request.message()));
    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location)
            .body(new GreetingResponse(saved.getId(), saved.getMessage()));
}
```
- The code is plain blocking code, which fits virtual threads. `JpaRepository.save` is already transactional, so no service layer or `@Transactional` is needed for one save.
- **Response:** `201 Created` with body `{"id": <long>, "message": "<string>"}` and header `Location: http://<host>/api/greetings/{id}`.
- **Location decision:** include it, because that is the standard for 201. `GET /api/greetings/{id}` does not exist yet, so the header points at a future resource (see Risks).

### 4. Validation and the 400 response body
- Validation failures (`MethodArgumentNotValidException`) and malformed or missing JSON (`HttpMessageNotReadableException`) are turned into **400** by Spring MVC with no extra code.
- **Body format:** keep Spring Boot's default error body (`timestamp`, `status`, `error`, `path`). The existing 404 from `GET /api/greeting` already returns that body. No `@ControllerAdvice` and no config change, which is the smallest change consistent with what exists.
- The default body contains no field-level details. If you want them, see open question 2.

### 5. Database / Flyway
**No migration.** `message varchar(255)` already fits any value that passes the `@Size(max = 200)` check. Next free version if one is ever needed: `V2__...`.

### 6. Change: `README.md`
Add a row to the API table: `| POST | /api/greetings | Creates a greeting ({"message": "..."}, 1-200 chars) |`.

### 7. Frontend
No changes.

## Tests
All tests go in the existing integration test class `backend/src/test/java/dev/katran/pet/greeting/GreetingControllerIT.java`. It uses `@SpringBootTest(RANDOM_PORT)`, `@Import(TestcontainersConfiguration.class)` (Postgres 17) and `RestTestClient`, the same as the existing test.

Setup changes:
- Move `RestTestClient` creation into a `@BeforeEach` field.
- `@Autowired GreetingRepository greetings` to check what is stored in the database.

| # | Test | Criterion |
|---|------|-----------|
| 1 | `createsGreetingAndReturns201`: POST `{"message":"Hi there"}` returns 201. The body has a non-null `id` and `message == "Hi there"`. The `Location` header ends with `/api/greetings/{id}`. `greetings.findById(id)` returns the row with that message. | AC1 |
| 2 | `acceptsMessageOfExactly200Chars`: `"a".repeat(200)` returns 201. This is the boundary case. | AC1, AC3 |
| 3 | `rejectsBlankMessage` (`@ParameterizedTest`): `""`, `"   "`, `"\t\n"` return 400. | AC2 |
| 4 | `rejectsMissingOrNullMessage`: `{}` and `{"message": null}` return 400. | AC2 |
| 5 | `rejectsMessageLongerThan200Chars`: `"a".repeat(201)` returns 400. | AC3 |
| 6 | `doesNotPersistInvalidGreeting`: `greetings.count()` is the same before and after a 400 request. This can be an extra assertion inside tests 3-5 instead of a separate test. | AC2, AC3 |
| 7 | Existing `returnsTheGreetingStoredByTheFlywayMigration`: assert only `$.message == "Hello from Postgres"` via `jsonPath`, or compare with `new GreetingResponse(1L, ...)`. It still passes after POST tests because GET returns the lowest id. | Regression |

AC4 is covered by tests 1-7. Done means `cd backend && ./gradlew test` is green.

Test notes:
- No test asserts a global row count; count checks are only before/after a single request.
- Test order does not matter.
- Build request bodies via Jackson serialization (a `Map` or `CreateGreetingRequest`), not string concatenation, so `"\t\n"` is sent as valid JSON and the 400 comes from validation, not a parse error. `{}` and malformed JSON are the only raw-string bodies.
- Add a case: malformed JSON (e.g. `{"message":`) returns 400.
- In 400 tests, additionally assert `$.status == 400` and `$.path == "/api/greetings"`.
- In test 7, assert only `$.message` via `jsonPath`; do not rely on the seed row having `id = 1`.

## Decisions (approved by the user)
- Keep the `Location` header (GET-by-id goes to a separate card).
- Keep Spring Boot's default error body for 400; no ProblemDetail, no `@RestControllerAdvice`.

## Risks and open questions
1. **Location header points at a missing endpoint.** No `GET /api/greetings/{id}` exists, so following the Location returns 404. Recommendation: keep the header and add GET-by-id in a separate card. The alternative is to leave the header out until that card. The plan assumes "keep".
2. **400 body format.** The plan keeps Spring Boot's default error body, with no field-level details. If the API should explain what is wrong (for example `message: must not be blank`), there are two options:
   - `spring.mvc.problemdetails.enabled=true` gives RFC 9457 `application/problem+json`. It also changes the body of the existing 404.
   - A `@RestControllerAdvice` with its own error shape.

   The plan assumes the default for now.
3. **Response shape.** The plan adds `id` to the shared `GreetingResponse`, which also changes the GET JSON by one extra field. The alternative is to return `{"message"}` only, with the id carried only in `Location`.
4. **Plural vs singular paths.** The new endpoint is `/api/greetings`; the existing one is `/api/greeting`. Kept as written on the card. Renaming the GET is not part of this card.
5. **What counts as "200 chars".** `@Size` counts UTF-16 code units, so 200 emoji (400 code units) would be rejected. Postgres `varchar` counts code points, so every accepted value fits the column.
6. **No database-level limit.** The column stays `varchar(255)`, so the 200 limit is checked only in the API. Tightening it with a `V2` migration is optional and not in this plan.

## Out of scope
- `GET /api/greetings` (list) and `GET /api/greetings/{id}`.
- Update and delete endpoints.
- Frontend form for creating greetings.
- A global error handler or ProblemDetail setup (unless decided in open question 2).
- Flyway migrations or changes to the database schema.
- Authentication, rate limiting, trimming or deduplicating messages.
