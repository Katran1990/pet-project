import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { deferred, json, mockFetch, problem, rawJson, requests } from '../test/fetchMock.ts'
import { CATEGORIES_ROUTE, FOOD, OLD, TRANSPORT, categoryPatchRoute, category } from '../test/fixtures.ts'
import { CategoriesPage } from './CategoriesPage.tsx'

const NETWORK = 'Could not reach the server. Check your connection and try again.'
const GONE = 'This category no longer exists. The list has been refreshed.'

const rowOf = (name: string) => screen.getByRole('rowheader', { name }).closest('tr')!
const cellTexts = (row: HTMLElement) =>
  within(row)
    .getAllByRole('cell')
    .map((cell) => cell.textContent)
const sent = (mock: ReturnType<typeof mockFetch>) => requests(mock).map((e) => `${e.method} ${e.url}`)
const createForm = () => screen.getByRole('form', { name: 'New category' })
const editForm = (name: string) => screen.getByRole('form', { name: `Edit ${name}` })
const editName = (name: string) => within(editForm(name)).getByLabelText('Name')
const editIcon = (name: string) => within(editForm(name)).getByLabelText('Icon')
const buttonIn = (name: string, button: string) => within(rowOf(name)).getByRole('button', { name: button })
const patches = (mock: ReturnType<typeof mockFetch>) => requests(mock).filter((r) => r.method === 'PATCH')

async function typeInto(user: ReturnType<typeof userEvent.setup>, input: HTMLElement, text: string) {
  await user.clear(input)
  if (text !== '') {
    await user.type(input, text)
  }
}

async function renderLoaded() {
  const user = userEvent.setup()
  render(<CategoriesPage />)
  await screen.findByRole('table', { name: 'Categories' })
  return user
}

describe('CategoriesPage inline edit, archive and restore', () => {
  it('CR1: renameAndIconChangeArePatchedInline', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [
        json([category({ icon: 'cart' }), TRANSPORT]),
        json([category({ name: 'Groceries', icon: 'basket' }), TRANSPORT]),
      ],
      [categoryPatchRoute(1)]: json(category({ name: 'Groceries', icon: 'basket' })),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Food', 'Edit'))
    expect(editForm('Food')).toBeInTheDocument()
    expect(editName('Food')).toHaveValue('Food')
    expect(editName('Food')).toHaveFocus()
    expect(editIcon('Food')).toHaveValue('cart')
    expect(screen.queryByRole('rowheader', { name: 'Food' })).toBeNull()
    const foodRow = editForm('Food').closest('tr')!
    expect(within(foodRow).getByText('Active')).toBeInTheDocument()
    expect(within(foodRow).getByRole('button', { name: 'Archive' })).toBeInTheDocument()
    expect(within(foodRow).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(rowOf('Transport')).toBeInTheDocument()
    expect(buttonIn('Transport', 'Edit')).toBeInTheDocument()

    await typeInto(user, editName('Food'), 'Groceries')
    await typeInto(user, editIcon('Food'), 'basket')
    await user.click(within(editForm('Food')).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Category updated.'))
    await screen.findByRole('rowheader', { name: 'Groceries' })
    const patch = requests(mock)[1]
    expect(patch.headers.get('Content-Type')).toBe('application/json')
    expect(patch.body).toEqual({ name: 'Groceries', icon: 'basket' })
    await waitFor(() =>
      expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/1', CATEGORIES_ROUTE]),
    )
    expect(screen.queryByRole('form', { name: 'Edit Food' })).toBeNull()
    expect(cellTexts(rowOf('Groceries'))[0]).toBe('basket')
  })

  it('CR2: emptyIconClearsTheIcon', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([category({ icon: 'cart' })]), json([category({ icon: null })])],
      [categoryPatchRoute(1)]: json(category({ icon: null })),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Food', 'Edit'))
    await user.clear(editIcon('Food'))
    await user.click(within(editForm('Food')).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(cellTexts(rowOf('Food'))[0]).toBe('—'))
    expect(requests(mock)[1].body).toEqual({ name: 'Food', icon: '' })
  })

  it('CR3: cancelAndSwitchingRowsMakeNoRequest', async () => {
    const mock = mockFetch({ [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]) })
    const user = await renderLoaded()

    await user.click(buttonIn('Food', 'Edit'))
    await user.type(editName('Food'), 'X')
    await user.click(within(editForm('Food')).getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('rowheader', { name: 'Food' })).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: /^Edit / })).toBeNull()

    await user.click(buttonIn('Food', 'Edit'))
    expect(editName('Food')).toHaveValue('Food')

    await user.click(buttonIn('Transport', 'Edit'))
    expect(screen.queryByRole('form', { name: 'Edit Food' })).toBeNull()
    expect(editForm('Transport')).toBeInTheDocument()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE])
  })

  it('CR4: renameDuplicateShows409NextToRowName', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]),
      [categoryPatchRoute(2)]: problem(409, 'Category name already exists'),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Transport', 'Edit'))
    await typeInto(user, editName('Transport'), 'food')
    await user.click(within(editForm('Transport')).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(editName('Transport')).toBeInvalid())
    expect(editName('Transport')).toHaveAccessibleDescription('Category name already exists')
    expect(editName('Transport')).toHaveValue('food')
    expect(editForm('Transport')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(within(createForm()).getByLabelText('Name')).not.toBeInvalid()
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/2'])
  })

  it('CR5: editValidationErrorsShownPerFieldInRow', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]),
      [categoryPatchRoute(1)]: [
        problem(400, 'Invalid request content.', [{ field: 'name', message: 'size must be between 1 and 64' }]),
        problem(400, 'Invalid request content.', [{ field: 'icon', message: 'size must be between 0 and 32' }]),
        problem(400, 'Failed to read request'),
      ],
    })
    const user = await renderLoaded()
    const createInputs = () => [
      within(createForm()).getByLabelText('Name'),
      within(createForm()).getByLabelText('Icon'),
    ]
    const save = () => within(editForm('Food')).getByRole('button', { name: 'Save' })

    await user.click(buttonIn('Food', 'Edit'))
    await user.clear(editName('Food'))
    await user.click(save())
    await waitFor(() => expect(editName('Food')).toBeInvalid())
    expect(requests(mock)[1].body).toEqual({ name: '', icon: '' })
    expect(editName('Food')).toHaveAccessibleDescription('size must be between 1 and 64')
    expect(editIcon('Food')).not.toBeInvalid()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/1'])
    for (const input of createInputs()) {
      expect(input).not.toBeInvalid()
    }

    await user.click(save())
    await waitFor(() => expect(editIcon('Food')).toBeInvalid())
    expect(editIcon('Food')).toHaveAccessibleDescription('size must be between 0 and 32')
    expect(editName('Food')).not.toBeInvalid()

    await user.click(save())
    expect(await within(editForm('Food')).findByRole('alert')).toHaveTextContent(
      'Could not update the category: Failed to read request',
    )
    expect(editName('Food')).not.toBeInvalid()
    expect(editIcon('Food')).not.toBeInvalid()
    for (const input of createInputs()) {
      expect(input).not.toBeInvalid()
    }
    expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(1)
  })

  it('CR6: editOfMissingCategoryLeavesEditAndRefreshes', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, TRANSPORT]), json([TRANSPORT])],
      [categoryPatchRoute(1)]: problem(404, 'Category not found'),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Food', 'Edit'))
    await user.click(within(editForm('Food')).getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(GONE)
    await waitFor(() => expect(screen.queryByRole('rowheader', { name: 'Food' })).toBeNull())
    expect(screen.queryByRole('form', { name: 'Edit Food' })).toBeNull()
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/1', CATEGORIES_ROUTE])
  })

  it('CR7: editNetworkServerUnexpectedErrorsArePageLevelAndKeepDraft', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]),
      [categoryPatchRoute(1)]: [new TypeError('Failed to fetch'), problem(500, 'Unexpected error'), rawJson('not json', 200)],
    })
    const user = await renderLoaded()
    await user.click(buttonIn('Food', 'Edit'))
    await typeInto(user, editName('Food'), 'Draft')

    const expected = [NETWORK, 'Server error (500): Unexpected error', 'Unexpected error. Please try again.']
    for (const [index, message] of expected.entries()) {
      await user.click(within(editForm('Food')).getByRole('button', { name: 'Save' }))
      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message))
      await waitFor(() => expect(within(editForm('Food')).getByRole('button', { name: 'Save' })).toBeEnabled())
      expect(editName('Food')).toHaveValue('Draft')
      expect(within(editForm('Food')).queryByRole('alert')).toBeNull()
      expect(patches(mock)).toHaveLength(index + 1)
    }
    expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(1)
  })

  it('CR8: archiveSendsArchivedTrueAndReloads', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, TRANSPORT]), json([category({ archived: true }), TRANSPORT])],
      [categoryPatchRoute(1)]: json(category({ archived: true })),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Food', 'Archive'))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Category "Food" archived.'))
    await waitFor(() => expect(rowOf('Food')).toHaveClass('archived'))
    expect(requests(mock)[1].body).toEqual({ archived: true })
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/1', CATEGORIES_ROUTE])
    expect(cellTexts(rowOf('Food'))[1]).toBe('Archived')
    expect(buttonIn('Food', 'Restore')).toBeInTheDocument()
    expect(within(rowOf('Food')).queryByRole('button', { name: 'Archive' })).toBeNull()
    expect(rowOf('Transport')).not.toHaveClass('archived')
    expect(cellTexts(rowOf('Transport'))[1]).toBe('Active')
  })

  it('CR9: restoreSendsArchivedFalseAndReloads', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, OLD]), json([FOOD, category({ ...OLD, archived: false })])],
      [categoryPatchRoute(9)]: json(category({ ...OLD, archived: false })),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Old', 'Restore'))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Category "Old" restored.'))
    await waitFor(() => expect(rowOf('Old')).not.toHaveClass('archived'))
    expect(requests(mock)[1].body).toEqual({ archived: false })
    expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/9', CATEGORIES_ROUTE])
    expect(cellTexts(rowOf('Old'))[1]).toBe('Active')
    expect(buttonIn('Old', 'Archive')).toBeInTheDocument()
  })

  describe('CR10: archiveAndRestoreErrorsArePageLevel', () => {
    it('(a) a 404 shows the gone message and reloads', async () => {
      const mock = mockFetch({
        [CATEGORIES_ROUTE]: [json([FOOD, TRANSPORT]), json([TRANSPORT])],
        [categoryPatchRoute(1)]: problem(404, 'Category not found'),
      })
      const user = await renderLoaded()

      await user.click(buttonIn('Food', 'Archive'))

      expect(await screen.findByRole('alert')).toHaveTextContent(GONE)
      await waitFor(() => expect(screen.queryByRole('rowheader', { name: 'Food' })).toBeNull())
      expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(2)
    })

    it.each([
      ['(b) a 500', 'Food', 'Archive', categoryPatchRoute(1), problem(500, 'Unexpected error'), 'Server error (500): Unexpected error'],
      ['(c) a network error', 'Old', 'Restore', categoryPatchRoute(9), new TypeError('Failed to fetch'), NETWORK],
      [
        '(d) a 400 without errors',
        'Food',
        'Archive',
        categoryPatchRoute(1),
        problem(400, 'Failed to read request'),
        'Could not archive the category: Failed to read request',
      ],
    ])('%s is a page alert with no reload', async (_label, name, button, route, reply, message) => {
      const mock = mockFetch({ [CATEGORIES_ROUTE]: json([FOOD, OLD]), [route]: reply })
      const user = await renderLoaded()
      const before = cellTexts(rowOf(name))

      await user.click(buttonIn(name, button))

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      await waitFor(() => expect(buttonIn(name, button)).toBeEnabled())
      expect(cellTexts(rowOf(name))).toEqual(before)
      expect(within(createForm()).queryByRole('alert')).toBeNull()
      expect(screen.getAllByRole('alert')).toHaveLength(1)
      expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(1)
    })
  })

  it('CR11: archiveIsSerialisedWithOtherActions', async () => {
    const patch = deferred()
    const reload = deferred()
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, OLD]), reload.promise],
      [categoryPatchRoute(1)]: patch.promise,
    })
    const user = await renderLoaded()
    const add = () => within(createForm()).getByRole('button', { name: 'Add category' })
    const rowButtons = () => within(screen.getByRole('table')).getAllByRole('button')

    await user.click(buttonIn('Food', 'Archive'))
    await waitFor(() => expect(add()).toBeDisabled())
    for (const button of rowButtons()) {
      expect(button).toBeDisabled()
    }
    await user.click(buttonIn('Food', 'Archive'))
    await user.click(add())
    expect(patches(mock)).toHaveLength(1)

    patch.resolve(json(category({ archived: true }))())
    await waitFor(() => expect(add()).toBeEnabled())
    await waitFor(() =>
      expect(sent(mock)).toEqual([CATEGORIES_ROUTE, 'PATCH /api/categories/1', CATEGORIES_ROUTE]),
    )
    for (const button of rowButtons()) {
      expect(button).toBeDisabled()
    }

    reload.resolve(json([category({ archived: true }), OLD])())
    await waitFor(() => expect(cellTexts(rowOf('Food'))[1]).toBe('Archived'))
    await waitFor(() => {
      for (const button of rowButtons()) {
        expect(button).toBeEnabled()
      }
    })
    expect(patches(mock)).toHaveLength(1)
  })

  it('CR13: saveIsSerialisedWithOtherActions', async () => {
    const patch = deferred()
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]),
      [categoryPatchRoute(2)]: patch.promise,
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Transport', 'Edit'))
    await user.click(within(editForm('Transport')).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(within(editForm('Transport')).getByRole('button', { name: 'Save' })).toBeDisabled())
    expect(within(editForm('Transport')).getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(within(createForm()).getByRole('button', { name: 'Add category' })).toBeDisabled()
    expect(buttonIn('Food', 'Edit')).toBeDisabled()
    expect(buttonIn('Food', 'Archive')).toBeDisabled()
    expect(within(editForm('Transport').closest('tr')!).getByRole('button', { name: 'Archive' })).toBeDisabled()
    await user.type(editName('Transport'), 'X')
    expect(editName('Transport')).toHaveValue('TransportX')
    await user.type(editIcon('Transport'), 'z')
    expect(editIcon('Transport')).toHaveValue('z')
    expect(patches(mock)).toHaveLength(1)

    patch.resolve(json(category({ id: 2, name: 'TransportX' }))())
    await waitFor(() => expect(screen.queryByRole('form', { name: /^Edit / })).toBeNull())
  })

  it('CR12: openEditSurvivesOtherRowChanges', async () => {
    const mock = mockFetch({
      [CATEGORIES_ROUTE]: [json([FOOD, TRANSPORT]), json([category({ archived: true }), TRANSPORT])],
      [categoryPatchRoute(1)]: json(category({ archived: true })),
    })
    const user = await renderLoaded()

    await user.click(buttonIn('Transport', 'Edit'))
    await typeInto(user, editName('Transport'), 'Trains')
    await user.click(buttonIn('Food', 'Archive'))

    await waitFor(() => expect(cellTexts(rowOf('Food'))[1]).toBe('Archived'))
    expect(sent(mock).filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(2)
    expect(editForm('Transport')).toBeInTheDocument()
    expect(editName('Transport')).toHaveValue('Trains')
  })
})
