import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client.ts'
import { CATEGORY_GONE_MESSAGE, classifyCategoryError } from './categoryErrors.ts'
import type { CategoryOperation } from './categoryErrors.ts'

const NETWORK = 'Could not reach the server. Check your connection and try again.'
const ALL: CategoryOperation[] = ['create', 'update', 'archive', 'restore']
const FORM_OPERATIONS: CategoryOperation[] = ['create', 'update']
const LABELS: Record<CategoryOperation, string> = {
  create: 'Could not add the category',
  update: 'Could not update the category',
  archive: 'Could not archive the category',
  restore: 'Could not restore the category',
}

describe('classifyCategoryError', () => {
  it('CE1: transportAndServerErrorsArePageLevel', () => {
    for (const operation of ALL) {
      const abort = new DOMException('This operation was aborted', 'AbortError')
      expect(classifyCategoryError(abort, operation)).toEqual({ target: 'ignore' })
      expect(classifyCategoryError(new Error('x'), operation)).toEqual({
        target: 'page',
        message: 'Unexpected error. Please try again.',
        refresh: false,
      })
      expect(classifyCategoryError(new ApiError(null, null), operation)).toEqual({
        target: 'page',
        message: NETWORK,
        refresh: false,
      })
      expect(classifyCategoryError(new ApiError(503, 'Down'), operation)).toEqual({
        target: 'page',
        message: 'Server error (503): Down',
        refresh: false,
      })
    }
  })

  it('CE2: createAndUpdateErrorsGoToTheForm', () => {
    for (const operation of FORM_OPERATIONS) {
      expect(classifyCategoryError(new ApiError(409, 'Category name already exists'), operation)).toEqual({
        target: 'form',
        fieldErrors: { name: 'Category name already exists' },
        formError: null,
      })
      expect(classifyCategoryError(new ApiError(409, null), operation)).toEqual({
        target: 'form',
        fieldErrors: { name: 'request failed with status 409' },
        formError: null,
      })
      expect(
        classifyCategoryError(
          new ApiError(400, 'Invalid request content.', { name: 'a', icon: 'b', other: 'c' }),
          operation,
        ),
      ).toEqual({ target: 'form', fieldErrors: { name: 'a', icon: 'b' }, formError: 'other: c' })
      expect(classifyCategoryError(new ApiError(400, 'Failed to read request'), operation)).toEqual({
        target: 'form',
        fieldErrors: {},
        formError: `${LABELS[operation]}: Failed to read request`,
      })
    }
    expect(classifyCategoryError(new ApiError(404, 'Not Found'), 'create')).toEqual({
      target: 'form',
      fieldErrors: {},
      formError: 'Could not add the category: Not Found',
    })
  })

  it('CE3: goneAndArchiveRestoreErrors', () => {
    for (const operation of ['update', 'archive', 'restore'] as const) {
      expect(classifyCategoryError(new ApiError(404, 'Category not found'), operation)).toEqual({
        target: 'page',
        message: CATEGORY_GONE_MESSAGE,
        refresh: true,
      })
    }
    expect(classifyCategoryError(new ApiError(400, 'Failed to read request'), 'archive')).toEqual({
      target: 'page',
      message: 'Could not archive the category: Failed to read request',
      refresh: false,
    })
    expect(classifyCategoryError(new ApiError(409, null), 'restore')).toEqual({
      target: 'page',
      message: 'Could not restore the category: request failed with status 409',
      refresh: false,
    })
  })
})
