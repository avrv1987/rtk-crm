import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'

const projectRoot = path.resolve(import.meta.dirname, '..')
const envFile = path.resolve(process.argv[2] || path.join(projectRoot, '.env.local'))
const phase = process.env.REPORT_SMOKE_PHASE || 'all'
const stateFile = process.env.REPORT_SMOKE_STATE || path.join(os.tmpdir(), 'rtk-report-persistence-smoke.json')
const owner = process.env.REPORT_SMOKE_OWNER || 'kam-a'
const foreign = process.env.REPORT_SMOKE_FOREIGN || 'kam-b'

const assert = (condition, message) => {
  if (!condition) throw new Error(message)
}

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const environment = Object.fromEntries(
  fs.readFileSync(envFile, 'utf8').split(/\r?\n/)
    .filter((line) => line.includes('=') && !line.trimStart().startsWith('#'))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)])
)
const password = environment.DEMO_USER_PASSWORD
const origin = (environment.PUBLIC_ORIGIN || 'http://rtk.localhost:8081').replace(/\/$/, '')

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')
const isoDate = (daysAgo) => new Date(Date.now() - daysAgo * 86400000).toISOString().slice(0, 10)

const compose = (...args) => execFileSync('docker', ['compose', '--env-file', envFile, ...args], {
  cwd: projectRoot,
  encoding: 'utf8',
  stdio: ['ignore', 'pipe', 'inherit']
}).trim()

async function login(username) {
  const cookies = new Map()
  const cookieHeader = () => [...cookies].map(([name, value]) => `${name}=${value}`).join('; ')
  const hop = async (url, init = {}) => {
    const response = await fetch(url, { ...init, headers: { ...(init.headers || {}), Cookie: cookieHeader() }, redirect: 'manual' })
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(';')
      const index = pair.indexOf('=')
      cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
    return response
  }
  const follow = async (response, url) => {
    for (let hops = 0; hops < 10 && response.status >= 300 && response.status < 400; hops += 1) {
      url = new URL(response.headers.get('location'), url).toString()
      response = await hop(url)
    }
    return { response, url }
  }
  const start = await follow(await hop(origin + '/api/auth/login'), origin + '/api/auth/login')
  assert(start.response.status === 200, `${username}: Keycloak did not return a login page (${start.response.status})`)
  const formTag = ((await start.response.text()).match(/<form[^>]*id="kc-form-login"[^>]*>/) || [])[0]
  const action = formTag && (formTag.match(/action="([^"]+)"/) || [])[1]
  assert(action, `${username}: Keycloak login form not found`)
  const actionUrl = new URL(action.replace(/&amp;/g, '&'), start.url).toString()
  const submitted = await hop(actionUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password, credentialId: '' }).toString()
  })
  const landed = await follow(submitted, actionUrl)
  assert(landed.response.status === 200, `${username}: login did not return to the CRM (${landed.response.status})`)
  let csrf
  const request = async (method, url, body) => {
    const headers = {}
    if (method !== 'GET') {
      csrf ||= await (await hop(origin + '/api/csrf')).json()
      headers[csrf.headerName] = csrf.token
      headers['Idempotency-Key'] = randomUUID()
      headers['Content-Type'] = 'application/json'
    }
    const response = await hop(origin + url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const bytes = Buffer.from(await response.arrayBuffer())
    return { status: response.status, bytes, json: () => JSON.parse(bytes.toString('utf8')) }
  }
  const me = await request('GET', '/api/me')
  assert(me.status === 200, `${username}: no CRM session after login, /api/me ${me.status}`)
  return request
}

async function create() {
  const request = await login(owner)
  const order = await request('POST', '/api/reports', {
    kind: 'PORTFOLIO', format: 'XLSX', from: isoDate(365), to: isoDate(0), periodBasis: 'ACTIVITY', filters: {}, columns: []
  })
  assert(order.status === 202, `report order returned ${order.status}`)
  const { jobId } = order.json()
  let job
  for (let attempt = 0; attempt < 200; attempt += 1) {
    job = (await request('GET', `/api/report-jobs/${jobId}`)).json()
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED') break
    await pause(300)
  }
  assert(job.status === 'SUCCEEDED' && job.resultReady, `report job ${jobId} ended as ${job.status}`)
  const download = await request('GET', `/api/report-jobs/${jobId}/result`)
  assert(download.status === 200 && download.bytes.length > 0, `fresh report download returned ${download.status}`)
  const state = { origin, jobId, size: download.bytes.length, sha256: sha256(download.bytes) }
  fs.writeFileSync(stateFile, JSON.stringify(state, null, 2))
  console.log(`created: job ${jobId}, ${state.size} bytes, sha256 ${state.sha256}, state ${stateFile}`)
}

async function recreate() {
  const before = compose('ps', '-q', 'backend')
  compose('up', '-d', '--force-recreate', '--no-deps', '--wait', 'backend')
  const after = compose('ps', '-q', 'backend')
  assert(before && after && before !== after, `backend container was not recreated (${before} -> ${after})`)
  console.log(`recreated: backend ${before.slice(0, 12)} -> ${after.slice(0, 12)}`)
}

async function verify() {
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  assert(state.origin === origin, `state was created for ${state.origin}, env file points to ${origin}`)
  const request = await login(owner)
  const job = await request('GET', `/api/report-jobs/${state.jobId}`)
  assert(job.status === 200 && job.json().status === 'SUCCEEDED' && job.json().resultReady, `job ${state.jobId}: ${job.status} ${job.bytes.toString('utf8').slice(0, 200)}`)
  const download = await request('GET', `/api/report-jobs/${state.jobId}/result`)
  assert(download.status === 200, `owner download returned ${download.status} ${download.status === 410 ? download.bytes.toString('utf8').slice(0, 200) : ''}`)
  assert(sha256(download.bytes) === state.sha256, `downloaded file differs from the original (${download.bytes.length} bytes, expected ${state.size})`)
  const other = await login(foreign)
  const foreignJob = await other('GET', `/api/report-jobs/${state.jobId}`)
  const foreignFile = await other('GET', `/api/report-jobs/${state.jobId}/result`)
  assert(foreignJob.status === 404 && foreignFile.status === 404, `${foreign} sees the job: ${foreignJob.status}/${foreignFile.status}`)
  console.log(`verified: ${owner} downloads the same ${state.size} bytes, ${foreign} gets 404`)
}

try {
  assert(password, `DEMO_USER_PASSWORD is missing in ${envFile}`)
  assert(['all', 'create', 'verify'].includes(phase), `unknown REPORT_SMOKE_PHASE ${phase}`)
  if (phase !== 'verify') await create()
  if (phase === 'all') await recreate()
  if (phase !== 'create') await verify()
  console.log('report-persistence-smoke: PASS')
} catch (error) {
  console.error(`report-persistence-smoke: FAIL: ${error.message}`)
  process.exit(1)
}
