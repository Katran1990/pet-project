import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import { json, mockFetch, requests } from './test/fetchMock.ts'
import { CATEGORIES_ROUTE, TEMPLATES_ROUTE, baseRoutes, categoryReport, list, reportRoute, reportRow } from './test/fixtures.ts'

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
    expect(Array.from(buttons).map((b) => b.textContent)).toEqual(['Expenses', 'Month'])
    expect(screen.getByRole('button', { name: 'Expenses' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Month' })).not.toHaveAttribute('aria-current')

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
})
