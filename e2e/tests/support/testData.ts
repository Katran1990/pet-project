import { randomBytes } from 'node:crypto'
import { test as base, type APIRequestContext, type APIResponse } from '@playwright/test'

export function runId(): string {
  const id = process.env.E2E_RUN_ID
  if (!id) throw new Error('E2E_RUN_ID is not set (playwright.config.ts sets it)')
  return id
}

// Name of the category and note of the expense: e2e-<runId>-<6 hex>. A fresh nonce per test
// attempt means a local re-run with the same E2E_RUN_ID cannot hit the 409 "duplicate name".
export function uniqueName(): string {
  const name = `e2e-${runId()}-${randomBytes(3).toString('hex')}`
  if (!name.startsWith('e2e-') || name.length > 64) {
    throw new Error(`invalid e2e name: ${name}`)
  }
  return name
}

interface CategoryDto {
  id: number
  name: string
  archived: boolean
}

interface ExpenseDto {
  id: number
  note: string | null
}

function warnIfUnexpected(method: string, path: string, response: APIResponse, tolerated: number[] = []): void {
  if (response.ok() || tolerated.includes(response.status())) return
  console.warn(`e2e cleanUp: ${method} ${path} returned ${response.status()}`)
}

// Safety net for the UI cleanup (D3): removes what a failed test left behind. Never touches
// anything whose name does not start with "e2e-".
export async function cleanUp(request: APIRequestContext, name: string): Promise<void> {
  if (!name.startsWith('e2e-')) throw new Error(`refusing to clean up "${name}"`)

  const categoriesPath = '/api/categories?includeArchived=true'
  const categoriesResponse = await request.get(categoriesPath)
  if (!categoriesResponse.ok()) {
    warnIfUnexpected('GET', categoriesPath, categoriesResponse)
    return
  }
  const categories = (await categoriesResponse.json()) as CategoryDto[]
  const category = categories.find((c) => c.name === name)
  if (!category) return

  const expensesPath = `/api/expenses?categoryIds=${category.id}&from=2000-01-01&to=2100-12-31&size=200`
  const expensesResponse = await request.get(expensesPath)
  if (!expensesResponse.ok()) warnIfUnexpected('GET', expensesPath, expensesResponse)
  else {
    const page = (await expensesResponse.json()) as { items: ExpenseDto[] }
    for (const expense of page.items.filter((e) => e.note === name)) {
      const path = `/api/expenses/${expense.id}`
      warnIfUnexpected('DELETE', path, await request.delete(path), [404]) // 404 is fine
    }
  }

  if (!category.archived) {
    const path = `/api/categories/${category.id}`
    warnIfUnexpected('PATCH', path, await request.patch(path, { data: { archived: true } }))
  }
}

export const test = base.extend<{ testName: string }>({
  testName: async ({ request }, use) => {
    const name = uniqueName()
    try {
      await use(name)
    } finally {
      await cleanUp(request, name)
    }
  },
})

export { expect } from '@playwright/test'
