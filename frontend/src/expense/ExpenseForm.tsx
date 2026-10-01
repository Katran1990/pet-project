import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { Category } from '../api/categories.ts'
import type { Expense, ExpenseInput } from '../api/expenses.ts'
import type { FieldErrors } from '../api/client.ts'
import type { SubmitResult } from './expenseErrors.ts'
import { localIsoDate } from './localDate.ts'

type Props = {
  categories: Category[] | null
  expense?: Expense
  busy: boolean
  onSubmit: (input: ExpenseInput) => Promise<SubmitResult>
  onCancel?: () => void
}

type FieldValues = { amount: string; categoryId: string; spentOn: string; note: string }

function initialValues(expense: Expense | undefined): FieldValues {
  if (expense) {
    return {
      amount: expense.amount,
      categoryId: String(expense.category.id),
      spentOn: expense.spentOn,
      note: expense.note ?? '',
    }
  }
  return { amount: '', categoryId: '', spentOn: localIsoDate(new Date()), note: '' }
}

export function ExpenseForm({ categories, expense, busy, onSubmit, onCancel }: Props) {
  const initial = initialValues(expense)
  const [amount, setAmount] = useState(initial.amount)
  const [categoryId, setCategoryId] = useState(initial.categoryId)
  const [spentOn, setSpentOn] = useState(initial.spentOn)
  const [note, setNote] = useState(initial.note)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)

  const headingId = useId()
  const amountId = useId()
  const categorySelectId = useId()
  const spentOnId = useId()
  const noteId = useId()
  const amountErrorId = useId()
  const categoryErrorId = useId()
  const spentOnErrorId = useId()
  const noteErrorId = useId()

  const activeCategories = categories ?? []
  const hasCurrentCategory = expense ? activeCategories.some((category) => category.id === expense.category.id) : true
  const categoryOptions =
    expense && !hasCurrentCategory
      ? [
          ...activeCategories.map((category) => ({ id: category.id, name: category.name })),
          {
            id: expense.category.id,
            name: categories !== null ? `${expense.category.name} (archived)` : expense.category.name,
          },
        ]
      : activeCategories.map((category) => ({ id: category.id, name: category.name }))

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldErrors({})
    setFormError(null)
    const input: ExpenseInput = {
      amount: amount === '' ? null : amount,
      categoryId: categoryId === '' ? null : Number(categoryId),
      spentOn: spentOn === '' ? null : spentOn,
      note,
    }
    const result = await onSubmit(input)
    if (!result.ok) {
      setFieldErrors(result.fieldErrors)
      setFormError(result.formError)
    }
  }

  const amountError = fieldErrors.amount ?? null
  const categoryError = fieldErrors.categoryId ?? null
  const spentOnError = fieldErrors.spentOn ?? null
  const noteError = fieldErrors.note ?? null

  return (
    <form noValidate aria-labelledby={headingId} onSubmit={(event) => void handleSubmit(event)}>
      <h2 id={headingId}>{expense ? 'Edit expense' : 'New expense'}</h2>

      <div className="field">
        <label htmlFor={amountId}>Amount</label>
        <input
          id={amountId}
          autoFocus={expense !== undefined}
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="12.50"
          value={amount}
          onChange={(event) => setAmount(event.target.value)}
          aria-invalid={amountError ? 'true' : undefined}
          aria-describedby={amountError ? amountErrorId : undefined}
        />
        {amountError && (
          <p id={amountErrorId} className="field-error">
            {amountError}
          </p>
        )}
      </div>

      <div className="field">
        <label htmlFor={categorySelectId}>Category</label>
        <select
          id={categorySelectId}
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
          aria-invalid={categoryError ? 'true' : undefined}
          aria-describedby={categoryError ? categoryErrorId : undefined}
        >
          <option value="">Select a category</option>
          {categoryOptions.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
        {categoryError && (
          <p id={categoryErrorId} className="field-error">
            {categoryError}
          </p>
        )}
      </div>

      <div className="field">
        <label htmlFor={spentOnId}>Date</label>
        <input
          id={spentOnId}
          type="date"
          value={spentOn}
          onChange={(event) => setSpentOn(event.target.value)}
          aria-invalid={spentOnError ? 'true' : undefined}
          aria-describedby={spentOnError ? spentOnErrorId : undefined}
        />
        {spentOnError && (
          <p id={spentOnErrorId} className="field-error">
            {spentOnError}
          </p>
        )}
      </div>

      <div className="field">
        <label htmlFor={noteId}>Note</label>
        <input
          id={noteId}
          type="text"
          value={note}
          onChange={(event) => setNote(event.target.value)}
          aria-invalid={noteError ? 'true' : undefined}
          aria-describedby={noteError ? noteErrorId : undefined}
        />
        {noteError && (
          <p id={noteErrorId} className="field-error">
            {noteError}
          </p>
        )}
      </div>

      {formError && (
        <p role="alert" className="error">
          {formError}
        </p>
      )}

      <div className="form-actions">
        {expense ? (
          <>
            <button type="submit" disabled={busy}>
              Save changes
            </button>
            <button type="button" disabled={busy} onClick={onCancel}>
              Cancel
            </button>
          </>
        ) : (
          <button type="submit" disabled={busy}>
            Add expense
          </button>
        )}
      </div>
    </form>
  )
}
