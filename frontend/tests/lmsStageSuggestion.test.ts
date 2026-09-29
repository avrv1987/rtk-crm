import assert from 'node:assert/strict'
import { test } from 'node:test'
import { findDuplicateTraining, findLmsStageSuggestion } from '../src/features/training/lmsStageSuggestionRules.ts'
import type { Interaction, InteractionStage, LearningSnapshot, TeacherTraining } from '../src/shared/api/client.ts'

const today = '2026-09-28'

const stage = (id: string, name: string, order: number): InteractionStage => ({ id, name, order, optional: false })

const interaction = (overrides: {
  currentStageId: string
  stages: InteractionStage[]
  stageCompletions?: Interaction['stageCompletions']
}): Interaction => ({
  id: 'interaction-1',
  organizationId: 'org-1',
  title: 'Работа',
  currentStageId: overrides.currentStageId,
  currentStageName: overrides.stages.find((item) => item.id === overrides.currentStageId)?.name ?? '',
  stages: overrides.stages,
  transitions: [],
  allowedTransitions: [],
  nextAction: null,
  nextActionAt: null,
  contactIds: [],
  programId: 'program-1',
  program: null,
  productIds: [],
  productAgreements: [],
  attachments: [],
  stageCompletions: overrides.stageCompletions ?? [],
  lastContactAt: null,
  version: 1,
  createdBy: 'user-1',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  marks: { status: 'ACTIVE', waiting: false, problem: false, risk: false }
} as unknown as Interaction)

const snapshot = (overrides: Partial<LearningSnapshot>): LearningSnapshot => ({
  mappingId: 'mapping-1',
  runKind: 'TEACHERS',
  courseId: 1,
  courseName: 'Курс повышения квалификации',
  groupId: null,
  groupName: null,
  participants: 12,
  teachers: 3,
  completed: 10,
  notCompleted: 2,
  unknown: 0,
  groupsCount: 0,
  runStartsOn: '2026-08-01',
  runEndsOn: '2026-09-15',
  observedAt: '2026-09-20T10:00:00Z',
  changedAt: '2026-09-20T10:00:00Z',
  ...overrides
} as unknown as LearningSnapshot)

const stages = [
  stage('s1', 'Сопровождение внедрения', 7),
  stage('s2', 'Обучение преподавателей', 8),
  stage('s3', 'Актуализация программы', 9),
  stage('s4', 'Повышение квалификации', 12)
]

test('предложение появляется, когда есть завершившие и этап ещё не отмечен', () => {
  const work = interaction({ currentStageId: 's2', stages })
  const suggestion = findLmsStageSuggestion(work, [snapshot({})], today)
  assert.notEqual(suggestion, null)
  assert.equal(suggestion?.stage.id, 's2')
  assert.equal(suggestion?.isCurrentStage, true)
  assert.equal(suggestion?.offerCycle, false)
  assert.equal(suggestion?.defaultTrainedOn, '2026-08-01')
})

test('предложение исчезает без данных о завершивших', () => {
  const work = interaction({ currentStageId: 's2', stages })
  assert.equal(findLmsStageSuggestion(work, [snapshot({ completed: null })], today), null)
})

test('предложение исчезает, если этап уже пройден по позиции текущего этапа', () => {
  const onlyTeacherStage = [stage('s1', 'Сопровождение внедрения', 7), stage('s2', 'Обучение преподавателей', 8), stage('s3', 'Актуализация программы', 9)]
  const work = interaction({ currentStageId: 's3', stages: onlyTeacherStage })
  assert.equal(findLmsStageSuggestion(work, [snapshot({})], today), null)
})

test('предложение исчезает после отметки этапа вне текущего (stage-completions)', () => {
  const onlyTeacherStage = [stage('s1', 'Сопровождение внедрения', 7), stage('s2', 'Обучение преподавателей', 8), stage('s3', 'Актуализация программы', 9)]
  const work = interaction({
    currentStageId: 's1',
    stages: onlyTeacherStage,
    stageCompletions: [{ stageId: 's2', completedOn: '2026-09-16', comment: null, eventId: 'e1', actorDisplayName: 'КАМ', markedAt: '2026-09-16T00:00:00Z' }]
  })
  assert.equal(findLmsStageSuggestion(work, [snapshot({})], today), null)
})

test('для «Повышения квалификации» предлагается новый цикл; идущий поток даёт дату начала, закончившийся — последний день', () => {
  const work = interaction({ currentStageId: 's4', stages })
  const running = findLmsStageSuggestion(work, [snapshot({ runEndsOn: '2026-10-05' })], today)
  assert.equal(running?.stage.id, 's4')
  assert.equal(running?.offerCycle, true)
  assert.equal(running?.defaultTrainedOn, '2026-08-01')
  const finished = findLmsStageSuggestion(work, [snapshot({})], today)
  assert.equal(finished?.defaultTrainedOn, '2026-09-14')
})

test('дата обучения не позже сегодняшней, если поток начнётся в будущем', () => {
  const work = interaction({ currentStageId: 's2', stages })
  const suggestion = findLmsStageSuggestion(work, [snapshot({ runStartsOn: '2026-10-01', runEndsOn: '2026-12-01' })], today)
  assert.equal(suggestion?.defaultTrainedOn, today)
})

test('запись об обучении по тому же курсу считается возможным дублем', () => {
  const training = { id: 't1', courseName: ' курс повышения квалификации ', trainedOn: '2026-08-01' } as unknown as TeacherTraining
  const other = { id: 't2', courseName: 'Другой курс', trainedOn: '2026-08-01' } as unknown as TeacherTraining
  assert.equal(findDuplicateTraining([other, training], snapshot({}))?.id, 't1')
  assert.equal(findDuplicateTraining([other], snapshot({})), null)
})

test('без потока обучения преподавателей ничего не предлагается, даже если этап есть', () => {
  const work = interaction({ currentStageId: 's2', stages })
  assert.equal(findLmsStageSuggestion(work, [snapshot({ runKind: 'STUDENTS' })], today), null)
})
