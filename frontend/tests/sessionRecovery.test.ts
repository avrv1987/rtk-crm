import assert from 'node:assert/strict'
import { mock, test } from 'node:test'
import { ApiClient, ApiError, isAccessDenied } from '../src/shared/api/client.ts'
import { startPolling } from '../src/shared/api/polling.ts'

type Call = { path: string; method: string; token: string | null }

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' }
})

const apiError = (code: string) => ({ code, message: 'Отказ', requestId: '00000000-0000-0000-0000-000000000001' })

const serve = (responses: Array<(call: Call) => Response>) => {
  const calls: Call[] = []
  mock.method(globalThis, 'fetch', async (path: string, init: RequestInit = {}) => {
    const call = { path, method: init.method ?? 'GET', token: new Headers(init.headers).get('X-CSRF-TOKEN') }
    calls.push(call)
    const next = responses.shift()
    assert.ok(next, `лишний запрос ${call.method} ${path}`)
    return next(call)
  })
  return calls
}

const csrf = (token: string) => () => json(200, { headerName: 'X-CSRF-TOKEN', token })

test('команда после отказа CSRF получает новый токен и повторяется один раз', async (t) => {
  t.after(() => mock.restoreAll())
  const calls = serve([
    csrf('old'),
    () => json(403, apiError('CSRF_INVALID')),
    csrf('fresh'),
    (call) => json(200, { logoutUrl: `http://idp.test/logout?token=${call.token}` })
  ])

  const result = await new ApiClient().logout()

  assert.equal(result.logoutUrl, 'http://idp.test/logout?token=fresh')
  assert.deepEqual(calls.map((call) => `${call.method} ${call.path} ${call.token ?? '-'}`), [
    'GET /api/csrf -',
    'POST /api/auth/logout old',
    'GET /api/csrf -',
    'POST /api/auth/logout fresh'
  ])
})

test('повторный отказ CSRF не зацикливает запросы и возвращается ошибкой', async (t) => {
  t.after(() => mock.restoreAll())
  const calls = serve([
    csrf('old'),
    () => json(403, apiError('CSRF_INVALID')),
    csrf('fresh'),
    () => json(403, apiError('CSRF_INVALID'))
  ])

  await assert.rejects(new ApiClient().command('/api/interactions', {}, 'key-1'), (error) => (
    error instanceof ApiError && error.code === 'CSRF_INVALID' && error.status === 403
  ))
  assert.equal(calls.length, 4)
})

test('отказ по правам не повторяется и не обновляет токен', async (t) => {
  t.after(() => mock.restoreAll())
  const calls = serve([
    csrf('old'),
    () => json(403, apiError('FORBIDDEN'))
  ])

  await assert.rejects(new ApiClient().command('/api/interactions', {}, 'key-2'), (error) => (
    error instanceof ApiError && error.code === 'FORBIDDEN'
  ))
  assert.equal(calls.length, 2)
})

test('чтение без токена CSRF не повторяется при 403', async (t) => {
  t.after(() => mock.restoreAll())
  const calls = serve([() => json(403, apiError('FORBIDDEN'))])

  await assert.rejects(new ApiClient().listDataSources(), (error) => isAccessDenied(error))
  assert.equal(calls.length, 1)
})

test('401 и 403 считаются потерей доступа, остальные ошибки нет', () => {
  assert.equal(isAccessDenied(new ApiError(apiError('UNAUTHENTICATED'), 401)), true)
  assert.equal(isAccessDenied(new ApiError(apiError('FORBIDDEN'), 403)), true)
  assert.equal(isAccessDenied(new ApiError(apiError('INTERNAL_ERROR'), 500)), false)
  assert.equal(isAccessDenied(new Error('network')), false)
})

test('фоновый опрос останавливается, когда шаг сообщает о потере доступа', async (t) => {
  mock.timers.enable({ apis: ['setInterval'] })
  t.after(() => mock.timers.reset())
  const answers = [true, true, false]
  let ticks = 0
  startPolling(async () => {
    ticks += 1
    return answers[ticks - 1] ?? true
  }, 60_000)
  await Promise.resolve()
  assert.equal(ticks, 1)

  for (let minute = 0; minute < 5; minute += 1) {
    mock.timers.tick(60_000)
    await Promise.resolve()
    await Promise.resolve()
  }

  assert.equal(ticks, 3)
})

test('остановка опроса снаружи прекращает запросы', async (t) => {
  mock.timers.enable({ apis: ['setInterval'] })
  t.after(() => mock.timers.reset())
  let ticks = 0
  const stop = startPolling(async () => {
    ticks += 1
    return true
  }, 1_000)
  await Promise.resolve()
  stop()
  mock.timers.tick(5_000)
  await Promise.resolve()

  assert.equal(ticks, 1)
})
