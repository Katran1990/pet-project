import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { FieldErrors } from '../api/client.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'

type Props = {
  categoryName: string
  limit: string | null
  busy: boolean
  onSet: (amount: string | null) => Promise<SubmitResult>
  onClear: () => Promise<SubmitResult>
}

export function LimitForm({ categoryName, limit, busy, onSet, onClear }: Props) {
  const [amount, setAmount] = useState(limit ?? '')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const errorId = useId()

  async function run(action: () => Promise<SubmitResult>) {
    setFieldErrors({})
    setFormError(null)
    const result = await action()
    if (!result.ok) {
      setFieldErrors(result.fieldErrors)
      setFormError(result.formError)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void run(() => onSet(amount === '' ? null : amount))
  }

  const amountError = fieldErrors.amount ?? null

  return (
    <form noValidate className="limit-form" onSubmit={handleSubmit}>
      <input
        type="text"
        inputMode="decimal"
        autoComplete="off"
        aria-label={`Limit for ${categoryName}`}
        value={amount}
        onChange={(event) => setAmount(event.target.value)}
        aria-invalid={amountError ? 'true' : undefined}
        aria-describedby={amountError ? errorId : undefined}
      />
      <button type="submit" disabled={busy}>
        Set limit
      </button>
      <button type="button" disabled={busy || limit === null} onClick={() => void run(onClear)}>
        Clear limit
      </button>
      {amountError && (
        <p id={errorId} className="field-error">
          {amountError}
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
