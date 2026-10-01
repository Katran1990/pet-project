import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, expect, vi } from 'vitest'
import { rejectUnexpected, unexpectedRequests } from './fetchMock.ts'

// Decision 20 by construction: a test that never calls mockFetch gets this stub, never Node's real fetch.
beforeEach(() => {
  unexpectedRequests.length = 0
  vi.stubGlobal('fetch', vi.fn(rejectUnexpected))
})

// Vitest runs without `globals`, so Testing Library cannot register its automatic cleanup.
afterEach(() => {
  cleanup()
  vi.useRealTimers() // E1 fakes Date
  expect(unexpectedRequests, 'requests without a mockFetch route').toEqual([])
})
