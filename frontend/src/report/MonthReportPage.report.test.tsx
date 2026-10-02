import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, json, mockFetch, problem, rawJson, requests } from '../test/fetchMock.ts'
import { FOOD, TRANSPORT, categoryReport, categorySummary, reportRoute, reportRow } from '../test/fixtures.ts'
import { MonthReportPage } from './MonthReportPage.tsx'

const NETWORK = 'Could not reach the server. Check your connection and try again.'

beforeEach(() => {
  // 2026-10-01 00:30 in Europe/Warsaw, while UTC is still September.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))
})

const rowOf = (name: string) => screen.getByRole('rowheader', { name }).closest('tr')!
const cellTexts = (row: HTMLElement) =>
  within(row)
    .getAllByRole('cell')
    .map((cell) => cell.textContent)
const sent = (mock: ReturnType<typeof mockFetch>) => requests(mock).map((e) => `${e.method} ${e.url}`)
const empty = (month: string, totalAmount: string) => json(categoryReport(month, [], { totalAmount }))

describe('MonthReportPage report', () => {
  it('MR1: loadsCurrentLocalMonthOnMount', async () => {
    const mock = mockFetch({
      [reportRoute('2026-10')]: json(categoryReport('2026-10', [reportRow()], { totalAmount: '10.00' })),
    })
    render(<MonthReportPage />)
    await screen.findByRole('table', { name: 'Report for 2026-10' })

    expect(sent(mock)).toEqual([reportRoute('2026-10')])
    expect(screen.getByRole('heading', { level: 1, name: 'Month' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '2026-10' })).toBeInTheDocument()
  })

  it('MR2: switcherReloadsReportForEachMonth', async () => {
    const mock = mockFetch({
      [reportRoute('2026-10')]: empty('2026-10', '10.00'),
      [reportRoute('2026-09')]: empty('2026-09', '9.00'),
      [reportRoute('2026-11')]: empty('2026-11', '11.00'),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByText('10.00')

    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    await screen.findByText('9.00')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('2026-09')
    expect(screen.getByText('9.00').closest('p')).toHaveTextContent('Total: 9.00')

    await user.click(screen.getByRole('button', { name: 'Next month' }))
    await screen.findByText('10.00')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('2026-10')
    expect(screen.getByText('10.00').closest('p')).toHaveTextContent('Total: 10.00')

    await user.click(screen.getByRole('button', { name: 'Next month' }))
    await screen.findByText('11.00')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('2026-11')
    expect(screen.getByText('11.00').closest('p')).toHaveTextContent('Total: 11.00')

    expect(sent(mock)).toEqual([
      reportRoute('2026-10'),
      reportRoute('2026-09'),
      reportRoute('2026-10'),
      reportRoute('2026-11'),
    ])
  })

  it('MR4: monthChangeClearsReportUntilLoaded', async () => {
    const september = deferred<Response>()
    mockFetch({
      [reportRoute('2026-10')]: json(
        categoryReport('2026-10', [reportRow({ category: categorySummary(FOOD), amount: '10.00' })], {
          totalAmount: '10.00',
        }),
      ),
      [reportRoute('2026-09')]: september.promise,
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table', { name: 'Report for 2026-10' })

    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText('Total:')).toBeNull()
    expect(screen.queryByText('10.00')).toBeNull()
    expect(screen.queryByRole('rowheader', { name: 'Food' })).toBeNull()

    september.resolve(
      json(
        categoryReport('2026-09', [reportRow({ category: categorySummary(TRANSPORT), amount: '3.00' })], {
          totalAmount: '9.00',
        }),
      )(),
    )
    await screen.findByRole('table', { name: 'Report for 2026-09' })
    expect(screen.getByRole('rowheader', { name: 'Transport' })).toBeInTheDocument()
    expect(screen.getByText('9.00').closest('p')).toHaveTextContent('Total: 9.00')
    expect(screen.queryByText('Loading…')).toBeNull()
  })

  it('MR5: latestMonthWins', async () => {
    const september = deferred<Response>()
    const mock = mockFetch({
      [reportRoute('2026-10')]: empty('2026-10', '10.00'),
      [reportRoute('2026-09')]: september.promise,
      [reportRoute('2026-08')]: empty('2026-08', '8.00'),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByText('10.00')

    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    await screen.findByText('8.00')

    september.resolve(empty('2026-09', '9.00')())
    // Let any (wrongly) pending state update settle.
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(screen.getByText('8.00').closest('p')).toHaveTextContent('Total: 8.00')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('2026-08')
    expect(screen.queryByText('9.00')).toBeNull()
    expect(sent(mock)).toEqual([reportRoute('2026-10'), reportRoute('2026-09'), reportRoute('2026-08')])
  })

  it('MR6: tableHasColumnsAndRowsInApiOrder', async () => {
    mockFetch({
      [reportRoute('2026-10')]: json(
        categoryReport(
          '2026-10',
          [
            reportRow({
              category: categorySummary(TRANSPORT),
              amount: '5.00',
              share: '2.0',
              limit: '50.00',
              remaining: '45.00',
            }),
            reportRow({
              category: categorySummary(FOOD),
              amount: '250.00',
              share: '98.0',
              limit: '200.00',
              remaining: '-50.00',
            }),
            reportRow({
              category: { id: 3, name: 'Gifts', icon: null },
              amount: '0.10',
              share: '0.0',
              limit: null,
              remaining: null,
            }),
          ],
          { totalAmount: '255.10' },
        ),
      ),
    })
    render(<MonthReportPage />)
    const table = await screen.findByRole('table', { name: 'Report for 2026-10' })

    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Category',
      'Amount',
      'Share',
      'Limit',
      'Remaining',
      'Change limit',
    ])
    expect(within(table).getAllByRole('rowheader').map((h) => h.textContent)).toEqual(['Transport', 'Food', 'Gifts'])

    expect(cellTexts(rowOf('Transport')).slice(0, 4)).toEqual(['5.00', '2.0%', '50.00', '45.00'])
    expect(cellTexts(rowOf('Food')).slice(0, 4)).toEqual(['250.00', '98.0%', '200.00', '-50.00'])
    expect(cellTexts(rowOf('Gifts')).slice(0, 4)).toEqual(['0.10', '0.0%', 'No limit', '—'])

    const expected: Array<[string, string, boolean]> = [
      ['Transport', '50.00', true],
      ['Food', '200.00', true],
      ['Gifts', '', false],
    ]
    for (const [name, value, canClear] of expected) {
      const row = rowOf(name)
      expect(within(row).getByLabelText(`Limit for ${name}`)).toHaveValue(value)
      expect(within(row).getByRole('button', { name: 'Set limit' })).toBeEnabled()
      const clear = within(row).getByRole('button', { name: 'Clear limit' })
      if (canClear) {
        expect(clear).toBeEnabled()
      } else {
        expect(clear).toBeDisabled()
      }
    }
    expect(screen.getAllByRole('button', { name: 'Set limit' })).toHaveLength(3)
  })

  it('MR7: highlightsOnlyRowsWithNegativeRemaining', async () => {
    const names = ['Negative', 'Zero', 'Positive', 'Unlimited']
    const remainings = ['-0.01', '0.00', '5.00', null]
    mockFetch({
      [reportRoute('2026-10')]: json(
        categoryReport(
          '2026-10',
          names.map((name, i) =>
            reportRow({
              category: { id: 10 + i, name, icon: null },
              amount: '1.00',
              share: '25.0',
              limit: remainings[i] === null ? null : '100.00',
              remaining: remainings[i],
            }),
          ),
          { totalAmount: '4.00' },
        ),
      ),
    })
    render(<MonthReportPage />)
    await screen.findByRole('table')

    expect(rowOf('Negative')).toHaveClass('over-limit')
    expect(rowOf('Zero')).not.toHaveClass('over-limit')
    expect(rowOf('Positive')).not.toHaveClass('over-limit')
    expect(rowOf('Unlimited')).not.toHaveClass('over-limit')
  })

  it('MR8: monthTotalIsTotalAmountAboveTable', async () => {
    mockFetch({
      [reportRoute('2026-10')]: json(
        categoryReport(
          '2026-10',
          [
            reportRow({ category: categorySummary(FOOD), amount: '10.00', share: '33.3' }),
            reportRow({ category: categorySummary(TRANSPORT), amount: '20.00', share: '66.7' }),
          ],
          { totalAmount: '136.00' },
        ),
      ),
    })
    const { unmount } = render(<MonthReportPage />)
    await screen.findByRole('table')

    const total = screen.getByText('136.00')
    expect(total.closest('p')).toHaveTextContent('Total: 136.00')
    expect(total.compareDocumentPosition(screen.getByRole('table')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    unmount()

    mockFetch({ [reportRoute('2026-10')]: empty('2026-10', '0.00') })
    render(<MonthReportPage />)
    await screen.findByText('No expenses or limits in this month.')
    expect(screen.getByText('0.00').closest('p')).toHaveTextContent('Total: 0.00')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('MR9: rendersServerValuesVerbatimWithoutArithmetic', async () => {
    const rows = [
      { name: 'A', amount: '10.00', share: '87.6', limit: '100.00', remaining: '7.77' },
      { name: 'B', amount: '500.00', share: '0.1', limit: '100.00', remaining: '25.00' },
      { name: 'C', amount: '1.00', share: '50.0', limit: '100.00', remaining: '-3.00' },
      { name: 'D', amount: '9999999999.99', share: '100.0', limit: null, remaining: null },
      { name: 'E', amount: '0.10', share: '0.0', limit: '9999999999.99', remaining: '9999999999.89' },
    ]
    mockFetch({
      [reportRoute('2026-10')]: json(
        categoryReport(
          '2026-10',
          rows.map(({ name, ...rest }, i) => reportRow({ category: { id: 20 + i, name, icon: null }, ...rest })),
          { totalAmount: '1234567890123456.78' },
        ),
      ),
    })
    render(<MonthReportPage />)
    const table = await screen.findByRole('table')

    expect(within(table).getAllByRole('rowheader').map((h) => h.textContent)).toEqual(['A', 'B', 'C', 'D', 'E'])
    for (const r of rows) {
      expect(cellTexts(rowOf(r.name)).slice(0, 4)).toEqual([
        r.amount,
        `${r.share}%`,
        r.limit ?? 'No limit',
        r.remaining ?? '—',
      ])
    }
    expect(screen.getByText('1234567890123456.78').closest('p')).toHaveTextContent('Total: 1234567890123456.78')
    expect(within(rowOf('A')).getByLabelText('Limit for A')).toHaveValue('100.00')

    for (const r of rows) {
      if (r.name === 'C') {
        expect(rowOf(r.name)).toHaveClass('over-limit')
      } else {
        expect(rowOf(r.name)).not.toHaveClass('over-limit')
      }
    }
  })

  it('MR10: loadErrorsArePageLevelAndSwitcherStaysUsable', async () => {
    const user = userEvent.setup()

    // (a) 500, then Next month succeeds and the alert is gone.
    mockFetch({
      [reportRoute('2026-10')]: problem(500, 'Unexpected error'),
      [reportRoute('2026-11')]: json(categoryReport('2026-11', [reportRow()], { totalAmount: '11.00' })),
    })
    const a = render(<MonthReportPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Server error (500): Unexpected error')
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText('Total:')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Next month' }))
    await screen.findByRole('table', { name: 'Report for 2026-11' })
    expect(screen.queryByRole('alert')).toBeNull()
    a.unmount()

    // (b) network failure
    mockFetch({ [reportRoute('2026-10')]: new TypeError('Failed to fetch') })
    const b = render(<MonthReportPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent(NETWORK)
    b.unmount()

    // (c) 400 with errors[]
    mockFetch({ [reportRoute('2026-10')]: problem(400, 'Validation failed', [{ field: 'month', message: 'invalid value' }]) })
    const c = render(<MonthReportPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the report: month: invalid value')
    c.unmount()

    // (d) a 2xx body that is not JSON
    mockFetch({ [reportRoute('2026-10')]: rawJson('not json') })
    render(<MonthReportPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the report: unexpected error.')
  })
})
