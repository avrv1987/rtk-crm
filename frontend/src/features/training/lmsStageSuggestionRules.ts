import type { Interaction, InteractionStage, LearningSnapshot, TeacherTraining } from '../../shared/api/client'

// Правила LMS находят этапы маршрута по названию: смыслового поля у этапа нет.
export const CLASSES_STAGE = 'Занятия'
export const TEACHER_TRAINING_STAGE = 'Обучение преподавателей'
export const PROGRAM_UPDATE_STAGE = 'Актуализация программы'
export const QUALIFICATION_STAGE = 'Повышение квалификации'

/** Этапы, которые LMS закрывает фактом обучения преподавателей. Порядок не важен — ищем оба. */
const LMS_STAGE_NAMES = [TEACHER_TRAINING_STAGE, QUALIFICATION_STAGE]

// ponytail: «Занятия», этапы обучения и повышения квалификации на сервере задаются настройками app.lms-signals и
// app.work; здесь их значения по умолчанию. Если настройки поменяют, список нужно поправить вместе с ними.
const LMS_STAGE_DEPENDENCIES: Record<string, string> = {
  [CLASSES_STAGE]: 'сигнал «В LMS появились обучающиеся» с переходом к занятиям и сверка этапа с LMS в «Контроле»'
    + ' и отчёте «Работа менеджеров»',
  [TEACHER_TRAINING_STAGE]: 'подсказка «Отметить этап выполненным по данным LMS» и напоминание о новом цикле обучения',
  [PROGRAM_UPDATE_STAGE]: 'подсказка «Перейти к этапу «Актуализация программы»» после курса преподавателей',
  [QUALIFICATION_STAGE]: 'подсказка «Отметить этап выполненным по данным LMS», кнопка «Запустить новый цикл»'
    + ' и напоминания о повышении квалификации и новом цикле обучения'
}

/**
 * Предупреждение редактора шаблонов: какие этапы из правил LMS пропадут после сохранения (переименованы или удалены)
 * и что перестанет работать. Сохранение не блокируется — маршрут решает тот, кто его настраивает.
 */
export const lmsStageWarning = (before: string[], after: string[]): string | null => {
  const kept = new Set(after.map((name) => name.trim()))
  const lost = [...new Set(before.map((name) => name.trim()))]
    .filter((name) => LMS_STAGE_DEPENDENCIES[name] !== undefined && !kept.has(name))
  if (lost.length === 0) {
    return null
  }
  return 'Подсказки и сигналы LMS ищут этапы по названию. После сохранения перестанут работать: '
    + lost.map((name) => `этап «${name}» — ${LMS_STAGE_DEPENDENCIES[name]}`).join('; ')
    + '. Чтобы они работали, оставьте прежнее название.'
}

// Редактор шаблона сравнивает черновик с исходным шаблоном: при правке — с ним самим, при «Создать на основе» — с
// шаблоном, с которого начата копия. Без исходного (новый шаблон) сравнивать не с чем.
export const templateLmsWarning = (
  source: { stages: { name: string }[] } | null,
  draft: { stages: { name: string }[] }
): string | null => (
  source === null ? null : lmsStageWarning(source.stages.map((stage) => stage.name), draft.stages.map((stage) => stage.name))
)

export type LmsStageSuggestion = {
  stage: InteractionStage
  isCurrentStage: boolean
  snapshot: LearningSnapshot
  offerCycle: boolean
  defaultTrainedOn: string
  /** Завершили не все записанные: порог этапа пока «хотя бы один», поэтому подсказка об этом предупреждает. */
  partial: boolean
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
    pendingStage.name === QUALIFICATION_STAGE && lastDay !== null && lastDay <= today ? lastDay : runStart,
    today
  )
  return {
    stage: pendingStage,
    isCurrentStage: pendingStage.id === interaction.currentStageId,
    snapshot,
    // Повышение квалификации повторяется: после него в карточке предлагается запустить новый цикл.
    offerCycle: pendingStage.name === QUALIFICATION_STAGE,
    defaultTrainedOn,
    partial: (snapshot.completed ?? 0) < snapshot.participants
  }
}

export type ProgramUpdateSuggestion = {
  stage: InteractionStage
  isCurrentStage: boolean
  /** Маршрут разрешает перейти к этапу прямо из текущего. */
  reachable: boolean
  snapshot: LearningSnapshot
}

/**
 * Цепочка этапов 9 → 10: когда преподаватели вуза завершили курс ППС (поток вида «Обучение преподавателей») и этап
 * «Обучение преподавателей» уже пройден или отмечен, подсказывает перейти к «Актуализации программы». Завершённый курс —
 * повод обновить программу, а не доказательство, поэтому этап отмечают вручную. Пока этот этап не отмечен, подсказка
 * о повышении квалификации по тому же потоку не показывается: этапы идут по порядку.
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
    reachable: interaction.allowedTransitions.some((option) => option.stageId === stage.id),
    snapshot: [...withCompletion].sort((a, b) => b.changedAt.localeCompare(a.changedAt))[0]
  }
}

export type StageMarkApi = {
  createTraining: () => Promise<Interaction>
  getInteraction: () => Promise<Interaction>
  completeStage: (version: number) => Promise<Interaction>
}

export type StageMarkResult =
  | { kind: 'done'; interaction: Interaction }
  | { kind: 'stageFailed'; saved: Interaction; error: unknown }

/**
 * Запись обучения и отметка этапа — два запроса. Если запись сохранена, а отметка нет, результат держит сохранённую
 * карточку, и повтор отправляет только отметку. Повтор берёт свежую версию карточки: прежняя могла устареть, а
 * отметка могла пройти, хотя ответ не дошёл, — тогда второй раз этап не отмечается.
 */
export const markStageByLms = async (
  api: StageMarkApi,
  stageId: string,
  markStage: boolean,
  saved: Interaction | null
): Promise<StageMarkResult> => {
  const training = saved ?? await api.createTraining()
  if (!markStage) {
    return { kind: 'done', interaction: training }
  }
  try {
    const current = saved === null ? training : await api.getInteraction()
    if (current.stageCompletions.some((completion) => completion.stageId === stageId)) {
      return { kind: 'done', interaction: current }
    }
    return { kind: 'done', interaction: await api.completeStage(current.version) }
  } catch (error) {
    return { kind: 'stageFailed', saved: training, error }
  }
}
