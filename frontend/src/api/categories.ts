import { request } from './client.ts'

export type Category = { id: number; name: string; icon: string | null; archived: boolean; createdAt: string }

// All categories, archived ones included: the filter offers them (expense-list D2);
// the expense form uses the active subset.
export const listAllCategories = (signal?: AbortSignal) =>
  request<Category[]>('GET', '/api/categories?includeArchived=true', { signal })
