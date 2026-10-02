import { useState } from 'react'
import './App.css'
import { ExpensesPage } from './expense/ExpensesPage.tsx'
import { MonthReportPage } from './report/MonthReportPage.tsx'

type Tab = 'expenses' | 'month'

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
      </nav>
      {/* Only the active page is mounted, so the other one reloads its data when shown again (decision 1). */}
      {tab === 'expenses' ? <ExpensesPage /> : <MonthReportPage />}
    </main>
  )
}

export default App
