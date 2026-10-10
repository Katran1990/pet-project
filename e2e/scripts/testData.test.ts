// Unit tests of the pure helpers in tests/support/testData.ts (no browser, no network).
// Kept in scripts/ because Playwright would pick up *.test.ts files under tests/ as specs.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { APIRequestContext } from '@playwright/test'
import { cleanUp, runId, uniqueName } from '../tests/support/testData.ts'

function withRunId<T>(id: string | undefined, fn: () => T): T {
  const previous = process.env.E2E_RUN_ID
  if (id === undefined) delete process.env.E2E_RUN_ID
  else process.env.E2E_RUN_ID = id
  try {
    return fn()
  } finally {
    if (previous === undefined) delete process.env.E2E_RUN_ID
    else process.env.E2E_RUN_ID = previous
  }
}

test('uniqueName is e2e-<runId>-<6 hex> and differs per call', () => {
  withRunId('gh-123456-1', () => {
    const a = uniqueName()
    const b = uniqueName()
    assert.match(a, /^e2e-gh-123456-1-[0-9a-f]{6}$/)
    assert.notEqual(a, b)
  })
})

test('uniqueName stays within 64 characters for the longest allowed run id', () => {
  const longest = 'a'.repeat(32)
  withRunId(longest, () => {
    const name = uniqueName()
    assert.ok(name.length <= 64)
    assert.equal(name.length, 43)
    assert.ok(name.startsWith('e2e-'))
  })
})

test('uniqueName rejects names over 64 characters', () => {
  withRunId('x'.repeat(60), () => assert.throws(() => uniqueName(), /invalid e2e name/))
})

test('runId requires E2E_RUN_ID', () => {
  withRunId(undefined, () => assert.throws(() => runId(), /E2E_RUN_ID/))
})

interface Call {
  method: string
  url: string
  data?: unknown
}

function fakeRequest(routes: Record<string, unknown>, statuses: Record<string, number> = {}): { request: APIRequestContext; calls: Call[] } {
  const calls: Call[] = []
  const respond = (method: string, url: string, data?: unknown) => {
    calls.push({ method, url, data })
    const body = routes[`${method} ${url}`]
    const status = statuses[`${method} ${url}`] ?? (body !== undefined ? 200 : 404)
    return { ok: () => status < 400, status: () => status, json: async () => body }
  }
  const request = {
    get: async (url: string) => respond('GET', url),
    delete: async (url: string) => respond('DELETE', url),
    patch: async (url: string, options: { data: unknown }) => respond('PATCH', url, options.data),
  } as unknown as APIRequestContext
  return { request, calls }
}

test('cleanUp refuses names without the e2e- prefix and makes no request', async () => {
  const { request, calls } = fakeRequest({})
  await assert.rejects(cleanUp(request, 'Groceries'), /refusing to clean up/)
  await assert.rejects(cleanUp(request, ''), /refusing to clean up/)
  await assert.rejects(cleanUp(request, 'E2E-upper'), /refusing to clean up/)
  assert.equal(calls.length, 0)
})

test('cleanUp does nothing when the category does not exist', async () => {
  const { request, calls } = fakeRequest({
    'GET /api/categories?includeArchived=true': [{ id: 1, name: 'Groceries', archived: false }],
  })
  await cleanUp(request, 'e2e-x-abcdef')
  assert.deepEqual(calls.map((c) => c.method), ['GET'])
})

test('cleanUp deletes only expenses whose note equals the name, then archives the category', async () => {
  const name = 'e2e-x-abcdef'
  const { request, calls } = fakeRequest({
    'GET /api/categories?includeArchived=true': [
      { id: 1, name: 'Groceries', archived: false },
      { id: 7, name, archived: false },
    ],
    'GET /api/expenses?categoryIds=7&from=2000-01-01&to=2100-12-31&size=200': {
      items: [
        { id: 10, note: name },
        { id: 11, note: 'other' },
        { id: 12, note: null },
      ],
    },
  })
  await cleanUp(request, name)
  const deletes = calls.filter((c) => c.method === 'DELETE').map((c) => c.url)
  assert.deepEqual(deletes, ['/api/expenses/10'])
  const patch = calls.filter((c) => c.method === 'PATCH')
  assert.deepEqual(patch, [{ method: 'PATCH', url: '/api/categories/7', data: { archived: true } }])
})

test('cleanUp does not patch an already archived category', async () => {
  const name = 'e2e-x-abcdef'
  const { request, calls } = fakeRequest({
    'GET /api/categories?includeArchived=true': [{ id: 7, name, archived: true }],
    'GET /api/expenses?categoryIds=7&from=2000-01-01&to=2100-12-31&size=200': { items: [] },
  })
  await cleanUp(request, name)
  assert.equal(calls.some((c) => c.method === 'PATCH' || c.method === 'DELETE'), false)
})

test('cleanUp warns about unexpected statuses but stays silent on a 404 for DELETE', async () => {
  const name = 'e2e-x-abcdef'
  const { request } = fakeRequest(
    {
      'GET /api/categories?includeArchived=true': [{ id: 7, name, archived: false }],
      'GET /api/expenses?categoryIds=7&from=2000-01-01&to=2100-12-31&size=200': {
        items: [
          { id: 10, note: name },
          { id: 11, note: name },
        ],
      },
    },
    { 'DELETE /api/expenses/10': 404, 'DELETE /api/expenses/11': 500, 'PATCH /api/categories/7': 503 },
  )
  const warnings: string[] = []
  const original = console.warn
  console.warn = (message: string) => void warnings.push(message)
  try {
    await cleanUp(request, name)
  } finally {
    console.warn = original
  }
  assert.deepEqual(warnings, [
    'e2e cleanUp: DELETE /api/expenses/11 returned 500',
    'e2e cleanUp: PATCH /api/categories/7 returned 503',
  ])
})
