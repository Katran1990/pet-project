import { expect, test } from './support/testData.ts'

const AMOUNT = '12.34'

test('create a category and an expense, see them in the report, then clean up', async ({
  page,
  request,
  testName,
}) => {
  await page.goto('/')

  await test.step('scenario 2: create a category', async () => {
    await page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: 'Categories' }).click()
    const form = page.getByRole('form', { name: 'New category' })
    await form.getByLabel('Name').fill(testName)
    await form.getByRole('button', { name: 'Add category' }).click()
    await expect(page.getByRole('status')).toHaveText('Category added.')
    const row = page.getByRole('table', { name: 'Categories' }).getByRole('row').filter({
      has: page.getByRole('rowheader', { name: testName, exact: true }),
    })
    await expect(row.getByRole('cell', { name: 'Active', exact: true })).toBeVisible()
  })

  await test.step('scenario 2: create an expense in it', async () => {
    await page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: 'Expenses' }).click()
    const form = page.getByRole('form', { name: 'New expense' })
    await form.getByLabel('Amount').fill(AMOUNT)
    await form.getByLabel('Category').selectOption({ label: testName })
    await form.getByLabel('Note').fill(testName)
    await form.getByRole('button', { name: 'Add expense' }).click()
    await expect(page.getByRole('status')).toHaveText('Expense added.')
    const row = page.getByRole('table', { name: 'Expenses this month' }).getByRole('row').filter({
      hasText: testName,
    })
    await expect(row).toContainText(`${AMOUNT} PLN`)
  })

  let month = ''
  let categoryId: number | undefined
  await test.step('scenario 2: see both in the by-category report', async () => {
    await page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: 'Month' }).click()
    const table = page.getByRole('table', { name: /^Report for / })
    const row = table.getByRole('row').filter({ has: page.getByRole('rowheader', { name: testName, exact: true }) })
    await expect(row).toContainText(AMOUNT)
    month = ((await table.getAttribute('aria-label')) ?? '').replace('Report for ', '')
    expect(month).toMatch(/^\d{4}-\d{2}$/)

    const categories = (await (await request.get('/api/categories')).json()) as { id: number; name: string }[]
    categoryId = categories.find((c) => c.name === testName)?.id
    expect(categoryId).toBeDefined()
  })

  await test.step('scenario 3: delete the expense and archive the category', async () => {
    await page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: 'Expenses' }).click()
    const expenseRow = page.getByRole('table', { name: 'Expenses this month' }).getByRole('row').filter({
      hasText: testName,
    })
    page.once('dialog', (dialog) => void dialog.accept())
    await expenseRow.getByRole('button', { name: 'Delete' }).click()
    await expect(page.getByRole('status')).toHaveText('Expense deleted.')
    await expect(expenseRow).toHaveCount(0)

    await page.getByRole('navigation', { name: 'Pages' }).getByRole('button', { name: 'Categories' }).click()
    const categoryRow = page.getByRole('table', { name: 'Categories' }).getByRole('row').filter({
      has: page.getByRole('rowheader', { name: testName, exact: true }),
    })
    await categoryRow.getByRole('button', { name: 'Archive' }).click()
    await expect(page.getByRole('status')).toHaveText(`Category "${testName}" archived.`)
    await expect(categoryRow.getByRole('cell', { name: 'Archived', exact: true })).toBeVisible()

    // An archived category without expenses or limits is left out of the report.
    const report = (await (await request.get(`/api/reports/by-category?month=${month}`)).json()) as {
      rows: { category: { id: number } }[]
    }
    expect(report.rows.some((r) => r.category.id === categoryId)).toBe(false)
  })
})
