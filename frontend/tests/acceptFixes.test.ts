import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const file = (path: string) => readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

test('роль сервиса записи Moodle: права на поиск по почте и назначение роли — в настройке демо-Moodle и в контракте', () => {
  const setup = file('infra/moodle/demo-setup.php')
  const writeRole = setup.slice(setup.indexOf('set_role_contextlevels($writeroleid'), setup.indexOf('role_assign($writeroleid'))
  for (const capability of ['moodle/site:viewuseridentity', 'moodle/role:assign']) {
    assert.match(writeRole, new RegExp(`'${capability}'`))
    assert.match(file('docs/integrations/lms-api.yaml'), new RegExp(`\`${capability}\``))
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

test('«Новый сотрудник» при включённой почте говорит о ссылке, а после приглашения — кнопка «Готово»', () => {
  const dialog = file('frontend/src/features/admin/EmployeeAccountDialog.tsx')
  assert.match(dialog, /getModules\(\)\.mail\n\s+\? 'CRM создаст учётную запись Keycloak и активный профиль и отправит на почту сотрудника одноразовую ссылку/)
  assert.match(dialog, /temporaryPassword === null && result\.credentials\.invitationSentTo \? 'Готово' : 'Я передал пароль, закрыть'/)
})
