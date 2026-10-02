import { ApiError, isAbortError } from '../api/client.ts'
import type { FieldErrors } from '../api/client.ts'
import { NETWORK_MESSAGE, UNEXPECTED_MUTATION_MESSAGE, detailOrFallback, serverErrorMessage } from '../expense/expenseErrors.ts'

export type CategoryOperation = 'create' | 'update' | 'archive' | 'restore'

export type CategoryErrorOutcome =
  | { target: 'form'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean }
  | { target: 'ignore' }

const FORM_FIELDS = ['name', 'icon']

export const CATEGORY_GONE_MESSAGE = 'This category no longer exists. The list has been refreshed.'

const OPERATION_LABELS: Record<CategoryOperation, string> = {
  create: 'Could not add the category',
  update: 'Could not update the category',
  archive: 'Could not archive the category',
  restore: 'Could not restore the category',
}

export function classifyCategoryError(error: unknown, operation: CategoryOperation): CategoryErrorOutcome {
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
  if (status === 404 && operation !== 'create') {
    return { target: 'page', message: CATEGORY_GONE_MESSAGE, refresh: true }
  }
  const label = OPERATION_LABELS[operation]
  if (operation === 'archive' || operation === 'restore') {
    return { target: 'page', message: `${label}: ${detailOrFallback(error, status)}`, refresh: false }
  }
  if (status === 409) {
    return { target: 'form', fieldErrors: { name: detailOrFallback(error, status) }, formError: null }
  }
  const entries = Object.entries(error.fieldErrors)
  if (entries.length > 0) {
    const fieldErrors: FieldErrors = {}
    const others: string[] = []
    for (const [field, message] of entries) {
      if (FORM_FIELDS.includes(field)) {
        fieldErrors[field] = message
      } else {
        others.push(`${field}: ${message}`)
      }
    }
    return { target: 'form', fieldErrors, formError: others.length > 0 ? others.join('; ') : null }
  }
  return { target: 'form', fieldErrors: {}, formError: `${label}: ${detailOrFallback(error, status)}` }
}
