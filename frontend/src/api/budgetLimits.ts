import { request } from './client.ts'
import type { ExpenseCategory } from './expenses.ts'

export type BudgetLimit = { id: number; month: string; amount: string; category: ExpenseCategory }
export type BudgetLimitInput = { categoryId: number; month: string; amount: string | null }

export const listBudgetLimits = (month: string, signal?: AbortSignal) =>
  request<BudgetLimit[]>('GET', `/api/budget-limits?${new URLSearchParams({ month }).toString()}`, { signal })
// Upsert: creates or updates the limit of (categoryId, month).
export const setBudgetLimit = (input: BudgetLimitInput) =>
  request<BudgetLimit>('PUT', '/api/budget-limits', { body: input })
export const deleteBudgetLimit = (id: number) => request<void>('DELETE', `/api/budget-limits/${id}`)
