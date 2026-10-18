import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { cardHash, primaryAction, signingRuleHint, transitionsToward } from '../src/features/interactions/workCardModel.ts'
import { canRespond, closedOrganizationHint } from '../src/features/issues/issueModel.ts'
import {
  contactMarkMissingChannel,
  contactMarkPayload,
  defaultNoContactDays,
  readNoContactDays,
  saveNoContactDays,
  toggleParticipant
} from '../src/features/work/contactMark.ts'
import { withViewed } from '../src/features/work/workNews.ts'
import { feedGroupsFor } from '../src/features/work/workView.ts'
import { periodPhrase, reportTasks, taskMethod } from '../src/features/reports/reportTasks.ts'
import { currentUnsavedLabels, forgetUnsavedDraft, markUnsavedDraft } from '../src/features/interactions/unsavedDrafts.ts'

const source = (path: string) => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

test('адрес карточки строится из вуза взаимодействия и сохраняет параметры', () => {
  assert.equal(cardHash('org-b', 'work-1', '#/organizations/org-a/work-0?issues=1'), '#/organizations/org-b/work-1?issues=1')
  assert.equal(cardHash('org-b', 'work-1', '#/organizations/org-a'), '#/organizations/org-b/work-1')
})

test('панель прежнего вуза не получает взаимодействие другого вуза, чужой вуз в адресе исправляется', () => {
  const screen = source('features/organizations/OrganizationsScreen.tsx')
  assert.match(screen, /detailMatchesRoute = detailOrganizationId === undefined \|\| detailOrganizationId === selectedOrganizationId/)
  assert.equal((screen.match(/detailMatchesRoute && detailState\.kind === 'ready'/g) ?? []).length, 3)
  const panel = source('features/interactions/InteractionsPanel.tsx')
  assert.match(panel, /interaction\.organizationId !== organizationId\) \{[\s\S]{0,300}window\.location\.replace\(cardHash\(interaction\.organizationId/)
  assert.match(panel, /replaceState\(null, '', cardHash\(organizationId, id, window\.location\.hash\)\)/)
})

test('реестр: подпись под закрытым вузом не спорит с «Требует назначения», ответить может только ответственный', () => {
  assert.equal(closedOrganizationHint({ ownerManagerName: null }), 'у вуза нет КАМ, карточка вам недоступна')
  assert.equal(closedOrganizationHint({ ownerManagerName: 'Анна' }), 'вуз ведёт коллега, карточка вам недоступна')
  const me = { id: 'me', role: 'USER' }
  assert.equal(canRespond({ responsibleId: 'me', status: 'OPEN' }, me), true)
  assert.equal(canRespond({ responsibleId: 'me', status: 'RESOLVED' }, me), false)
  assert.equal(canRespond({ responsibleId: 'other', status: 'OPEN' }, me), false)
  assert.equal(canRespond({ responsibleId: 'me', status: 'OPEN' }, { id: 'me', role: 'MANAGEMENT' }), false)
})

test('отметка «был контакт»: без канала не отправляется, участники переключаются', () => {
  assert.equal(contactMarkPayload(null), null)
  assert.equal(contactMarkMissingChannel(null), false)
  assert.equal(contactMarkMissingChannel({ channel: '', participantIds: [] }), true)
  assert.equal(contactMarkPayload({ channel: '', participantIds: ['c1'] }), null)
  const draft = toggleParticipant(toggleParticipant({ channel: 'CALL', participantIds: [] }, 'c1'), 'c2')
  assert.deepEqual(contactMarkPayload(draft), { channel: 'CALL', participantIds: ['c1', 'c2'] })
  assert.deepEqual(contactMarkPayload(toggleParticipant(draft, 'c1')), { channel: 'CALL', participantIds: ['c2'] })
  const both = [source('features/work/StepCompletion.tsx'), source('features/interactions/InteractionsPanel.tsx')]
  both.forEach((text) => assert.match(text, /contact: contactMarkPayload\(/))
})

test('порог «нет контакта» по умолчанию 30 дней и хранится в браузере', () => {
  assert.equal(defaultNoContactDays, 30)
  assert.equal(readNoContactDays(null), 30)
  const values = new Map<string, string>()
  const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => void values.set(key, value) }
  assert.equal(saveNoContactDays(storage, 45), true)
  assert.equal(readNoContactDays(storage), 45)
  values.set('rtk-crm.work.no-contact-days', 'abc')
  assert.equal(readNoContactDays(storage), 30)
  assert.equal(readNoContactDays({ getItem: () => { throw new Error('blocked') } }), 30)
  assert.deepEqual(feedGroupsFor('NO_CONTACT'), [])
  assert.match(source('features/work/WorkList.tsx'), /noContactDays: current\.noContactDays \?\? undefined/)
  assert.match(source('features/control/ControlScreen.tsx'), /<ControlNoContact /)
})

test('«Новое по моим вузам»: просмотренная запись уходит к просмотренным и не считается', () => {
  const item = (eventId: string, viewed: boolean) => ({
    eventId, viewed, interactionId: 'w', organizationId: 'o', organizationName: 'Вуз', interactionTitle: 'Работа',
    kind: 'SITE_REQUEST' as const, summary: 'Заявка с сайта', occurredAt: '2026-10-18T10:00:00Z'
  })
  const news = { days: 7, unviewed: 2, items: [item('a', false), item('b', false), item('c', true)] }
  const after = withViewed(news, 'a')
  assert.equal(after.unviewed, 1)
  assert.deepEqual(after.items.map((value) => [value.eventId, value.viewed]), [['b', false], ['a', true], ['c', true]])
  assert.equal(withViewed(after, 'a'), after)
  assert.deepEqual(feedGroupsFor('NEWS'), [])
})

test('переход вперёд и возврат — разные списки, ближайший этап первым', () => {
  const stages = [{ id: 's0', order: 0 }, { id: 's1', order: 1 }, { id: 's2', order: 2 }, { id: 's3', order: 3 }]
  const options = [{ stageId: 's3' }, { stageId: 's0' }, { stageId: 's2' }, { stageId: 's1' }]
  assert.deepEqual(transitionsToward(options, stages, 's2', 'forward').map((option) => option.stageId), ['s3'])
  assert.deepEqual(transitionsToward(options, stages, 's2', 'back').map((option) => option.stageId), ['s1', 's0'])
  assert.deepEqual(primaryAction({
    canEdit: true, status: 'ACTIVE', hasNextStep: true, transitionCount: 0, agreementStageRole: null, agreementCount: 0, signingBlocked: null
  }), { kind: 'transition', label: 'Перейти на следующий этап', disabled: true, hint: 'Дальше по маршруту переходов нет' })
  const panel = source('features/interactions/InteractionsPanel.tsx')
  assert.match(panel, /label: 'Вернуться на этап…', onSelect: \(\) => openTransition\('back'\)/)
  assert.match(panel, /title=\{transitionDirection === 'back' \? 'Вернуться на этап' : 'Перейти на следующий этап'\}/)
  assert.match(panel, /'Отметить этап выполненным \(без перехода\)…'/)
  assert.match(panel, /required=\{transitionDirection === 'back' \|\|/)
  assert.match(source('features/interactions/RouteDisclosure.tsx'), /Вернуться на этап…/)
})

test('подсказка подписания меняется после подписания договора', () => {
  const draft = { number: 'Д-1', status: 'REVIEW' as const, concludedOn: null, validUntil: null, statusChangedAt: '2026-10-01T10:00:00Z' }
  const signed = { ...draft, status: 'ACTIVE' as const, concludedOn: '2026-10-10', validUntil: '2027-10-10' }
  assert.equal(signingRuleHint('SIGNING', [draft], '2026-10-18'), 'Этап завершится, когда договор будет подписан.')
  assert.equal(signingRuleHint('SIGNING', [draft, signed], '2026-10-18'), 'Договор подписан: этап «Подписание» можно завершить.')
  assert.equal(signingRuleHint(null, [signed], '2026-10-18'), null)
  assert.equal(signingRuleHint(null, [], '2026-10-18'), 'Этап «Подписание» завершится только с подписанным договором.')
  assert.equal(signingRuleHint('SIGNING', [{ ...signed, validUntil: '2026-10-01' }], '2026-10-18'), 'Этап завершится, когда договор будет подписан.')
})

test('описание задачи отчёта следует за выбранным периодом', () => {
  const done = reportTasks.find((task) => task.id === 'done')
  assert.ok(done)
  const today = '2026-10-18'
  assert.match(taskMethod(done, '2026-09-01', '2026-09-30', today), /«Работа менеджеров» за прошлый месяц:/)
  assert.match(taskMethod(done, '2026-10-01', '2026-10-31', today), /«Работа менеджеров» за этот месяц:/)
  assert.match(taskMethod(done, '2026-10-05', '2026-10-12', today), /за период с 05\.10\.2026 по 12\.10\.2026:/)
  assert.equal(periodPhrase('', '', today), 'без периода')
  const attention = reportTasks.find((task) => task.id === 'attention')
  assert.ok(attention)
  assert.equal(taskMethod(attention, '', '', today), attention.method)
})

test('после сохранения статуса, данных и отметки ожидания метка черновика снимается сразу', () => {
  const panels: [string, string][] = [
    ['features/interactions/InteractionStatusPanel.tsx', 'status'],
    ['features/interactions/InteractionDetailsForm.tsx', 'details'],
    ['features/interactions/InteractionFlagsForm.tsx', 'flags']
  ]
  for (const [path, key] of panels) {
    const text = source(path)
    const forget = text.indexOf(`forgetUnsavedDraft(\`card:\${interaction.id}:${key}\`)`)
    assert.ok(forget > 0, path)
    assert.ok(forget < text.indexOf('onChanged(updated)', forget), path)
  }
  markUnsavedDraft('card:w1:status', 'статус взаимодействия', true)
  assert.ok(currentUnsavedLabels().includes('статус взаимодействия'))
  forgetUnsavedDraft('card:w1:status')
  assert.ok(!currentUnsavedLabels().includes('статус взаимодействия'))
})
