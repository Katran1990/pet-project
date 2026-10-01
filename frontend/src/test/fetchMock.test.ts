import { describe, expect, it } from 'vitest'
import { deferred, mockFetch, unexpectedRequests } from './fetchMock.ts'

describe('fetchMock', () => {
  it('S1: unexpectedRequestsAreRecorded', async () => {
    // Without mockFetch, setup.ts's beforeEach stub records the request and rejects.
    await expect(fetch('/api/x')).rejects.toThrow('Unexpected request: GET /api/x')
    expect(unexpectedRequests).toEqual(['GET /api/x'])

    // afterEach would fail the test if we left this populated, so empty it before moving on.
    unexpectedRequests.length = 0

    // With mockFetch({}), a POST to an unknown route is recorded the same way.
    mockFetch({})
    await expect(fetch('/api/unknown', { method: 'POST' })).rejects.toThrow('Unexpected request: POST /api/unknown')
    expect(unexpectedRequests).toEqual(['POST /api/unknown'])

    // Empty it again so this test's own afterEach check passes.
    unexpectedRequests.length = 0
  })

  it('S2: honoursAbortSignal', async () => {
    mockFetch({
      'GET /api/already-aborted': deferred().promise,
      'GET /api/pending': deferred().promise,
      // An Error reply: if the aborted check ran after building this reply's promise, that
      // promise would reject with nothing ever attached to observe it (an unhandled rejection).
      'GET /api/already-aborted-error-reply': new Error('must never be observed'),
    })

    // An already aborted signal rejects immediately with AbortError.
    const alreadyAborted = new AbortController()
    alreadyAborted.abort()
    const rejection: unknown = await fetch('/api/already-aborted', { signal: alreadyAborted.signal }).catch(
      (error: unknown) => error,
    )
    expect((rejection as { name?: unknown }).name).toBe('AbortError')

    // Same, but the route's reply is an Error: must still be AbortError, and must not leave an
    // unhandled rejection behind (decision 16, suggestion 6).
    const alreadyAbortedWithError = new AbortController()
    alreadyAbortedWithError.abort()
    const errorReplyRejection: unknown = await fetch('/api/already-aborted-error-reply', {
      signal: alreadyAbortedWithError.signal,
    }).catch((error: unknown) => error)
    expect((errorReplyRejection as { name?: unknown }).name).toBe('AbortError')

    // A pending request (deferred reply that never settles) rejects as soon as its controller aborts.
    const controller = new AbortController()
    const pending = fetch('/api/pending', { signal: controller.signal })
    controller.abort()
    const pendingRejection: unknown = await pending.catch((error: unknown) => error)
    expect((pendingRejection as { name?: unknown }).name).toBe('AbortError')
  })
})
