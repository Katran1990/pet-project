import { request } from './client.ts'

export type Category = { id: number; name: string; icon: string | null; archived: boolean; createdAt: string }

export const listActiveCategories = (signal?: AbortSignal) => request<Category[]>('GET', '/api/categories', { signal })
