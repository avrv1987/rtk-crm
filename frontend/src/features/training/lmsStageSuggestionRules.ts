import type { Interaction, InteractionStage, LearningSnapshot, TeacherTraining } from '../../shared/api/client'

/** Этапы, которые LMS закрывает фактом обучения преподавателей. Порядок не важен — ищем оба. */
const LMS_STAGE_NAMES = ['Обучение преподавателей', 'Повышение квалификации']

export type LmsStageSuggestion = {
  stage: InteractionStage
  isCurrentStage: boolean
  snapshot: LearningSnapshot
  offerCycle: boolean
  defaultTrainedOn: string
}

// Этап выполнен, если текущий этап уже дальше него по порядку или он явно отмечен: пропущенный этап повторно не
// предлагается.
const stageAlreadyDone = (interaction: Interaction, stage: InteractionStage): boolean => {
  const currentStage = interaction.stages.find((candidate) => candidate.id === interaction.currentStageId)
  if (currentStage !== undefined && stage.order < currentStage.order) {
    return true
  }
  return interaction.stageCompletions.some((completion) => completion.stageId === stage.id)
}

// Дата берётся из ISO-строки без пересчёта часового пояса: подсказка лишь предлагает дату, менеджер подтверждает её
// сам.
const dateOnly = (isoInstant: string): string => isoInstant.slice(0, 10)

const minDate = (a: string, b: string): string => (a < b ? a : b)

// runEndsOn не входит в поток (это первый день после него), последний день потока — на сутки раньше; сдвиг в UTC не
// зависит от часового пояса браузера.
const dayBefore = (value: string): string => {
  const shifted = new Date(`${value}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() - 1)
  return shifted.toISOString().slice(0, 10)
}

const courseKey = (name: string): string => name.trim().toLowerCase()

// Курс считается уже учтённым по названию без учёта регистра и краевых пробелов: вторая запись об обучении по тому же
// курсу предупреждается, а не создаётся молча.
export const findDuplicateTraining = (
  trainings: TeacherTraining[],
  snapshot: LearningSnapshot
): TeacherTraining | null => (
  trainings.find((training) => courseKey(training.courseName) === courseKey(snapshot.courseName)) ?? null
)

/**
 * Ищет ближайший не отмеченный этап «Обучение преподавателей»/«Повышение квалификации», для которого есть
 * поток LMS, где завершил хотя бы один преподаватель. Возвращает null, если предлагать нечего — так решают,
 * показывать ли предложение в карточке работы, ничего не меняя автоматически.
 */
export const findLmsStageSuggestion = (
  interaction: Interaction,
  teacherSnapshots: LearningSnapshot[],
  today: string
): LmsStageSuggestion | null => {
  // Нулевое и неизвестное число завершивших — не повод закрывать этап: при 0 этап ещё никто не прошёл,
  // а при выключенном отслеживании завершения в Moodle числа нет совсем.
  const withCompletion = teacherSnapshots.filter(
    (snapshot) => snapshot.runKind === 'TEACHERS' && snapshot.completed !== null && snapshot.completed !== undefined && snapshot.completed > 0
  )
  if (withCompletion.length === 0) {
    return null
  }
  // Этапы ищутся по названию, а не по идентификатору: маршрут настраивается, у разных шаблонов идентификаторы разные.
  // Берётся ближайший не пройденный.
  const pendingStage = interaction.stages
    .filter((stage) => LMS_STAGE_NAMES.includes(stage.name) && !stageAlreadyDone(interaction, stage))
    .sort((a, b) => a.order - b.order)[0]
  if (pendingStage === undefined) {
    return null
  }
  // Берётся поток, изменившийся последним: у него самые свежие данные.
  const snapshot = [...withCompletion].sort((a, b) => b.changedAt.localeCompare(a.changedAt))[0]
  const runStart = snapshot.runStartsOn ?? dateOnly(snapshot.observedAt)
  const lastDay = snapshot.runEndsOn ? dayBefore(snapshot.runEndsOn) : null
  // Дата по умолчанию: для повышения квалификации — последний день потока, если он прошёл, иначе начало потока; не
  // позже сегодня, чтобы не отметить обучение в будущем.
  const defaultTrainedOn = minDate(
    pendingStage.name === 'Повышение квалификации' && lastDay !== null && lastDay <= today ? lastDay : runStart,
    today
  )
  return {
    stage: pendingStage,
    isCurrentStage: pendingStage.id === interaction.currentStageId,
    snapshot,
    // Повышение квалификации повторяется: после него в карточке предлагается запустить новый цикл.
    offerCycle: pendingStage.name === 'Повышение квалификации',
    defaultTrainedOn
  }
}

// Этапы цепочки ищутся по названию, как и выше: маршрут работы настраиваемый.
const TEACHER_TRAINING_STAGE = 'Обучение преподавателей'
const PROGRAM_UPDATE_STAGE = 'Актуализация программы'

export type ProgramUpdateSuggestion = {
  stage: InteractionStage
  isCurrentStage: boolean
  snapshot: LearningSnapshot
}

/**
 * Цепочка этапов 9 → 10: когда преподаватели вуза завершили курс ППС (поток вида «Обучение преподавателей») и этап
 * «Обучение преподавателей» уже пройден или отмечен, подсказывает отметить «Актуализацию программы». Пока этот этап
 * не отмечен, подсказка о повышении квалификации по тому же потоку не показывается: этапы идут по порядку.
 */
export const findProgramUpdateSuggestion = (
  interaction: Interaction,
  teacherSnapshots: LearningSnapshot[]
): ProgramUpdateSuggestion | null => {
  const withCompletion = teacherSnapshots.filter(
    (snapshot) => snapshot.runKind === 'TEACHERS' && snapshot.completed !== null && snapshot.completed !== undefined && snapshot.completed > 0
  )
  if (withCompletion.length === 0) {
    return null
  }
  const training = interaction.stages.find((stage) => stage.name === TEACHER_TRAINING_STAGE)
  if (training !== undefined && !stageAlreadyDone(interaction, training)) {
    return null
  }
  const stage = interaction.stages
    .filter((candidate) => candidate.name === PROGRAM_UPDATE_STAGE && !stageAlreadyDone(interaction, candidate))
    .sort((a, b) => a.order - b.order)[0]
  if (stage === undefined) {
    return null
  }
  return {
    stage,
    isCurrentStage: stage.id === interaction.currentStageId,
    snapshot: [...withCompletion].sort((a, b) => b.changedAt.localeCompare(a.changedAt))[0]
  }
}
