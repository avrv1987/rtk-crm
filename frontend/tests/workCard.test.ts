import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  agreementFact,
  historyMatches,
  newestFirst,
  primaryAction,
  routeWindow,
  signingBlock,
  type PrimaryContext
} from '../src/features/interactions/workCardModel.ts'

const base: PrimaryContext = {
  canEdit: true,
  status: 'ACTIVE',
  hasNextStep: true,
  transitionCount: 2,
  agreementStageRole: null,
  agreementCount: 0,
  signingBlocked: null
}

test('главная кнопка: обычный случай — переход на следующий этап', () => {
  assert.deepEqual(primaryAction(base), { kind: 'transition', label: 'Перейти на следующий этап', disabled: false })
})

test('главной кнопки нет у роли только для просмотра', () => {
  assert.equal(primaryAction({ ...base, canEdit: false }), null)
})

test('главная кнопка по контексту: статус, договор, правило подписания, шаг, нет переходов', () => {
  assert.equal(primaryAction({ ...base, status: 'PAUSED' })?.kind, 'resume')
  assert.equal(primaryAction({ ...base, status: 'COMPLETED', agreementStageRole: 'SIGNING' })?.kind, 'resume')
  assert.equal(primaryAction({ ...base, agreementStageRole: 'EXCHANGE' })?.label, 'Создать договор')
  assert.equal(primaryAction({ ...base, agreementStageRole: 'EXCHANGE', agreementCount: 1 })?.kind, 'transition')
  const blocked = primaryAction({ ...base, agreementStageRole: 'SIGNING', agreementCount: 1, signingBlocked: 'Переведите договор' })
  assert.deepEqual(blocked, { kind: 'openAgreement', label: 'Перейти к договору', hint: 'Переведите договор' })
  assert.equal(primaryAction({ ...base, hasNextStep: false })?.label, 'Задать следующий шаг')
  assert.deepEqual(primaryAction({ ...base, transitionCount: 0 }), {
    kind: 'transition', label: 'Перейти на следующий этап', disabled: true, hint: 'Из текущего этапа переходы не разрешены'
  })
})

const stages = [
  { id: 's1', order: 0 },
  { id: 's2', order: 1 },
  { id: 'sign', order: 2 },
  { id: 's4', order: 3 },
  { id: 's5', order: 4 }
]
const work = (agreements: { number: string; status: 'DRAFT' | 'SIGNING' | 'ACTIVE' | 'COMPLETED'; concludedOn?: string | null; validUntil?: string | null; statusChangedAt: string }[], signingRuleEnabled = true) => ({
  signingRuleEnabled,
  stages: [{ stageId: 's2', role: 'EXCHANGE' as const }, { stageId: 'sign', role: 'SIGNING' as const }],
  agreements
})

test('правило «Подписание»: уход вперёд с подписания или через него без подписанного договора блокируется', () => {
  assert.match(signingBlock(work([]), stages, 'sign', 's4') ?? '', /Создайте договор/)
  assert.match(signingBlock(work([]), stages, 's1', 's5') ?? '', /Создайте договор/)
  const pending = work([
    { number: 'Д-1', status: 'DRAFT', statusChangedAt: '2026-10-01T10:00:00Z' },
    { number: 'Д-2', status: 'SIGNING', statusChangedAt: '2026-10-05T10:00:00Z' }
  ])
  assert.match(signingBlock(pending, stages, 'sign', 's4') ?? '', /договор № Д-2 в статус «Действует»/)
})

test('правило «Подписание»: «Действует» с истёкшим сроком не пускает дальше, «Завершён» пускает', () => {
  const signed = { number: 'Д-5', concludedOn: '2025-10-01', statusChangedAt: '2025-10-01T10:00:00Z' }
  const expired = work([{ ...signed, status: 'ACTIVE', validUntil: '2026-10-14' }])
  assert.match(signingBlock(expired, stages, 'sign', 's4', '2026-10-18') ?? '', /Срок договора № Д-5 истёк 14\.10\.2026: продлите срок/)
  assert.equal(signingBlock(expired, stages, 'sign', 's4', '2026-10-14'), null)
  assert.equal(signingBlock(work([{ ...signed, status: 'COMPLETED', validUntil: '2026-10-14' }]), stages, 'sign', 's4', '2026-10-18'), null)
})

test('правило «Подписание» не мешает возврату, переходу до подписания, подписанному договору и выключенной настройке', () => {
  assert.equal(signingBlock(work([]), stages, 'sign', 's2'), null)
  assert.equal(signingBlock(work([]), stages, 's1', 'sign'), null)
  assert.equal(signingBlock(work([{ number: 'Д-1', status: 'ACTIVE', concludedOn: '2026-10-02', statusChangedAt: '2026-10-02T10:00:00Z' }]), stages, 'sign', 's4'), null)
  assert.match(signingBlock(work([{ number: 'Д-1', status: 'ACTIVE', concludedOn: null, statusChangedAt: '2026-10-02T10:00:00Z' }]), stages, 'sign', 's4') ?? '', /Д-1/)
  assert.equal(signingBlock(work([], false), stages, 'sign', 's4'), null)
  assert.equal(signingBlock(null, stages, 'sign', 's4'), null)
})

test('факт «Договор с вузом»: действующий договор, «Создать» на этапах документов, иначе «Нет»', () => {
  const agreements = work([
    { number: 'Д-1', status: 'COMPLETED', concludedOn: '2025-01-01', statusChangedAt: '2026-10-09T10:00:00Z' },
    { number: 'Д-2', status: 'SIGNING', statusChangedAt: '2026-10-05T10:00:00Z' }
  ])
  const fact = agreementFact(agreements, 's4')
  assert.equal(fact.kind === 'agreement' ? fact.agreement.number : '', 'Д-2')
  assert.equal(agreementFact(work([]), 'sign').kind, 'create')
  assert.equal(agreementFact(work([]), 's2').kind, 'create')
  assert.equal(agreementFact(work([]), 's1').kind, 'none')
  assert.equal(agreementFact(null, 's1').kind, 'none')
})

test('свёрнутый маршрут показывает текущий этап и соседей, остальное — числом', () => {
  assert.deepEqual(routeWindow(13, 0), { from: 0, to: 1, hiddenBefore: 0, hiddenAfter: 11 })
  assert.deepEqual(routeWindow(13, 6), { from: 5, to: 7, hiddenBefore: 5, hiddenAfter: 5 })
  assert.deepEqual(routeWindow(13, 12), { from: 11, to: 12, hiddenBefore: 11, hiddenAfter: 0 })
  assert.deepEqual(routeWindow(1, 0), { from: 0, to: 0, hiddenBefore: 0, hiddenAfter: 0 })
  assert.deepEqual(routeWindow(5, -1), { from: 0, to: 1, hiddenBefore: 0, hiddenAfter: 3 })
})

test('отбор истории на клиенте и порядок от новых к старым', () => {
  assert.equal(historyMatches('COMMENTED', false, 'COMMENTS'), true)
  assert.equal(historyMatches('TRANSITIONED', false, 'COMMENTS'), false)
  assert.equal(historyMatches('PLAN_UPDATED', false, 'STAGES'), true)
  assert.equal(historyMatches('COMMENTED', true, 'DOCUMENTS'), true)
  assert.equal(historyMatches('ATTACHMENT_DELETED', false, 'DOCUMENTS'), true)
  assert.equal(historyMatches('STATUS_CHANGED', false, 'STAGES'), false)
  assert.equal(historyMatches('STATUS_CHANGED', false, 'ALL'), true)
  const events = [{ id: 'a', occurredAt: '2026-10-01T10:00:00Z' }, { id: 'b', occurredAt: '2026-10-03T10:00:00Z' }]
  assert.deepEqual(newestFirst(events).map((event) => event.id), ['b', 'a'])
})
