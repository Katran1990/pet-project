import { useEffect, useState } from 'react'
import { createCategory, listAllCategories, updateCategory } from '../api/categories.ts'
import type { Category, CategoryInput } from '../api/categories.ts'
import { describeLoadError } from '../expense/expenseErrors.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'
import { CategoryForm } from './CategoryForm.tsx'
import { CategoryTable } from './CategoryTable.tsx'
import { classifyCategoryError } from './categoryErrors.ts'
import type { CategoryOperation } from './categoryErrors.ts'

const NOTHING: SubmitResult = { ok: false, fieldErrors: {}, formError: null }

export function CategoriesPage() {
  const [categories, setCategories] = useState<Category[] | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  // True from a change until the reload it triggered has settled, so Edit never prefills from a stale list.
  const [reloadPending, setReloadPending] = useState(false)
  // True until the first load settles and while a reload runs, independent of pageError (start() clears it).
  const [loading, setLoading] = useState(true)
  const [formKey, setFormKey] = useState(0)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [mutating, setMutating] = useState(false)
  const [pageError, setPageError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    listAllCategories(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return
        }
        setReloadPending(false)
        setLoading(false)
        setCategories(result)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        setReloadPending(false)
        setLoading(false)
        const message = describeLoadError(error, 'categories')
        if (message !== null) {
          setPageError(message)
        }
      })
    return () => controller.abort()
  }, [reloadKey])

  function reload() {
    setReloadPending(true)
    setLoading(true)
    setReloadKey((key) => key + 1)
  }

  function start() {
    setPageError(null)
    setStatus(null)
    setMutating(true)
  }

  function handleError(error: unknown, operation: CategoryOperation): SubmitResult {
    const outcome = classifyCategoryError(error, operation)
    if (outcome.target === 'form') {
      return { ok: false, fieldErrors: outcome.fieldErrors, formError: outcome.formError }
    }
    if (outcome.target === 'page') {
      setPageError(outcome.message)
      if (outcome.refresh) {
        if (operation === 'update') {
          setEditingId(null)
        }
        reload()
      }
    }
    return NOTHING
  }

  async function handleCreate(input: CategoryInput): Promise<SubmitResult> {
    start()
    try {
      await createCategory(input)
      setFormKey((key) => key + 1)
      setStatus('Category added.')
      reload()
      return { ok: true }
    } catch (error) {
      return handleError(error, 'create')
    } finally {
      setMutating(false)
    }
  }

  async function handleSave(category: Category, input: CategoryInput): Promise<SubmitResult> {
    start()
    try {
      await updateCategory(category.id, input)
      setEditingId(null)
      setStatus('Category updated.')
      reload()
      return { ok: true }
    } catch (error) {
      return handleError(error, 'update')
    } finally {
      setMutating(false)
    }
  }

  async function handleToggleArchived(category: Category) {
    const archived = !category.archived
    start()
    try {
      await updateCategory(category.id, { archived })
      setStatus(`Category "${category.name}" ${archived ? 'archived' : 'restored'}.`)
      reload()
    } catch (error) {
      handleError(error, archived ? 'archive' : 'restore')
    } finally {
      setMutating(false)
    }
  }

  function handleEdit(category: Category) {
    setPageError(null)
    setStatus(null)
    setEditingId(category.id)
  }

  function handleCancelEdit() {
    setPageError(null)
    setStatus(null)
    setEditingId(null)
  }

  return (
    <>
      <h1>Categories</h1>
      {pageError && (
        <p role="alert" className="error">
          {pageError}
        </p>
      )}
      <p role="status">{status}</p>
      <CategoryForm key={formKey} busy={mutating} onSubmit={handleCreate} />
      {categories === null && loading && <p className="hint">Loading…</p>}
      {categories !== null && (
        <CategoryTable
          categories={categories}
          editingId={editingId}
          busy={mutating || reloadPending}
          onEdit={handleEdit}
          onCancelEdit={handleCancelEdit}
          onSave={handleSave}
          onToggleArchived={(category) => void handleToggleArchived(category)}
        />
      )}
    </>
  )
}
