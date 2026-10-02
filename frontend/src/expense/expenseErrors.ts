import { ApiError, isAbortError } from '../api/client.ts'
import type { FieldErrors } from '../api/client.ts'

export type SubmitResult = { ok: true } | { ok: false; fieldErrors: FieldErrors; formError: string | null }

export type ErrorOutcome =
  | { target: 'form'; fieldErrors: FieldErrors; formError: string | null }
  | { target: 'page'; message: string; refresh: boolean; leaveEdit: boolean }
  | { target: 'ignore' }

const FORM_FIELDS = ['amount', 'categoryId', 'spentOn', 'note']

export const NETWORK_MESSAGE = 'Could not reach the server. Check your connection and try again.'
export const UNEXPECTED_MUTATION_MESSAGE = 'Unexpected error. Please try again.'
const EXPENSE_GONE_MESSAGE = 'This expense no longer exists. The list has been refreshed.'

export function detailOrFallback(error: ApiError, status: number): string {
  return error.detail ?? `request failed with status ${status}`
}

export function serverErrorMessage(error: ApiError, status: number): string {
  return error.detail ? `Server error (${status}): ${error.detail}` : `Server error (${status})`
}

function splitFieldErrors(fieldErrors: FieldErrors): { form: FieldErrors; other: FieldErrors } {
  const form: FieldErrors = {}
  const other: FieldErrors = {}
  for (const [field, message] of Object.entries(fieldErrors)) {
    if (FORM_FIELDS.includes(field)) {
      form[field] = message
    } else {
      other[field] = message
    }
  }
  return { form, other }
}

function formErrorFromOther(other: FieldErrors): string | null {
  const entries = Object.entries(other)
  if (entries.length === 0) {
    return null
  }
  return entries.map(([field, message]) => `${field}: ${message}`).join('; ')
}

function classifyDeleteError(error: ApiError, status: number): ErrorOutcome {
  if (status === 404) {
    return { target: 'page', message: EXPENSE_GONE_MESSAGE, refresh: true, leaveEdit: true }
  }
  if (status >= 500) {
    return { target: 'page', message: serverErrorMessage(error, status), refresh: false, leaveEdit: false }
  }
  return {
    target: 'page',
    message: `Could not delete the expense: ${detailOrFallback(error, status)}`,
    refresh: false,
    leaveEdit: false,
  }
}

function classifyFormError(error: ApiError, operation: 'create' | 'update', status: number): ErrorOutcome {
  if (status === 404) {
    if (operation === 'update') {
      return { target: 'page', message: EXPENSE_GONE_MESSAGE, refresh: true, leaveEdit: true }
    }
    return { target: 'form', fieldErrors: {}, formError: `Could not save the expense: ${detailOrFallback(error, status)}` }
  }
  if (status >= 500) {
    return { target: 'page', message: serverErrorMessage(error, status), refresh: false, leaveEdit: false }
  }
  if (status === 409) {
    return { target: 'form', fieldErrors: { categoryId: detailOrFallback(error, status) }, formError: null }
  }
  if (Object.keys(error.fieldErrors).length > 0) {
    const { form, other } = splitFieldErrors(error.fieldErrors)
    return { target: 'form', fieldErrors: form, formError: formErrorFromOther(other) }
  }
  return { target: 'form', fieldErrors: {}, formError: `Could not save the expense: ${detailOrFallback(error, status)}` }
}

function classifyApplyError(error: ApiError, status: number): ErrorOutcome {
  if (status >= 500) {
    return { target: 'page', message: serverErrorMessage(error, status), refresh: false, leaveEdit: false }
  }
  return {
    target: 'page',
    message: `Could not apply the template: ${detailOrFallback(error, status)}`,
    refresh: false,
    leaveEdit: false,
  }
}

export function classifyMutationError(error: unknown, operation: 'create' | 'update' | 'delete' | 'apply'): ErrorOutcome {
  if (isAbortError(error)) {
    return { target: 'ignore' }
  }
  if (!(error instanceof ApiError)) {
    return { target: 'page', message: UNEXPECTED_MUTATION_MESSAGE, refresh: false, leaveEdit: false }
  }
  if (error.status === null) {
    return { target: 'page', message: NETWORK_MESSAGE, refresh: false, leaveEdit: false }
  }
  if (operation === 'delete') {
    return classifyDeleteError(error, error.status)
  }
  if (operation === 'apply') {
    return classifyApplyError(error, error.status)
  }
  return classifyFormError(error, operation, error.status)
}

const LOAD_ERROR_LABELS = {
  expenses: 'Could not load expenses',
  categories: 'Could not load categories',
  quickTemplates: 'Could not load quick templates',
  report: 'Could not load the report',
}

export function describeLoadError(error: unknown, what: 'expenses' | 'categories' | 'quickTemplates' | 'report'): string | null {
  if (isAbortError(error)) {
    return null
  }
  const label = LOAD_ERROR_LABELS[what]
  if (!(error instanceof ApiError)) {
    return `${label}: unexpected error.`
  }
  if (error.status === null) {
    return NETWORK_MESSAGE
  }
  if (error.status >= 500) {
    return serverErrorMessage(error, error.status)
  }
  const fieldEntries = Object.entries(error.fieldErrors)
  if (fieldEntries.length > 0) {
    return `${label}: ${fieldEntries.map(([field, message]) => `${field}: ${message}`).join('; ')}`
  }
  return `${label}: ${detailOrFallback(error, error.status)}`
}
