// Entry point: node scripts/wait-for-deploy.ts (inputs from env, see waitForDeploy.ts).
import { errorText, parseInputs, waitForDeploy, type FetchResult } from './waitForDeploy.ts'

const parsed = parseInputs(process.env)
if (!parsed.ok) {
  console.log(parsed.message)
  process.exitCode = 2
} else {
  process.exitCode = await waitForDeploy(parsed, {
    fetchInfo: async (url): Promise<FetchResult> => {
      try {
        const response = await fetch(url, { signal: AbortSignal.timeout(5000), cache: 'no-store' })
        let body: unknown
        try {
          body = await response.json()
        } catch {
          body = undefined
        }
        return { status: response.status, body }
      } catch (error) {
        return { error: errorText(error) }
      }
    },
    now: Date.now,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    log: console.log,
  })
}
