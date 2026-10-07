// Logic of the "wait until dev runs this commit" step (ADR 0022). No side effects and no
// imports, so unit tests can drive it with a fake clock and a fake fetch.

export interface Inputs {
  baseUrl: string
  expectedSha: string
  timeoutMs: number
  intervalMs: number
}

export type ParsedInputs = ({ ok: true } & Inputs) | { ok: false; message: string }

export interface FetchResult {
  status?: number
  body?: unknown
  error?: string
}

export interface Deps {
  fetchInfo: (url: string) => Promise<FetchResult>
  now: () => number
  sleep: (ms: number) => Promise<void>
  log: (line: string) => void
}

const REPORT_AT_LEAST_EVERY_MS = 60_000

function positiveInt(env: Record<string, string | undefined>, name: string, fallback: number): number | string {
  const raw = env[name]
  if (raw === undefined || raw === '') return fallback
  if (!/^[1-9][0-9]*$/.test(raw)) return `::error::${name} must be a positive integer, got "${raw}"`
  return Number(raw)
}

export function parseInputs(env: Record<string, string | undefined>): ParsedInputs {
  const rawUrl = env.E2E_BASE_URL
  if (!rawUrl) {
    return {
      ok: false,
      message:
        '::error::E2E_BASE_URL is empty: create the repository variable E2E_BASE_URL (Settings > Secrets and variables > Actions > Variables)',
    }
  }
  if (!/^https?:\/\/[^\s]+$/.test(rawUrl)) {
    return { ok: false, message: `::error::E2E_BASE_URL must be an http(s) URL, got "${rawUrl}"` }
  }
  const sha = env.EXPECTED_SHA
  if (!sha || !/^[0-9a-f]{40}$/.test(sha)) {
    return { ok: false, message: `::error::EXPECTED_SHA must be a 40-character lowercase hex commit SHA, got "${sha ?? ''}"` }
  }
  const timeoutSeconds = positiveInt(env, 'WAIT_TIMEOUT_SECONDS', 600)
  if (typeof timeoutSeconds === 'string') return { ok: false, message: timeoutSeconds }
  const intervalSeconds = positiveInt(env, 'POLL_INTERVAL_SECONDS', 10)
  if (typeof intervalSeconds === 'string') return { ok: false, message: intervalSeconds }
  return {
    ok: true,
    baseUrl: rawUrl.replace(/\/+$/, ''),
    expectedSha: sha,
    timeoutMs: timeoutSeconds * 1000,
    intervalMs: intervalSeconds * 1000,
  }
}

export function matchesCommit(expectedSha: string, reportedId: unknown): boolean {
  return typeof reportedId === 'string' && /^[0-9a-f]{7,40}$/.test(reportedId) && expectedSha.startsWith(reportedId)
}

// Server- or error-derived text ends up in stdout, which the runner parses for workflow commands
// ("::error::" and others): drop line breaks and break up "::" sequences.
export function sanitize(text: string): string {
  return text.replace(/[\r\n]+/g, ' ').replace(/::/g, ': :')
}

// Node's fetch rejects with the generic "fetch failed"; the real reason (ENOTFOUND,
// ECONNREFUSED, ...) is in error.cause.
export function errorText(error: unknown): string {
  if (!(error instanceof Error)) return String(error)
  const cause = error.cause as { code?: unknown; message?: unknown } | undefined
  const detail = cause?.code ?? cause?.message
  return typeof detail === 'string' && detail !== '' ? `${error.message}: ${detail}` : error.message
}

export function describe(result: FetchResult): { observation: string; reportedId?: string } {
  if (result.error !== undefined) return { observation: sanitize(result.error) }
  if (result.status !== 200) return { observation: `HTTP ${result.status}` }
  const id = (result.body as { git?: { commit?: { id?: unknown } } } | null)?.git?.commit?.id
  if (typeof id !== 'string') return { observation: 'no git commit in /actuator/info' }
  if (!/^[0-9a-f]{7,40}$/.test(id)) return { observation: 'unexpected git.commit.id in /actuator/info' }
  return { observation: `commit ${id}`, reportedId: id }
}

function humanDuration(ms: number): string {
  const seconds = Math.round(ms / 1000)
  if (seconds % 60 === 0) {
    const minutes = seconds / 60
    return `${minutes} minute${minutes === 1 ? '' : 's'}`
  }
  return `${seconds} seconds`
}

export function timeoutMessage(inputs: Inputs, lastObservation: string): string {
  const sha7 = inputs.expectedSha.slice(0, 7)
  return (
    `::error title=dev is not on the expected commit::${inputs.baseUrl} did not report commit ${sha7} ` +
    `within ${humanDuration(inputs.timeoutMs)} (last: ${lastObservation}). ` +
    `Check that "Update deploy manifests" pushed "deploy(dev): ${sha7}" to pet-project-deploy ` +
    `and that Argo CD synced pet-project-dev.`
  )
}

export async function waitForDeploy(inputs: Inputs, deps: Deps): Promise<0 | 1> {
  const start = deps.now()
  const deadline = start + inputs.timeoutMs
  const sha7 = inputs.expectedSha.slice(0, 7)
  const url = `${inputs.baseUrl}/api/actuator/info`
  let lastObservation = 'no response yet'
  let lastLogged: string | undefined
  let lastLoggedAt = start

  for (;;) {
    const { observation, reportedId } = describe(await deps.fetchInfo(url))
    lastObservation = observation
    const t = deps.now()
    const elapsed = Math.round((t - start) / 1000)

    if (matchesCommit(inputs.expectedSha, reportedId)) {
      deps.log(`dev runs ${sha7} (after ${elapsed}s)`)
      return 0
    }
    if (observation !== lastLogged || t - lastLoggedAt >= REPORT_AT_LEAST_EVERY_MS) {
      deps.log(`[${elapsed}s] waiting for ${sha7}: ${observation}`)
      lastLogged = observation
      lastLoggedAt = t
    }
    if (t + inputs.intervalMs > deadline) break
    await deps.sleep(inputs.intervalMs)
  }

  deps.log(timeoutMessage(inputs, lastObservation))
  return 1
}
