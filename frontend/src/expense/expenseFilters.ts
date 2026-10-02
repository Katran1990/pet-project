import { expenseFilterParams } from '../api/expenses.ts'
import type { ExpenseFilters } from '../api/expenses.ts'

export const NO_FILTERS: ExpenseFilters = { from: null, to: null, categoryIds: [] }

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

// Keeps a value only when it has valid syntax and is a real calendar date. Both sides use UTC,
// so there is no time-zone effect.
function parseDate(value: string | null): string | null {
  if (value === null || !DATE_PATTERN.test(value)) {
    return null
  }
  const date = new Date(`${value}T00:00:00Z`)
  if (Number.isNaN(date.getTime())) {
    return null
  }
  return date.toISOString().slice(0, 10) === value ? value : null
}

const CATEGORY_ID_PATTERN = /^[1-9]\d{0,15}$/

function parseCategoryIds(value: string | null): number[] {
  const ids = new Set<number>()
  for (const entry of (value ?? '').split(',')) {
    if (CATEGORY_ID_PATTERN.test(entry) && Number.isSafeInteger(Number(entry))) {
      ids.add(Number(entry))
    }
  }
  return [...ids].sort((a, b) => a - b)
}

// These rules clean up the URL; they do not validate business rules (decision 8). `from` after
// `to` is kept: the server reports it. Every other param (page, size, unknown ones) is ignored.
export function parseFilters(search: string): ExpenseFilters {
  const params = new URLSearchParams(search)
  return {
    from: parseDate(params.get('from')),
    to: parseDate(params.get('to')),
    categoryIds: parseCategoryIds(params.get('categoryIds')),
  }
}

export function filtersToSearch(filters: ExpenseFilters): string {
  const query = expenseFilterParams(filters).toString()
  return query === '' ? '' : `?${query}`
}

export function withCategory(filters: ExpenseFilters, id: number, selected: boolean): ExpenseFilters {
  const categoryIds = selected
    ? [...new Set([...filters.categoryIds, id])].sort((a, b) => a - b)
    : filters.categoryIds.filter((categoryId) => categoryId !== id)
  return { ...filters, categoryIds }
}

export function hasDateFilter(filters: ExpenseFilters): boolean {
  return filters.from !== null || filters.to !== null
}

export function hasAnyFilter(filters: ExpenseFilters): boolean {
  return hasDateFilter(filters) || filters.categoryIds.length > 0
}
