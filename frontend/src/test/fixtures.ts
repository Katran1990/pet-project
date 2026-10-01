import type { Category } from '../api/categories.ts'
import type { Expense, ExpenseList } from '../api/expenses.ts'

export const FOOD: Category = { id: 1, name: 'Food', icon: null, archived: false, createdAt: '2026-01-01T00:00:00Z' }
export const TRANSPORT: Category = {
  id: 2,
  name: 'Transport',
  icon: null,
  archived: false,
  createdAt: '2026-01-01T00:00:00Z',
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
