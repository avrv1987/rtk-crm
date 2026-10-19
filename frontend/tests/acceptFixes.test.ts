import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { test } from 'node:test'

const file = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')
// Внутренние документы (docs/*, кроме docs/public) в публичный репозиторий не выкладываются: там их проверки пропускаются.
const internalDoc = (path: string, check: (text: string) => void) => {
  if (existsSync(new URL(`../../${path}`, import.meta.url))) check(file(path))
}

test('роль сервиса записи Moodle: права на поиск по почте и назначение роли — в настройке демо-Moodle и в контракте', () => {
  const setup = file('infra/moodle/demo-setup.php')
  const writeRole = setup.slice(setup.indexOf('set_role_contextlevels($writeroleid'), setup.indexOf('role_assign($writeroleid'))
  for (const capability of ['moodle/site:viewuseridentity', 'moodle/role:assign']) {
    assert.match(writeRole, new RegExp(`'${capability}'`))
    internalDoc('docs/integrations/lms-api.yaml', (text) => assert.match(text, new RegExp(`\`${capability}\``)))
  }
  assert.match(setup, /core_role_set_assign_allowed\(\$writeroleid, \$studentroleid\)/)
})

test('wave4-smoke: ограничение, снятие ограничения и обезличивание передают основание', () => {
  const smoke = file('scripts/wave4-smoke.mjs')
  const calls = smoke.split('\n').filter((line) => /'\/restriction'|personal-data\/anonymization'/.test(line))
  assert.equal(calls.length, 3)
  for (const line of calls) {
    assert.match(line, /reason: 'SUBJECT_REQUEST'/)
  }
})

test('«Контроль» на телефоне: не меньше двух колонок плиток и на 320', () => {
  assert.match(file('frontend/src/features/control/control.css'), /minmax\(min\(8rem, calc\(\(100% - var\(--space-1\)\) \/ 2\)\), 1fr\)/)
})

// w48 (Н-А6): о ссылке форма говорит только в режиме SMTP — в режиме заглушки пароль показывается на экране.
test('«Новый сотрудник» при почте через SMTP говорит о ссылке, а после приглашения — кнопка «Готово»', () => {
  const dialog = file('frontend/src/features/admin/EmployeeAccountDialog.tsx')
  assert.match(dialog, /sendsLinks === 'SMTP'\n\s+\? 'CRM создаст учётную запись Keycloak и активный профиль и отправит на почту сотрудника одноразовую ссылку/)
  assert.match(dialog, /temporaryPassword === null && result\.credentials\.invitationSentTo \? 'Готово' : 'Я передал пароль, закрыть'/)
})

test('«Контроль» на телефоне: подпись плитки переносится по словам, а не рвётся посреди слова', () => {
  const css = file('frontend/src/features/control/control.css')
  const label = css.slice(css.indexOf('.control .stat-tile__label {'))
  const rule = label.slice(0, label.indexOf('}'))
  assert.match(rule, /overflow-wrap: break-word/)
  assert.match(rule, /hyphens: auto/)
  assert.doesNotMatch(rule, /overflow-wrap: anywhere/)
})

test('клиент MCP в Keycloak: в токен попадают только роли CRM — scope crm-mcp с ролями crm-*, без fullScopeAllowed', () => {
  const sh = file('scripts/bootstrap-demo.sh')
  assert.match(sh, /"clientId":"crm-mcp-client"[^\n]*"consentRequired":true,"fullScopeAllowed":false/)
  assert.match(sh, /client-scopes\/\$scope_id\/scope-mappings\/realm/)
  const ps = file('scripts/bootstrap-demo.ps1')
  assert.match(ps, /consentRequired = \$true\n\s+fullScopeAllowed = \$false/)
  assert.match(ps, /scope-mappings\/realm/)
})

test('сигнал LMS по устаревшим данным несёт пометку «данные устарели», как вкладка «Обучение»', () => {
  const item = file('frontend/src/features/work/LmsSignals.tsx')
  assert.match(item, /\{signal\.stale && <> <span className="source-badge">данные устарели<\/span><\/>\}/)
  assert.match(file('frontend/src/features/interactions/LearningSnapshots.tsx'), /<span className="source-badge">данные устарели<\/span>/)
  const signal = file('backend/src/main/resources/static/openapi.yaml')
  const schema = signal.slice(signal.indexOf('    LmsSignal:\n'), signal.indexOf('    LmsSignalAction:'))
  assert.match(schema, /observedAt, stale, dismissed/)
  assert.match(schema, /\n        stale:\n          type: boolean/)
})

test('письмо о возврате ответа координатору — отдельный вид в «Почте» администратора', () => {
  const yaml = file('backend/src/main/resources/static/openapi.yaml')
  assert.match(yaml, /PARTNER_REQUEST, PARTNER_RETURNED, PARTNER_REPLY/)
  internalDoc('docs/mail.md', (text) => assert.match(text, /\| Ответ возвращён на доработку \| координатор вуза \|/))
})
