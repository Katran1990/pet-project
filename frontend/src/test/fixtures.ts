import type { Category } from '../api/categories.ts'
import type { Expense, ExpenseList } from '../api/expenses.ts'
import type { QuickTemplate } from '../api/quickTemplates.ts'
import { json } from './fetchMock.ts'
import type { MockRoutes } from './fetchMock.ts'

export const FOOD: Category = { id: 1, name: 'Food', icon: null, archived: false, createdAt: '2026-01-01T00:00:00Z' }
export const TRANSPORT: Category = {
  id: 2,
  name: 'Transport',
  icon: null,
  archived: false,
  createdAt: '2026-01-01T00:00:00Z',
}
// id 9 matches E15's archived row.
export const OLD: Category = { id: 9, name: 'Old', icon: null, archived: true, createdAt: '2026-01-01T00:00:00Z' }

export const CATEGORIES_ROUTE = 'GET /api/categories?includeArchived=true'
export const TEMPLATES_ROUTE = 'GET /api/quick-templates'

// Returns exactly [FOOD, TRANSPORT] and no templates, so card 7's assertions keep their
// meaning. New tests that need OLD or templates override the key after spreading.
export function baseRoutes(): MockRoutes {
  return {
    [CATEGORIES_ROUTE]: json([FOOD, TRANSPORT]),
    [TEMPLATES_ROUTE]: json([]),
  }
}

export function quickTemplate(
  overrides: Partial<Omit<QuickTemplate, 'category'>> & { category?: QuickTemplate['category'] } = {},
): QuickTemplate {
  return {
    id: 1,
    name: 'Coffee',
    amount: '12.50',
    sortOrder: 0,
    category: { id: FOOD.id, name: FOOD.name, icon: FOOD.icon, archived: false },
    ...overrides,
  }
}

export function expense(overrides: Partial<Omit<Expense, 'category'>> & { category?: Expense['category'] } = {}): Expense {
  return {
    id: 1,
    amount: '10.00',
    currency: 'PLN',
    spentOn: '2026-10-01',
    note: null,
    createdAt: '2026-10-01T10:00:00Z',
    category: { id: FOOD.id, name: FOOD.name, icon: FOOD.icon },
    ...overrides,
  }
}

// totalAmount is a required string: the builder never derives it from the item amounts,
// so the test code never does money arithmetic either (AC5).
export function list(
  items: Expense[],
  options: { totalAmount: string; page?: number; size?: number; totalItems?: number },
): ExpenseList {
  const { totalAmount, page = 0, size = 50, totalItems = items.length } = options
  return { items, page, size, totalItems, totalAmount }
}
