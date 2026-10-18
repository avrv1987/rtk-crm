#!/usr/bin/env node
// Проверка MCP на стенде с MCP_ENABLED=true. Вход — как у настоящего клиента: 401 → метаданные ресурса (RFC 9728) →
// Keycloak → код авторизации с PKCE в браузере → access token. Токен не печатается и никуда не сохраняется.
//
// Запуск: CRM_URL=http://rtk.localhost:8081 node scripts/mcp-smoke.mjs
//   MCP_CLIENT_ID — клиент Keycloak (по умолчанию crm-mcp-client);
//   MCP_CALLBACK_PORT — порт адреса возврата http://127.0.0.1:<порт>/callback (по умолчанию 8976, он же в MCP_REDIRECT_URIS);
//   MCP_SCOPE — запрашиваемые scope (по умолчанию «openid crm-mcp», с MCP_PLAN_INTERACTION_ID — плюс crm-mcp.write);
//   MCP_PLAN_INTERACTION_ID — взаимодействие КАМ для сценария записи: агент готовит план, сотрудник подтверждает его
//   в CRM по ссылке confirmUrl, скрипт ждёт итог через get_change_plan.
// Скрипт печатает ссылку входа: открыть её в браузере, войти учёткой КАМ или руководителя и подтвердить доступ.
import { createHash, randomBytes } from 'node:crypto'
import { createServer } from 'node:http'

const crmUrl = (process.env.CRM_URL ?? 'http://rtk.localhost:8081').replace(/\/$/, '')
const clientId = process.env.MCP_CLIENT_ID ?? 'crm-mcp-client'
const port = Number(process.env.MCP_CALLBACK_PORT ?? 8976)
const planInteractionId = process.env.MCP_PLAN_INTERACTION_ID
const scope = process.env.MCP_SCOPE ?? (planInteractionId ? 'openid crm-mcp crm-mcp.write' : 'openid crm-mcp')
const writeScope = scope.split(/\s+/).includes('crm-mcp.write')
const redirectUri = `http://127.0.0.1:${port}/callback`
const mcpUrl = `${crmUrl}/mcp`

const fail = (message) => {
  console.error(`mcp-smoke: ${message}`)
  process.exit(1)
}

const base64url = (buffer) => buffer.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

const rpc = async (token, method, params = {}, headers = {}) => {
  const response = await fetch(mcpUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      'MCP-Protocol-Version': '2025-11-25',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params })
  })
  return response
}

const discover = async () => {
  const anonymous = await rpc(null, 'tools/list')
  if (anonymous.status !== 401) {
    fail(`без токена ожидался 401, получен ${anonymous.status}; включён ли MCP_ENABLED?`)
  }
  const challenge = anonymous.headers.get('www-authenticate') ?? ''
  const metadataUrl = /resource_metadata="([^"]+)"/.exec(challenge)?.[1]
  if (!metadataUrl) {
    fail('в WWW-Authenticate нет resource_metadata')
  }
  const metadata = await (await fetch(metadataUrl)).json()
  const issuer = metadata.authorization_servers?.[0]
  if (!issuer) {
    fail('в метаданных ресурса нет authorization_servers')
  }
  const oidc = await (await fetch(`${issuer}/.well-known/openid-configuration`)).json()
  if (oidc.issuer !== issuer) {
    fail('issuer в OIDC Discovery не совпадает с метаданными ресурса')
  }
  console.log(`ресурс ${metadata.resource}, сервер авторизации ${issuer}`)
  return { resource: metadata.resource, oidc }
}

const authorize = ({ resource, oidc }) => new Promise((resolve, reject) => {
  const verifier = base64url(randomBytes(32))
  const state = base64url(randomBytes(16))
  const url = new URL(oidc.authorization_endpoint)
  url.search = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirectUri,
    scope,
    state,
    resource,
    code_challenge: base64url(createHash('sha256').update(verifier).digest()),
    code_challenge_method: 'S256'
  }).toString()
  const server = createServer(async (request, response) => {
    const callback = new URL(request.url ?? '/', redirectUri)
    if (callback.pathname !== '/callback') {
      response.writeHead(404).end()
      return
    }
    server.close()
    if (callback.searchParams.get('state') !== state || !callback.searchParams.get('code')) {
      response.writeHead(400).end('Вход не завершён')
      reject(new Error(`вход отклонён: ${callback.searchParams.get('error') ?? 'неверный state'}`))
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Готово, окно можно закрыть.')
    const token = await fetch(oidc.token_endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: clientId,
        code: callback.searchParams.get('code'),
        redirect_uri: redirectUri,
        code_verifier: verifier
      })
    })
    const body = await token.json()
    if (!token.ok || !body.access_token) {
      reject(new Error(`обмен кода не удался: ${body.error ?? token.status}`))
      return
    }
    resolve(body.access_token)
  })
  server.listen(port, '127.0.0.1', () => {
    console.log('Откройте в браузере и войдите учёткой КАМ или руководителя:')
    console.log(url.toString())
  })
})

const result = async (token, method, params) => {
  const response = await rpc(token, method, params)
  if (!response.ok) {
    fail(`${method}: HTTP ${response.status}`)
  }
  const body = await response.json()
  if (body.error) {
    fail(`${method}: ${body.error.message}`)
  }
  return body.result
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

// Запись через план: агент только готовит план, данные меняет CRM, когда сотрудник нажимает «Записать».
const planScenario = async (token) => {
  const nextActionAt = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString()
  const prepared = await result(token, 'tools/call', {
    name: 'prepare_note_update',
    arguments: {
      interactionId: planInteractionId,
      note: 'Проверка mcp-smoke: итог встречи',
      nextAction: 'Проверка mcp-smoke: следующий шаг',
      nextActionAt
    }
  })
  if (prepared.isError) {
    fail(`prepare_note_update: ${prepared.structuredContent?.error}`)
  }
  const plan = prepared.structuredContent.result
  if (plan.status !== 'PENDING' || !plan.confirmUrl) {
    fail(`prepare_note_update: ожидался PENDING со ссылкой, получен ${plan.status}`)
  }
  console.log('Откройте в CRM под тем же сотрудником, проверьте «было / станет» и нажмите «Записать» или «Отклонить»:')
  console.log(plan.confirmUrl)
  // Токен живёт 5 минут, поэтому ждём не дольше четырёх.
  for (let attempt = 0; attempt < 48; attempt++) {
    await sleep(5000)
    const status = await result(token, 'tools/call', { name: 'get_change_plan', arguments: { planId: plan.planId } })
    const value = status.structuredContent?.result?.status
    if (value === 'APPLIED' || value === 'REJECTED') {
      console.log(`get_change_plan: ${value}`)
      return
    }
    if (value !== 'PENDING') {
      fail(`get_change_plan: ${value}${status.structuredContent?.result?.errorCode ? ` (${status.structuredContent.result.errorCode})` : ''}`)
    }
  }
  fail('план не подтвердили за 4 минуты')
}

const main = async () => {
  const discovered = await discover()
  const token = await authorize(discovered)
  const init = await result(token, 'initialize', {
    protocolVersion: '2025-11-25',
    capabilities: {},
    clientInfo: { name: 'mcp-smoke', version: '1' }
  })
  console.log(`initialize: ревизия ${init.protocolVersion}, сервер ${init.serverInfo?.name}`)
  const tools = (await result(token, 'tools/list', {})).tools.map((tool) => tool.name)
  console.log(`tools/list: ${tools.join(', ')}`)
  const first = tools.includes('my_today') ? 'my_today' : 'team_control'
  for (const [name, args] of [[first, {}], ['find_interactions', { limit: 3 }]]) {
    const call = await result(token, 'tools/call', { name, arguments: args })
    if (call.isError) {
      fail(`${name}: ${call.structuredContent?.error}`)
    }
    // Печатаются только ключи и признаки полноты: содержимое карточек в журнал проверки не попадает.
    const data = call.structuredContent?.result ?? {}
    const summary = Object.entries(data)
      .map(([key, value]) => (value && typeof value === 'object' && 'total' in value
        ? `${key}: ${value.returned} из ${value.total ?? '?'}${value.isComplete ? '' : ' (не всё)'}`
        : key))
      .join('; ')
    console.log(`${name}: ${summary}`)
  }
  // Без scope записи инструментов плана быть не должно: клиент получает только чтение.
  if (tools.includes('prepare_note_update') !== writeScope) {
    fail(writeScope ? 'со scope crm-mcp.write нет prepare_note_update' : 'без scope crm-mcp.write виден prepare_note_update')
  }
  if (planInteractionId) {
    await planScenario(token)
  }
  const foreign = await rpc(token, 'tools/list', {}, { Origin: 'http://evil.example' })
  if (foreign.status !== 403) {
    fail(`чужой Origin должен давать 403, получен ${foreign.status}`)
  }
  console.log('чужой Origin: 403')
  console.log('mcp-smoke: OK')
}

main().catch((error) => fail(error.message))
