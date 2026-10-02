import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { deferred, json, mockFetch, problem, rawJson, requests } from '../test/fetchMock.ts'
import { CATEGORIES_ROUTE, CATEGORY_POST_ROUTE, FOOD, OLD, TRANSPORT, category } from '../test/fixtures.ts'
import { CategoriesPage } from './CategoriesPage.tsx'

const NETWORK = 'Could not reach the server. Check your connection and try again.'
const GIFTS = category({ id: 10, name: 'Gifts', icon: 'gift' })

const rowOf = (name: string) => screen.getByRole('rowheader', { name }).closest('tr')!
const cellTexts = (row: HTMLElement) =>
  within(row)
    .getAllByRole('cell')
    .map((cell) => cell.textContent)
const sent = (mock: ReturnType<typeof mockFetch>) => requests(mock).map((e) => `${e.method} ${e.url}`)
const createForm = () => screen.getByRole('form', { name: 'New category' })
const nameInput = () => within(createForm()).getByLabelText('Name')
const iconInput = () => within(createForm()).getByLabelText('Icon')
const addButton = () => within(createForm()).getByRole('button', { name: 'Add category' })

async function typeInto(user: ReturnType<typeof userEvent.setup>, input: HTMLElement, text: string) {
  await user.clear(input)
  if (text !== '') {
    await user.type(input, text)
  }
}

describe('CategoriesPage list and create', () => {
  it('CP1: listsAllCategoriesWithIconNameAndStatus', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([TRANSPORT, category({ icon: 'cart' }), OLD]),
    })
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    expect(screen.getByRole('heading', { level: 1, name: 'Categories' })).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Icon', 'Name', 'Status', 'Actions'])
    expect(screen.getAllByRole('rowheader').map((h) => h.textContent)).toEqual(['Transport', 'Food', 'Old'])
    expect(cellTexts(rowOf('Transport')).slice(0, 2)).toEqual(['—', 'Active'])
    expect(cellTexts(rowOf('Food')).slice(0, 2)).toEqual(['cart', 'Active'])
    expect(cellTexts(rowOf('Old')).slice(0, 2)).toEqual(['—', 'Archived'])
    expect(rowOf('Old')).toHaveClass('archived')
    expect(rowOf('Food')).not.toHaveClass('archived')
    expect(rowOf('Transport')).not.toHaveClass('archived')

    for (const name of ['Food', 'Transport']) {
      const row = within(rowOf(name))
      expect(row.getByRole('button', { name: 'Edit' })).toBeEnabled()
      expect(row.getByRole('button', { name: 'Archive' })).toBeEnabled()
      expect(row.queryByRole('button', { name: 'Restore' })).toBeNull()
    }
    const old = within(rowOf('Old'))
    expect(old.getByRole('button', { name: 'Edit' })).toBeEnabled()
    expect(old.getByRole('button', { name: 'Restore' })).toBeEnabled()
    expect(old.queryByRole('button', { name: 'Archive' })).toBeNull()
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull()

    expect(sent(mock)).toEqual([CATEGORIES_ROUTE])
  })

  it('CP2: emptyDatabaseShowsHintAndCreateForm', async () => {
    mockFetch({ [CATEGORIES_ROUTE]: json([]) })
    render(<CategoriesPage />)
    await screen.findByText('No categories yet.')

    expect(screen.queryByRole('table')).toBeNull()
    expect(createForm()).toBeInTheDocument()
    expect(nameInput()).toBeInTheDocument()
    expect(iconInput()).toBeInTheDocument()
    expect(addButton()).toBeEnabled()
  })

  describe('CP3: loadingAndLoadErrorsArePageLevel', () => {
    it('(a) shows Loading… until the list arrives', async () => {
      const reply = deferred()
      mockFetch({ [CATEGORIES_ROUTE]: reply.promise })
      render(<CategoriesPage />)

      expect(screen.getByText('Loading…')).toBeInTheDocument()
      expect(screen.queryByRole('table')).toBeNull()

      reply.resolve(json([FOOD])())
      await screen.findByRole('table', { name: 'Categories' })
      expect(screen.queryByText('Loading…')).toBeNull()
    })

    it.each([
      ['(b) a 500', problem(500, 'Unexpected error'), 'Server error (500): Unexpected error'],
      ['(c) a network error', new TypeError('Failed to fetch'), NETWORK],
      ['(d) a body that is not JSON', rawJson('not json'), 'Could not load categories: unexpected error.'],
    ])('%s is a page alert, with the create form still there', async (_label, reply, message) => {
      mockFetch({ [CATEGORIES_ROUTE]: reply })
      render(<CategoriesPage />)

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      expect(screen.queryByRole('table')).toBeNull()
      expect(screen.queryByText('Loading…')).toBeNull()
      expect(createForm()).toBeInTheDocument()
    })

    it('(e) a create error after a failed first load does not show Loading…', async () => {
      const mock = mockFetch({
        [CATEGORIES_ROUTE]: problem(500, 'Unexpected error'),
        [CATEGORY_POST_ROUTE]: problem(409, 'Category name already exists'),
      })
      const user = userEvent.setup()
      render(<CategoriesPage />)
      expect(await screen.findByRole('alert')).toHaveTextContent('Server error (500): Unexpected error')

      await user.type(nameInput(), 'Food')
      await user.click(addButton())

      await waitFor(() => expect(nameInput()).toHaveAccessibleDescription('Category name already exists'))
      expect(nameInput()).toBeInvalid()
      expect(screen.queryByText('Loading…')).toBeNull()
      expect(sent(mock)).toEqual([CATEGORIES_ROUTE, CATEGORY_POST_ROUTE])
    })
  })

  it('CP4: createPostsTypedValuesAndReloadsList', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD]), json([FOOD, GIFTS])],
      [CATEGORY_POST_ROUTE]: json(category({ id: 10, name: 'Gifts', icon: 'from-post' }), 201),
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    await user.type(nameInput(), '  Gifts ')
    await user.type(iconInput(), 'gift')
    await user.click(addButton())

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Category added.'))
    await screen.findByRole('rowheader', { name: 'Gifts' })
    const post = requests(mock)[1]
    expect(post.headers.get('Content-Type')).toBe('application/json')
    expect(post.body).toEqual({ name: '  Gifts ', icon: 'gift' })
    await waitFor(() =>
      expect(sent(mock)).toEqual([CATEGORIES_ROUTE, CATEGORY_POST_ROUTE, CATEGORIES_ROUTE]),
    )
    expect(cellTexts(rowOf('Gifts'))[0]).toBe('gift')
    expect(screen.queryByText('from-post')).toBeNull()
    expect(nameInput()).toHaveValue('')
    expect(iconInput()).toHaveValue('')
    expect(nameInput()).not.toBeInvalid()
    expect(iconInput()).not.toBeInvalid()
  })

  it('CP5: createWithEmptyIconSendsEmptyString', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD]), json([FOOD, category({ id: 10, name: 'Gifts', icon: null })])],
      [CATEGORY_POST_ROUTE]: json(category({ id: 10, name: 'Gifts', icon: null }), 201),
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    await user.type(nameInput(), 'Gifts')
    await user.click(addButton())

    await screen.findByRole('rowheader', { name: 'Gifts' })
    expect(requests(mock)[1].body).toEqual({ name: 'Gifts', icon: '' })
    expect(cellTexts(rowOf('Gifts'))[0]).toBe('—')
  })

  it('CP6: createValidationErrorsShownPerField', async () => {
    const lastReply = deferred()
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD]),
      [CATEGORY_POST_ROUTE]: [
        problem(400, 'Invalid request content.', [{ field: 'name', message: 'must not be blank' }]),
        problem(400, 'Invalid request content.', [
          { field: 'icon', message: 'size must be between 0 and 32' },
          { field: 'name', message: 'size must be between 0 and 64' },
        ]),
        problem(400, 'Failed to read request'),
        lastReply.promise,
      ],
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    // (a) empty inputs: the server says the name is blank
    await user.click(addButton())
    await waitFor(() => expect(nameInput()).toBeInvalid())
    expect(requests(mock)[1].body).toEqual({ name: '', icon: '' })
    expect(nameInput()).toHaveAttribute('aria-invalid', 'true')
    expect(nameInput()).toHaveAccessibleDescription('must not be blank')
    expect(iconInput()).not.toBeInvalid()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, CATEGORY_POST_ROUTE])

    // (b) both fields
    await typeInto(user, nameInput(), 'N'.repeat(65))
    await typeInto(user, iconInput(), 'I'.repeat(33))
    await user.click(addButton())
    await waitFor(() => expect(iconInput()).toBeInvalid())
    expect(nameInput()).toBeInvalid()
    expect(nameInput()).toHaveAccessibleDescription('size must be between 0 and 64')
    expect(iconInput()).toHaveAccessibleDescription('size must be between 0 and 32')
    expect(nameInput()).toHaveValue('N'.repeat(65))
    expect(iconInput()).toHaveValue('I'.repeat(33))

    // (c) no errors[]: a form-level alert
    await user.click(addButton())
    expect(await within(createForm()).findByRole('alert')).toHaveTextContent(
      'Could not add the category: Failed to read request',
    )
    expect(nameInput()).not.toBeInvalid()
    expect(iconInput()).not.toBeInvalid()

    // (d) a new submit clears the alert before the reply arrives
    await user.click(addButton())
    await waitFor(() => expect(requests(mock).filter((r) => r.method === 'POST')).toHaveLength(4))
    expect(screen.queryByRole('alert')).toBeNull()
    lastReply.resolve(json(category({ id: 10, name: 'Gifts' }), 201)())
    await waitFor(() => expect(sent(mock)).toHaveLength(6))
  })

  it('CP7: createDuplicateNameShows409NextToName', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD]),
      [CATEGORY_POST_ROUTE]: problem(409, 'Category name already exists'),
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    await user.type(nameInput(), 'FOOD')
    await user.click(addButton())

    await waitFor(() => expect(nameInput()).toBeInvalid())
    expect(nameInput()).toHaveAccessibleDescription('Category name already exists')
    expect(nameInput()).toHaveValue('FOOD')
    expect(iconInput()).not.toBeInvalid()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, CATEGORY_POST_ROUTE])
  })

  it('CP8: createNetworkServerAndUnexpectedErrorsArePageLevel', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD]),
      [CATEGORY_POST_ROUTE]: [new TypeError('Failed to fetch'), problem(500, 'Unexpected error'), rawJson('not json', 201)],
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })
    await user.type(nameInput(), 'Gifts')

    const expected = [NETWORK, 'Server error (500): Unexpected error', 'Unexpected error. Please try again.']
    for (const [index, message] of expected.entries()) {
      await user.click(addButton())
      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message))
      await waitFor(() => expect(addButton()).toBeEnabled())
      expect(nameInput()).toHaveValue('Gifts')
      expect(within(createForm()).queryByRole('alert')).toBeNull()
      expect(requests(mock).filter((r) => r.method === 'POST')).toHaveLength(index + 1)
    }
    expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(1)
  })

  it('CP9: createIsSerialisedWithRowActions', async () => {
    const post = deferred()
    const reload = deferred()
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, OLD]), reload.promise],
      [CATEGORY_POST_ROUTE]: post.promise,
    })
    const user = userEvent.setup()
    render(<CategoriesPage />)
    await screen.findByRole('table', { name: 'Categories' })

    const rowButtons = () => within(screen.getByRole('table')).getAllByRole('button')
    expect(rowButtons()).toHaveLength(4)

    await user.type(nameInput(), 'Gifts')
    await user.click(addButton())
    await waitFor(() => expect(addButton()).toBeDisabled())
    for (const button of rowButtons()) {
      expect(button).toBeDisabled()
    }
    await user.click(addButton())
    expect(requests(mock).filter((r) => r.method === 'POST')).toHaveLength(1)

    post.resolve(json(category({ id: 10, name: 'Gifts' }), 201)())
    await waitFor(() => expect(addButton()).toBeEnabled())
    await waitFor(() =>
      expect(sent(mock)).toEqual([CATEGORIES_ROUTE, CATEGORY_POST_ROUTE, CATEGORIES_ROUTE]),
    )
    for (const button of rowButtons()) {
      expect(button).toBeDisabled()
    }

    reload.resolve(json([FOOD, OLD, GIFTS])())
    await screen.findByRole('rowheader', { name: 'Gifts' })
    await waitFor(() => {
      for (const button of rowButtons()) {
        expect(button).toBeEnabled()
      }
    })
    expect(addButton()).toBeEnabled()
    expect(requests(mock).filter((r) => r.method === 'POST')).toHaveLength(1)
  })
})
