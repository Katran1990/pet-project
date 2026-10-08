import { expect, test } from '@playwright/test'

test('root page loads', async ({ page }) => {
  const response = await page.goto('/')
  expect(response?.ok()).toBe(true)
  await expect(page.getByRole('heading', { level: 1, name: 'Expenses' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Pages' })).toBeVisible()
})

test('backend health is UP', async ({ request }) => {
  const response = await request.get('/api/actuator/health')
  expect(response.status()).toBe(200)
  expect(((await response.json()) as { status: string }).status).toBe('UP')
})

test('only health and info are exposed under /api/actuator', async ({ request }) => {
  const info = await request.get('/api/actuator/info')
  expect(info.status()).toBe(200)
  const body = (await info.json()) as { git?: { commit?: { id?: string } } }
  expect(body.git?.commit?.id).toMatch(/^[0-9a-f]{7,40}$/)

  for (const path of ['/api/actuator', '/api/actuator/prometheus', '/api/actuator/health/liveness']) {
    const response = await request.get(path)
    expect(response.status(), path).toBe(404)
  }

  // Outside /api the path falls back to the SPA's index.html, never to the backend.
  const outside = await request.get('/actuator/prometheus')
  expect(await outside.text()).not.toContain('jvm_memory_used_bytes')
})

// Note: on dev the Traefik ingress in front of nginx may itself answer 400 for ";" or encoded
// paths, so the result here alone does not prove anything about nginx or the backend. The real
// layers are covered by PrometheusEndpointIT (backend) and the manual nginx stub check (ADR 0022).
test('path-parameter traversal under /api does not reach the actuator', async ({ request }) => {
  // Neither path contains a dot segment for the WHATWG URL parser, so they are sent unchanged.
  for (const path of ['/api/..;/actuator/prometheus', '/api/%2e%2e;/actuator/prometheus']) {
    const response = await request.get(path)
    expect(response.status(), path).toBeGreaterThanOrEqual(400)
    expect(await response.text(), path).not.toContain('jvm_memory_used_bytes')
  }
})
