import { useEffect, useId, useState } from 'react'
import { listAllCategories } from '../api/categories.ts'
import type { Category } from '../api/categories.ts'
import { createExpense, deleteExpense, listExpenses, updateExpense } from '../api/expenses.ts'
import type { Expense, ExpenseFilters, ExpenseInput, ExpenseList } from '../api/expenses.ts'
import { applyQuickTemplate, listQuickTemplates } from '../api/quickTemplates.ts'
import type { QuickTemplate } from '../api/quickTemplates.ts'
import { classifyMutationError, describeLoadError } from './expenseErrors.ts'
import type { SubmitResult } from './expenseErrors.ts'
import { filtersToSearch, hasAnyFilter, hasDateFilter, parseFilters } from './expenseFilters.ts'
import { ExpenseFilterControls } from './ExpenseFilterControls.tsx'
import { ExpenseForm } from './ExpenseForm.tsx'
import { ExpenseTable } from './ExpenseTable.tsx'
import { QuickTemplateBar } from './QuickTemplateBar.tsx'

const PAGE_SIZE = 50

export function ExpensesPage() {
  const [categories, setCategories] = useState<Category[] | null>(null)
  const [templates, setTemplates] = useState<QuickTemplate[] | null>(null)
  const [filters, setFilters] = useState<ExpenseFilters>(() => parseFilters(window.location.search))
  const [list, setList] = useState<ExpenseList | null>(null)
  const [page, setPage] = useState(0)
  const [reloadKey, setReloadKey] = useState(0)
  const [editing, setEditing] = useState<Expense | null>(null)
  const [formKey, setFormKey] = useState(0)
  const [mutating, setMutating] = useState(false)
  const [pageError, setPageError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const sectionHeadingId = useId()

  const activeCategories = categories === null ? null : categories.filter((category) => !category.archived)

  // Categories are loaded once on mount. A failure goes to the single pageError slot.
  useEffect(() => {
    const controller = new AbortController()
    listAllCategories(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return
        }
        setCategories(result)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        const message = describeLoadError(error, 'categories')
        if (message !== null) {
          setPageError(message)
        }
      })
    return () => controller.abort()
  }, [])

  // Templates are loaded once on mount. A failure goes to the single pageError slot.
  useEffect(() => {
    const controller = new AbortController()
    listQuickTemplates(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return
        }
        setTemplates(result)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        const message = describeLoadError(error, 'quickTemplates')
        if (message !== null) {
          setPageError(message)
        }
      })
    return () => controller.abort()
  }, [])

  // The list is reloaded on every filter or page change and after every successful mutation
  // (D1). The latest request wins: the previous one is aborted.
  useEffect(() => {
    const controller = new AbortController()
    listExpenses(filters, page, PAGE_SIZE, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return
        }
        if (result.items.length === 0 && result.page > 0) {
          // Decision 17: a page emptied by a mutation steps back one page.
          setPage(result.page - 1)
        } else {
          setList(result)
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        const message = describeLoadError(error, 'expenses')
        if (message !== null) {
          setPageError(message)
        }
      })
    return () => controller.abort()
  }, [filters, page, reloadKey])

  // Keeps the URL in sync with the filters (decision 1), and normalises garbage params on
  // mount (decision 8). replaceState, not pushState (decision 2): no extra history entry.
  useEffect(() => {
    const target = window.location.pathname + filtersToSearch(filters) + window.location.hash
    const current = window.location.pathname + window.location.search + window.location.hash
    if (target !== current) {
      window.history.replaceState(window.history.state, '', target)
    }
  }, [filters])

  async function handleSubmit(input: ExpenseInput): Promise<SubmitResult> {
    setPageError(null)
    setStatus(null)
    setMutating(true)
    try {
      if (editing) {
        await updateExpense(editing.id, input)
        setEditing(null)
        setStatus('Expense updated.')
        setReloadKey((key) => key + 1)
      } else {
        await createExpense(input)
        setFormKey((key) => key + 1)
        setStatus('Expense added.')
        setReloadKey((key) => key + 1)
      }
      return { ok: true }
    } catch (error) {
      const outcome = classifyMutationError(error, editing ? 'update' : 'create')
      if (outcome.target === 'form') {
        return { ok: false, fieldErrors: outcome.fieldErrors, formError: outcome.formError }
      }
      if (outcome.target === 'page') {
        setPageError(outcome.message)
        if (outcome.refresh) {
          setReloadKey((key) => key + 1)
        }
        if (outcome.leaveEdit) {
          setEditing(null)
        }
      }
      return { ok: false, fieldErrors: {}, formError: null }
    } finally {
      setMutating(false)
    }
  }

  async function handleDelete(expense: Expense) {
    const confirmed = window.confirm(
      `Delete the expense of ${expense.amount} ${expense.currency} (${expense.category.name}, ${expense.spentOn})?`,
    )
    if (!confirmed) {
      return
    }
    setPageError(null)
    setStatus(null)
    setMutating(true)
    try {
      await deleteExpense(expense.id)
      setStatus('Expense deleted.')
      if (editing?.id === expense.id) {
        setEditing(null)
      }
      setReloadKey((key) => key + 1)
    } catch (error) {
      const outcome = classifyMutationError(error, 'delete')
      if (outcome.target === 'page') {
        setPageError(outcome.message)
        if (outcome.refresh) {
          setReloadKey((key) => key + 1)
        }
        if (outcome.leaveEdit && editing?.id === expense.id) {
          setEditing(null)
        }
      }
    } finally {
      setMutating(false)
    }
  }

  function handleEdit(expense: Expense) {
    setPageError(null)
    setStatus(null)
    setEditing(expense)
  }

  function handleCancelEdit() {
    setPageError(null)
    setStatus(null)
    setEditing(null)
  }

  function handlePageChange(target: number) {
    setPageError(null)
    setStatus(null)
    if (target === page) {
      // The requested page's own load failed, so `list` still shows the previous page and
      // `page` never advanced: setPage(target) would be a no-op. Force a refetch instead.
      setReloadKey((key) => key + 1)
    } else {
      setPage(target)
    }
  }

  function handleFiltersChange(next: ExpenseFilters) {
    setPageError(null)
    setStatus(null)
    setFilters(next)
    setPage(0)
    setList(null)
  }

  async function handleApply(template: QuickTemplate) {
    setPageError(null)
    setStatus(null)
    setMutating(true)
    try {
      await applyQuickTemplate(template.id)
      setStatus(`Expense added from template "${template.name}".`)
      setReloadKey((key) => key + 1)
    } catch (error) {
      const outcome = classifyMutationError(error, 'apply')
      if (outcome.target === 'page') {
        setPageError(outcome.message)
      }
    } finally {
      setMutating(false)
    }
  }

  const periodIsSelected = hasDateFilter(filters)
  const anyFilterSet = hasAnyFilter(filters)

  return (
    <>
      <h1>Expenses</h1>
      {pageError && (
        <p role="alert" className="error">
          {pageError}
        </p>
      )}
      <p role="status">{status}</p>
      <QuickTemplateBar templates={templates} busy={mutating} onApply={(template) => void handleApply(template)} />
      <ExpenseForm
        key={editing ? `edit-${editing.id}` : `new-${formKey}`}
        categories={activeCategories}
        expense={editing ?? undefined}
        busy={mutating}
        onSubmit={handleSubmit}
        onCancel={editing ? handleCancelEdit : undefined}
      />
      <section aria-labelledby={sectionHeadingId}>
        <h2 id={sectionHeadingId}>{periodIsSelected ? 'Selected period' : 'This month'}</h2>
        <ExpenseFilterControls filters={filters} categories={categories} onChange={handleFiltersChange} />
        {list === null && pageError === null && <p className="hint">Loading…</p>}
        {list !== null && (
          <>
            <p className="month-total">
              Total: <strong>{list.totalAmount}</strong>
            </p>
            <ExpenseTable
              list={list}
              actionsDisabled={mutating}
              label={periodIsSelected ? 'Expenses in the selected period' : 'Expenses this month'}
              emptyText={anyFilterSet ? 'No expenses match the filters.' : 'No expenses this month.'}
              onEdit={handleEdit}
              onDelete={(expense) => void handleDelete(expense)}
              onPageChange={handlePageChange}
            />
          </>
        )}
      </section>
    </>
  )
}
