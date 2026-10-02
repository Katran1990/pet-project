import { useState } from 'react'
import './App.css'
import { CategoriesPage } from './category/CategoriesPage.tsx'
import { ExpensesPage } from './expense/ExpensesPage.tsx'
import { MonthReportPage } from './report/MonthReportPage.tsx'

type Tab = 'expenses' | 'month' | 'categories'

function App() {
  const [tab, setTab] = useState<Tab>('expenses')
  return (
    <main className="app">
      <nav aria-label="Pages" className="tabs">
        <button type="button" aria-current={tab === 'expenses' ? 'page' : undefined} onClick={() => setTab('expenses')}>
          Expenses
        </button>
        <button type="button" aria-current={tab === 'month' ? 'page' : undefined} onClick={() => setTab('month')}>
          Month
        </button>
        <button type="button" aria-current={tab === 'categories' ? 'page' : undefined} onClick={() => setTab('categories')}>
          Categories
        </button>
      </nav>
      {/* Only the active page is mounted, so the others load their data again when shown.
          This is also how category changes reach the expense form. */}
      {tab === 'expenses' && <ExpensesPage />}
      {tab === 'month' && <MonthReportPage />}
      {tab === 'categories' && <CategoriesPage />}
    </main>
  )
}

export default App
