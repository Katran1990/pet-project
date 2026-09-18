import { useEffect, useState } from 'react'
import './App.css'

type GreetingResponse = {
  message: string
}

type State =
  | { status: 'loading' }
  | { status: 'loaded'; message: string }
  | { status: 'failed'; error: string }

function App() {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()

    fetch('/api/greeting', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`GET /api/greeting failed with status ${response.status}`)
        }
        const greeting: GreetingResponse = await response.json()
        setState({ status: 'loaded', message: greeting.message })
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
        setState({ status: 'failed', error: error instanceof Error ? error.message : String(error) })
      })

    return () => controller.abort()
  }, [])

  return (
    <main className="app">
      <h1>My pet project</h1>
      {state.status === 'loading' && <p className="hint">Loading…</p>}
      {state.status === 'loaded' && <p className="greeting">{state.message}</p>}
      {state.status === 'failed' && <p className="error">{state.error}</p>}
    </main>
  )
}

export default App
