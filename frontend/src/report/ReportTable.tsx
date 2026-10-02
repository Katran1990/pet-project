import type { CategoryReport, CategoryReportRow } from '../api/reports.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'
import { LimitForm } from './LimitForm.tsx'

type Props = {
  report: CategoryReport
  version: number
  busy: boolean
  onSetLimit: (row: CategoryReportRow, amount: string | null) => Promise<SubmitResult>
  onClearLimit: (row: CategoryReportRow) => Promise<SubmitResult>
}

export function ReportTable({ report, version, busy, onSetLimit, onClearLimit }: Props) {
  if (report.rows.length === 0) {
    return <p>No expenses or limits in this month.</p>
  }
  return (
    <table className="report-table" aria-label={`Report for ${report.month}`}>
      <thead>
        <tr>
          <th scope="col">Category</th>
          <th scope="col">Amount</th>
          <th scope="col">Share</th>
          <th scope="col">Limit</th>
          <th scope="col">Remaining</th>
          <th scope="col">Change limit</th>
        </tr>
      </thead>
      <tbody>
        {report.rows.map((row) => (
          <tr key={row.category.id} className={row.remaining?.startsWith('-') ? 'over-limit' : undefined}>
            <th scope="row">{row.category.name}</th>
            <td className="amount">{row.amount}</td>
            <td className="amount">{`${row.share}%`}</td>
            <td className="amount">{row.limit ?? 'No limit'}</td>
            <td className="amount">{row.remaining ?? '—'}</td>
            <td>
              <LimitForm
                key={`${version}:${row.category.id}`}
                categoryName={row.category.name}
                limit={row.limit}
                busy={busy}
                onSet={(amount) => onSetLimit(row, amount)}
                onClear={() => onClearLimit(row)}
              />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
