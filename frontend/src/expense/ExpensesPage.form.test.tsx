import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { deferred, html, json, mockFetch, problem, rawJson, requests } from '../test/fetchMock.ts'
import { CATEGORIES_ROUTE, FOOD, TEMPLATES_ROUTE, baseRoutes, expense, list } from '../test/fixtures.ts'
import { localIsoDate } from './localDate.ts'
import { ExpensesPage } from './ExpensesPage.tsx'

describe('ExpensesPage form', () => {
  it('E1: rendersFormWithActiveCategoriesAndTodayAsDefaultDate', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))

    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })

    render(<ExpensesPage />)

    await screen.findByRole('option', { name: 'Transport' })
    await screen.findByText('No expenses this month.')

    const options = screen.getAllByRole('option').map((option) => option.textContent)
    expect(options).toEqual(['Select a category', 'Food', 'Transport'])

    expect(screen.getByLabelText('Amount')).toHaveValue('')
    expect(screen.getByLabelText('Date')).toHaveValue('2026-10-01')
    expect(screen.getByLabelText('Note')).toHaveValue('')

    const sent = requests(mock).map((entry) => `${entry.method} ${entry.url}`)
    expect(sent).toEqual([CATEGORIES_ROUTE, TEMPLATES_ROUTE, 'GET /api/expenses?page=0&size=50'])
  })

  it('E2: createPostsTypedValuesAndRefetchesCurrentPage', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))
    const today = localIsoDate(new Date())

    const newRow = expense({ id: 99, amount: '12.50', note: 'Lunch', spentOn: today })
    const existingRow = expense({ id: 1, amount: '5.00', spentOn: '2026-09-29' })
    const secondList = list([newRow, existingRow], { totalAmount: '999.99' })

    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [json(list([], { totalAmount: '0.00' })), json(secondList)],
      'POST /api/expenses': json(expense({ id: 99 }), 201),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    // The status live region is always rendered, empty before any action.
    expect(screen.getByRole('status')).toHaveTextContent('')

    await user.type(screen.getByLabelText('Amount'), '12.5')
    await user.selectOptions(screen.getByLabelText('Category'), 'Food')
    await user.type(screen.getByLabelText('Note'), 'Lunch')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await screen.findByText('Expense added.')
    expect(screen.getByRole('status')).toHaveTextContent('Expense added.')

    const sent = requests(mock)
    const post = sent.find((entry) => entry.method === 'POST')!
    expect(post.headers.get('Content-Type')).toBe('application/json')
    expect(post.body).toEqual({ amount: '12.5', categoryId: FOOD.id, spentOn: today, note: 'Lunch' })
    expect(typeof (post.body as { amount: unknown }).amount).toBe('string')

    const listGets = sent.filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGets).toHaveLength(2)
    const categoryGets = sent.filter((entry) => entry.url === '/api/categories?includeArchived=true')
    expect(categoryGets).toHaveLength(1)

    const table = screen.getByRole('table')
    const firstRow = within(table).getAllByRole('row')[1]
    expect(within(firstRow).getByText('12.50 PLN')).toBeInTheDocument()
    expect(screen.getByText('999.99')).toBeInTheDocument()

    expect(screen.getByLabelText('Amount')).toHaveValue('')
    expect(screen.getByLabelText('Category')).toHaveValue('')
    expect(screen.getByLabelText('Note')).toHaveValue('')
    expect(screen.getByLabelText('Date')).toHaveValue(today)
  })

  it('E3: rendersRefetchedRowsNotThePostResponse', async () => {
    // (a) the refetched list excludes the POST response's (previous month) row entirely.
    const refetchedA = list([expense({ id: 2, spentOn: '2026-10-01' })], { totalAmount: '42.00' })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [json(list([], { totalAmount: '0.00' })), json(refetchedA)],
      'POST /api/expenses': json(expense({ id: 999, spentOn: '2025-01-01', note: 'previous month' }), 201),
    })

    const user = userEvent.setup()
    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await screen.findByText('42.00')
    expect(screen.queryByText('2025-01-01')).not.toBeInTheDocument()
    expect(screen.getByText('2026-10-01')).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2)
    unmount()

    // (b) the back-dated new row shows up at its sorted (third) position.
    const rowA = expense({ id: 10, spentOn: '2026-10-01', note: 'A' })
    const rowC = expense({ id: 12, spentOn: '2026-09-20', note: 'C' })
    const rowB = expense({ id: 11, spentOn: '2026-09-28', note: 'back-dated new row', amount: '7.00' })
    const refetchedB = list([rowA, rowC, rowB], { totalAmount: '77.00' })
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': [json(list([], { totalAmount: '0.00' })), json(refetchedB)],
      'POST /api/expenses': json(expense({ id: 998 }), 201),
    })

    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await screen.findByText('77.00')
    const dataRows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(within(dataRows[2]).getByText('back-dated new row')).toBeInTheDocument()
  })

  it('E4: emptyFieldsAreSentAsNullWithoutClientValidation', async () => {
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': problem(400, 'Invalid request content.', [
        { field: 'amount', message: 'must not be null' },
        { field: 'categoryId', message: 'must not be null' },
        { field: 'spentOn', message: 'must not be null' },
      ]),
    })

    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '' } })

    const form = screen.getByRole('form', { name: 'New expense' })
    const result = fireEvent.submit(form)
    expect(result).toBe(false)

    await waitFor(() => expect(screen.getByLabelText('Amount')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Amount')).toHaveAccessibleDescription('must not be null')
    expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('must not be null')
    expect(screen.getByLabelText('Date')).toHaveAccessibleDescription('must not be null')

    const post = requests(mock).find((entry) => entry.method === 'POST')!
    expect(post.body).toEqual({ amount: null, categoryId: null, spentOn: null, note: '' })
  })

  it('E5: showsValidationErrorsNextToFields', async () => {
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': problem(400, 'Invalid request content.', [
        { field: 'amount', message: 'must be greater than 0' },
        { field: 'amount', message: 'numeric value out of bounds' },
        { field: 'categoryId', message: 'Category not found' },
        { field: 'spentOn', message: 'must be a date in the past or in the present' },
        { field: 'note', message: 'size must be between 0 and 255' },
      ]),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    await user.type(screen.getByLabelText('Amount'), '-5')
    await user.type(screen.getByLabelText('Note'), 'some note')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.getByLabelText('Amount')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Amount')).toHaveAccessibleDescription(
      'must be greater than 0; numeric value out of bounds',
    )
    expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('Category not found')
    expect(screen.getByLabelText('Date')).toHaveAccessibleDescription('must be a date in the past or in the present')
    expect(screen.getByLabelText('Note')).toHaveAccessibleDescription('size must be between 0 and 255')

    expect(screen.getByLabelText('Amount')).toHaveValue('-5')
    expect(screen.getByLabelText('Note')).toHaveValue('some note')

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    const gets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(gets).toHaveLength(1)
  })

  it('E6: showsArchivedCategoryConflictNextToCategory', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': problem(409, 'Category is archived'),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.getByLabelText('Category')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Category')).toHaveAccessibleDescription('Category is archived')
    expect(screen.getByLabelText('Amount')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('Date')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('Note')).not.toHaveAttribute('aria-invalid')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('E7: showsUnreadableBodyErrorAtFormLevel', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': problem(400, 'Failed to read request'),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.type(screen.getByLabelText('Amount'), '12,50')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    const form = screen.getByRole('form', { name: 'New expense' })
    const alert = await within(form).findByRole('alert')
    expect(alert).toHaveTextContent('Could not save the expense: Failed to read request')

    expect(screen.getByLabelText('Amount')).not.toHaveAttribute('aria-invalid')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('E8: showsNetworkErrorAsPageLevelMessage', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': new TypeError('Failed to fetch'),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.type(screen.getByLabelText('Amount'), '12.5')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not reach the server. Check your connection and try again.')

    const form = screen.getByRole('form', { name: 'New expense' })
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument()

    expect(screen.getByLabelText('Amount')).toHaveValue('12.5')
    expect(screen.getByRole('button', { name: 'Add expense' })).not.toBeDisabled()
    expect(screen.getByLabelText('Amount')).not.toHaveAttribute('aria-invalid')
  })

  it('E9: showsServerErrorsAsPageLevelMessage', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': [problem(500, 'Unexpected error'), html(502)],
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await screen.findByText('Server error (500): Unexpected error')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await screen.findByText('Server error (502)')
  })

  it('E10: showsPageLevelErrorWhenLoadsFail', async () => {
    // expenses load rejects (network)
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': new TypeError('Failed to fetch'),
    })
    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('Could not reach the server. Check your connection and try again.')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Amount')).toBeInTheDocument()
    unmount()

    // categories load fails with a 500
    mockFetch({
      ...baseRoutes(),
      [CATEGORIES_ROUTE]: problem(500, 'Unexpected error'),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    render(<ExpensesPage />)
    await screen.findByText('Server error (500): Unexpected error')
  })

  it('E11: disablesSubmitWhileRequestIsInFlight', async () => {
    const { promise, resolve } = deferred<Response>()
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': promise,
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    const button = screen.getByRole('button', { name: 'Add expense' })
    await user.click(button)
    await waitFor(() => expect(button).toBeDisabled())

    await user.click(button)

    resolve(json(expense({ id: 5 }), 201)())
    await screen.findByText('Expense added.')

    const posts = requests(mock).filter((entry) => entry.method === 'POST')
    expect(posts).toHaveLength(1)
  })

  it('E12: clearsErrorsOnResubmit', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': [
        problem(400, 'Invalid request content.', [{ field: 'amount', message: 'must be greater than 0' }]),
        json(expense({ id: 7 }), 201),
      ],
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await waitFor(() => expect(screen.getByLabelText('Amount')).toHaveAttribute('aria-invalid', 'true'))

    await user.type(screen.getByLabelText('Amount'), '12.5')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await screen.findByText('Expense added.')
    expect(screen.getByLabelText('Amount')).not.toHaveAttribute('aria-invalid')
    expect(screen.queryByText('must be greater than 0')).not.toBeInTheDocument()
  })

  it('E27: showsGenericMessageForUnexpectedErrors', async () => {
    // (a) a 2xx list body that is not JSON gives a generic load-error message.
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': rawJson('not json', 200),
    })
    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('Could not load expenses: unexpected error.')
    unmount()

    // (b) a 2xx create response that is not JSON gives a generic page-level message, keeps the form, no refetch.
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': rawJson('not json', 201),
    })
    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.type(screen.getByLabelText('Amount'), '12.5')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await screen.findByText('Unexpected error. Please try again.')
    expect(screen.getByLabelText('Amount')).toHaveValue('12.5')
    expect(screen.getByLabelText('Amount')).not.toHaveAttribute('aria-invalid')

    const gets = requests(mock).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(gets).toHaveLength(1)
  })

  it('newClientBehaviour: bodyReadFailureIsShownAsNetworkErrorNotUnexpectedError', async () => {
    // A rejected response.text() (e.g. a TypeError mid-body, not an abort) is wrapped by
    // readBodyText into an ApiError with status null, so the user sees the network message,
    // not "Could not load expenses: unexpected error." (client.ts's new readBodyText/cause).
    const bodyError = new TypeError('network error mid-body')
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': () => {
        const response = new Response(null, { status: 200, headers: { 'Content-Type': 'application/json' } })
        Object.defineProperty(response, 'text', { value: () => Promise.reject(bodyError) })
        return response
      },
    })

    render(<ExpensesPage />)

    await screen.findByText('Could not reach the server. Check your connection and try again.')
    expect(screen.queryByText('Could not load expenses: unexpected error.')).not.toBeInTheDocument()
  })

  it('E29: showsErrorsForUnknownFieldsAtFormLevel', async () => {
    mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/expenses': problem(400, 'Invalid request content.', [
        { field: 'amount', message: 'must be greater than 0' },
        { field: 'currency', message: 'must be null' },
      ]),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Add expense' }))

    await waitFor(() => expect(screen.getByLabelText('Amount')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByLabelText('Amount')).toHaveAccessibleDescription('must be greater than 0')

    const form = screen.getByRole('form', { name: 'New expense' })
    const alert = within(form).getByRole('alert')
    expect(alert).toHaveTextContent('currency: must be null')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('E30: listLoadDoesNotClearCategoriesError', async () => {
    const { promise, resolve } = deferred<Response>()
    mockFetch({
      ...baseRoutes(),
      [CATEGORIES_ROUTE]: problem(500, 'Unexpected error'),
      'GET /api/expenses?page=0&size=50': promise,
    })

    render(<ExpensesPage />)
    await screen.findByText('Server error (500): Unexpected error')

    resolve(json(list([expense({ id: 1 })], { totalAmount: '10.00' }))())

    await screen.findByRole('table')
    expect(screen.getByText('Server error (500): Unexpected error')).toBeInTheDocument()
  })
})
