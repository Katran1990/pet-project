import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { deferred, json, mockFetch, problem, rawJson, requests } from '../test/fetchMock.ts'
import { OLD, TEMPLATES_ROUTE, baseRoutes, expense, list, quickTemplate } from '../test/fixtures.ts'
import { ExpensesPage } from './ExpensesPage.tsx'

describe('ExpensesPage templates', () => {
  it('QT1: rendersTemplateButtonsInApiOrderAboveForm', async () => {
    // (a) templates render as buttons in API order, above the form; the archived one is disabled.
    const bus = quickTemplate({ id: 1, name: 'Bus', amount: '3.40', sortOrder: -1 })
    const coffee = quickTemplate({ id: 2, name: 'Coffee', amount: '12.50', sortOrder: 0 })
    const oldPass = quickTemplate({
      id: 3,
      name: 'Old pass',
      amount: '100.00',
      sortOrder: 0,
      category: { id: OLD.id, name: OLD.name, icon: OLD.icon, archived: true },
    })
    const rent = quickTemplate({ id: 4, name: 'Rent', amount: '9999999999.99', sortOrder: 5 })

    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([bus, coffee, oldPass, rent]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })

    const { unmount } = render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    const group = screen.getByRole('group', { name: 'Quick templates' })
    const buttons = within(group).getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual([
      'Bus 3.40',
      'Coffee 12.50',
      'Old pass 100.00 (category archived)',
      'Rent 9999999999.99',
    ])
    expect(buttons[0]).not.toBeDisabled()
    expect(buttons[1]).not.toBeDisabled()
    expect(buttons[2]).toBeDisabled()
    expect(buttons[3]).not.toBeDisabled()

    const form = screen.getByRole('form', { name: 'New expense' })
    expect(group.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    unmount()

    // (b) no templates: no group is rendered at all.
    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    expect(screen.queryByRole('group', { name: 'Quick templates' })).not.toBeInTheDocument()
  })

  it('QT2: applyPostsAndRefreshesTableAndTotal', async () => {
    window.history.replaceState(null, '', '/?categoryIds=1')
    const template = quickTemplate()
    const existingRow = expense({ id: 1 })
    const newRow = expense({ id: 2, amount: '12.50' })
    const mock = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([template]),
      'GET /api/expenses?categoryIds=1&page=0&size=50': [
        json(list([existingRow], { totalAmount: '10.00' })),
        json(list([newRow, existingRow], { totalAmount: '22.50' })),
      ],
      'POST /api/quick-templates/1/apply': json(expense({ id: 2, amount: '12.50' }), 201),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('10.00')

    await user.type(screen.getByLabelText('Amount'), '5')
    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))

    await screen.findByText('Expense added from template "Coffee".')
    await screen.findByText('22.50')
    const table = screen.getByRole('table')
    const firstRow = within(table).getAllByRole('row')[1]!
    expect(within(firstRow).getByText('12.50 PLN')).toBeInTheDocument()
    expect(screen.getByLabelText('Amount')).toHaveValue('5')

    const post = requests(mock).find((entry) => entry.method === 'POST')!
    expect(post.body).toBeUndefined()
    expect(post.headers.has('Content-Type')).toBe(false)

    const categoryGets = requests(mock).filter((entry) => entry.url === '/api/categories?includeArchived=true')
    expect(categoryGets).toHaveLength(1)
    const templateGets = requests(mock).filter((entry) => entry.url === '/api/quick-templates')
    expect(templateGets).toHaveLength(1)
  })

  it('QT3: appliedExpenseOutsideFilterStaysHidden', async () => {
    window.history.replaceState(null, '', '/?from=2026-09-01&to=2026-09-30')
    const row = expense({ id: 1, spentOn: '2026-09-15' })
    const mock = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([quickTemplate()]),
      'GET /api/expenses?from=2026-09-01&to=2026-09-30&page=0&size=50': [
        json(list([row], { totalAmount: '50.00' })),
        json(list([row], { totalAmount: '50.00' })),
      ],
      'POST /api/quick-templates/1/apply': json(expense({ id: 2, spentOn: '2026-10-01' }), 201),
    })

    const user = userEvent.setup()
    render(<ExpensesPage />)
    await screen.findByText('50.00')

    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))
    await screen.findByText('Expense added from template "Coffee".')

    expect(screen.queryByText('2026-10-01')).not.toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('2026-09-01')
    expect(screen.getByLabelText('To')).toHaveValue('2026-09-30')
    expect(window.location.search).toBe('?from=2026-09-01&to=2026-09-30')

    const sent = requests(mock)
    expect(sent.filter((entry) => entry.method === 'POST')).toHaveLength(1)
    expect(sent.filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))).toHaveLength(2)
  })

  it('QT4: applyIsSerialisedWithOtherMutations', async () => {
    // (a) a deferred apply disables every template button, the form's submit and every row action.
    const row = expense({ id: 1 })
    const applyDeferred = deferred<Response>()
    const mockA = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([quickTemplate()]),
      'GET /api/expenses?page=0&size=50': [
        json(list([row], { totalAmount: '10.00' })),
        json(list([row, expense({ id: 2 })], { totalAmount: '22.50' })),
      ],
      'POST /api/quick-templates/1/apply': applyDeferred.promise,
    })

    const user = userEvent.setup()
    const { unmount } = render(<ExpensesPage />)
    const table = await screen.findByRole('table')

    const templateButton = screen.getByRole('button', { name: 'Coffee 12.50' })
    await user.click(templateButton)

    await waitFor(() => expect(templateButton).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Add expense' })).toBeDisabled()
    expect(within(table).getByRole('button', { name: 'Edit' })).toBeDisabled()
    expect(within(table).getByRole('button', { name: 'Delete' })).toBeDisabled()

    // A second click while the first apply is in flight sends no second POST.
    await user.click(templateButton)

    applyDeferred.resolve(json(expense({ id: 2 }), 201)())
    await screen.findByText('Expense added from template "Coffee".')
    expect(templateButton).not.toBeDisabled()
    expect(screen.getByRole('button', { name: 'Add expense' })).not.toBeDisabled()

    const posts = requests(mockA).filter((entry) => entry.method === 'POST')
    expect(posts).toHaveLength(1)
    unmount()

    // (b) a deferred create disables the template buttons too, through the shared `mutating` flag.
    const postDeferred = deferred<Response>()
    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([quickTemplate()]),
      'GET /api/expenses?page=0&size=50': [
        json(list([], { totalAmount: '0.00' })),
        json(list([expense({ id: 5 })], { totalAmount: '12.50' })),
      ],
      'POST /api/expenses': postDeferred.promise,
    })
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')

    await user.click(screen.getByRole('button', { name: 'Add expense' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Coffee 12.50' })).toBeDisabled())

    postDeferred.resolve(json(expense({ id: 5 }), 201)())
    await screen.findByText('Expense added.')
    expect(screen.getByRole('button', { name: 'Coffee 12.50' })).not.toBeDisabled()
  })

  it('QT5: applyErrorsArePageLevel', async () => {
    const template = quickTemplate()
    const user = userEvent.setup()

    // (a) a 409 (category archived) is shown page-level, with no refetch.
    const mockA = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([template]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/quick-templates/1/apply': problem(409, 'Category is archived'),
    })
    const { unmount: unmountA } = render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))
    await screen.findByText('Could not apply the template: Category is archived')
    expect(screen.getByRole('button', { name: 'Coffee 12.50' })).not.toBeDisabled()
    const form = screen.getByRole('form', { name: 'New expense' })
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument()
    const listGetsA = requests(mockA).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGetsA).toHaveLength(1)
    unmountA()

    // (b) a rejected fetch (network) shows the network message, with no refetch.
    const mockB = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([template]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/quick-templates/1/apply': new TypeError('Failed to fetch'),
    })
    const { unmount: unmountB } = render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))
    await screen.findByText('Could not reach the server. Check your connection and try again.')
    expect(screen.getByRole('button', { name: 'Coffee 12.50' })).not.toBeDisabled()
    const formB = screen.getByRole('form', { name: 'New expense' })
    expect(within(formB).queryByRole('alert')).not.toBeInTheDocument()
    const listGetsB = requests(mockB).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGetsB).toHaveLength(1)
    unmountB()

    // (c) a 5xx is shown through the shared server-error message, with no refetch.
    const mockC = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([template]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/quick-templates/1/apply': problem(500, 'Unexpected error'),
    })
    const { unmount: unmountC } = render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))
    await screen.findByText('Server error (500): Unexpected error')
    expect(screen.getByRole('button', { name: 'Coffee 12.50' })).not.toBeDisabled()
    const formC = screen.getByRole('form', { name: 'New expense' })
    expect(within(formC).queryByRole('alert')).not.toBeInTheDocument()
    const listGetsC = requests(mockC).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGetsC).toHaveLength(1)
    unmountC()

    // (d) a 2xx body that is not JSON gives the generic unexpected-error message, with no refetch.
    const mockD = mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: json([template]),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      'POST /api/quick-templates/1/apply': rawJson('not json', 201),
    })
    render(<ExpensesPage />)
    await screen.findByText('No expenses this month.')
    await user.click(screen.getByRole('button', { name: 'Coffee 12.50' }))
    await screen.findByText('Unexpected error. Please try again.')
    expect(screen.getByRole('button', { name: 'Coffee 12.50' })).not.toBeDisabled()
    const formD = screen.getByRole('form', { name: 'New expense' })
    expect(within(formD).queryByRole('alert')).not.toBeInTheDocument()
    const listGetsD = requests(mockD).filter((entry) => entry.method === 'GET' && entry.url.startsWith('/api/expenses'))
    expect(listGetsD).toHaveLength(1)
  })

  it('QT6: templatesLoadFailureKeepsPageUsable', async () => {
    const row = expense({ id: 1 })

    // (a) a 5xx on the templates load.
    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: problem(500, 'Unexpected error'),
      'GET /api/expenses?page=0&size=50': json(list([row], { totalAmount: '10.00' })),
    })
    const { unmount: unmountA } = render(<ExpensesPage />)
    await screen.findByText('Server error (500): Unexpected error')
    expect(screen.queryByRole('group', { name: 'Quick templates' })).not.toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'New expense' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    unmountA()

    // (b) a 2xx body that is not JSON.
    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: rawJson('not json', 200),
      'GET /api/expenses?page=0&size=50': json(list([row], { totalAmount: '10.00' })),
    })
    const { unmount: unmountB } = render(<ExpensesPage />)
    await screen.findByText('Could not load quick templates: unexpected error.')
    expect(screen.queryByRole('group', { name: 'Quick templates' })).not.toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'New expense' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    unmountB()

    // (c) a rejected fetch (network).
    mockFetch({
      ...baseRoutes(),
      [TEMPLATES_ROUTE]: new TypeError('Failed to fetch'),
      'GET /api/expenses?page=0&size=50': json(list([row], { totalAmount: '10.00' })),
    })
    render(<ExpensesPage />)
    await screen.findByText('Could not reach the server. Check your connection and try again.')
    expect(screen.queryByRole('group', { name: 'Quick templates' })).not.toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'New expense' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})
