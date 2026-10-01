import { describe, expect, it } from 'vitest'
import { localIsoDate } from './localDate.ts'

describe('localIsoDate', () => {
  it('L1: proves tests run east of UTC, and formats the local date with zero padding', () => {
    const date = new Date(2026, 9, 1, 0, 30) // 2026-10-01 00:30 local (month is 0-based)
    // Sanity: in TZ=Europe/Warsaw this instant is still 2026-09-30 in UTC.
    expect(date.toISOString().startsWith('2026-09-30')).toBe(true)

    expect(localIsoDate(date)).toBe('2026-10-01')

    // Zero padding for single-digit month/day.
    expect(localIsoDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05')
  })
})
