import { describe, expect, it } from 'vitest'
import { deferred, html, json, mockFetch, noContent, problem, rawJson, requests } from '../test/fetchMock.ts'
import { ApiError, isAbortError, request } from './client.ts'

describe('client', () => {
  it('C1: GET with a 200 JSON body returns the parsed body, with no body and no Content-Type sent', async () => {
    const mock = mockFetch({
      'GET /api/things': json({ hello: 'world' }),
    })

    const result = await request<{ hello: string }>('GET', '/api/things')

    expect(result).toEqual({ hello: 'world' })
    const [sent] = requests(mock)
    expect(sent.method).toBe('GET')
    expect(sent.body).toBeUndefined()
    expect(sent.headers.get('Content-Type')).toBeNull()
  })

  it('C2: POST sends Content-Type and a JSON body; a 201 returns the parsed body', async () => {
    const mock = mockFetch({
      'POST /api/things': json({ id: 1 }, 201),
    })

    const result = await request<{ id: number }>('POST', '/api/things', { body: { name: 'x' } })

    expect(result).toEqual({ id: 1 })
    const [sent] = requests(mock)
    expect(sent.method).toBe('POST')
    expect(sent.headers.get('Content-Type')).toBe('application/json')
    expect(sent.body).toEqual({ name: 'x' })
  })

  it('C3: DELETE with a 204 resolves to undefined', async () => {
    mockFetch({ 'DELETE /api/things/1': noContent() })

    const result = await request<void>('DELETE', '/api/things/1')

    expect(result).toBeUndefined()
  })

  it('C4: a 400 with repeated and single field errors joins messages per field', async () => {
    mockFetch({
      'POST /api/things': problem(400, 'Invalid request content.', [
        { field: 'amount', message: 'a' },
        { field: 'amount', message: 'b' },
        { field: 'note', message: 'c' },
      ]),
    })

    const error = await request('POST', '/api/things', { body: {} }).catch((error: unknown) => error)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(400)
    expect(apiError.detail).toBe('Invalid request content.')
    expect(apiError.fieldErrors).toEqual({ amount: 'a; b', note: 'c' })
  })

  it('C5: a 400 without errors, or with an empty errors array, gives fieldErrors {} and a detail', async () => {
    mockFetch({
      'POST /api/without': problem(400, 'Failed to read request'),
      'POST /api/empty': problem(400, 'Failed to read request', []),
    })

    const withoutErrors = (await request('POST', '/api/without', { body: {} }).catch((error: unknown) => error)) as ApiError
    const emptyErrors = (await request('POST', '/api/empty', { body: {} }).catch((error: unknown) => error)) as ApiError

    expect(withoutErrors.fieldErrors).toEqual({})
    expect(withoutErrors.detail).toBe('Failed to read request')
    expect(emptyErrors.fieldErrors).toEqual({})
    expect(emptyErrors.detail).toBe('Failed to read request')
  })

  it('C6: a 409 gives status 409 and the detail text', async () => {
    mockFetch({ 'PUT /api/expenses/1': problem(409, 'Category is archived') })

    const error = (await request('PUT', '/api/expenses/1', { body: {} }).catch((error: unknown) => error)) as ApiError

    expect(error.status).toBe(409)
    expect(error.detail).toBe('Category is archived')
  })

  it('C7: a 502 html body and a 500 problem body that is not valid JSON give detail null without throwing', async () => {
    mockFetch({
      'GET /api/html': html(502),
      'GET /api/badjson': rawJson('not actually json', 500),
    })

    const htmlError = (await request('GET', '/api/html').catch((error: unknown) => error)) as ApiError
    const badJsonError = (await request('GET', '/api/badjson').catch((error: unknown) => error)) as ApiError

    expect(htmlError).toBeInstanceOf(ApiError)
    expect(htmlError.status).toBe(502)
    expect(htmlError.detail).toBeNull()
    expect(badJsonError).toBeInstanceOf(ApiError)
    expect(badJsonError.status).toBe(500)
    expect(badJsonError.detail).toBeNull()
  })

  it('C8: fetch rejecting with a TypeError gives an ApiError with status null and the original error as cause', async () => {
    const networkError = new TypeError('Failed to fetch')
    mockFetch({ 'GET /api/net': networkError })

    const error = (await request('GET', '/api/net').catch((error: unknown) => error)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBeNull()
    expect(error.cause).toBe(networkError)
  })

  it('C9: aborting while pending rethrows the abort unchanged, and isAbortError classifies by name', async () => {
    const { promise } = deferred<Response>()
    mockFetch({ 'GET /api/slow': promise })
    const controller = new AbortController()

    const pending = request('GET', '/api/slow', { signal: controller.signal })
    controller.abort()
    const error = await pending.catch((error: unknown) => error)

    expect(error).not.toBeInstanceOf(ApiError)
    expect((error as { name?: unknown }).name).toBe('AbortError')
    expect(isAbortError(error)).toBe(true)
    expect(isAbortError(new Error('AbortError-ish'))).toBe(false)
    const plainAbort = new Error('aborted')
    plainAbort.name = 'AbortError'
    expect(isAbortError(plainAbort)).toBe(true)
    expect(isAbortError(new TypeError('x'))).toBe(false)
    expect(isAbortError(new ApiError(400, 'x'))).toBe(false)
  })

  it('C10: a 200 response with a non-JSON body rejects with a SyntaxError, not an ApiError (still true with readBodyText)', async () => {
    mockFetch({ 'GET /api/things': rawJson('not json', 200) })

    const error = await request('GET', '/api/things').catch((error: unknown) => error)

    expect(error).toBeInstanceOf(SyntaxError)
    expect(error).not.toBeInstanceOf(ApiError)
  })

  it('C11: a body read that rejects (not an abort) becomes a network ApiError with the original error as cause', async () => {
    const bodyError = new TypeError('network error mid-body')
    mockFetch({
      'GET /api/things': () => {
        const response = new Response(null, { status: 200, headers: { 'Content-Type': 'application/json' } })
        Object.defineProperty(response, 'text', { value: () => Promise.reject(bodyError) })
        return response
      },
    })

    const error = (await request('GET', '/api/things').catch((error: unknown) => error)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBeNull()
    expect(error.detail).toBeNull()
    expect(error.cause).toBe(bodyError)
  })

  it('C12: an abort while reading the body is rethrown as an abort, not wrapped into an ApiError', async () => {
    const abort = new DOMException('This operation was aborted', 'AbortError')
    mockFetch({
      'GET /api/things': () => {
        const response = new Response(null, { status: 200, headers: { 'Content-Type': 'application/json' } })
        Object.defineProperty(response, 'text', { value: () => Promise.reject(abort) })
        return response
      },
    })

    const error = await request('GET', '/api/things').catch((error: unknown) => error)

    expect(error).not.toBeInstanceOf(ApiError)
    expect(isAbortError(error)).toBe(true)
    expect(error).toBe(abort)
  })

  it('C13: PATCH sends method PATCH, Content-Type and a JSON body; a 200 returns the parsed body', async () => {
    const mock = mockFetch({
      'PATCH /api/things/1': json({ id: 1 }),
    })

    const result = await request<{ id: number }>('PATCH', '/api/things/1', { body: { icon: '' } })

    expect(result).toEqual({ id: 1 })
    // The raw method: fetch does not upper-case 'patch', and requests() would hide the case.
    expect(mock.mock.calls[0][1]?.method).toBe('PATCH')
    const [sent] = requests(mock)
    expect(sent.headers.get('Content-Type')).toBe('application/json')
    expect(sent.body).toEqual({ icon: '' })
  })
})
