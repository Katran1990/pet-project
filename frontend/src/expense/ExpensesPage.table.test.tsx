import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { deferred, json, mockFetch, noContent, problem, requests } from '../test/fetchMock.ts'
import { CATEGORIES_ROUTE, FOOD, TRANSPORT, baseRoutes, expense, list } from '../test/fixtures.ts'
import { localIsoDate } from './localDate.ts'
import { ExpensesPage } from './ExpensesPage.tsx'

function buildItems(count: number, startId: number) {
  return Array.from({ length: count }, (_, i) => expense({ id: startId + i }))
}

describe('ExpensesPage table', () => {
  it('E13: tableHasColumnsAndRowsFromApi', async () => {
    const rowWithNote = expense({ id: 1, amount: '200.00', spentOn: '2026-10-01', note: 'Lunch' })
    const rowWithoutNote = expense({
      id: 2,
      amount: '5.00',
      spentOn: '2026-09-29',
      note: null,
      category: { id: TRANSPORT.id, name: TRANSPORT.name, icon: TRANSPORT.icon },
    })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([rowWithNote, rowWithoutNote], { totalAmount: '205.00' })),
    })

    render(<ExpensesPage />)
    const table = await screen.findByRole('table', { name: 'Expenses this month' })

    const headers = within(table).getAllByRole('columnheader').map((header) => header.textContent)
    expect(headers).toEqual(['Date', 'Category', 'Amount', 'Note', 'Actions'])

    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)

    const firstCells = within(rows[0])
      .getAllByRole('cell')
      .map((cell) => cell.textContent)
    expect(firstCells.slice(0, 4)).toEqual(['2026-10-01', 'Food', '200.00 PLN', 'Lunch'])

    const secondCells = within(rows[1]).getAllByRole('cell')
    expect(secondCells[3].textContent).toBe('')

    for (const row of rows) {
      expect(within(row).getByRole('button', { name: 'Edit' })).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Delete' })).toBeInTheDocument()
    }
  })

  it('E14: editPrefillsFormAndPutsAllFields', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))
    const today = localIsoDate(new Date())

    const row1 = expense({ id: 1, amount: '10.00', spentOn: '2026-10-01', note: 'A' })
    const row2 = expense({
      id: 2,
      amount: '12.50',
      spentOn: '2026-09-28',
      note: 'B',
      category: { id: TRANSPORT.id, name: TRANSPORT.name, icon: TRANSPORT.icon },
    })
    const firstList = list([row1, row2], { totalAmount: '22.50' })
    const secondList = list([row1, { ...row2, amount: '15.00' }], { totalAmount: '25.00' })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [json(firstList), json(secondList)],
      'PUT /api/expenses/2': json({ ...row2, amount: '15.00' }),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[1]).getByRole('button', { name: 'Edit' }))

    await screen.findByRole('heading', { name: 'Edit expense' })
    expect(screen.getByLabelText('Amount')).toHaveValue('12.50')
    expect(screen.getByLabelText('Category')).toHaveValue(String(TRANSPORT.id))
    expect(screen.getByLabelText('Date')).toHaveValue('2026-09-28')
    expect(screen.getByLabelText('Note')).toHaveValue('B')
    expect(screen.getByLabelText('Amount')).toHaveFocus()

    await user.clear(screen.getByLabelText('Amount'))
    await user.type(screen.getByLabelText('Amount'), '15')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await screen.findByText('Expense updated.')

    const put = requests(mock).find((entry) => entry.method === 'PUT')!
    expect(put.url).toBe('/api/expenses/2')
    expect(put.body).toEqual({ amount: '15', categoryId: TRANSPORT.id, spentOn: '2026-09-28', note: 'B' })

    await screen.findByRole('heading', { name: 'New expense' })
    expect(screen.getByLabelText('Amount')).toHaveValue('')
    expect(screen.getByLabelText('Category')).toHaveValue('')
    expect(screen.getByLabelText('Date')).toHaveValue(today)
    expect(screen.getByLabelText('Note')).toHaveValue('')

    const listGets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGets).toHaveLength(2)
  })

  it('E15: editKeepsArchivedCategory', async () => {
    // (a) the current (archived) category is kept as an extra, preselected option.
    const oldRow = expense({ id: 1, category: { id: 9, name: 'Old', icon: null } })
    const mockA = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([oldRow], { totalAmount: '10.00' })),
        json(list([oldRow], { totalAmount: '10.00' })),
      ],
      'PUT /api/expenses/1': json(oldRow),
    })

    const user = userEvent.setup()
    const { unmount } = render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })

    const categorySelect = screen.getByLabelText('Category')
    expect(within(categorySelect).getByText('Old (archived)')).toBeInTheDocument()
    expect(categorySelect).toHaveValue('9')

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await screen.findByText('Expense updated.')
    const put = requests(mockA).find((entry) => entry.method === 'PUT')!
    expect(put.body).toMatchObject({ categoryId: 9 })

    const table2 = await screen.findByRole('table')
    await user.click(within(table2).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await screen.findByRole('heading', { name: 'New expense' })
    expect(screen.queryByText('Old (archived)')).not.toBeInTheDocument()
    unmount()

    // (b) categories failed to load: no "(archived)" suffix, and only the current category is offered.
    const foodRow = expense({ id: 2 })
    mockFetch({
      ...baseRoutes(),
      [CATEGORIES_ROUTE]: problem(500, 'Unexpected error'),
      'GET /api/expenses?page=0&size=50': json(list([foodRow], { totalAmount: '10.00' })),
    })
    render(<ExpensesPage />)
    const table3 = await screen.findByRole('table')
    await user.click(within(table3).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })

    const options = screen.getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Select a category', 'Food'])
    expect(screen.getByLabelText('Category')).toHaveValue(String(FOOD.id))
    expect(options.some((option) => option?.includes('(archived)'))).toBe(false)
  })

  it('E16: editShowsConflictAndValidationErrorsNextToFields', async () => {
    const row = expense({ id: 1 })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([row], { totalAmount: '10.00' })),
      'PUT /api/expenses/1': [
        problem(409, 'Category is archived'),
        problem(400, 'Invalid request content.', [{ field: 'amount', message: 'must be greater than 0' }]),
      ],
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })

    await user.clear(screen.getByLabelText('Amount'))
    await user.type(screen.getByLabelText('Amount'), '25.00')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByLabelText('Category')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('Category is archived')
    expect(screen.getByRole('heading', { name: 'Edit expense' })).toBeInTheDocument()
    // The edited amount survives the 409: the form keeps the user's values.
    expect(screen.getByLabelText('Amount')).toHaveValue('25.00')

    await user.clear(screen.getByLabelText('Amount'))
    await user.type(screen.getByLabelText('Amount'), '30.00')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.getByLabelText('Amount')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Amount')).toHaveAccessibleDescription('must be greater than 0')
    expect(screen.getByRole('heading', { name: 'Edit expense' })).toBeInTheDocument()
    // The edited amount survives the 400 too.
    expect(screen.getByLabelText('Amount')).toHaveValue('30.00')
  })

  it('E17: editOfDeletedExpenseShowsPageMessageAndRefreshes', async () => {
    const row = expense({ id: 1 })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([row], { totalAmount: '10.00' })),
        json(list([], { totalAmount: '0.00' })),
      ],
      'PUT /api/expenses/1': problem(404, 'Expense not found'),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })

    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await screen.findByText('This expense no longer exists. The list has been refreshed.')
    await screen.findByRole('heading', { name: 'New expense' })
    await screen.findByText('No expenses this month.')

    const listGets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGets).toHaveLength(2)
  })

  it('E18: cancelEditMakesNoRequest', async () => {
    const row = expense({ id: 1 })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([row], { totalAmount: '10.00' })),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await screen.findByRole('heading', { name: 'New expense' })
    expect(screen.getByLabelText('Amount')).toHaveValue('')

    const puts = requests(mock).filter((entry) => entry.method === 'PUT')
    expect(puts).toHaveLength(0)
  })

  it('E19: deleteAsksForConfirmation', async () => {
    const row = expense({ id: 1, amount: '10.00' })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([row], { totalAmount: '10.00' })),
        json(list([], { totalAmount: '0.00' })),
      ],
      'DELETE /api/expenses/1': noContent(),
    })
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')

    await user.click(within(table).getByRole('button', { name: 'Delete' }))
    expect(requests(mock).some((entry) => entry.method === 'DELETE')).toBe(false)

    await user.click(within(table).getByRole('button', { name: 'Delete' }))
    await screen.findByText('Expense deleted.')
    await screen.findByText('No expenses this month.')
    // The total comes from the refetch's response, not a client-side recomputation.
    expect(screen.getByText('0.00')).toBeInTheDocument()

    const del = requests(mock).find((entry) => entry.method === 'DELETE')!
    expect(del.body).toBeUndefined()
    expect(confirmSpy).toHaveBeenCalledWith(`Delete the expense of 10.00 PLN (Food, ${row.spentOn})?`)
  })

  it('E20: deleteFailuresAndEditedRow', async () => {
    // (a) a 404 on delete shows the page-level message and refetches the list.
    const rowA = expense({ id: 1 })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([rowA], { totalAmount: '10.00' })),
        json(list([], { totalAmount: '0.00' })),
      ],
      'DELETE /api/expenses/1': problem(404, 'Expense not found'),
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    const { unmount } = render(<ExpensesPage />)
    const tableA = await screen.findByRole('table')
    await user.click(within(tableA).getByRole('button', { name: 'Delete' }))
    await screen.findByText('This expense no longer exists. The list has been refreshed.')
    await screen.findByText('No expenses this month.')
    unmount()

    // (b) a rejected delete (network) shows the page-level network message and does not refetch.
    const rowB = expense({ id: 2 })
    const mockB = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([rowB], { totalAmount: '10.00' })),
      'DELETE /api/expenses/2': new TypeError('Failed to fetch'),
    })
    const { unmount: unmountB } = render(<ExpensesPage />)
    const tableB = await screen.findByRole('table')
    await user.click(within(tableB).getByRole('button', { name: 'Delete' }))
    await screen.findByText('Could not reach the server. Check your connection and try again.')
    expect(screen.getByRole('table')).toBeInTheDocument()
    const listGetsB = requests(mockB).filter(
      (entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'),
    )
    expect(listGetsB).toHaveLength(1)
    unmountB()

    // (c) deleting the row currently being edited returns the form to create mode.
    const rowC1 = expense({ id: 3 })
    const rowC2 = expense({ id: 4 })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([rowC1, rowC2], { totalAmount: '20.00' })),
        json(list([rowC2], { totalAmount: '10.00' })),
      ],
      'DELETE /api/expenses/3': noContent(),
    })
    render(<ExpensesPage />)
    const tableC = await screen.findByRole('table')
    const rowsC = within(tableC).getAllByRole('row').slice(1)
    await user.click(within(rowsC[0]).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    await user.click(within(rowsC[0]).getByRole('button', { name: 'Delete' }))
    await screen.findByText('Expense deleted.')
    await screen.findByRole('heading', { name: 'New expense' })
  })

  it('E21: monthTotalIsTotalAmountFromResponse', async () => {
    const row1 = expense({ id: 1, amount: '10.00' })
    const row2 = expense({ id: 2, amount: '20.00' })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([row1, row2], { totalAmount: '136.00' })),
    })
    const { unmount } = render(<ExpensesPage />)
    await screen.findByRole('table')

    const total = screen.getByText('136.00')
    expect(total.closest('p')).toHaveTextContent('Total:')
    const table = screen.getByRole('table')
    expect(total.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    unmount()

    // an empty month still shows the total, with no table.
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    expect(screen.getByText('0.00')).toBeInTheDocument()
  })

  it('E22: amountsAreRenderedVerbatim', async () => {
    const row1 = expense({ id: 1, amount: '200.00' })
    const row2 = expense({ id: 2, amount: '0.10' })
    const row3 = expense({ id: 3, amount: '9999999999.99' })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([row1, row2, row3], { totalAmount: '1234567890123456.78' })),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    const table = await screen.findByRole('table')

    expect(within(table).getByText('200.00 PLN')).toBeInTheDocument()
    expect(within(table).getByText('0.10 PLN')).toBeInTheDocument()
    expect(within(table).getByText('9999999999.99 PLN')).toBeInTheDocument()
    expect(screen.getByText('1234567890123456.78')).toBeInTheDocument()

    const rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[0]).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    expect(screen.getByLabelText('Amount')).toHaveValue('200.00')
  })

  it('E23: paginatesWithPrevNextAndCaption', async () => {
    const page0 = list(buildItems(50, 1), { totalAmount: '100.00', page: 0, totalItems: 120 })
    const page1 = list(buildItems(50, 51), { totalAmount: '200.00', page: 1, totalItems: 120 })
    const page2 = list(buildItems(20, 101), { totalAmount: '300.00', page: 2, totalItems: 120 })
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(page0),
      'GET /api/expenses?page=1&size=50': json(page1),
      'GET /api/expenses?page=2&size=50': json(page2),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 120')
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next' })).not.toBeDisabled()
    expect(screen.getByText('100.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–100 of 120')
    expect(screen.getByText('200.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('101–120 of 120')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    expect(screen.getByText('300.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    await screen.findByText('51–100 of 120')

    const gets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(gets.map((entry) => entry.url)).toEqual([
      '/api/expenses?page=0&size=50',
      '/api/expenses?page=1&size=50',
      '/api/expenses?page=2&size=50',
      '/api/expenses?page=1&size=50',
    ])
  })

  it('E24: mutationsRefetchTheCurrentPage', async () => {
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

    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(page0),
      'GET /api/expenses?page=1&size=50': [
        json(page1Initial),
        json(page1AfterCreate),
        json(page1AfterEdit),
        json(page1AfterDelete),
      ],
      'POST /api/expenses': json(expense({ id: 200 }), 201),
      'PUT /api/expenses/51': json(expense({ id: 51, amount: '99.00' })),
      'DELETE /api/expenses/51': noContent(),
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 51')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–51 of 51')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await screen.findByText('Expense added.')
    let page1Gets = requests(mock).filter(
      (entry) => entry.method === 'GET' && entry.url === '/api/expenses?page=1&size=50',
    )
    expect(page1Gets).toHaveLength(2)

    let table = screen.getByRole('table')
    let rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[0]).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await screen.findByText('Expense updated.')
    page1Gets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url === '/api/expenses?page=1&size=50')
    expect(page1Gets).toHaveLength(3)

    table = screen.getByRole('table')
    rows = within(table).getAllByRole('row').slice(1)
    await user.click(within(rows[0]).getByRole('button', { name: 'Delete' }))
    await screen.findByText('Expense deleted.')
    page1Gets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url === '/api/expenses?page=1&size=50')
    expect(page1Gets).toHaveLength(4)
  })

  it('E25: stepsBackWhenRefetchedPageIsEmpty', async () => {
    const page0Initial = list(buildItems(50, 1), { totalAmount: '500.00', page: 0, totalItems: 51 })
    const page1Initial = list([expense({ id: 51 })], { totalAmount: '10.00', page: 1, totalItems: 51 })
    const page1Empty = list([], { totalAmount: '500.00', page: 1, totalItems: 50 })
    const page0AfterStepBack = list(buildItems(50, 1), { totalAmount: '500.00', page: 0, totalItems: 50 })

    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [json(page0Initial), json(page0AfterStepBack)],
      'GET /api/expenses?page=1&size=50': [json(page1Initial), json(page1Empty)],
      'DELETE /api/expenses/51': noContent(),
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('1–50 of 51')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('51–51 of 51')

    const table = screen.getByRole('table')
    await user.click(within(table).getByRole('button', { name: 'Delete' }))

    await screen.findByText('1–50 of 50')
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
  })

  it('E26: showsLoadingState', async () => {
    const { promise, resolve } = deferred<Response>()
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': promise,
    })
    render(<ExpensesPage />)

    expect(await screen.findByText('Loading…')).toBeInTheDocument()

    resolve(json(list([expense({ id: 1 })], { totalAmount: '10.00' }))())

    await screen.findByRole('table')
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })

  it('E28: disablesAllActionsWhileAnyMutationIsInFlight', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    // (a) editing with a deferred PUT disables every row action and the form's own buttons.
    const rowA1 = expense({ id: 1 })
    const rowA2 = expense({ id: 2 })
    const putDeferred = deferred<Response>()
    const mockA = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([rowA1, rowA2], { totalAmount: '20.00' })),
        json(list([rowA1, rowA2], { totalAmount: '20.00' })),
      ],
      'PUT /api/expenses/1': putDeferred.promise,
    })
    const { unmount: unmountA } = render(<ExpensesPage />)
    const tableA = await screen.findByRole('table')
    const rowsA = within(tableA).getAllByRole('row').slice(1)
    await user.click(within(rowsA[0]).getByRole('button', { name: 'Edit' }))
    await screen.findByRole('heading', { name: 'Edit expense' })
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    for (const row of within(tableA).getAllByRole('row').slice(1)) {
      expect(within(row).getByRole('button', { name: 'Edit' })).toBeDisabled()
      expect(within(row).getByRole('button', { name: 'Delete' })).toBeDisabled()
    }

    putDeferred.resolve(json(rowA1)())
    await screen.findByText('Expense updated.')
    await screen.findByRole('heading', { name: 'New expense' })
    expect(screen.getByRole('button', { name: 'Add expense' })).not.toBeDisabled()
    for (const row of within(screen.getByRole('table')).getAllByRole('row').slice(1)) {
      expect(within(row).getByRole('button', { name: 'Edit' })).not.toBeDisabled()
      expect(within(row).getByRole('button', { name: 'Delete' })).not.toBeDisabled()
    }
    // The list was refetched after the mutation (D1), not just re-enabled in place.
    const listGetsA = requests(mockA).filter(
      (entry) => entry.method === 'GET' && entry.url === '/api/expenses?page=0&size=50',
    )
    expect(listGetsA).toHaveLength(2)
    unmountA()

    // (b) deleting with a deferred DELETE disables every row action and the create form's submit.
    const rowB1 = expense({ id: 3 })
    const deleteDeferred = deferred<Response>()
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([rowB1], { totalAmount: '10.00' })),
        json(list([], { totalAmount: '0.00' })),
      ],
      'DELETE /api/expenses/3': deleteDeferred.promise,
    })
    const { unmount: unmountB } = render(<ExpensesPage />)
    const tableB = await screen.findByRole('table')
    await user.click(within(tableB).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Add expense' })).toBeDisabled())
    expect(within(tableB).getByRole('button', { name: 'Edit' })).toBeDisabled()
    expect(within(tableB).getByRole('button', { name: 'Delete' })).toBeDisabled()

    deleteDeferred.resolve(noContent()())
    await screen.findByText('Expense deleted.')
    await screen.findByText('No expenses this month.')
    expect(screen.getByRole('button', { name: 'Add expense' })).not.toBeDisabled()
    unmountB()

    // (c) creating with a deferred POST disables every row action.
    const postDeferred = deferred<Response>()
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [
        json(list([expense({ id: 4 })], { totalAmount: '10.00' })),
        json(list([expense({ id: 4 }), expense({ id: 5 })], { totalAmount: '20.00' })),
      ],
      'POST /api/expenses': postDeferred.promise,
    })
    render(<ExpensesPage />)
    const tableC = await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(within(tableC).getByRole('button', { name: 'Edit' })).toBeDisabled())
    expect(within(tableC).getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Add expense' })).toBeDisabled()

    postDeferred.resolve(json(expense({ id: 5 }), 201)())
    await screen.findByText('Expense added.')
    await waitFor(() => expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(3))
    for (const button of within(screen.getByRole('table')).getAllByRole('button', { name: 'Edit' })) {
      expect(button).not.toBeDisabled()
    }
  })
})
