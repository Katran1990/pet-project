export type FieldErrors = Record<string, string> // field -> messages joined with "; "

export class ApiError extends Error {
  readonly status: number | null // null: no response (fetch rejected)
  readonly detail: string | null // Problem Details "detail", when the body was JSON with a string detail
  readonly fieldErrors: FieldErrors // from "errors": [{field, message}], in response order

  constructor(status: number | null, detail: string | null, fieldErrors: FieldErrors = {}, options?: ErrorOptions) {
    super(detail ?? (status === null ? 'Network error' : `request failed with status ${status}`), options)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
    this.fieldErrors = fieldErrors
  }
}

// name-based, not instanceof DOMException: jsdom and Node each have their own DOMException
export function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError'
}

// Reading the body can itself reject (e.g. a network error mid-stream). An abort must still
// propagate as an abort; anything else becomes a network ApiError, with the original error
// kept as `cause`, so the user sees the network message instead of "Unexpected error".
async function readBodyText(response: Response): Promise<string> {
  try {
    return await response.text()
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new ApiError(null, null, {}, { cause: error })
  }
}

function parseErrorBody(text: string, contentType: string): { detail: string | null; fieldErrors: FieldErrors } {
  let detail: string | null = null
  const fieldErrors: FieldErrors = {}
  if (!contentType.includes('json') || text.length === 0) {
    return { detail, fieldErrors }
  }
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed !== null && typeof parsed === 'object') {
      const body = parsed as Record<string, unknown>
      if (typeof body.detail === 'string') {
        detail = body.detail
      }
      if (Array.isArray(body.errors)) {
        for (const entry of body.errors as unknown[]) {
          if (entry !== null && typeof entry === 'object') {
            const { field, message } = entry as Record<string, unknown>
            if (typeof field === 'string' && typeof message === 'string') {
              fieldErrors[field] = field in fieldErrors ? `${fieldErrors[field]}; ${message}` : message
            }
          }
        }
      }
    }
  } catch {
    // body claimed to be JSON but was not: detail and fieldErrors stay empty
  }
  return { detail, fieldErrors }
}

export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  options?: { body?: unknown; signal?: AbortSignal },
): Promise<T> {
  const init: RequestInit = { method }
  if (options?.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(options.body)
  }
  if (options?.signal) {
    init.signal = options.signal
  }

  let response: Response
  try {
    // Looked up on every call (not captured at module load), so vi.stubGlobal('fetch', …) works.
    response = await fetch(path, init)
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new ApiError(null, null, {}, { cause: error })
  }

  if (!response.ok) {
    const text = await readBodyText(response)
    const { detail, fieldErrors } = parseErrorBody(text, response.headers.get('Content-Type') ?? '')
    throw new ApiError(response.status, detail, fieldErrors)
  }

  const text = await readBodyText(response)
  if (response.status === 204 || text.length === 0) {
    return undefined as T
  }
  // A 2xx body that is not valid JSON rejects with that SyntaxError, unwrapped.
  return JSON.parse(text) as T
}
