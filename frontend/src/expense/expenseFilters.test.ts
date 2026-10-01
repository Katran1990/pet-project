import { describe, expect, it } from 'vitest'
import type { ExpenseFilters } from '../api/expenses.ts'
import { NO_FILTERS, filtersToSearch, hasAnyFilter, hasDateFilter, parseFilters, withCategory } from './expenseFilters.ts'

describe('expenseFilters', () => {
  it('U1: parsesValidParams', () => {
    expect(parseFilters('?from=2026-09-01&to=2026-09-30&categoryIds=2,1')).toEqual({
      from: '2026-09-01',
      to: '2026-09-30',
      categoryIds: [1, 2],
    })

    expect(parseFilters('?categoryIds=1%2C2')).toEqual(parseFilters('?categoryIds=1,2'))

    expect(parseFilters('')).toEqual(NO_FILTERS)

    // A leap day in a leap year is a real calendar date and is kept.
    expect(parseFilters('?from=2028-02-29').from).toBe('2028-02-29')
  })

  it('U2: dropsInvalidParams', () => {
    const invalidDates = [
      '2026-02-30', // day does not exist
      '2026-02-29', // 2026 is not a leap year
      '2026-13-01', // month out of range
      '2026-9-1', // not zero-padded
      '20260901', // no separators
      '2026-09-01T00:00', // extra characters
      '%202026-09-01', // decodes to a leading space
      '', // from=, an empty value
      'abc', // not a date at all
    ]
    for (const value of invalidDates) {
      expect(parseFilters(`?from=${value}`).from).toBeNull()
      expect(parseFilters(`?to=${value}`).to).toBeNull()
    }

    // Repeated from: the first value wins (URLSearchParams.get semantics).
    expect(parseFilters('?from=2026-09-01&from=2026-10-01').from).toBe('2026-09-01')

    // categoryIds: every kind of invalid entry is dropped one by one.
    // 'x' (not numeric), '' (empty entry), '0' and '-1' (outside 1..), '1.5' (not an integer),
    // '01' (leading zero), '%2B1' (a literal "+1"), '+1' (decodes to " 1"),
    // '9007199254740993' (valid syntax but not a safe integer).
    expect(parseFilters('?categoryIds=x,,0,-1,1.5,01,%2B1,+1,9007199254740993,2,1,2').categoryIds).toEqual([1, 2])
    // The plan's own worked example.
    expect(parseFilters('?categoryIds=2,x,,0,2,1').categoryIds).toEqual([1, 2])
    expect(parseFilters('?categoryIds=').categoryIds).toEqual([])

    // from after to is kept: the server reports it (decision 8), this is not validation here.
    expect(parseFilters('?from=2026-09-30&to=2026-09-01')).toEqual({
      from: '2026-09-30',
      to: '2026-09-01',
      categoryIds: [],
    })

    // page, size and unknown params are ignored.
    expect(parseFilters('?page=3&size=500&foo=bar')).toEqual(NO_FILTERS)
  })

  it('U3: serialisesInApiFormat', () => {
    expect(filtersToSearch(NO_FILTERS)).toBe('')

    const full: ExpenseFilters = { from: '2026-09-01', to: '2026-09-30', categoryIds: [1, 2] }
    expect(filtersToSearch(full)).toBe('?from=2026-09-01&to=2026-09-30&categoryIds=1%2C2')

    expect(filtersToSearch({ from: null, to: null, categoryIds: [2] })).toBe('?categoryIds=2')

    const samples: ExpenseFilters[] = [
      NO_FILTERS,
      full,
      { from: '2026-01-01', to: null, categoryIds: [] },
      { from: null, to: '2026-12-31', categoryIds: [1, 5, 9] },
    ]
    for (const sample of samples) {
      expect(parseFilters(filtersToSearch(sample))).toEqual(sample)
    }
  })

  it('U4: withCategoryAndPredicates', () => {
    const base: ExpenseFilters = { from: null, to: null, categoryIds: [1, 3] }

    const added = withCategory(base, 2, true)
    expect(added.categoryIds).toEqual([1, 2, 3])
    expect(base.categoryIds).toEqual([1, 3]) // withCategory never mutates its input

    const removed = withCategory(base, 1, false)
    expect(removed.categoryIds).toEqual([3])
    expect(base.categoryIds).toEqual([1, 3])

    const duplicate = withCategory(base, 1, true)
    expect(duplicate.categoryIds).toEqual([1, 3])

    expect(hasDateFilter(NO_FILTERS)).toBe(false)
    expect(hasDateFilter({ ...NO_FILTERS, from: '2026-09-01' })).toBe(true)
    expect(hasDateFilter({ ...NO_FILTERS, to: '2026-09-30' })).toBe(true)
    expect(hasDateFilter({ ...NO_FILTERS, categoryIds: [1] })).toBe(false)

    expect(hasAnyFilter(NO_FILTERS)).toBe(false)
    expect(hasAnyFilter({ ...NO_FILTERS, categoryIds: [1] })).toBe(true)
    expect(hasAnyFilter({ ...NO_FILTERS, from: '2026-09-01' })).toBe(true)
    expect(hasAnyFilter({ ...NO_FILTERS, to: '2026-09-30' })).toBe(true)
  })
})
