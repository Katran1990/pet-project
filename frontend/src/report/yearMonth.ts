import { localIsoDate } from '../expense/localDate.ts'

// The browser's local month as YYYY-MM (reuses localIsoDate; never toISOString(), the UTC date).
export function currentYearMonth(now: Date): string {
  return localIsoDate(now).slice(0, 7)
}

// Moves a YYYY-MM month by delta calendar months. Integer arithmetic on year*12 + month - 1, no Date:
// no time-zone or DST effects. Year padded to 4 digits, month to 2.
export function shiftMonth(month: string, delta: number): string {
  const [year, monthNumber] = month.split('-').map(Number)
  const index = year * 12 + (monthNumber - 1) + delta
  const newYear = Math.floor(index / 12)
  const newMonth = (index % 12) + 1
  return `${String(newYear).padStart(4, '0')}-${String(newMonth).padStart(2, '0')}`
}
