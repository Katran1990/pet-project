import { describe, expect, it } from 'vitest'
import { currentYearMonth, shiftMonth } from './yearMonth.ts'

describe('yearMonth', () => {
  it('Y1: currentYearMonthUsesLocalDate', () => {
    const date = new Date(2026, 9, 1, 0, 30)
    // Sanity: the UTC date is still September, so toISOString() would give the wrong month.
    expect(date.toISOString().startsWith('2026-09-30')).toBe(true)
    expect(currentYearMonth(date)).toBe('2026-10')
    expect(currentYearMonth(new Date(2026, 0, 5))).toBe('2026-01')
  })

  it.each([
    ['2026-10', -1, '2026-09'],
    ['2026-10', 1, '2026-11'],
    ['2026-09', 1, '2026-10'],
    ['2026-01', -1, '2025-12'],
    ['2026-12', 1, '2027-01'],
  ])('Y2: shiftMonthMovesOneCalendarMonth %s %i -> %s', (month, delta, expected) => {
    expect(shiftMonth(month, delta)).toBe(expected)
  })
})
