import { request } from './client.ts'
import type { ExpenseCategory } from './expenses.ts'

// Money and share are strings, rendered as sent (AC5). limit and remaining are null when the
// category has no limit in that month. Rows carry no limit id (see budgetLimits.ts).
export type CategoryReportRow = {
  category: ExpenseCategory // the backend's CategorySummary {id, name, icon}
  amount: string
  share: string
  limit: string | null
  remaining: string | null
}
export type CategoryReport = { month: string; totalAmount: string; rows: CategoryReportRow[] }

export function getCategoryReport(month: string, signal?: AbortSignal): Promise<CategoryReport> {
  return request<CategoryReport>('GET', `/api/reports/by-category?${new URLSearchParams({ month }).toString()}`, {
    signal,
  })
}
