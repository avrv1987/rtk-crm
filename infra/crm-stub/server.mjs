import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'

const token = process.env.CRM_TOKEN ?? ''
const port = Number(process.env.PORT ?? 8080)
const pageSize = Number(process.env.CRM_PAGE_SIZE ?? 4)
const keepReceived = 200
const maxBody = 1024 * 1024
const eventKinds = new Set(['STAGE_CHANGED', 'NEXT_STEP_CHANGED', 'MANAGER_CHANGED', 'AGREEMENT_STATUS'])
const load = (name) => JSON.parse(readFileSync(new URL(`./data/${name}.json`, import.meta.url), 'utf8'))
const organizations = load('organizations')
const contacts = load('contacts')
const received = []
const seen = new Set()

const send = (response, status, body) => {
  const payload = JSON.stringify(body)
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(payload) })
  response.end(payload)
}

const authorized = (request) => token !== '' && request.headers.authorization === `Bearer ${token}`

const page = (items, url) => {
  const number = Number(url.searchParams.get('page'))
  if (!Number.isInteger(number) || number < 1) {
    return { status: 400, body: { message: 'page is required' } }
  }
  const since = url.searchParams.get('updated_since')
  const sinceTime = since === null ? null : Date.parse(since)
  if (since !== null && Number.isNaN(sinceTime)) {
    return { status: 400, body: { message: 'updated_since must be ISO 8601' } }
  }
  const matching = items.filter((item) => sinceTime === null || Date.parse(item.updatedAt) >= sinceTime)
  const from = (number - 1) * pageSize
  return {
    status: 200,
    body: { items: matching.slice(from, from + pageSize), nextPage: from + pageSize < matching.length ? number + 1 : null }
  }
}

const rejection = (event) => {
  if (typeof event?.eventId !== 'string' || event.eventId === '') {
    return 'нет eventId'
  }
  if (!eventKinds.has(event.kind)) {
    return 'неизвестный вид события'
  }
  if (typeof event.organization?.id !== 'string') {
    return 'нет организации'
  }
  return null
}

const readBody = (request) => new Promise((resolve, reject) => {
  const chunks = []
  let size = 0
  request.on('data', (chunk) => {
    size += chunk.length
    if (size > maxBody) {
      reject(new Error('too large'))
      request.destroy()
      return
    }
    chunks.push(chunk)
  })
  request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
  request.on('error', reject)
})

const receive = async (request, response) => {
  let body
  try {
    body = JSON.parse(await readBody(request))
  } catch {
    send(response, 400, { message: 'body must be JSON' })
    return
  }
  if (typeof body?.batchId !== 'string' || !Array.isArray(body.events)) {
    send(response, 400, { message: 'batchId and events are required' })
    return
  }
  const answer = { accepted: 0, duplicates: 0, rejected: [] }
  for (const event of body.events) {
    const reason = rejection(event)
    if (reason !== null) {
      answer.rejected.push({ eventId: event?.eventId ?? '', reason })
    } else if (seen.has(event.eventId)) {
      answer.duplicates += 1
    } else {
      seen.add(event.eventId)
      received.unshift({ receivedAt: new Date().toISOString(), batchId: body.batchId, event })
      answer.accepted += 1
    }
  }
  received.length = Math.min(received.length, keepReceived)
  send(response, 200, answer)
}

createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://crm-stub')
  if (url.pathname === '/healthz') {
    send(response, 200, { status: 'ok' })
    return
  }
  if (!url.pathname.startsWith('/api/crm-exchange/')) {
    send(response, 404, { message: 'not found' })
    return
  }
  if (!authorized(request)) {
    send(response, 401, { message: 'unauthorized' })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/crm-exchange/organizations') {
    const result = page(organizations, url)
    send(response, result.status, result.body)
  } else if (request.method === 'GET' && url.pathname === '/api/crm-exchange/contacts') {
    const result = page(contacts, url)
    send(response, result.status, result.body)
  } else if (request.method === 'POST' && url.pathname === '/api/crm-exchange/events') {
    await receive(request, response)
  } else if (request.method === 'GET' && url.pathname === '/api/crm-exchange/events') {
    send(response, 200, { total: seen.size, received })
  } else {
    send(response, 404, { message: 'not found' })
  }
}).listen(port, '0.0.0.0')
