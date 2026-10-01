// The browser's local calendar date as yyyy-MM-dd. Not toISOString(): that is the UTC date,
// which is still "yesterday" in Warsaw between 00:00 and 01:00/02:00 local time.
export function localIsoDate(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
