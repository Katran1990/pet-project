import type { Expense, ExpenseList } from '../api/expenses.ts'

type Props = {
  list: ExpenseList
  actionsDisabled: boolean
  label: string
  emptyText: string
  onEdit: (expense: Expense) => void
  onDelete: (expense: Expense) => void
  onPageChange: (page: number) => void
}

export function ExpenseTable({ list, actionsDisabled, label, emptyText, onEdit, onDelete, onPageChange }: Props) {
  const { items, page, size, totalItems } = list

  if (totalItems === 0) {
    return <p>{emptyText}</p>
  }

  const firstShown = page * size + 1
  const lastShown = page * size + items.length

  return (
    <>
      <table className="expense-table" aria-label={label}>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Category</th>
            <th scope="col">Amount</th>
            <th scope="col">Note</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <td>{item.spentOn}</td>
              <td>{item.category.name}</td>
              <td className="amount">{`${item.amount} ${item.currency}`}</td>
              <td>{item.note ?? ''}</td>
              <td>
                <button type="button" disabled={actionsDisabled} onClick={() => onEdit(item)}>
                  Edit
                </button>
                <button type="button" disabled={actionsDisabled} onClick={() => onDelete(item)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="pager">
        {`${firstShown}–${lastShown} of ${totalItems}`}
        <button type="button" disabled={page === 0} onClick={() => onPageChange(page - 1)}>
          Previous
        </button>
        <button type="button" disabled={(page + 1) * size >= totalItems} onClick={() => onPageChange(page + 1)}>
          Next
        </button>
      </p>
    </>
  )
}
