import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { missingStepIds } from '../src/features/control/controlModel.ts'
import { enrolmentQueueSearch } from '../src/features/enrolment/enrolmentQueueModel.ts'
import type { WorkControl } from '../src/shared/api/client.ts'

const work = (interactionId: string, due: string) => ({ interactionId, due })

test('«Задать шаг» берёт из отбора «Контроля» только карточки без шага или срока', () => {
  const control = {
    teams: [
      { managers: [{ works: [work('a', 'NO_NEXT_STEP'), work('b', 'OVERDUE')] }] },
      { managers: [{ works: [work('c', 'NO_NEXT_STEP'), work('d', 'IN_ORDER')] }, { works: [] }] }
    ]
  } as unknown as WorkControl
  assert.deepEqual(missingStepIds(control), ['a', 'c'])
})

test('очередь «Что обработать» передаёт поток, группу и смещение только когда они заданы', () => {
  assert.equal(enrolmentQueueSearch({}), '')
  assert.equal(enrolmentQueueSearch({ streamId: 's1', kind: 'READY', offset: 50 }), '?streamId=s1&kind=READY&offset=50')
  assert.equal(enrolmentQueueSearch({ kind: 'LMS_ERROR', offset: 0 }), '?kind=LMS_ERROR')
})

test('шаблоны истории, договоров и переноса лежат рядом с шаблоном каталога', () => {
  for (const name of ['history-import-template.xlsx', 'agreements-import-template.xlsx', 'flow-transfer-template.xlsx']) {
    const bytes = readFileSync(new URL(`../public/${name}`, import.meta.url))
    assert.equal(bytes.subarray(0, 2).toString('latin1'), 'PK', name)
  }
})
