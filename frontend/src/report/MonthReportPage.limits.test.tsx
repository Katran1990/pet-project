import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, json, mockFetch, noContent, problem, rawJson, requests } from '../test/fetchMock.ts'
import {
  FOOD,
  LIMIT_PUT_ROUTE,
  TRANSPORT,
  budgetLimit,
  categoryReport,
  categorySummary,
  limitDeleteRoute,
  limitsRoute,
  reportRoute,
  reportRow,
} from '../test/fixtures.ts'
import { MonthReportPage } from './MonthReportPage.tsx'

const NETWORK = 'Could not reach the server. Check your connection and try again.'
const GIFTS = { id: 3, name: 'Gifts', icon: null }

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
const input = (name: string) => screen.getByLabelText(`Limit for ${name}`)
const setIn = (name: string) => within(rowOf(name)).getByRole('button', { name: 'Set limit' })
const clearIn = (name: string) => within(rowOf(name)).getByRole('button', { name: 'Clear limit' })
const wait = () => new Promise((resolve) => setTimeout(resolve, 20))

const foodNoLimit = () => reportRow({ category: categorySummary(FOOD), amount: '10.00', share: '60.0' })
const transportLimit = () =>
  reportRow({
    category: categorySummary(TRANSPORT),
    amount: '5.00',
    share: '40.0',
    limit: '50.00',
    remaining: '45.00',
  })
const october = () => categoryReport('2026-10', [foodNoLimit(), transportLimit()], { totalAmount: '15.00' })

async function typeInto(user: ReturnType<typeof userEvent.setup>, name: string, text: string) {
  await user.clear(input(name))
  if (text !== '') {
    await user.type(input(name), text)
  }
}

describe('MonthReportPage limits', () => {
  it('LM1: setLimitPutsTypedAmountAndReloadsReport', async () => {
    const reloaded = categoryReport(
      '2026-10',
      [
        reportRow({
          category: categorySummary(FOOD),
          amount: '10.00',
          share: '60.0',
          limit: '250.00',
          remaining: '240.00',
        }),
        transportLimit(),
      ],
      { totalAmount: '15.00' },
    )
    const mock = mockFetch({
      [reportRoute('2026-10')]: [json(october()), json(reloaded)],
      [LIMIT_PUT_ROUTE]: json(budgetLimit({ amount: '250.00' })),
      [reportRoute('2026-09')]: json(categoryReport('2026-09', [], { totalAmount: '9.00' })),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')

    await typeInto(user, 'Food', '250')
    await user.click(setIn('Food'))

    expect(await screen.findByText('Limit set for Food.')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Limit set for Food.')
    await waitFor(() => expect(cellTexts(rowOf('Food')).slice(0, 4)).toEqual(['10.00', '60.0%', '250.00', '240.00']))
    expect(input('Food')).toHaveValue('250.00')

    expect(sent(mock)).toEqual([reportRoute('2026-10'), LIMIT_PUT_ROUTE, reportRoute('2026-10')])
    const put = requests(mock)[1]
    expect(put.headers.get('Content-Type')).toBe('application/json')
    expect(put.body).toEqual({ categoryId: 1, month: '2026-10', amount: '250' })
    expect(typeof (put.body as { amount: unknown }).amount).toBe('string')

    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('LM2: changesApplyToTheDisplayedMonth', async () => {
    const september = categoryReport('2026-09', [foodNoLimit(), transportLimit()], { totalAmount: '9.00' })
    const mock = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [reportRoute('2026-09')]: json(september),
      [LIMIT_PUT_ROUTE]: json(budgetLimit({ month: '2026-09', category: categorySummary(TRANSPORT) })),
      [limitsRoute('2026-09')]: json([budgetLimit({ id: 3, month: '2026-09', category: categorySummary(TRANSPORT) })]),
      [limitDeleteRoute(3)]: noContent(),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table', { name: 'Report for 2026-10' })

    await user.click(screen.getByRole('button', { name: 'Previous month' }))
    await screen.findByRole('table', { name: 'Report for 2026-09' })

    await typeInto(user, 'Transport', '60')
    await user.click(setIn('Transport'))
    await screen.findByText('Limit set for Transport.')
    await waitFor(() => expect(sent(mock)).toHaveLength(4))
    // The row buttons stay disabled until the reload has settled; a click on a disabled button is ignored.
    await waitFor(() => expect(clearIn('Transport')).toBeEnabled())

    await user.click(clearIn('Transport'))
    await screen.findByText('Limit cleared for Transport.')
    await waitFor(() => expect(sent(mock)).toHaveLength(7))

    expect(sent(mock)).toEqual([
      reportRoute('2026-10'),
      reportRoute('2026-09'),
      LIMIT_PUT_ROUTE,
      reportRoute('2026-09'),
      limitsRoute('2026-09'),
      limitDeleteRoute(3),
      reportRoute('2026-09'),
    ])
    expect(requests(mock)[2].body).toEqual({ categoryId: 2, month: '2026-09', amount: '60' })
  })

  it('LM3: displayedLimitComesFromReloadNotFromPutResponse', async () => {
    const reloaded = categoryReport(
      '2026-10',
      [
        reportRow({
          category: categorySummary(FOOD),
          amount: '10.00',
          share: '60.0',
          limit: '222.22',
          remaining: '-5.00',
        }),
        transportLimit(),
      ],
      { totalAmount: '15.00' },
    )
    mockFetch({
      [reportRoute('2026-10')]: [json(october()), json(reloaded)],
      [LIMIT_PUT_ROUTE]: json(budgetLimit({ amount: '111.11' })),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')

    await typeInto(user, 'Food', '111.11')
    await user.click(setIn('Food'))

    await waitFor(() => expect(cellTexts(rowOf('Food')).slice(2, 4)).toEqual(['222.22', '-5.00']))
    expect(rowOf('Food')).toHaveClass('over-limit')
    expect(input('Food')).toHaveValue('222.22')
    expect(screen.queryByText('111.11')).toBeNull()
  })

  it('LM4: clearLimitResolvesIdAndDeletes', async () => {
    const reloaded = categoryReport('2026-10', [foodNoLimit(), reportRow({ category: categorySummary(TRANSPORT), amount: '5.00', share: '40.0' })], {
      totalAmount: '15.00',
    })
    const mock = mockFetch({
      [reportRoute('2026-10')]: [json(october()), json(reloaded)],
      [limitsRoute('2026-10')]: json([
        budgetLimit({ id: 5, category: categorySummary(FOOD) }),
        budgetLimit({ id: 7, category: categorySummary(TRANSPORT) }),
      ]),
      [limitDeleteRoute(7)]: noContent(),
    })
    const user = userEvent.setup()
    const a = render(<MonthReportPage />)
    await screen.findByRole('table')

    await user.click(clearIn('Transport'))
    expect(await screen.findByText('Limit cleared for Transport.')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Limit cleared for Transport.')
    await waitFor(() => expect(cellTexts(rowOf('Transport')).slice(2, 4)).toEqual(['No limit', '—']))
    expect(input('Transport')).toHaveValue('')
    expect(clearIn('Transport')).toBeDisabled()

    expect(sent(mock)).toEqual([
      reportRoute('2026-10'),
      limitsRoute('2026-10'),
      limitDeleteRoute(7),
      reportRoute('2026-10'),
    ])
    const del = requests(mock)[2]
    expect(del.body).toBeUndefined()
    expect(del.headers.has('Content-Type')).toBe(false)
    a.unmount()

    // (b) a row with no expenses that had a limit disappears after the clear.
    const withGifts = categoryReport(
      '2026-10',
      [foodNoLimit(), reportRow({ category: GIFTS, amount: '0.00', share: '0.0', limit: '30.00', remaining: '30.00' })],
      { totalAmount: '10.00' },
    )
    mockFetch({
      [reportRoute('2026-10')]: [json(withGifts), json(categoryReport('2026-10', [foodNoLimit()], { totalAmount: '10.00' }))],
      [limitsRoute('2026-10')]: json([budgetLimit({ id: 4, category: GIFTS })]),
      [limitDeleteRoute(4)]: noContent(),
    })
    render(<MonthReportPage />)
    await screen.findByRole('rowheader', { name: 'Gifts' })
    await user.click(clearIn('Gifts'))
    await waitFor(() => expect(screen.queryByRole('rowheader', { name: 'Gifts' })).toBeNull())
    expect(screen.getByRole('rowheader', { name: 'Food' })).toBeInTheDocument()
  })

  it('LM5: clearWhenLimitIsAlreadyGone', async () => {
    const gone = 'This limit no longer exists. The report has been refreshed.'
    const user = userEvent.setup()

    // (a) the month has no limits any more
    const mockA = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [limitsRoute('2026-10')]: json([]),
    })
    const a = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await screen.findByRole('alert')).toHaveTextContent(gone)
    await waitFor(() => expect(sent(mockA)).toHaveLength(3))
    await wait()
    expect(sent(mockA)).toEqual([reportRoute('2026-10'), limitsRoute('2026-10'), reportRoute('2026-10')])
    expect(screen.getByRole('alert')).toHaveTextContent(gone)
    a.unmount()

    // (b) DELETE answers 404
    const mockB = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [limitsRoute('2026-10')]: json([budgetLimit({ id: 7, category: categorySummary(TRANSPORT) })]),
      [limitDeleteRoute(7)]: problem(404, 'Budget limit not found'),
    })
    render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await screen.findByRole('alert')).toHaveTextContent(gone)
    await waitFor(() => expect(sent(mockB)).toHaveLength(4))
    expect(sent(mockB)).toEqual([
      reportRoute('2026-10'),
      limitsRoute('2026-10'),
      limitDeleteRoute(7),
      reportRoute('2026-10'),
    ])
    expect(screen.getByRole('alert')).toHaveTextContent(gone)
  })

  it('LM6: setValidationErrorsShownNextToRowInput', async () => {
    const mock = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [LIMIT_PUT_ROUTE]: [
        problem(400, 'Validation failed', [{ field: 'amount', message: 'must be greater than 0' }]),
        problem(400, 'Validation failed', [{ field: 'amount', message: 'must not be null' }]),
        problem(400, 'Validation failed', [
          { field: 'amount', message: 'numeric value out of bounds' },
          { field: 'amount', message: 'must be less than or equal to 9999999999.99' },
        ]),
        problem(400, 'Validation failed', [{ field: 'categoryId', message: 'Category not found' }]),
      ],
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')
    const puts = () => requests(mock).filter((r) => r.method === 'PUT')

    // (a)
    await typeInto(user, 'Food', '0')
    await user.click(setIn('Food'))
    await waitFor(() => expect(input('Food')).toHaveAttribute('aria-invalid', 'true'))
    expect(input('Food')).toHaveAccessibleDescription('must be greater than 0')
    expect(input('Food')).toHaveValue('0')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(sent(mock)).toEqual([reportRoute('2026-10'), LIMIT_PUT_ROUTE])
    expect(input('Transport')).not.toHaveAttribute('aria-invalid', 'true')

    // (b)
    await typeInto(user, 'Food', '')
    await user.click(setIn('Food'))
    await waitFor(() => expect(input('Food')).toHaveAccessibleDescription('must not be null'))
    expect(puts()[1].body).toEqual({ categoryId: 1, month: '2026-10', amount: null })
    expect(input('Food')).toHaveAttribute('aria-invalid', 'true')

    // (c)
    await typeInto(user, 'Food', '99999999999')
    await user.click(setIn('Food'))
    await waitFor(() =>
      expect(input('Food')).toHaveAccessibleDescription(
        'numeric value out of bounds; must be less than or equal to 9999999999.99',
      ),
    )

    // (d)
    await user.click(setIn('Food'))
    await waitFor(() =>
      expect(within(rowOf('Food')).getByRole('alert')).toHaveTextContent('categoryId: Category not found'),
    )
    expect(input('Food')).not.toHaveAttribute('aria-invalid', 'true')
    expect(sent(mock)).toEqual([reportRoute('2026-10'), ...Array(4).fill(LIMIT_PUT_ROUTE)])
  })

  it('LM7: setConflictAndUnreadableBodyShownInRow', async () => {
    const third = deferred<Response>()
    const mock = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [LIMIT_PUT_ROUTE]: [
        problem(409, 'Category is archived'),
        problem(400, 'Failed to read request'),
        third.promise,
      ],
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')

    // (a)
    await typeInto(user, 'Food', '5')
    await user.click(setIn('Food'))
    expect(await within(rowOf('Food')).findByRole('alert')).toHaveTextContent(
      'Could not set the limit: Category is archived',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(sent(mock)).toEqual([reportRoute('2026-10'), LIMIT_PUT_ROUTE])

    // (b)
    await typeInto(user, 'Food', '12,50')
    await user.click(setIn('Food'))
    await waitFor(() =>
      expect(within(rowOf('Food')).getByRole('alert')).toHaveTextContent(
        'Could not set the limit: Failed to read request',
      ),
    )
    expect(requests(mock)[2].body).toEqual({ categoryId: 1, month: '2026-10', amount: '12,50' })

    // (c) the row's alert is cleared before the new reply arrives
    await user.click(setIn('Food'))
    await waitFor(() => expect(sent(mock)).toHaveLength(4))
    expect(within(rowOf('Food')).queryByRole('alert')).toBeNull()
    third.resolve(problem(409, 'Category is archived')())
    expect(await within(rowOf('Food')).findByRole('alert')).toHaveTextContent('Category is archived')
    expect(sent(mock)).toHaveLength(4)
  })

  it('LM8: networkServerAndUnexpectedErrorsArePageLevel', async () => {
    const mock = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [LIMIT_PUT_ROUTE]: [new TypeError('Failed to fetch'), problem(500, 'Unexpected error'), rawJson('not json', 200)],
      [limitsRoute('2026-10')]: [
        problem(500, 'Unexpected error'),
        json([budgetLimit({ id: 7, category: categorySummary(TRANSPORT) })]),
      ],
      [limitDeleteRoute(7)]: new TypeError('Failed to fetch'),
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    const table = await screen.findByRole('table')

    async function expectPageAlert(text: string) {
      expect(await screen.findByText(text)).toHaveAttribute('role', 'alert')
      await wait()
      expect(screen.getAllByRole('alert')).toHaveLength(1)
      expect(within(table).queryByRole('alert')).toBeNull()
      for (const name of ['Food', 'Transport']) {
        expect(setIn(name)).toBeEnabled()
      }
      expect(clearIn('Transport')).toBeEnabled()
      expect(screen.getByRole('button', { name: 'Previous month' })).toBeEnabled()
    }

    await typeInto(user, 'Food', '250')
    await user.click(setIn('Food'))
    await expectPageAlert(NETWORK)
    expect(input('Food')).toHaveValue('250')

    await user.click(setIn('Food'))
    await expectPageAlert('Server error (500): Unexpected error')
    expect(input('Food')).toHaveValue('250')

    await user.click(setIn('Food'))
    await expectPageAlert('Unexpected error. Please try again.')
    expect(input('Food')).toHaveValue('250')

    await user.click(clearIn('Transport'))
    await expectPageAlert('Server error (500): Unexpected error')
    expect(input('Transport')).toHaveValue('50.00')

    await user.click(clearIn('Transport'))
    await expectPageAlert(NETWORK)
    expect(input('Transport')).toHaveValue('50.00')

    expect(sent(mock)).toEqual([
      reportRoute('2026-10'),
      LIMIT_PUT_ROUTE,
      LIMIT_PUT_ROUTE,
      LIMIT_PUT_ROUTE,
      limitsRoute('2026-10'),
      limitsRoute('2026-10'),
      limitDeleteRoute(7),
    ])
  })

  it('LM9: limitChangesAreSerialised', async () => {
    const put = deferred<Response>()
    const del = deferred<Response>()
    const reload1 = deferred<Response>()
    const reload2 = deferred<Response>()
    const mock = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reload1.promise, reload2.promise],
      [LIMIT_PUT_ROUTE]: put.promise,
      [limitsRoute('2026-10')]: json([budgetLimit({ id: 7, category: categorySummary(TRANSPORT) })]),
      [limitDeleteRoute(7)]: del.promise,
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')

    const previous = () => screen.getByRole('button', { name: 'Previous month' })
    const next = () => screen.getByRole('button', { name: 'Next month' })
    const rowButtonsDisabled = () => {
      for (const button of screen.getAllByRole('button', { name: 'Set limit' })) {
        expect(button).toBeDisabled()
      }
      for (const button of screen.getAllByRole('button', { name: 'Clear limit' })) {
        expect(button).toBeDisabled()
      }
    }
    const puts = () => requests(mock).filter((r) => r.method === 'PUT')

    // (a) PUT in flight: everything is disabled.
    await typeInto(user, 'Food', '250')
    await user.click(setIn('Food'))
    await waitFor(() => expect(previous()).toBeDisabled())
    rowButtonsDisabled()
    expect(next()).toBeDisabled()
    await user.click(setIn('Food'))
    await user.click(setIn('Transport'))
    expect(puts()).toHaveLength(1)

    // PUT resolved, reload pending: row buttons stay disabled, month buttons are enabled.
    put.resolve(json(budgetLimit())())
    await waitFor(() => expect(previous()).toBeEnabled())
    await waitFor(() => expect(sent(mock)).toEqual([reportRoute('2026-10'), LIMIT_PUT_ROUTE, reportRoute('2026-10')]))
    expect(next()).toBeEnabled()
    rowButtonsDisabled()

    reload1.resolve(json(october())())
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(setIn('Transport')).toBeEnabled()
    expect(clearIn('Food')).toBeDisabled()
    expect(clearIn('Transport')).toBeEnabled()
    expect(puts()).toHaveLength(1)

    // (b) DELETE in flight: everything is disabled.
    await user.click(clearIn('Transport'))
    await waitFor(() => expect(sent(mock).includes(limitDeleteRoute(7))).toBe(true))
    rowButtonsDisabled()
    expect(previous()).toBeDisabled()
    expect(next()).toBeDisabled()

    del.resolve(noContent()())
    await waitFor(() => expect(previous()).toBeEnabled())
    await waitFor(() => expect(sent(mock).filter((r) => r === reportRoute('2026-10'))).toHaveLength(3))
    expect(next()).toBeEnabled()
    rowButtonsDisabled()

    reload2.resolve(json(october())())
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(setIn('Transport')).toBeEnabled()
    expect(clearIn('Transport')).toBeEnabled()
  })

  it('LM10: successfulReloadResetsEveryRowForm', async () => {
    const rows = (giftsLimit: string | null) => [
      transportLimit(),
      foodNoLimit(),
      reportRow({
        category: GIFTS,
        amount: '1.00',
        share: '5.0',
        limit: giftsLimit,
        remaining: giftsLimit === null ? null : '249.00',
      }),
    ]
    mockFetch({
      [reportRoute('2026-10')]: [
        json(categoryReport('2026-10', rows(null), { totalAmount: '16.00' })),
        json(categoryReport('2026-10', rows('250.00'), { totalAmount: '16.00' })),
      ],
      [LIMIT_PUT_ROUTE]: [
        problem(400, 'Validation failed', [{ field: 'amount', message: 'must be greater than 0' }]),
        json(budgetLimit({ amount: '250.00', category: GIFTS })),
      ],
    })
    const user = userEvent.setup()
    render(<MonthReportPage />)
    await screen.findByRole('table')

    await typeInto(user, 'Transport', '0')
    await user.click(setIn('Transport'))
    await waitFor(() => expect(input('Transport')).toHaveAccessibleDescription('must be greater than 0'))

    await typeInto(user, 'Food', '77')
    await typeInto(user, 'Gifts', '250')
    await user.click(setIn('Gifts'))

    await waitFor(() => expect(input('Gifts')).toHaveValue('250.00'))
    expect(input('Transport')).toHaveValue('50.00')
    expect(input('Transport')).not.toHaveAttribute('aria-invalid', 'true')
    expect(input('Transport')).not.toHaveAccessibleDescription('must be greater than 0')
    expect(within(rowOf('Transport')).queryByRole('alert')).toBeNull()
    expect(input('Food')).toHaveValue('')
  })

  it('LM11: rowButtonsStayDisabledUntilReloadSettles', async () => {
    const user = userEvent.setup()
    const allRowButtons = () => [
      ...screen.getAllByRole('button', { name: 'Set limit' }),
      ...screen.getAllByRole('button', { name: 'Clear limit' }),
    ]
    const expectRowButtonsDisabled = () => {
      for (const button of allRowButtons()) {
        expect(button).toBeDisabled()
      }
    }
    const previous = () => screen.getByRole('button', { name: 'Previous month' })
    const next = () => screen.getByRole('button', { name: 'Next month' })

    // (a) after a successful Set with a pending reload
    const reload = deferred<Response>()
    const mockA = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reload.promise],
      [LIMIT_PUT_ROUTE]: json(budgetLimit()),
    })
    const a = render(<MonthReportPage />)
    await screen.findByRole('table')
    await typeInto(user, 'Food', '250')
    await user.click(setIn('Food'))
    await screen.findByText('Limit set for Food.')
    await waitFor(() => expect(sent(mockA)).toHaveLength(3))
    expectRowButtonsDisabled()
    expect(previous()).toBeEnabled()
    expect(next()).toBeEnabled()
    await user.click(setIn('Transport'))
    expect(requests(mockA).filter((r) => r.method === 'PUT')).toHaveLength(1)
    reload.resolve(json(october())())
    await waitFor(() => expect(setIn('Transport')).toBeEnabled())
    expect(setIn('Food')).toBeEnabled()
    expect(clearIn('Transport')).toBeEnabled()
    a.unmount()

    // (b) a failing reload does not leave the buttons stuck
    const reloadB = deferred<Response>()
    const mockB = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reloadB.promise],
      [LIMIT_PUT_ROUTE]: json(budgetLimit()),
    })
    const b = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(setIn('Food'))
    await waitFor(() => expect(sent(mockB)).toHaveLength(3))
    expectRowButtonsDisabled()
    reloadB.resolve(problem(500, 'Unexpected error')())
    expect(await screen.findByRole('alert')).toHaveTextContent('Server error (500): Unexpected error')
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(setIn('Transport')).toBeEnabled()
    expect(clearIn('Transport')).toBeEnabled()
    b.unmount()

    // (c) a month change while the reload is pending
    const reloadC = deferred<Response>()
    const mockC = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reloadC.promise],
      [reportRoute('2026-09')]: json(categoryReport('2026-09', [foodNoLimit(), transportLimit()], { totalAmount: '9.00' })),
      [LIMIT_PUT_ROUTE]: json(budgetLimit()),
    })
    const c = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(setIn('Food'))
    await waitFor(() => expect(sent(mockC)).toHaveLength(3))
    expectRowButtonsDisabled()
    await user.click(previous())
    await screen.findByRole('table', { name: 'Report for 2026-09' })
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(setIn('Transport')).toBeEnabled()
    expect(clearIn('Transport')).toBeEnabled()
    c.unmount()

    // (d) the "limit gone" refresh
    const reloadD = deferred<Response>()
    const mockD = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reloadD.promise],
      [limitsRoute('2026-10')]: json([]),
    })
    const d = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await screen.findByRole('alert')).toHaveTextContent('This limit no longer exists.')
    await waitFor(() => expect(sent(mockD)).toHaveLength(3))
    expectRowButtonsDisabled()
    reloadD.resolve(json(october())())
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(clearIn('Transport')).toBeEnabled()
    d.unmount()

    // (e) the DELETE answers 404 (outcome.refresh path)
    const reloadE = deferred<Response>()
    const mockE = mockFetch({
      [reportRoute('2026-10')]: [json(october()), reloadE.promise],
      [limitsRoute('2026-10')]: json([budgetLimit({ id: 7, category: categorySummary(TRANSPORT) })]),
      [limitDeleteRoute(7)]: problem(404, 'Budget limit not found'),
    })
    render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This limit no longer exists. The report has been refreshed.',
    )
    await waitFor(() => expect(sent(mockE)).toHaveLength(4))
    expectRowButtonsDisabled()
    expect(previous()).toBeEnabled()
    expect(next()).toBeEnabled()
    reloadE.resolve(json(october())())
    await waitFor(() => expect(setIn('Food')).toBeEnabled())
    expect(setIn('Transport')).toBeEnabled()
    expect(clearIn('Transport')).toBeEnabled()
  })

  it('LM12: clearRowErrorsAndMixedSetErrors', async () => {
    const user = userEvent.setup()

    // (a) the limits GET fails with a non-404 4xx
    const mockA = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [limitsRoute('2026-10')]: problem(409, 'Conflict detail'),
    })
    const a = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await within(rowOf('Transport')).findByRole('alert')).toHaveTextContent(
      'Could not clear the limit: Conflict detail',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    await waitFor(() => expect(clearIn('Transport')).toBeEnabled())
    expect(sent(mockA)).toEqual([reportRoute('2026-10'), limitsRoute('2026-10')])
    a.unmount()

    // (b) the DELETE fails with a non-404 4xx
    const mockB = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [limitsRoute('2026-10')]: json([budgetLimit({ id: 7, category: categorySummary(TRANSPORT) })]),
      [limitDeleteRoute(7)]: problem(409, 'Delete conflict'),
    })
    const b = render(<MonthReportPage />)
    await screen.findByRole('table')
    await user.click(clearIn('Transport'))
    expect(await within(rowOf('Transport')).findByRole('alert')).toHaveTextContent(
      'Could not clear the limit: Delete conflict',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(sent(mockB)).toEqual([reportRoute('2026-10'), limitsRoute('2026-10'), limitDeleteRoute(7)])
    b.unmount()

    // (c) a PUT 400 with errors for amount and another field
    const mockC = mockFetch({
      [reportRoute('2026-10')]: json(october()),
      [LIMIT_PUT_ROUTE]: problem(400, 'Validation failed', [
        { field: 'amount', message: 'must be greater than 0' },
        { field: 'categoryId', message: 'Category not found' },
      ]),
    })
    render(<MonthReportPage />)
    await screen.findByRole('table')
    await typeInto(user, 'Food', '0')
    await user.click(setIn('Food'))
    expect(await within(rowOf('Food')).findByRole('alert')).toHaveTextContent('categoryId: Category not found')
    expect(input('Food')).toHaveAttribute('aria-invalid', 'true')
    expect(input('Food')).toHaveAccessibleDescription('must be greater than 0')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(sent(mockC)).toEqual([reportRoute('2026-10'), LIMIT_PUT_ROUTE])
  })
})
