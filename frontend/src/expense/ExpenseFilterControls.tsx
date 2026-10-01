import { useId } from 'react'
import type { Category } from '../api/categories.ts'
import type { ExpenseFilters } from '../api/expenses.ts'
import { NO_FILTERS, hasAnyFilter, withCategory } from './expenseFilters.ts'

type Props = {
  filters: ExpenseFilters
  categories: Category[] | null // all categories, or null while loading or after a failed load
  onChange: (next: ExpenseFilters) => void
}

export function ExpenseFilterControls({ filters, categories, onChange }: Props) {
  const fromId = useId()
  const toId = useId()
  const dateHintId = useId()
  const categoryHintId = useId()

  const known = categories ?? []
  // A selected id missing from the known list (a hand-edited id, or categories not loaded
  // or failed to load) still gets a checked entry, so a filter in effect is never invisible.
  const unknownSelectedIds = filters.categoryIds.filter((id) => !known.some((category) => category.id === id))

  return (
    <div role="search" aria-label="Filter expenses" className="filters">
      <div className="field">
        <label htmlFor={fromId}>From</label>
        <input
          id={fromId}
          type="date"
          value={filters.from ?? ''}
          aria-describedby={dateHintId}
          onChange={(event) => onChange({ ...filters, from: event.target.value === '' ? null : event.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor={toId}>To</label>
        <input
          id={toId}
          type="date"
          value={filters.to ?? ''}
          aria-describedby={dateHintId}
          onChange={(event) => onChange({ ...filters, to: event.target.value === '' ? null : event.target.value })}
        />
      </div>
      <p id={dateHintId} className="hint">
        Empty dates mean the current month.
      </p>

      <fieldset aria-describedby={categoryHintId}>
        <legend>Categories</legend>
        {known.map((category) => (
          <label key={category.id}>
            <input
              type="checkbox"
              checked={filters.categoryIds.includes(category.id)}
              onChange={(event) => onChange(withCategory(filters, category.id, event.target.checked))}
            />
            {category.archived ? `${category.name} (archived)` : category.name}
          </label>
        ))}
        {unknownSelectedIds.map((id) => (
          <label key={id}>
            <input type="checkbox" checked onChange={(event) => onChange(withCategory(filters, id, event.target.checked))} />
            {`Category #${id}`}
          </label>
        ))}
        <p id={categoryHintId} className="hint">
          No category selected means all categories.
        </p>
      </fieldset>

      <button type="button" disabled={!hasAnyFilter(filters)} onClick={() => onChange(NO_FILTERS)}>
        Clear filters
      </button>
    </div>
  )
}
