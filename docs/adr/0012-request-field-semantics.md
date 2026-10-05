# 0012. Semantics of empty, null and numeric request fields

Date: 2026-09-25
Status: accepted

## Context
The category card's PATCH test showed that an absent `icon` wiped the stored icon:
Jackson 3.1.5 deserializes an absent `Optional` record component as `Optional.empty()`, so
"absent" and "null" could not be told apart. Later cards added PUT (full replacement),
numeric fields and integer ids, where Jackson silently truncated `7.9` to `7`.

## Decision
- **Optional strings** (rule in `CLAUDE.md`):
  - `""` is normalised to `null` in POST and PATCH;
  - in PATCH, an omitted or `null` field means "leave unchanged", and `""` means "clear";
  - PATCH request records use plain `String` fields, not `Optional`.
- **PUT is a full replacement:** an omitted, `null` or `""` optional string stores `null`.
- **Strings are not trimmed in general.** Only required names are `strip()`-ped, and all
  normalisation happens in the record's compact constructor, before validation. A compact
  constructor must never throw.
- A required string sent in PATCH cannot be cleared: `""` or blank gives 400.
- **Numeric fields keep Jackson's default:** `""` is read as `null`. So POST reports
  `must not be null`, PATCH leaves the field unchanged, and an apply override is not
  applied.
- **Integer fields reject every floating-point token** (`1.5`, `7.0`, `1e1`) with 400 via
  `spring.jackson.deserialization.accept-float-as-int=false`, set project-wide on
  2026-10-01 (quick-templates card).
- Unknown JSON properties are ignored (for example `currency`).

## Alternatives considered
- `Optional<String>` to distinguish absent from null in PATCH: failed with Jackson 3.1.5.
- Rejecting `""` in numeric fields through Jackson configuration: dropped. It would lose
  the field-level `errors[]` and needs code.
- A `JsonMapperBuilderCustomizer` for the float rule: only a fallback if the Boot property
  did not bind.
- Rejecting unknown properties with 400: not chosen.
- Collapsing internal whitespace or rejecting control characters in names: not chosen.

## Consequences
- The frontend sends optional strings as typed, including `""`, and lets the backend
  normalise them.
- Whitespace-only optional strings (icon, note) are stored as sent.
- A client that sends an id as `7.0` gets a 400 without `errors[]`.
- New request records follow the same split: separate Create/Update records when PATCH
  semantics differ, one record when PUT is a full replacement.
