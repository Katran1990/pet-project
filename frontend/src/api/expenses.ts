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

export function listExpenses(page: number, size: number, signal?: AbortSignal): Promise<ExpenseList> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
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
