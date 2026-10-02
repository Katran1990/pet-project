import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import { json, mockFetch, requests } from './test/fetchMock.ts'
import {
  CATEGORIES_ROUTE,
  CATEGORY_POST_ROUTE,
  FOOD,
  OLD,
  TEMPLATES_ROUTE,
  TRANSPORT,
  baseRoutes,
  categoryPatchRoute,
  category,
  categoryReport,
  list,
  reportRoute,
  reportRow,
} from './test/fixtures.ts'

beforeEach(() => {
  // 2026-10-01 00:30 in Europe/Warsaw, while UTC is still September.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-09-30T22:30:00Z'))
})

const sent = (mock: ReturnType<typeof mockFetch>) => requests(mock).map((e) => `${e.method} ${e.url}`)

describe('App', () => {
  it('N1: startsOnExpensesTab', async () => {
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    render(<App />)
    await screen.findByText('No expenses this month.')

    expect(screen.getByRole('heading', { level: 1, name: 'Expenses' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Pages' })
    const buttons = nav.querySelectorAll('button')
    expect(Array.from(buttons).map((b) => b.textContent)).toEqual(['Expenses', 'Month', 'Categories'])
    expect(screen.getByRole('button', { name: 'Expenses' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Month' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('button', { name: 'Categories' })).not.toHaveAttribute('aria-current')

    expect([...sent(mock)].sort()).toEqual(
      ['GET /api/expenses?page=0&size=50', `${CATEGORIES_ROUTE}`, `${TEMPLATES_ROUTE}`].sort(),
    )
    expect(sent(mock).some((r) => r.includes('/api/reports'))).toBe(false)
  })

  it('N2: switchesToMonthAndBackWithoutChangingUrl', async () => {
    window.history.replaceState(null, '', '/?categoryIds=1')
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?categoryIds=1&page=0&size=50': json(list([], { totalAmount: '0.00' })),
      [reportRoute('2026-10')]: json(categoryReport('2026-10', [reportRow()], { totalAmount: '10.00' })),
    })
    const user = userEvent.setup()
    render(<App />)
    await screen.findByRole('heading', { level: 1, name: 'Expenses' })
    await vi.waitFor(() => expect(sent(mock)).toContain('GET /api/expenses?categoryIds=1&page=0&size=50'))

    const href = window.location.href
    const historyLength = window.history.length
    const before = sent(mock).length

    await user.click(screen.getByRole('button', { name: 'Month' }))
    await screen.findByRole('table', { name: 'Report for 2026-10' })
    expect(screen.getByRole('heading', { level: 1, name: 'Month' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Expenses' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-current', 'page')
    expect(sent(mock).slice(before)).toEqual([reportRoute('2026-10')])
    expect(window.location.href).toBe(href)
    expect(window.history.length).toBe(historyLength)

    const beforeBack = sent(mock).length
    await user.click(screen.getByRole('button', { name: 'Expenses' }))
    await screen.findByRole('heading', { level: 1, name: 'Expenses' })
    await vi.waitFor(() =>
      expect(sent(mock).slice(beforeBack)).toContain('GET /api/expenses?categoryIds=1&page=0&size=50'),
    )
    expect(window.location.href).toBe(href)
    expect(window.history.length).toBe(historyLength)
  })

  it('N3: categoriesTabMountsCategoriesPage', async () => {
    const mock = mockFetch({
      ...baseRoutes(),
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
    })
    const user = userEvent.setup()
    render(<App />)
    await screen.findByText('No expenses this month.')
    const href = window.location.href
    const historyLength = window.history.length
    const before = sent(mock).length

    await user.click(screen.getByRole('button', { name: 'Categories' }))
    await screen.findByRole('table', { name: 'Categories' })

    expect(screen.getByRole('heading', { level: 1, name: 'Categories' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Categories' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByRole('heading', { name: 'Expenses' })).toBeNull()
    expect(sent(mock).slice(before)).toEqual([CATEGORIES_ROUTE])
    expect(window.location.href).toBe(href)
    expect(window.history.length).toBe(historyLength)
  })

  it('N4: categoryChangesReachTheExpenseFormWithoutReload', async () => {
    const GIFTS = category({ id: 10, name: 'Gifts', icon: null })
    const FOOD_ARCHIVED = category({ archived: true })
    const OLD_RESTORED = { ...OLD, archived: false }
    const mock = mockFetch({
      ...baseRoutes(),
      [CATEGORIES_ROUTE]: [
        json([FOOD, TRANSPORT, OLD]), // Expenses mount
        json([FOOD, TRANSPORT, OLD]), // Categories mount
        json([FOOD, TRANSPORT, OLD, GIFTS]), // after create
        json([FOOD_ARCHIVED, TRANSPORT, OLD, GIFTS]), // after archive
        json([FOOD_ARCHIVED, TRANSPORT, OLD_RESTORED, GIFTS]), // after restore, repeats for the Expenses remount
      ],
      'GET /api/expenses?page=0&size=50': json(list([], { totalAmount: '0.00' })),
      [CATEGORY_POST_ROUTE]: json(GIFTS, 201),
      [categoryPatchRoute(1)]: json(FOOD_ARCHIVED),
      [categoryPatchRoute(9)]: json(OLD_RESTORED),
    })
    const user = userEvent.setup()
    render(<App />)
    await screen.findByText('No expenses this month.')
    const optionTexts = () =>
      within(screen.getByRole('combobox', { name: 'Category' }))
        .getAllByRole('option')
        .map((o) => o.textContent)
    await waitFor(() => expect(optionTexts()).toEqual(['Select a category', 'Food', 'Transport']))
    const href = window.location.href
    const historyLength = window.history.length

    await user.click(screen.getByRole('button', { name: 'Categories' }))
    await screen.findByRole('table', { name: 'Categories' })
    const rowOf = (name: string) => screen.getByRole('rowheader', { name }).closest('tr')!

    const createForm = screen.getByRole('form', { name: 'New category' })
    await user.type(within(createForm).getByLabelText('Name'), 'Gifts')
    await user.click(within(createForm).getByRole('button', { name: 'Add category' }))
    await screen.findByRole('rowheader', { name: 'Gifts' })

    await waitFor(() => expect(within(rowOf('Food')).getByRole('button', { name: 'Archive' })).toBeEnabled())
    await user.click(within(rowOf('Food')).getByRole('button', { name: 'Archive' }))
    await waitFor(() => expect(within(rowOf('Food')).getByRole('button', { name: 'Restore' })).toBeEnabled())

    await user.click(within(rowOf('Old')).getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(within(rowOf('Old')).getByRole('button', { name: 'Archive' })).toBeEnabled())

    const requestsBeforeExpensesClick = sent(mock).length
    await user.click(screen.getByRole('button', { name: 'Expenses' }))
    await screen.findByRole('option', { name: 'Gifts' })
    expect(optionTexts()).toEqual(['Select a category', 'Transport', 'Old', 'Gifts'])
    expect(screen.getByRole('checkbox', { name: 'Food (archived)' })).toBeInTheDocument()

    const categoryRequests = sent(mock)
    expect(categoryRequests.filter((r) => r === CATEGORIES_ROUTE)).toHaveLength(6)
    expect(categoryRequests.slice(requestsBeforeExpensesClick)).toContain(CATEGORIES_ROUTE)
    expect(window.location.href).toBe(href)
    expect(window.history.length).toBe(historyLength)
  })
})
