import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { CategoryInput } from '../api/categories.ts'
import type { FieldErrors } from '../api/client.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'

type Props = {
  busy: boolean
  onSubmit: (input: CategoryInput) => Promise<SubmitResult>
}

export function CategoryForm({ busy, onSubmit }: Props) {
  const [name, setName] = useState('')
  const [icon, setIcon] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const headingId = useId()
  const nameId = useId()
  const nameErrorId = useId()
  const iconId = useId()
  const iconErrorId = useId()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldErrors({})
    setFormError(null)
    const result = await onSubmit({ name, icon })
    if (!result.ok) {
      setFieldErrors(result.fieldErrors)
      setFormError(result.formError)
    }
  }

  const nameError = fieldErrors.name ?? null
  const iconError = fieldErrors.icon ?? null

  return (
    <form noValidate aria-labelledby={headingId} onSubmit={(event) => void handleSubmit(event)}>
      <h2 id={headingId}>New category</h2>
      <div className="field">
        <label htmlFor={nameId}>Name</label>
        <input
          id={nameId}
          type="text"
          autoComplete="off"
          value={name}
          onChange={(event) => setName(event.target.value)}
          aria-invalid={nameError ? 'true' : undefined}
          aria-describedby={nameError ? nameErrorId : undefined}
        />
        {nameError && (
          <p id={nameErrorId} className="field-error">
            {nameError}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor={iconId}>Icon</label>
        <input
          id={iconId}
          type="text"
          autoComplete="off"
          value={icon}
          onChange={(event) => setIcon(event.target.value)}
          aria-invalid={iconError ? 'true' : undefined}
          aria-describedby={iconError ? iconErrorId : undefined}
        />
        {iconError && (
          <p id={iconErrorId} className="field-error">
            {iconError}
          </p>
        )}
      </div>
      {formError && (
        <p role="alert" className="error">
          {formError}
        </p>
      )}
      <div className="form-actions">
        <button type="submit" disabled={busy}>
          Add category
        </button>
      </div>
    </form>
  )
}
