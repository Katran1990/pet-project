import { request } from './client.ts'
import type { Expense } from './expenses.ts'

export type QuickTemplateCategory = { id: number; name: string; icon: string | null; archived: boolean }
export type QuickTemplate = { id: number; name: string; amount: string; sortOrder: number; category: QuickTemplateCategory }

export const listQuickTemplates = (signal?: AbortSignal) =>
  request<QuickTemplate[]>('GET', '/api/quick-templates', { signal })

// No body: the template's amount and category are used (overrides are out of scope).
export const applyQuickTemplate = (id: number) => request<Expense>('POST', `/api/quick-templates/${id}/apply`)
