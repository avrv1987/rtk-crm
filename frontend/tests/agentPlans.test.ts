import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canDecide, statusText } from '../src/features/agentPlans/agentPlan.ts'
import { parseRoute, sectionsFor } from '../src/app/routing.ts'

const modules = { enrolment: false, partner: false, mcp: true }

test('план ИИ-агента открывается по ссылке у КАМ и руководителя, но не у «Руководства» и администратора', () => {
  assert.deepEqual(parseRoute('#/agent-plans/3f2b'), { section: 'agent-plans', organizationId: '3f2b', interactionId: undefined, query: '' })
  assert.ok(sectionsFor('USER', false, modules).includes('agent-plans'))
  assert.ok(sectionsFor('LEADER', false, modules).includes('agent-plans'))
  assert.ok(!sectionsFor('MANAGEMENT', false, modules).includes('agent-plans'))
  assert.ok(!sectionsFor('ADMIN', false, modules).includes('agent-plans'))
})

test('без модуля MCP маршрут плана ИИ-агента не регистрируется ни у одной роли', () => {
  for (const off of [{ enrolment: false, partner: false }, { enrolment: false, partner: false, mcp: false }]) {
    for (const role of ['USER', 'LEADER', 'MANAGEMENT', 'ADMIN'] as const) {
      assert.ok(!sectionsFor(role, false, off).includes('agent-plans'), role)
    }
  }
})

test('кнопки только у ожидающего плана; итог каждого статуса говорит, изменилось ли взаимодействие', () => {
  assert.ok(canDecide({ status: 'PENDING' }))
  for (const status of ['APPLIED', 'REJECTED', 'EXPIRED', 'STALE', 'FAILED'] as const) {
    assert.ok(!canDecide({ status }))
    const text = statusText({ status, cardChanged: false })
    assert.ok(status === 'APPLIED' ? text.startsWith('Записано') : text.includes('не изменилось'), status)
  }
  assert.match(statusText({ status: 'PENDING', cardChanged: false }), /Агент сам записать не может/)
  assert.match(statusText({ status: 'PENDING', cardChanged: true }), /Карточку изменили/)
})
