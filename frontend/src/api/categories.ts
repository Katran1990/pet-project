import { request } from './client.ts'

export type Category = { id: number; name: string; icon: string | null; archived: boolean; createdAt: string }

// All categories, archived ones included: the expense filter and the Categories page show them;
// the expense form uses the active subset.
export const listAllCategories = (signal?: AbortSignal) =>
  request<Category[]>('GET', '/api/categories?includeArchived=true', { signal })

// POST body. Both values are sent as typed: the server strips the name and stores an empty icon as null.
export type CategoryInput = { name: string; icon: string }

// PATCH body. An omitted key leaves that field unchanged; icon "" clears the icon (CLAUDE.md).
export type CategoryPatch = { name?: string; icon?: string; archived?: boolean }

export const createCategory = (input: CategoryInput) =>
  request<Category>('POST', '/api/categories', { body: input })

export const updateCategory = (id: number, patch: CategoryPatch) =>
  request<Category>('PATCH', `/api/categories/${id}`, { body: patch })
