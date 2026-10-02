import { vi } from 'vitest'

// Decision 20: requests that match no route (including when no mockFetch was set up at all)
// are recorded here, so setup.ts can fail the test loudly instead of letting them hang or
// silently fall through to a real fetch.
export const unexpectedRequests: string[] = []

function describeRequest(input: RequestInfo | URL, init?: RequestInit): { method: string; url: string } {
  let method = init?.method
  let url: string
  if (typeof input === 'string') {
    url = input
  } else if (input instanceof URL) {
    url = input.toString()
  } else {
    url = input.url
    method = method ?? input.method
  }
  return { method: (method ?? 'GET').toUpperCase(), url }
}

export function rejectUnexpected(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const { method, url } = describeRequest(input, init)
  const key = `${method} ${url}`
  unexpectedRequests.push(key)
  return Promise.reject(new Error(`Unexpected request: ${key}`))
}

// A reply is either a factory for a fresh Response (a body can only be read once), an Error
// to reject with, or a Promise<Response> for a request that is still in flight.
export type MockReply = (() => Response) | Error | Promise<Response>

export type MockRoutes = Record<string, MockReply | MockReply[]>

function abortError(): DOMException {
  return new DOMException('This operation was aborted', 'AbortError')
}

function toPromise(reply: MockReply): Promise<Response> {
  if (reply instanceof Error) {
    return Promise.reject(reply)
  }
  if (reply instanceof Promise) {
    return reply
  }
  return Promise.resolve(reply())
}

function honourSignal(promise: Promise<Response>, signal: AbortSignal | null | undefined): Promise<Response> {
  if (!signal) {
    return promise
  }
  if (signal.aborted) {
    return Promise.reject(abortError())
  }
  return new Promise<Response>((resolve, reject) => {
    const onAbort = () => reject(abortError())
    signal.addEventListener('abort', onAbort, { once: true })
    promise.then(
      (response) => {
        signal.removeEventListener('abort', onAbort)
        resolve(response)
      },
      (error: unknown) => {
        signal.removeEventListener('abort', onAbort)
        reject(error)
      },
    )
  })
}

export function mockFetch(routes: MockRoutes) {
  const queues = new Map<string, MockReply[]>(
    Object.entries(routes).map(([key, value]) => [key, Array.isArray(value) ? [...value] : [value]]),
  )

  const mock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const { method, url } = describeRequest(input, init)
    const key = `${method} ${url}`
    const queue = queues.get(key)
    if (!queue || queue.length === 0) {
      return rejectUnexpected(input, init)
    }
    if (init?.signal?.aborted) {
      // Checked before building the reply promise: an Error reply would otherwise create an
      // unhandled rejection, because toPromise(reply) rejects immediately and nothing would
      // ever attach a handler to it (honourSignal short-circuits on an aborted signal).
      return Promise.reject(abortError())
    }
    // The last reply repeats; earlier ones are consumed in order.
    const reply = queue.length > 1 ? queue.shift()! : queue[0]
    return honourSignal(toPromise(reply), init?.signal)
  })

  vi.stubGlobal('fetch', mock)
  return mock
}

export function json(body: unknown, status = 200): () => Response {
  return () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// A 2xx (or any) body with a JSON content type that is not actually valid JSON.
export function rawJson(text: string, status = 200): () => Response {
  return () => new Response(text, { status, headers: { 'Content-Type': 'application/json' } })
}

export function problem(
  status: number,
  detail?: string | null,
  errors?: Array<{ field: string; message: string }>,
): () => Response {
  const body: Record<string, unknown> = { status }
  if (detail !== undefined) {
    body.detail = detail
  }
  if (errors !== undefined) {
    body.errors = errors
  }
  return () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json' } })
}

export function html(status: number): () => Response {
  return () => new Response('<html><body>error</body></html>', { status, headers: { 'Content-Type': 'text/html' } })
}

export function noContent(): () => Response {
  return () => new Response(null, { status: 204 })
}

export type RecordedRequest = { method: string; url: string; headers: Headers; body: unknown }

export function requests(mock: ReturnType<typeof mockFetch>): RecordedRequest[] {
  return mock.mock.calls.map(([input, init]) => {
    const { method, url } = describeRequest(input, init)
    const headers = new Headers(init?.headers)
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined
    return { method, url, headers, body }
  })
}

export function deferred<T = Response>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
