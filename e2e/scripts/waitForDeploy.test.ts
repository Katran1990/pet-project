import assert from 'node:assert/strict'
import { execFile, spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import {
  errorText,
  matchesCommit,
  parseInputs,
  sanitize,
  waitForDeploy,
  type Deps,
  type FetchResult,
  type Inputs,
} from './waitForDeploy.ts'

const SHA = '0123456789abcdef0123456789abcdef01234567'
const OLD = 'fedcba9876543210fedcba9876543210fedcba98'

const inputs: Inputs = { baseUrl: 'http://dev', expectedSha: SHA, timeoutMs: 600_000, intervalMs: 10_000 }
const info = (id: string): FetchResult => ({ status: 200, body: { git: { commit: { id } } } })

function fakeDeps(results: FetchResult[]): { deps: Deps; lines: string[] } {
  let clock = 0
  let i = 0
  const lines: string[] = []
  return {
    lines,
    deps: {
      fetchInfo: async () => results[Math.min(i++, results.length - 1)],
      now: () => clock,
      sleep: async (ms) => {
        clock += ms
      },
      log: (line) => lines.push(line),
    },
  }
}

test('matchesCommit accepts 7 and 40 character prefixes only', () => {
  assert.equal(matchesCommit(SHA, SHA.slice(0, 7)), true)
  assert.equal(matchesCommit(SHA, SHA), true)
  assert.equal(matchesCommit(SHA, OLD.slice(0, 7)), false)
  assert.equal(matchesCommit(SHA, 'zzzzzzz'), false)
  assert.equal(matchesCommit(SHA, SHA.slice(0, 6)), false)
  assert.equal(matchesCommit(SHA, undefined), false)
})

test('returns 0 once the new commit shows up and logs each change once', async () => {
  const { deps, lines } = fakeDeps([
    { status: 502 },
    { status: 502 },
    info(OLD.slice(0, 7)),
    info(OLD.slice(0, 7)),
    info(SHA.slice(0, 7)),
  ])
  assert.equal(await waitForDeploy(inputs, deps), 0)
  assert.deepEqual(lines, [
    '[0s] waiting for 0123456: HTTP 502',
    `[20s] waiting for 0123456: commit ${OLD.slice(0, 7)}`,
    'dev runs 0123456 (after 40s)',
  ])
})

test('returns 1 after the timeout with a clear message', async () => {
  const { deps, lines } = fakeDeps([{ error: 'connect ECONNREFUSED' }])
  assert.equal(await waitForDeploy(inputs, deps), 1)
  const last = lines.at(-1)!
  assert.ok(last.includes('::error title=dev is not on the expected commit::'))
  assert.ok(last.includes('0123456'))
  assert.ok(last.includes('10 minutes'))
  assert.ok(last.includes('(last: connect ECONNREFUSED)'))
})

test('parseInputs explains a missing E2E_BASE_URL and names a bad EXPECTED_SHA', () => {
  const missing = parseInputs({ EXPECTED_SHA: SHA })
  assert.equal(missing.ok, false)
  assert.ok(!missing.ok && missing.message.includes('create the repository variable E2E_BASE_URL'))

  const bad = parseInputs({ E2E_BASE_URL: 'http://dev/', EXPECTED_SHA: 'abc' })
  assert.equal(bad.ok, false)
  assert.ok(!bad.ok && bad.message.includes('EXPECTED_SHA'))

  const good = parseInputs({ E2E_BASE_URL: 'http://dev/', EXPECTED_SHA: SHA })
  assert.deepEqual(good, { ok: true, baseUrl: 'http://dev', expectedSha: SHA, timeoutMs: 600_000, intervalMs: 10_000 })
})

test('the entry script exits with 2 on bad input', () => {
  const cwd = fileURLToPath(new URL('..', import.meta.url))
  const result = spawnSync(process.execPath, ['scripts/wait-for-deploy.ts'], { env: {}, cwd, encoding: 'utf8' })
  assert.equal(result.status, 2)
  assert.ok(result.stdout.includes('E2E_BASE_URL'))
})

test('matchesCommit rejects a long non-prefix, uppercase and non-string ids', () => {
  assert.equal(matchesCommit(SHA, OLD), false)
  assert.equal(matchesCommit(SHA, SHA.slice(0, 7).toUpperCase().replace(/[0-9]/g, 'A')), false)
  assert.equal(matchesCommit(SHA, SHA + '0'), false)
  assert.equal(matchesCommit(SHA, 1234567), false)
  assert.equal(matchesCommit(SHA, null), false)
})

test('timeout message has the exact documented text', async () => {
  const { deps, lines } = fakeDeps([{ status: 200, body: { build: {} } }])
  assert.equal(await waitForDeploy(inputs, deps), 1)
  assert.equal(
    lines.at(-1),
    '::error title=dev is not on the expected commit::http://dev did not report commit 0123456 within 10 minutes ' +
      '(last: no git commit in /actuator/info). Check that "Update deploy manifests" pushed "deploy(dev): 0123456" ' +
      'to pet-project-deploy and that Argo CD synced pet-project-dev.',
  )
})

test('"10 minutes" is derived from the timeout, not hard-coded', async () => {
  const { deps, lines } = fakeDeps([{ status: 503 }])
  assert.equal(await waitForDeploy({ ...inputs, timeoutMs: 120_000 }, deps), 1)
  assert.ok(lines.at(-1)!.includes('within 2 minutes (last: HTTP 503)'))
})

test('an unchanged observation is logged again at least once a minute', async () => {
  const { deps, lines } = fakeDeps([{ status: 502 }])
  assert.equal(await waitForDeploy(inputs, deps), 1)
  const progress = lines.filter((l) => l.startsWith('['))
  assert.equal(progress.length, 11) // t=0, 60, ..., 600 (the poll at the deadline still happens)
  assert.equal(progress[1], '[60s] waiting for 0123456: HTTP 502')
})

test('describe-level observations: non-JSON body, missing commit id and errors are reported', async () => {
  const { deps, lines } = fakeDeps([
    { status: 200 },
    { status: 200, body: { git: { commit: {} } } },
    { error: 'timeout' },
    info(SHA.slice(0, 7)),
  ])
  assert.equal(await waitForDeploy(inputs, deps), 0)
  assert.deepEqual(lines, [
    '[0s] waiting for 0123456: no git commit in /actuator/info',
    '[20s] waiting for 0123456: timeout',
    'dev runs 0123456 (after 30s)',
  ])
})

test('a 40-character id of the expected commit succeeds too', async () => {
  const { deps } = fakeDeps([info(SHA)])
  assert.equal(await waitForDeploy(inputs, deps), 0)
})

test('polls <base>/api/actuator/info', async () => {
  const urls: string[] = []
  const { deps } = fakeDeps([info(SHA)])
  await waitForDeploy(inputs, { ...deps, fetchInfo: async (u) => (urls.push(u), info(SHA)) })
  assert.deepEqual(urls, ['http://dev/api/actuator/info'])
})

test('parseInputs: bad URL, bad numbers, optional overrides', () => {
  const base = { E2E_BASE_URL: 'https://dev.example', EXPECTED_SHA: SHA }
  const empty = parseInputs({ ...base, E2E_BASE_URL: '' })
  assert.ok(!empty.ok && empty.message.startsWith('::error::') && empty.message.includes('E2E_BASE_URL'))
  const ftp = parseInputs({ ...base, E2E_BASE_URL: 'ftp://x' })
  assert.ok(!ftp.ok && ftp.message.includes('E2E_BASE_URL'))
  const upper = parseInputs({ ...base, EXPECTED_SHA: SHA.toUpperCase() })
  assert.ok(!upper.ok && upper.message.includes('EXPECTED_SHA'))
  const noSha = parseInputs({ E2E_BASE_URL: 'http://dev' })
  assert.ok(!noSha.ok && noSha.message.includes('EXPECTED_SHA'))
  const t = parseInputs({ ...base, WAIT_TIMEOUT_SECONDS: 'abc' })
  assert.ok(!t.ok && t.message.includes('WAIT_TIMEOUT_SECONDS'))
  const zero = parseInputs({ ...base, POLL_INTERVAL_SECONDS: '0' })
  assert.ok(!zero.ok && zero.message.includes('POLL_INTERVAL_SECONDS'))
  const ok = parseInputs({ ...base, E2E_BASE_URL: 'https://dev.example///', WAIT_TIMEOUT_SECONDS: '30', POLL_INTERVAL_SECONDS: '2' })
  assert.deepEqual(ok, { ok: true, baseUrl: 'https://dev.example', expectedSha: SHA, timeoutMs: 30_000, intervalMs: 2_000 })
})

test('the entry script prints an ::error:: line for bad input', () => {
  const cwd = fileURLToPath(new URL('..', import.meta.url))
  const result = spawnSync(process.execPath, ['scripts/wait-for-deploy.ts'], {
    env: { E2E_BASE_URL: 'http://localhost:1', EXPECTED_SHA: 'nope' },
    cwd,
    encoding: 'utf8',
  })
  assert.equal(result.status, 2)
  assert.ok(result.stdout.includes('::error::') && result.stdout.includes('EXPECTED_SHA'))
})

test('the entry script exits 0 against a local server reporting the expected commit', async () => {
  const server = createServer((req, res) => {
    res.setHeader('content-type', 'application/json')
    if (req.url === '/api/actuator/info') res.end(JSON.stringify({ git: { commit: { id: SHA.slice(0, 7) } } }))
    else res.writeHead(404).end('{}')
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const { port } = server.address() as AddressInfo
    const cwd = fileURLToPath(new URL('..', import.meta.url))
    const result = await new Promise<{ status: number | null; stdout: string }>((resolve) => {
      execFile(
        process.execPath,
        ['scripts/wait-for-deploy.ts'],
        { cwd, env: { E2E_BASE_URL: `http://127.0.0.1:${port}/`, EXPECTED_SHA: SHA, WAIT_TIMEOUT_SECONDS: '20' } },
        (error, stdout) => resolve({ status: error ? ((error as { code?: number }).code ?? 1) : 0, stdout }),
      )
    })
    assert.equal(result.status, 0)
    assert.ok(result.stdout.includes('dev runs 0123456'))
  } finally {
    server.close()
  }
})

test('the entry script exits 1 with the timeout error when the commit never shows up', async () => {
  const server = createServer((_req, res) => {
    res.setHeader('content-type', 'application/json')
    res.end(JSON.stringify({ git: { commit: { id: OLD.slice(0, 7) } } }))
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const { port } = server.address() as AddressInfo
    const cwd = fileURLToPath(new URL('..', import.meta.url))
    const result = await new Promise<{ status: number; stdout: string }>((resolve) => {
      execFile(
        process.execPath,
        ['scripts/wait-for-deploy.ts'],
        {
          cwd,
          env: {
            E2E_BASE_URL: `http://127.0.0.1:${port}`,
            EXPECTED_SHA: SHA,
            WAIT_TIMEOUT_SECONDS: '1',
            POLL_INTERVAL_SECONDS: '1',
          },
        },
        (error, stdout) => resolve({ status: error ? Number((error as { code?: number }).code) : 0, stdout }),
      )
    })
    assert.equal(result.status, 1)
    assert.ok(result.stdout.includes('::error title=dev is not on the expected commit::'))
    assert.ok(result.stdout.includes(`(last: commit ${OLD.slice(0, 7)})`))
  } finally {
    server.close()
  }
})

test('errorText appends the cause code or message to the generic "fetch failed"', () => {
  const withCode = new Error('fetch failed', { cause: Object.assign(new Error('getaddrinfo'), { code: 'ENOTFOUND' }) })
  assert.equal(errorText(withCode), 'fetch failed: ENOTFOUND')
  assert.equal(errorText(new Error('fetch failed', { cause: new Error('socket hang up') })), 'fetch failed: socket hang up')
  assert.equal(errorText(new Error('boom')), 'boom')
  assert.equal(errorText('plain'), 'plain')
})

test('a timeout reports the cause of a failed fetch', async () => {
  const { deps, lines } = fakeDeps([{ error: errorText(new Error('fetch failed', { cause: { code: 'ENOTFOUND' } })) }])
  assert.equal(await waitForDeploy(inputs, deps), 1)
  assert.ok(lines.at(-1)!.includes('(last: fetch failed: ENOTFOUND)'))
})

test('sanitize removes line breaks and breaks up "::"', () => {
  assert.equal(sanitize('a\r\n::error::b\nc'), 'a : :error: :b c')
})

test('a non-hex commit id is not echoed, and error text cannot inject workflow commands', async () => {
  const { deps, lines } = fakeDeps([
    { status: 200, body: { git: { commit: { id: '::error::pwned\n::set-output' } } } },
    { error: 'bad\n::warning::x' },
    info(SHA.slice(0, 7)),
  ])
  assert.equal(await waitForDeploy(inputs, deps), 0)
  assert.deepEqual(lines, [
    '[0s] waiting for 0123456: unexpected git.commit.id in /actuator/info',
    '[10s] waiting for 0123456: bad : :warning: :x',
    'dev runs 0123456 (after 20s)',
  ])
})
