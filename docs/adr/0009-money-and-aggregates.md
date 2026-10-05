# 0009. Money as exact decimals, aggregates computed by the database

Date: 2026-09-25
Status: accepted

## Context
The Wallet feature stores expenses and budget limits and shows totals, shares and
remainders. `CLAUDE.md` requires money as `BigDecimal` in Java and as a string with two
decimals in JSON, never float or double. JavaScript parses JSON numbers as floats, so
`0.0` becomes `0` and large amounts lose digits. Totals must cover all matching rows, not
only the current page.

## Decision
- **Money in the backend:**
  - Java type `BigDecimal`; column `numeric(12, 2)` with a `> 0` check.
  - Requests accept a JSON string or a JSON number; Jackson builds the `BigDecimal` from
    the literal text.
  - Validation: `@NotNull @Positive @Digits(integer = 10, fraction = 2)
    @DecimalMax("9999999999.99")`. The literal scale counts, so `"200.500"` is rejected.
  - Responses use `@JsonFormat(shape = STRING)` and `setScale(2, RoundingMode.UNNECESSARY)`,
    so the output is always like `"200.00"`.
  - Percentages (`share`) are strings with one decimal (`"64.8"`, `"0.0"`).
- **Currency:** single currency `PLN`. It is a database default (`char(3)`), returned in
  responses and not accepted in requests.
- **Aggregates are computed by Postgres:**
  - the expense list runs one JPQL aggregate (`count`, `coalesce(sum)`) over the same
    filter as the page query;
  - the monthly report is one SQL statement with a window total (ADR 0017);
  - Java only maps values.
- **Clients never compute money:** the frontend types money as `string`, renders it
  exactly as sent, sends the user's input unchanged, and never uses `Number`,
  `parseFloat`, `toFixed` or `Intl` on it. Totals, shares and remainders come only from
  the server. Test fixtures pass totals explicitly instead of deriving them.

## Alternatives considered
- Accepting amounts only as JSON strings: needs a custom deserializer (about 20 lines plus
  tests) for little gain.
- `stripTrailingZeros()` before validation, so `"200.500"` is accepted as `200.50`:
  rejected, more than two decimals means more than two decimals.
- `share` as a JSON number: JavaScript turns `0.0` into `0`, and it would be the only
  decimal in the API that is not a string.
- One page query with window functions (`count(*) over ()`): returns no totals for pages
  past the end.
- The largest-remainder method so that shares sum to exactly 100: more complex SQL; the
  requirement is "100 within rounding".
- `type="number"` inputs in the frontend: browsers clean up or localise the value.
- Multi-currency: out of scope project-wide.

## Consequences
- Every new money field copies the full constraint set, including `@DecimalMax`, which
  protects against the `@Digits` integer overflow on literals like `"1e2147483647"`.
- Shares are rounded one by one and may sum to 100 ± 0.05 per non-zero row.
- A comma decimal (`12,50`) is an unreadable body: 400 without `errors[]`.
- Grafana shows `numeric` as float64 with two decimals; the "money is a string" rule
  applies to the API, not to Grafana.
- Introducing a second currency would require grouping every aggregate by currency.
