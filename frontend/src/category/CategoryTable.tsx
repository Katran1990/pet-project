import type { Category, CategoryInput } from '../api/categories.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'
import { CategoryEditForm } from './CategoryEditForm.tsx'

type Props = {
  categories: Category[]
  editingId: number | null
  busy: boolean
  onEdit: (category: Category) => void
  onCancelEdit: () => void
  onSave: (category: Category, input: CategoryInput) => Promise<SubmitResult>
  onToggleArchived: (category: Category) => void
}

export function CategoryTable({ categories, editingId, busy, onEdit, onCancelEdit, onSave, onToggleArchived }: Props) {
  if (categories.length === 0) {
    return <p>No categories yet.</p>
  }
  return (
    <table className="category-table" aria-label="Categories">
      <thead>
        <tr>
          <th scope="col">Icon</th>
          <th scope="col">Name</th>
          <th scope="col">Status</th>
          <th scope="col">Actions</th>
        </tr>
      </thead>
      <tbody>
        {categories.map((category) => {
          const editing = category.id === editingId
          return (
            <tr key={category.id} className={category.archived ? 'archived' : undefined}>
              {editing ? (
                <td colSpan={2}>
                  <CategoryEditForm
                    category={category}
                    busy={busy}
                    onSave={(input) => onSave(category, input)}
                    onCancel={onCancelEdit}
                  />
                </td>
              ) : (
                <>
                  <td>{category.icon ?? '—'}</td>
                  <th scope="row">{category.name}</th>
                </>
              )}
              <td>{category.archived ? 'Archived' : 'Active'}</td>
              <td>
                {!editing && (
                  <button type="button" disabled={busy} onClick={() => onEdit(category)}>
                    Edit
                  </button>
                )}
                <button type="button" disabled={busy} onClick={() => onToggleArchived(category)}>
                  {category.archived ? 'Restore' : 'Archive'}
                </button>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
