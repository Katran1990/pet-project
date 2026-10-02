import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { Category, CategoryInput } from '../api/categories.ts'
import type { FieldErrors } from '../api/client.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'

type Props = {
  category: Category
  busy: boolean
  onSave: (input: CategoryInput) => Promise<SubmitResult>
  onCancel: () => void
}

export function CategoryEditForm({ category, busy, onSave, onCancel }: Props) {
  const [name, setName] = useState(category.name)
  const [icon, setIcon] = useState(category.icon ?? '')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const nameErrorId = useId()
  const iconErrorId = useId()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldErrors({})
    setFormError(null)
    const result = await onSave({ name, icon })
    if (!result.ok) {
      setFieldErrors(result.fieldErrors)
      setFormError(result.formError)
    }
  }

  const nameError = fieldErrors.name ?? null
  const iconError = fieldErrors.icon ?? null

  return (
    <form
      noValidate
      aria-label={`Edit ${category.name}`}
      className="category-edit-form"
      onSubmit={(event) => void handleSubmit(event)}
    >
      <input
        type="text"
        autoComplete="off"
        aria-label="Icon"
        value={icon}
        onChange={(event) => setIcon(event.target.value)}
        aria-invalid={iconError ? 'true' : undefined}
        aria-describedby={iconError ? iconErrorId : undefined}
      />
      <input
        type="text"
        autoComplete="off"
        aria-label="Name"
        autoFocus
        value={name}
        onChange={(event) => setName(event.target.value)}
        aria-invalid={nameError ? 'true' : undefined}
        aria-describedby={nameError ? nameErrorId : undefined}
      />
      <button type="submit" disabled={busy}>
        Save
      </button>
      <button type="button" disabled={busy} onClick={onCancel}>
        Cancel
      </button>
      {iconError && (
        <p id={iconErrorId} className="field-error">
          {iconError}
        </p>
      )}
      {nameError && (
        <p id={nameErrorId} className="field-error">
          {nameError}
        </p>
      )}
      {formError && (
        <p role="alert" className="error">
          {formError}
        </p>
      )}
    </form>
  )
}
