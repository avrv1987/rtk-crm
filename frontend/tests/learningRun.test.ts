import assert from 'node:assert/strict'
import { test } from 'node:test'
import { notFinishedRow, runPhase } from '../src/features/interactions/learningRun.ts'

const run = { participants: 10, completed: 4, notCompleted: 5, runStartsOn: '2026-09-01', runEndsOn: '2026-10-01' }

test('фаза потока определяется по дате: конец потока не входит в обучение', () => {
  assert.equal(runPhase(run, '2026-08-31'), 'upcoming')
  assert.equal(runPhase(run, '2026-09-01'), 'running')
  assert.equal(runPhase(run, '2026-09-30'), 'running')
  assert.equal(runPhase(run, '2026-10-01'), 'finished')
  assert.equal(runPhase({ ...run, runEndsOn: null }, '2026-09-15'), 'unknown')
})

test('в идущем потоке «Учатся сейчас» — участники минус завершившие', () => {
  assert.deepEqual(notFinishedRow(run, '2026-09-15'), { label: 'Учатся сейчас', value: 6 })
  assert.deepEqual(notFinishedRow({ ...run, completed: 10 }, '2026-09-15'), { label: 'Учатся сейчас', value: 0 })
})

test('без отслеживания завершения число не выдумывается', () => {
  assert.deepEqual(notFinishedRow({ ...run, completed: null, notCompleted: null }, '2026-09-15'), { label: 'Учатся сейчас', value: null })
  assert.deepEqual(notFinishedRow({ ...run, completed: undefined, notCompleted: undefined }, '2026-10-05'), { label: 'Не завершили', value: null })
})

test('у закончившегося потока — «Не завершили», у не начавшегося строки нет', () => {
  assert.deepEqual(notFinishedRow(run, '2026-10-05'), { label: 'Не завершили', value: 5 })
  assert.equal(notFinishedRow(run, '2026-08-01'), null)
  assert.deepEqual(notFinishedRow({ ...run, runStartsOn: null }, '2026-09-15'), { label: 'Не завершили', value: 5 })
})
