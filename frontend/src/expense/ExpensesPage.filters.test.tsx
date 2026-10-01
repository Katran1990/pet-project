import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { deferred, json, mockFetch, noContent, problem, requests } from '../test/fetchMock.ts'
import { CATEGORIES_ROUTE, FOOD, OLD, TEMPLATES_ROUTE, TRANSPORT, baseRoutes, expense, list, quickTemplate } from '../test/fixtures.ts'
import { ExpensesPage } from './ExpensesPage.tsx'

// Unless a test says otherwise, the categories route returns [FOOD, TRANSPORT, OLD], so the
// archived category is visible in the filter (decision 9).
function routesWithOld() {
  return { ...baseRoutes(), [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT, OLD]) }
}

function buildItems(count: number, startId: number) {
  return Array.from({ length: count }, (_, i) => expense({ id: startId + i }))
}

function checkboxLabel(checkbox: Element): string {
  return checkbox.closest('label')?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
}

describe('ExpensesPage filters', () => {
  it('F1: defaultViewSendsNoFilterParams', async () => {
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })

    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    const sent = requests(mock).map((entry) => `${entry.method} ${entry.url}`)
    expect(sent).toEqual([CATEGORIES_ROUTE, TEMPLATES_ROUTE, 'GET /api/expenses?page=0&size=50'])

    expect(screen.getByLabelText('From')).toHaveValue('')
    expect(screen.getByLabelText('To')).toHaveValue('')

    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes.map((checkbox) => checkboxLabel(checkbox))).toEqual(['Food', 'Transport', 'Old (archived)'])
    for (const checkbox of checkboxes) {
      expect(checkbox).not.toBeChecked()
    }
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled()

    expect(screen.getByRole('heading', { name: 'This month' })).toBeInTheDocument()
    expect(window.location.search).toBe('')

    const options = screen.getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Select a category', 'Food', 'Transport'])
  })

  it('F2: everyChangeIssuesRequestWithFilters', async () => {
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '1.00' })),
      'GET /api/expenses?from=2026-09-01&page=0&size=50': json(list([], { totalAmount: '2.00' })),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&page=0&size=50': json(list([], { totalAmount: '3.00' })),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=2&page=0&size=50': json(
        list([], { totalAmount: '4.00' }),
      ),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=1%2C2&page=0&size=50': json(
        list([], { totalAmount: '5.00' }),
      ),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=1&page=0&size=50': json(
        list([], { totalAmount: '6.00' }),
      ),
      'GET /api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50': json(list([], { totalAmount: '7.00' })),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1.00')

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-01' } })
    await screen.findByText('2.00')
    expect(window.location.search).toBe('?from=2026-09-01')

    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-09-30' } })
    await screen.findByText('3.00')
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30')

    await user.click(screen.getByRole('checkbox', { name: 'Transport' }))
    await screen.findByText('4.00')
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30&categoryIds=2')

    await user.click(screen.getByRole('checkbox', { name: 'Food' }))
    await screen.findByText('5.00')
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30&categoryIds=1%2C2')

    await user.click(screen.getByRole('checkbox', { name: 'Transport' }))
    await screen.findByText('6.00')
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30&categoryIds=1')

    fireEvent.change(screen.getByLabelText('To'), { target: { value: '' } })
    await screen.findByText('7.00')
    expect(window.location.search).toBe('?from=2026-09-01&categoryIds=1')

    const gets = requests(mock)
      .filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
      .map((entry) => entry.url)
    expect(gets).toEqual([
      '/api/expenses?page=0&size=50',
      '/api/expenses?from=2026-09-01&page=0&size=50',
      '/api/expenses?from=2026-09-01&to=2026-09-30&page=0&size=50',
      '/api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=2&page=0&size=50',
      '/api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=1%2C2&page=0&size=50',
      '/api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=1&page=0&size=50',
      '/api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50',
    ])
  })

  it('F3: noFilteringInTheBrowser', async () => {
    window.history.replaceState(null, '', '/?categoryIds=1')

    const rowFood = expense({ id: 1, spentOn: '2026-10-01' })
    const rowTransport = expense({
      id: 2,
      spentOn: '2026-10-02',
      category: { id: TRANSPORT.id, name: TRANSPORT.name, icon: TRANSPORT.icon },
    })
    const rowOld = expense({ id: 3, spentOn: '2025-01-01' })
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?categoryIds=1&page=0&size=50': json(
        list([rowFood, rowTransport, rowOld], { totalAmount: '123.45' }),
      ),
    })

    render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]!).getByText('2026-10-01')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('2026-10-02')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('2025-01-01')).toBeInTheDocument()
    expect(screen.getByText('123.45')).toBeInTheDocument()
    expect(screen.getByText('1–3 of 3')).toBeInTheDocument()
  })

  it('F4: bookmarkedViewLoadsAndSurvivesReload', async () => {
    // (a) a bookmarked filtered URL loads with exactly that filter, and the URL is normalised.
    window.history.replaceState(null, '', '/?from=2026-09-01&to=2026-09-30&categoryIds=2,9')
    const mockA = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=2%2C9&page=0&size=50': json(
        list([], { totalAmount: '1.00' }),
      ),
    })
    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('1.00')

    expect(screen.getByLabelText('From')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('To')).toHaveValue('2026-09-30')
    expect(screen.getByRole('checkbox', { name: 'Transport' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Old (archived)' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Food' })).not.toBeChecked()
    expect(screen.getByRole('heading', { name: 'Selected period' })).toBeInTheDocument()
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30&categoryIds=2%2C9')

    const getsA = requests(mockA).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(getsA[0]!.url).toBe('/api/expenses?from=2026-09-01&to=2026-09-30&categoryIds=2%2C9&page=0&size=50')
    unmount()

    // (b) a filter built through the UI survives an unmount + render (a reload).
    window.history.replaceState(null, '', '/')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'GET /api/expenses?categoryIds=1&page=0&size=50': json(list([], { totalAmount: '1.00' })),
      'GET /api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50': json(list([], { totalAmount: '2.00' })),
    })
    const user = userEvent.setup()
    const { unmount: unmountB } = render(<ExpensesPage />)
    await screen.findByText('0.00')
    await user.click(screen.getByRole('checkbox', { name: 'Food' }))
    await screen.findByText('1.00')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-01' } })
    await screen.findByText('2.00')
    expect(window.location.search).toBe('?from=2026-09-01&categoryIds=1')
    unmountB()

    const mockReload = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50': json(list([], { totalAmount: '9.00' })),
    })
    render(<ExpensesPage />)
    await screen.findByText('9.00')
    expect(screen.getByLabelText('From')).toHaveValue('2026-09-01')
    expect(screen.getByRole('checkbox', { name: 'Food' })).toBeChecked()
    const getsReload = requests(mockReload).filter(
      (entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'),
    )
    expect(getsReload[0]!.url).toBe('/api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50')
  })

  it('F5: garbageUrlParamsAreDroppedAndUrlNormalised', async () => {
    // (a) syntactically invalid or meaningless params are dropped; valid ones survive; page/size/foo are ignored.
    window.history.replaceState(
      null,
      '',
      '/?from=2026-02-30&to=yesterday&categoryIds=2,x,,0,-1,1.5,2,1&page=3&size=500&foo=bar',
    )
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?categoryIds=1%2C2&page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('0.00')

    expect(window.location.search).toBe('?categoryIds=1%2C2')
    expect(screen.getByLabelText('From')).toHaveValue('')
    expect(screen.getByLabelText('To')).toHaveValue('')
    expect(screen.getByRole('checkbox', { name: 'Food' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Transport' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Old (archived)' })).not.toBeChecked()
    unmount()

    // (b) a param with valid syntax but an invalid meaning (from after to) is sent unchanged;
    // the server's 400 is shown.
    window.history.replaceState(null, '', '/?from=2026-09-30&to=2026-09-01')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-30&to=2026-09-01&page=0&size=50': problem(400, 'Invalid request content.', [
        { field: 'from', message: 'must not be after to (2026-09-30 > 2026-09-01)' },
      ]),
    })
    render(<ExpensesPage />)
    await screen.findByText('Could not load expenses: from: must not be after to (2026-09-30 > 2026-09-01)')
  })

  it('F6: unknownCategoryIdFromUrlIsVisibleAndRemovable', async () => {
    window.history.replaceState(null, '', '/?categoryIds=9,42')
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?categoryIds=9%2C42&page=0&size=50': problem(400, 'Invalid request content.', [
        { field: 'categoryIds', message: 'Category not found: 42' },
      ]),
      'GET /api/expenses?categoryIds=9&page=0&size=50': json(
        list([expense({ id: 1, category: { id: OLD.id, name: OLD.name, icon: OLD.icon } })], { totalAmount: '10.00' }),
      ),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('Could not load expenses: categoryIds: Category not found: 42')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Old (archived)' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Category #42' })).toBeChecked()

    await user.click(screen.getByRole('checkbox', { name: 'Category #42' }))
    await screen.findByRole('table')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Category #42' })).not.toBeInTheDocument()
    expect(window.location.search).toBe('?categoryIds=9')

    expect(requests(mock).some((entry) => entry.url === '/api/expenses?categoryIds=9&page=0&size=50')).toBe(true)
  })

  it('F7: filterChangeResetsPageToZero', async () => {
    const page0 = list(buildItems(50, 1), { totalAmount: '100.00', page: 0, totalItems: 120 })
    const page1 = list(buildItems(50, 51), { totalAmount: '200.00', page: 1, totalItems: 120 })
    const filtered = list(buildItems(10, 1), { totalAmount: '10.00', page: 0, totalItems: 10 })
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(page0),
      'GET /api/expenses?page=1&size=50': json(page1),
      'GET /api/expenses?categoryIds=1&page=0&size=50': json(filtered),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 120')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–100 of 120')

    await user.click(screen.getByRole('checkbox', { name: 'Food' }))
    await screen.findByText('1–10 of 10')

    // Exactly one request per change (decision 6): no stray page=1 request survives the reset to 0.
    const gets = requests(mock)
      .filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
      .map((entry) => entry.url)
    expect(gets).toEqual([
      '/api/expenses?page=0&size=50',
      '/api/expenses?page=1&size=50',
      '/api/expenses?categoryIds=1&page=0&size=50',
    ])
  })

  it('F8: invalidRangeShowsServerErrorAndNoStaleTotal', async () => {
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'GET /api/expenses?from=2026-09-30&page=0&size=50': json(list([expense({ id: 1 })], { totalAmount: '5.00' })),
      'GET /api/expenses?from=2026-09-30&to=2026-09-01&page=0&size=50': problem(400, 'Invalid request content.', [
        { field: 'from', message: 'must not be after to (2026-09-30 > 2026-09-01)' },
      ]),
      'GET /api/expenses?from=2026-09-30&to=2026-10-15&page=0&size=50': json(
        list([expense({ id: 2 })], { totalAmount: '7.00' }),
      ),
    })

    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-09-30' } })
    const total5 = await screen.findByText('5.00')
    expect(total5.closest('p')).toHaveTextContent('Total:')

    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-09-01' } })
    await screen.findByText('Could not load expenses: from: must not be after to (2026-09-30 > 2026-09-01)')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText(/Total:/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('2026-09-30')
    expect(screen.getByLabelText('To')).toHaveValue('2026-09-01')

    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-15' } })
    await screen.findByRole('table')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('F9: latestFilterWins', async () => {
    const pendingFood = deferred<Response>()
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'GET /api/expenses?categoryIds=1&page=0&size=50': pendingFood.promise,
      'GET /api/expenses?categoryIds=1%2C2&page=0&size=50': json(list([], { totalAmount: '20.00' })),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    await user.click(screen.getByRole('checkbox', { name: 'Food' }))
    await user.click(screen.getByRole('checkbox', { name: 'Transport' }))

    await screen.findByText('20.00')

    // The superseded request's signal must actually be aborted, not merely ignored by luck.
    const supersededCall = mock.mock.calls.find(([input]) => input === '/api/expenses?categoryIds=1&page=0&size=50')
    expect((supersededCall?.[1] as RequestInit | undefined)?.signal?.aborted).toBe(true)

    pendingFood.resolve(json(list([], { totalAmount: '10.00' }))())
    // A real flush (not just a couple of microtasks): the stale reply still has to go through
    // Response.text(), JSON.parse, the effect's .then and a render before it could appear.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
    expect(screen.queryByText('10.00')).not.toBeInTheDocument()
    expect(screen.getByText('20.00')).toBeInTheDocument()
  })

  it('F10: clearFiltersRestoresDefault', async () => {
    window.history.replaceState(null, '', '/?from=2026-09-01&categoryIds=1')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-01&categoryIds=1&page=0&size=50': json(list([], { totalAmount: '1.00' })),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1.00')

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await screen.findByText('0.00')

    expect(window.location.search).toBe('')
    expect(screen.getByLabelText('From')).toHaveValue('')
    expect(screen.getByLabelText('To')).toHaveValue('')
    for (const checkbox of screen.getAllByRole('checkbox')) {
      expect(checkbox).not.toBeChecked()
    }
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled()
    expect(screen.getByRole('heading', { name: 'This month' })).toBeInTheDocument()
  })

  it('F11: labelsFollowFilters', async () => {
    // (a) a date filter with rows: "Selected period" heading and table name.
    window.history.replaceState(null, '', '/?from=2026-09-01')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-01&page=0&size=50': json(list([expense({ id: 1 })], { totalAmount: '10.00' })),
    })
    const { unmount } = render(<ExpensesPage />)
    expect(await screen.findByRole('heading', { name: 'Selected period' })).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Expenses in the selected period' })).toBeInTheDocument()
    unmount()

    // (a) same date filter, an empty result.
    window.history.replaceState(null, '', '/?from=2026-09-01')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?from=2026-09-01&page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    const { unmount: unmountEmpty } = render(<ExpensesPage />)
    await screen.findByText('No expenses match the filters.')
    unmountEmpty()

    // (b) a category-only filter, an empty result: "This month" heading, filtered empty text.
    window.history.replaceState(null, '', '/?categoryIds=1')
    mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?categoryIds=1&page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    render(<ExpensesPage />)
    expect(await screen.findByRole('heading', { name: 'This month' })).toBeInTheDocument()
    await screen.findByText('No expenses match the filters.')
  })

  it('F12: mutationsRefetchWithCurrentFiltersAndPage', async () => {
    window.history.replaceState(null, '', '/?from=2026-09-01&to=2026-09-30&categoryIds=1')
    const filterQuery = 'from=2026-09-01&to=2026-09-30&categoryIds=1'
    const page0 = list(buildItems(50, 1), { totalAmount: '10.00', page: 0, totalItems: 51 })
    const page1Initial = list([expense({ id: 51 })], { totalAmount: '20.00', page: 1, totalItems: 51 })
    const page1AfterCreate = list([expense({ id: 51 }), expense({ id: 200 })], {
      totalAmount: '30.00',
      page: 1,
      totalItems: 52,
    })
    const page1AfterEdit = list([expense({ id: 51, amount: '99.00' }), expense({ id: 200 })], {
      totalAmount: '40.00',
      page: 1,
      totalItems: 52,
    })
    const page1AfterDelete = list([expense({ id: 200 })], { totalAmount: '50.00', page: 1, totalItems: 51 })
    const page1AfterApply = list([expense({ id: 300 }), expense({ id: 200 })], {
      totalAmount: '60.00',
      page: 1,
      totalItems: 52,
    })

    const template = quickTemplate()
    const mock = mockFetch({
      ...routesWithOld(),
      [TEMPLATES_ROUTE]: json([template]),
      [`GET /api/expenses?${filterQuery}&page=0&size=50`]: json(page0),
      [`GET /api/expenses?${filterQuery}&page=1&size=50`]: [
        json(page1Initial),
        json(page1AfterCreate),
        json(page1AfterEdit),
        json(page1AfterDelete),
        json(page1AfterApply),
      ],
      'POST /api/expenses': json(expense({ id: 200 }), 201),
      'PUT /api/expenses/51': json(expense({ id: 51, amount: '99.00' })),
      'DELETE /api/expenses/51': noContent(),
      [`POST /api/quick-templates/${template.id}/apply`]: json(expense({ id: 300 }), 201),
    })

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 51')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–51 of 51')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await screen.findByText('30.00')

    let table = screen.getByRole('table')
    let rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[0]!).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await screen.findByText('40.00')

    table = screen.getByRole('table')
    rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[0]!).getByRole('button', { name: 'Delete' }))
    await screen.findByText('50.00')

    await user.click(screen.getByRole('button', { name: `${template.name} ${template.amount}` }))
    await screen.findByText('60.00')

    const page1Gets = requests(mock).filter(
      (entry) => entry.method === 'GET' && entry.url === `/api/expenses?${filterQuery}&page=1&size=50`,
    )
    expect(page1Gets).toHaveLength(5)
  })

  it('P1: filteredPagination', async () => {
    window.history.replaceState(null, '', '/?categoryIds=2')
    const page0 = list(buildItems(50, 1), { totalAmount: '100.00', page: 0, totalItems: 120 })
    const page1 = list(buildItems(50, 51), { totalAmount: '200.00', page: 1, totalItems: 120 })
    const mock = mockFetch({
      ...routesWithOld(),
      'GET /api/expenses?categoryIds=2&page=0&size=50': json(page0),
      'GET /api/expenses?categoryIds=2&page=1&size=50': json(page1),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 120')
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByText('100.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–100 of 120')
    expect(screen.getByText('200.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    await screen.findByText('1–50 of 120')
    expect(screen.getByText('100.00')).toBeInTheDocument()

    const gets = requests(mock)
      .filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
      .map((entry) => entry.url)
    expect(gets).toEqual([
      '/api/expenses?categoryIds=2&page=0&size=50',
      '/api/expenses?categoryIds=2&page=1&size=50',
      '/api/expenses?categoryIds=2&page=0&size=50',
    ])
  })

  it('P2: pagerUsesPageAndSizeFromResponse', async () => {
    const page0 = list(buildItems(20, 1), { totalAmount: '1.00', page: 0, size: 20, totalItems: 45 })
    const page1 = list(buildItems(20, 21), { totalAmount: '2.00', page: 1, size: 20, totalItems: 45 })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(page0),
      'GET /api/expenses?page=1&size=50': json(page1),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–20 of 45')
    expect(screen.getByRole('button', { name: 'Next' })).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('21–40 of 45')
  })

  it('P3: pagerRetriesPageAfterFailedLoad', async () => {
    const page0 = list(buildItems(50, 1), { totalAmount: '100.00', page: 0, totalItems: 120 })
    const page1 = list(buildItems(50, 51), { totalAmount: '200.00', page: 1, totalItems: 120 })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(page0),
      // The first load of page 1 fails; the second (the retry) succeeds.
      'GET /api/expenses?page=1&size=50': [problem(500, 'Unexpected error'), json(page1)],
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 120')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Server error (500): Unexpected error')
    // The page-0 list is still displayed: a failed load for the target page is not a blank screen.
    expect(screen.getByText('1–50 of 120')).toBeInTheDocument()
    expect(screen.getByText('100.00')).toBeInTheDocument()

    // Next again: since `page` already equals the (failed) target, this is no longer a no-op;
    // it retries the same page instead of being stuck.
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–100 of 120')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    const gets = requests(mock)
      .filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
      .map((entry) => entry.url)
    expect(gets).toEqual([
      '/api/expenses?page=0&size=50',
      '/api/expenses?page=1&size=50',
      '/api/expenses?page=1&size=50',
    ])
  })

  it('P4: doubleNextDuringLoadDoesNotSkipPage', async () => {
    const page0 = list(buildItems(50, 1), { totalAmount: '100.00', page: 0, totalItems: 120 })
    const page1 = list(buildItems(50, 51), { totalAmount: '200.00', page: 1, totalItems: 120 })
    // The first page-1 load never settles on its own; it is superseded (aborted) by the second
    // click before it could resolve. The second page-1 load is the one that actually completes.
    const stuckPage1 = deferred<Response>()
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(page0),
      'GET /api/expenses?page=1&size=50': [stuckPage1.promise, json(page1)],
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 120')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    // A second click while the page-1 load is still pending: `list` still shows page 0, so the
    // target is still page 1, not page 2.
    await user.click(screen.getByRole('button', { name: 'Next' }))

    await screen.findByText('51–100 of 120')

    const expenseCalls = mock.mock.calls.filter((call) => String(call[0]).startsWith('/api/expenses'))
    // No request for page=2 is ever sent; page=1 is requested twice (the second click forces a
    // retry because the target already equals the current page).
    expect(expenseCalls.map((call) => String(call[0]))).toEqual([
      '/api/expenses?page=0&size=50',
      '/api/expenses?page=1&size=50',
      '/api/expenses?page=1&size=50',
    ])
    // The first page-1 request was actually aborted, not just superseded by luck.
    expect(expenseCalls[1]![1]?.signal?.aborted).toBe(true)
    expect(expenseCalls[2]![1]?.signal?.aborted).toBe(false)
  })
})
