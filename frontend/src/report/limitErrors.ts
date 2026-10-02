import { ApiError, isAbortError } from '../api/client.ts'
import type { FieldErrors } from '../api/client.ts'
import { NETWORK_MESSAGE, UNEXPECTED_MUTATION_MESSAGE, detailOrFallback, serverErrorMessage } from '../expense/expenseErrors.ts'

export type LimitErrorOutcome =
  | { target: 'row'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean }
  | { target: 'ignore' }

export const LIMIT_GONE_MESSAGE = 'This limit no longer exists. The report has been refreshed.'

export function classifyLimitError(error: unknown, operation: 'set' | 'clear'): LimitErrorOutcome {
  if (isAbortError(error)) {
    return { target: 'ignore' }
  }
  if (!(error instanceof ApiError)) {
    return { target: 'page', message: UNEXPECTED_MUTATION_MESSAGE, refresh: false }
  }
  const status = error.status
  if (status === null) {
    return { target: 'page', message: NETWORK_MESSAGE, refresh: false }
  }
  if (status >= 500) {
    return { target: 'page', message: serverErrorMessage(error, status), refresh: false }
  }
  if (operation === 'set') {
    const entries = Object.entries(error.fieldErrors)
    if (entries.length > 0) {
      const fieldErrors: FieldErrors = 'amount' in error.fieldErrors ? { amount: error.fieldErrors.amount } : {}
      const others = entries.filter(([field]) => field !== 'amount')
      const formError = others.length > 0 ? others.map(([field, message]) => `${field}: ${message}`).join('; ') : null
      return { target: 'row', fieldErrors, formError }
    }
    return {
      target: 'row',
      fieldErrors: {},
      formError: `Could not set the limit: ${detailOrFallback(error, status)}`,
    }
  }
  if (status === 404) {
    return { target: 'page', message: LIMIT_GONE_MESSAGE, refresh: true }
  }
  return {
    target: 'row',
    fieldErrors: {},
    formError: `Could not clear the limit: ${detailOrFallback(error, status)}`,
  }
}
