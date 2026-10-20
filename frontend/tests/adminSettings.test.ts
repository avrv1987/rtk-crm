import assert from 'node:assert/strict'
import test from 'node:test'
import {
  dueText,
  moduleHealth,
  outdatedRoutes,
  settingDraftProblem,
  settingValueText,
  versionsText
} from '../src/features/admin/adminSettings.ts'
import { draftProblems, emptyDraft, payloadFromDraft } from '../src/features/admin/workflowTemplateDraft.ts'
import { diffRoutes, parseRouteFile, routeToFile, type Route } from '../src/features/admin/workflowVersions.ts'
import { isStuck } from '../src/features/control/controlModel.ts'

test('значение настройки показывается с единицей, флаг — словами; черновик проверяется по пределам', () => {
  assert.equal(settingValueText('DAYS', 30), '30 дн.')
  assert.equal(settingValueText('HOURS', 26), '26 ч')
  assert.equal(settingValueText('FLAG', 0), 'выключено')
  assert.equal(settingValueText('FLAG', 1), 'включено')
  const setting = { min: 1, max: 3650, unit: 'DAYS' as const }
  assert.equal(settingDraftProblem(setting, '12'), null)
  assert.equal(settingDraftProblem(setting, '0'), 'Целое число от 1 до 3650 дн.')
  assert.equal(settingDraftProblem(setting, '1.5'), 'Целое число от 1 до 3650 дн.')
  assert.equal(settingDraftProblem(setting, ''), 'Целое число от 1 до 3650 дн.')
})

test('норматив этапа важнее общего порога «долго на этапе» в обе стороны', () => {
  const item = { status: 'ACTIVE' as const, daysOnStage: 10, stageUnderApproval: false }
  assert.equal(isStuck(item, 30), false)
  assert.equal(isStuck({ ...item, stageNormDays: 5 }, 30), true)
  assert.equal(isStuck({ ...item, stageNormDays: 20 }, 7), false)
  assert.equal(isStuck({ ...item, stageNormDays: null }, 7), true)
  assert.equal(isStuck({ ...item, stageNormDays: 5, stageUnderApproval: true }, 30), false)
})

test('норматив этапа — необязательное целое от 1 до 3650, уходит в шаблон, файл и сравнение версий', () => {
  const draft = emptyDraft()
  draft.name = 'Маршрут'
  draft.stages = [{ ...draft.stages[0], name: 'Старт', normDays: 0 }]
  assert.ok(draftProblems(draft).includes('Норматив этапа 1 — целое число дней от 1 до 3650 или пусто.'))
  draft.stages[0].normDays = 14
  assert.equal(draftProblems(draft).length, 0)
  assert.equal(payloadFromDraft(draft).stages[0].normDays, 14)

  const before: Route = { name: 'М', stages: [{ id: 'a', name: 'Старт', order: 0, optional: false }], transitions: [] }
  const after: Route = { name: 'М', stages: [{ id: 'a', name: 'Старт', order: 0, optional: false, normDays: 7 }], transitions: [] }
  assert.deepEqual(diffRoutes(before, after).propertiesChanged, ['«Старт» — норматив: 7 дн.'])
  assert.deepEqual(diffRoutes(after, before).propertiesChanged, ['«Старт» — норматив: общий порог'])
  const parsed = parseRouteFile(JSON.stringify(routeToFile(after)))
  assert.ok('file' in parsed)
  assert.equal(parsed.file.stages[0].normDays, 7)
  const broken = { ...routeToFile(after), stages: [{ name: 'Старт', optional: false, normDays: 0 }] }
  assert.ok('error' in parseRouteFile(JSON.stringify(broken)))
})

test('сводка версий маршрута: напоминание только о командах с работами на прежних версиях', () => {
  const rows = [
    { teamId: 't1', teamName: 'А', templateId: 'p', templateName: 'Базовый', latestVersion: 3,
      versions: [{ version: 2, works: 4 }, { version: 3, works: 1 }], outdatedWorks: 4 },
    { teamId: 't2', teamName: 'Б', templateId: 'p', templateName: 'Базовый', latestVersion: 3,
      versions: [{ version: 3, works: 2 }], outdatedWorks: 0 }
  ]
  assert.deepEqual(outdatedRoutes(rows).map((row) => row.teamId), ['t1'])
  assert.equal(versionsText(rows[0]), 'v2: 4, v3 (последняя): 1')
})

test('состояние модуля: выключен, работает, без операций, с ошибками', () => {
  const base = { code: 'MAIL' as const, title: 'Почта', variable: 'MAIL_ENABLED', lastSuccess: null, lastError: null }
  assert.equal(moduleHealth({ ...base, enabled: false, lastSuccessAt: null, errors: null }).tone, 'off')
  assert.equal(moduleHealth({ ...base, enabled: true, lastSuccessAt: '2026-10-20T09:00:00Z', errors: 0 }).tone, 'ok')
  assert.equal(moduleHealth({ ...base, enabled: true, lastSuccessAt: null, errors: null }).tone, 'warning')
  assert.deepEqual(moduleHealth({ ...base, enabled: true, lastSuccessAt: '2026-10-20T09:00:00Z', errors: 2 }),
    { tone: 'error', text: 'Ошибок за 7 дней: 2' })
})

test('срок ответа на обращение: истёк, близко, обычный', () => {
  const format = (value: string) => value.split('-').reverse().join('.')
  assert.equal(dueText({ dueState: 'OVERDUE', dueOn: '2026-10-19' }, format), 'Срок истёк 19.10.2026')
  assert.equal(dueText({ dueState: 'SOON', dueOn: '2026-10-21' }, format), 'Срок 21.10.2026')
  assert.equal(dueText({ dueState: null, dueOn: '2026-11-01' }, format), 'до 01.11.2026')
})
