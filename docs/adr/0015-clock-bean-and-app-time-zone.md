# 0015. "Today" and "current month" from a Clock bean in APP_TIME_ZONE

Date: 2026-09-30
Status: accepted

## Context
"spentOn must not be in the future", "the expense list defaults to the current month" and
"apply a template dated today" all depend on a calendar date. The JVM default zone and
UTC give the wrong answer around midnight in Poland, and tests must be able to fix
"today".

## Decision
- Property `app.time-zone=${APP_TIME_ZONE:Europe/Warsaw}`. `ClockConfiguration` (package
  `dev.katran.pet.time`) provides `Clock.system(zone)`; an invalid zone fails startup.
- The same clock is given to Bean Validation through `ValidationConfigurationCustomizer`,
  so `@PastOrPresent` uses the configured zone.
- All server-side "today" and "current month" logic uses this bean (`LocalDate.now(clock)`,
  `YearMonth.now(clock)`).
- Tests replace the bean with `@TestBean` and fixed instants where Warsaw and UTC
  disagree.
- **Months:**
  - the API uses strict `YYYY-MM` (`YearMonth`, `@JsonFormat(pattern = "uuuu-MM")` in
    bodies, Spring's `YearMonthFormatter` in queries);
  - the database stores the first day of the month as a `date`, enforced by a check;
  - `GET /api/reports/by-category` requires an explicit month, while `GET /api/expenses`
    defaults to the server's current month.
- The frontend uses the browser's local date for default inputs (`localIsoDate`, never
  `toISOString`) and relies on server defaults where they exist.
- `createdAt` is set by Hibernate `@CreationTimestamp` from JVM time, not from the clock.

## Alternatives considered
- `TZ` or `-Duser.timezone`: changes the zone for everything, logs included, and tests
  could not swap it.
- A `@ConfigurationProperties` record for the zone: one scalar with no validation beyond
  parsing; `@Value` chosen.
- A `@Primary` fixed clock in `TestcontainersConfiguration`: would affect every test and
  `bootTestRun`.
- `YearMonth` in entities or an `AttributeConverter`: `LocalDate` of the first day chosen.
- A string month with `@Pattern` for field-level errors in request bodies: not chosen.
- Defaulting the report month to the current month: not chosen; the month is required.
- An endpoint exposing the server's zone or month to the client: not added.

## Consequences
- Near midnight the browser and the server can disagree about the date. The user then
  sees a 400 next to the date field, or a saved expense outside the shown month.
- Prod uses the default zone because the chart does not set `APP_TIME_ZONE`. Changing it
  also requires updating the Grafana Wallet `month` variable query (its list and default),
  which hard-codes `Europe/Warsaw`; the panel SQL receives an explicit date (ADR 0020).
- Each test class with its own fixed clock gets its own Spring context (ADR 0002).
- CI must not set `APP_TIME_ZONE`, or `ClockConfigurationTest` fails.
- Years outside Postgres' date range parse in Java but fail in SQL with a generic 500.
