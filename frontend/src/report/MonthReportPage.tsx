import { useEffect, useState } from 'react'
import { deleteBudgetLimit, listBudgetLimits, setBudgetLimit } from '../api/budgetLimits.ts'
import { getCategoryReport } from '../api/reports.ts'
import type { CategoryReport, CategoryReportRow } from '../api/reports.ts'
import { describeLoadError } from '../expense/expenseErrors.ts'
import type { SubmitResult } from '../expense/expenseErrors.ts'
import { LIMIT_GONE_MESSAGE, classifyLimitError } from './limitErrors.ts'
import { ReportTable } from './ReportTable.tsx'
import { currentYearMonth, shiftMonth } from './yearMonth.ts'

const NOTHING: SubmitResult = { ok: false, fieldErrors: {}, formError: null }

export function MonthReportPage() {
  const [month, setMonth] = useState(() => currentYearMonth(new Date()))
  const [report, setReport] = useState<CategoryReport | null>(null)
  const [reportVersion, setReportVersion] = useState(0)
  const [reloadKey, setReloadKey] = useState(0)
  const [mutating, setMutating] = useState(false)
  // True from a limit change until the report reload it triggered has settled, so a row form is
  // never remounted (by that reload) while a later change from it is still in flight.
  const [reloadPending, setReloadPending] = useState(false)
  const [pageError, setPageError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    getCategoryReport(month, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) {
          return
        }
        setReloadPending(false)
        setReport(result)
        setReportVersion((v) => v + 1)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        setReloadPending(false)
        const message = describeLoadError(error, 'report')
        if (message !== null) {
          setPageError(message)
        }
      })
    return () => controller.abort()
  }, [month, reloadKey])

  function reload() {
    setReloadPending(true)
    setReloadKey((key) => key + 1)
  }

  function handleMonthChange(delta: -1 | 1) {
    setPageError(null)
    setStatus(null)
    setMonth((current) => shiftMonth(current, delta))
    setReport(null)
  }

  function handleLimitError(error: unknown, operation: 'set' | 'clear'): SubmitResult {
    const outcome = classifyLimitError(error, operation)
    if (outcome.target === 'row') {
      return { ok: false, fieldErrors: outcome.fieldErrors, formError: outcome.formError }
    }
    if (outcome.target === 'page') {
      setPageError(outcome.message)
      if (outcome.refresh) {
        reload()
      }
    }
    return NOTHING
  }

  async function handleSetLimit(reportMonth: string, row: CategoryReportRow, amount: string | null): Promise<SubmitResult> {
    setPageError(null)
    setStatus(null)
    setMutating(true)
    try {
      await setBudgetLimit({ categoryId: row.category.id, month: reportMonth, amount })
      setStatus(`Limit set for ${row.category.name}.`)
      reload()
      return { ok: true }
    } catch (error) {
      return handleLimitError(error, 'set')
    } finally {
      setMutating(false)
    }
  }

  async function handleClearLimit(reportMonth: string, row: CategoryReportRow): Promise<SubmitResult> {
    setPageError(null)
    setStatus(null)
    setMutating(true)
    try {
      const limits = await listBudgetLimits(reportMonth)
      const limit = limits.find((entry) => entry.category.id === row.category.id)
      if (!limit) {
        setPageError(LIMIT_GONE_MESSAGE)
        reload()
        return NOTHING
      }
      await deleteBudgetLimit(limit.id)
      setStatus(`Limit cleared for ${row.category.name}.`)
      reload()
      return { ok: true }
    } catch (error) {
      return handleLimitError(error, 'clear')
    } finally {
      setMutating(false)
    }
  }

  return (
    <>
      <h1>Month</h1>
      {pageError && (
        <p role="alert" className="error">
          {pageError}
        </p>
      )}
      <p role="status">{status}</p>
      <div role="group" aria-label="Select month" className="month-switcher">
        <button type="button" disabled={mutating} onClick={() => handleMonthChange(-1)}>
          Previous month
        </button>
        <h2>{month}</h2>
        <button type="button" disabled={mutating} onClick={() => handleMonthChange(1)}>
          Next month
        </button>
      </div>
      {report === null && pageError === null && <p className="hint">Loading…</p>}
      {report !== null && (
        <>
          <p className="month-total">
            Total: <strong>{report.totalAmount}</strong>
          </p>
          <ReportTable
            report={report}
            version={reportVersion}
            busy={mutating || reloadPending}
            onSetLimit={(row, amount) => handleSetLimit(report.month, row, amount)}
            onClearLimit={(row) => handleClearLimit(report.month, row)}
          />
        </>
      )}
    </>
  )
}
