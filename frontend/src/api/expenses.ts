import { request } from './client.ts'

// money is always a string
export type ExpenseCategory = { id: number; name: string; icon: string | null }

export type Expense = {
  id: number
  amount: string
  currency: string
  spentOn: string
  note: string | null
  createdAt: string
  category: ExpenseCategory
}

export type ExpenseList = { items: Expense[]; page: number; size: number; totalItems: number; totalAmount: string }

export type ExpenseInput = { amount: string | null; categoryId: number | null; spentOn: string | null; note: string }

// Unset values are null / []: they are left out of the query, and the API applies its defaults
// (current month in APP_TIME_ZONE, all categories).
export type ExpenseFilters = { from: string | null; to: string | null; categoryIds: number[] }

// The one serializer for the API query and the page URL: from, to (yyyy-MM-dd),
// categoryIds comma-separated (URLSearchParams encodes "," as %2C; the servlet container decodes it).
export function expenseFilterParams(filters: ExpenseFilters): URLSearchParams {
  const query = new URLSearchParams()
  if (filters.from !== null) {
    query.append('from', filters.from)
  }
  if (filters.to !== null) {
    query.append('to', filters.to)
  }
  if (filters.categoryIds.length > 0) {
    query.append('categoryIds', filters.categoryIds.join(','))
  }
  return query
}

export function listExpenses(
  filters: ExpenseFilters,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<ExpenseList> {
  const query = expenseFilterParams(filters)
  query.append('page', String(page))
  query.append('size', String(size))
  return request<ExpenseList>('GET', `/api/expenses?${query.toString()}`, { signal })
}

export function createExpense(input: ExpenseInput): Promise<Expense> {
  return request<Expense>('POST', '/api/expenses', { body: input })
}

export function updateExpense(id: number, input: ExpenseInput): Promise<Expense> {
  return request<Expense>('PUT', `/api/expenses/${id}`, { body: input })
}

export function deleteExpense(id: number): Promise<void> {
  return request<void>('DELETE', `/api/expenses/${id}`)
}
