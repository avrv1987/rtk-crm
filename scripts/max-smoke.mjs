// Проверка модуля MAX на стенде с MAX_ENABLED=true и тестовым ботом. Против рабочего сервера не запускать.
//
// Переменные окружения (значения секретов скрипт не печатает):
//   MAX_SMOKE_ORIGIN          — адрес стенда, например https://crm-stand.example
//   MAX_SMOKE_SESSION_COOKIE  — значение cookie SESSION сотрудника-КАМ, вошедшего на стенд в браузере
//   MAX_SMOKE_BOT_TOKEN       — токен тестового бота (тот же, что введён в «Источники данных» → «Бот MAX»)
//   MAX_SMOKE_MAX_USER_ID     — id пользователя MAX, уже подключённого к этому КАМ (необязательно: без него
//                               проверяется только отказ поддельной initData)
//
// Запуск: node scripts/max-smoke.mjs
import { createHmac } from 'node:crypto'

const origin = (process.env.MAX_SMOKE_ORIGIN ?? '').replace(/\/+$/, '')
const sessionCookie = process.env.MAX_SMOKE_SESSION_COOKIE ?? ''
const botToken = process.env.MAX_SMOKE_BOT_TOKEN ?? ''
const maxUserId = process.env.MAX_SMOKE_MAX_USER_ID ?? ''

if (!origin.startsWith('https://')) {
  console.error('Задайте MAX_SMOKE_ORIGIN=https://<стенд>')
  process.exit(2)
}

let failures = 0
const check = (name, ok, details = '') => {
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}${details ? ` — ${details}` : ''}`)
  if (!ok) {
    failures += 1
  }
}

const request = async (path, init = {}) => {
  const response = await fetch(`${origin}${path}`, { redirect: 'manual', ...init })
  const text = await response.text()
  let body = null
  try {
    body = text === '' ? null : JSON.parse(text)
  } catch {
    body = null
  }
  return { status: response.status, headers: response.headers, body, text }
}

// Подпись initData по dev.max.ru/docs/webapps/validation: секрет = HMAC-SHA256("WebAppData", токен), подпись —
// hex HMAC-SHA256(секрет, пары без hash по алфавиту через \n).
export const signInitData = (pairs, token) => {
  const data = Object.keys(pairs).sort().map((key) => `${key}=${pairs[key]}`).join('\n')
  const secret = createHmac('sha256', 'WebAppData').update(token).digest()
  const hash = createHmac('sha256', secret).update(data).digest('hex')
  return `${Object.entries(pairs).map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&')}&hash=${hash}`
}

const config = await request('/api/config')
check('модуль включён (/api/config → modules.max)', config.body?.modules?.max === true)

const app = await request('/max/')
const csp = app.headers.get('content-security-policy') ?? ''
check('страница мини-приложения /max/ отдаётся', app.status === 200 && app.text.includes('st.max.ru/js/max-web-app.js'), `HTTP ${app.status}`)
check('заголовки /max/: мост st.max.ru и frame-ancestors доменов MAX', csp.includes('https://st.max.ru') && csp.includes('frame-ancestors https://max.ru'))
check('/max/ без X-Frame-Options DENY', !app.headers.has('x-frame-options'))

const event = JSON.stringify({ update_type: 'bot_started', timestamp: Date.now(), chat_id: 1, user: { user_id: 1, first_name: 'smoke', is_bot: false } })
const noSecret = await request('/api/max/webhook', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: event })
check('вебхук без секрета отклонён', noSecret.status === 401, `HTTP ${noSecret.status}`)
const forgedSecret = await request('/api/max/webhook', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Max-Bot-Api-Secret': 'forged-secret-value' },
  body: event
})
check('вебхук с поддельным секретом отклонён', forgedSecret.status === 401, `HTTP ${forgedSecret.status}`)

if (sessionCookie === '') {
  console.log('SKIP выдача кода: не задан MAX_SMOKE_SESSION_COOKIE')
} else {
  const cookie = `SESSION=${sessionCookie}`
  const csrf = await request('/api/csrf', { headers: { Cookie: cookie } })
  check('сессия КАМ на стенде действует', csrf.status === 200, `HTTP ${csrf.status}`)
  if (csrf.status === 200) {
    const csrfCookie = (csrf.headers.get('set-cookie') ?? '').match(/XSRF-TOKEN=([^;]+)/)?.[1]
    const headers = {
      Cookie: csrfCookie ? `${cookie}; XSRF-TOKEN=${csrfCookie}` : cookie,
      [csrf.body.headerName]: csrf.body.token
    }
    const link = await request('/api/max/link', { method: 'POST', headers })
    check('выдана одноразовая ссылка https://max.ru/<бот>?start=<код>',
      link.status === 200 && /^https:\/\/max\.ru\/[\w-]+\?start=[A-Za-z0-9]{24}$/.test(link.body?.link ?? ''), `HTTP ${link.status}`)
    const status = await request('/api/max/link', { headers: { Cookie: headers.Cookie } })
    check('состояние после выдачи — WAITING', status.body?.state === 'WAITING', status.body?.state ?? `HTTP ${status.status}`)
  }
}

if (botToken === '') {
  console.log('SKIP вход мини-приложения: не задан MAX_SMOKE_BOT_TOKEN')
} else {
  const now = Math.floor(Date.now() / 1000)
  const user = maxUserId === '' ? 1 : Number(maxUserId)
  const pairs = { auth_date: String(now), query_id: 'smoke', user: JSON.stringify({ id: user, first_name: 'smoke' }) }
  const forged = signInitData(pairs, `${botToken}-forged`)
  const rejected = await request('/api/max/app/session', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ initData: forged })
  })
  check('initData с чужой подписью отклонена', rejected.status === 401 && rejected.body?.code === 'MAX_INIT_DATA_INVALID', `HTTP ${rejected.status}`)
  const stale = signInitData({ ...pairs, auth_date: String(now - 2 * 3600) }, botToken)
  const expired = await request('/api/max/app/session', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ initData: stale })
  })
  check('initData старше часа отклонена', expired.body?.code === 'MAX_INIT_DATA_EXPIRED', `HTTP ${expired.status}`)
  if (maxUserId === '') {
    console.log('SKIP вход подключённого пользователя: не задан MAX_SMOKE_MAX_USER_ID')
  } else {
    const started = await request('/api/max/app/session', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ initData: signInitData(pairs, botToken) })
    })
    check('вход мини-приложения с подписанной initData', started.status === 200 && typeof started.body?.token === 'string', `HTTP ${started.status}`)
    if (started.status === 200) {
      const bearer = { Authorization: `Bearer ${started.body.token}` }
      const work = await request('/api/max/app/work?due=TODAY', { headers: bearer })
      check('«Моя работа» по сеансу MAX', work.status === 200 && work.body?.feed !== undefined, `HTTP ${work.status}`)
      const ended = await request('/api/max/app/session', { method: 'DELETE', headers: bearer })
      check('выход из сеанса', ended.status === 204, `HTTP ${ended.status}`)
      const after = await request('/api/max/app/me', { headers: bearer })
      check('после выхода токен не действует', after.status === 401, `HTTP ${after.status}`)
    }
  }
}

console.log(failures === 0 ? 'max-smoke: OK' : `max-smoke: ошибок ${failures}`)
process.exit(failures === 0 ? 0 : 1)
