import assert from 'node:assert/strict'
import { test } from 'node:test'
import { initialStack, moscowDateTime, nextStepErrors, pop, push, readInitData } from '../src/max/startup.ts'
import { MaxApiError, createMaxApi, startupProblem } from '../src/max/maxApi.ts'

test('initData берётся из моста MAX, а без него — из фрагмента #WebAppData', () => {
  assert.equal(readInitData({ initData: 'auth_date=1&hash=a' }, '#WebAppData=ignored'), 'auth_date=1&hash=a')
  assert.equal(
    readInitData(undefined, '#WebAppData=auth_date%3D1%26hash%3Da&WebAppPlatform=web&WebAppVersion=26.2.8'),
    'auth_date=1&hash=a'
  )
  assert.equal(readInitData({ initData: '' }, ''), null)
  assert.equal(readInitData(undefined, '#/work'), null)
})

test('первый экран по payload кнопки, «Назад» не уходит дальше ленты', () => {
  assert.deepEqual(initialStack(null, false), [{ screen: 'work' }])
  assert.deepEqual(initialStack('i_0123456789abcdef0123456789abcdef', false), [
    { screen: 'work' }, { screen: 'card', id: '01234567-89ab-cdef-0123-456789abcdef' }
  ])
  assert.deepEqual(initialStack('control', false), [{ screen: 'work' }])
  assert.deepEqual(initialStack('control', true), [{ screen: 'work' }, { screen: 'control' }])
  assert.deepEqual(initialStack('i_../admin', true), [{ screen: 'work' }])
  const stack = push([{ screen: 'work' }], { screen: 'card', id: 'x' })
  assert.deepEqual(pop(stack), [{ screen: 'work' }])
  assert.deepEqual(pop(pop(stack)), [{ screen: 'work' }])
})

test('следующий шаг обязателен целиком, срок — по Москве', () => {
  assert.deepEqual(nextStepErrors('Позвонить', '2026-10-21T10:00'), {})
  assert.deepEqual(Object.keys(nextStepErrors('Позвонить', '')), ['nextActionAt'])
  assert.deepEqual(Object.keys(nextStepErrors(' ', '2026-10-21T10:00')), ['nextAction'])
  assert.equal(moscowDateTime('2026-10-21T10:00'), '2026-10-21T10:00:00+03:00')
  assert.equal(moscowDateTime('21.10.2026'), null)
})

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

test('клиент держит токен в памяти, ставит Bearer и один раз входит заново после 401', async () => {
  const calls: { url: string; init?: RequestInit }[] = []
  let sessions = 0
  const fetchImpl = async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    if (url === '/api/max/app/session') {
      sessions++
      return json(200, { token: `t${sessions}`, expiresAt: '2026-10-18T12:00:00Z', idleMinutes: 30, role: 'USER' })
    }
    const auth = new Headers(init?.headers).get('Authorization')
    return auth === 'Bearer t2' ? json(200, { ok: true }) : json(401, { code: 'MAX_SESSION_EXPIRED', message: 'истёк' })
  }
  const api = createMaxApi('auth_date=1&hash=a', fetchImpl)
  const result = await api.comment('id-1', { version: 1, text: 't', nextStep: { nextAction: 'a', nextActionAt: '2026-10-21T10:00:00+03:00' } }, 'key-1')
  assert.deepEqual(result, { ok: true })
  assert.equal(sessions, 2)
  const commentCalls = calls.filter((call) => call.url.endsWith('/comments'))
  assert.equal(commentCalls.length, 2)
  assert.deepEqual(commentCalls.map((call) => new Headers(call.init?.headers).get('Idempotency-Key')), ['key-1', 'key-1'])
  assert.equal(JSON.parse(String(calls[0].init?.body)).initData, 'auth_date=1&hash=a')
  assert.ok(calls.every((call) => call.init?.credentials === undefined))
})

test('просроченная initData при повторном входе даёт понятное сообщение, а не бесконечный повтор', async () => {
  let requests = 0
  const fetchImpl = async (url: string) => {
    requests++
    return url === '/api/max/app/session'
      ? json(401, { code: 'MAX_INIT_DATA_EXPIRED', message: 'устарело' })
      : json(401, { code: 'MAX_SESSION_EXPIRED', message: 'истёк' })
  }
  const api = createMaxApi('x', fetchImpl)
  await assert.rejects(api.work('TODAY', 0), (error: unknown) => error instanceof MaxApiError && error.code === 'MAX_INIT_DATA_EXPIRED')
  assert.equal(requests, 1)
  assert.match(startupProblem(new MaxApiError(401, 'MAX_INIT_DATA_EXPIRED', '')), /Закройте его и откройте снова/)
  assert.match(startupProblem(new MaxApiError(403, 'MAX_NOT_LINKED', '')), /Подключить MAX/)
  assert.match(startupProblem(new Error('сеть')), /Проверьте интернет/)
})
