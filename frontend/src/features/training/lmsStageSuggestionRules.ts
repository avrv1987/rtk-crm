import type { Interaction, InteractionStage, LearningSnapshot } from '../../shared/api/client'

/** Этапы, которые LMS закрывает фактом обучения преподавателей. Порядок не важен — ищем оба. */
const LMS_STAGE_NAMES = ['Обучение преподавателей', 'Повышение квалификации']

export type LmsStageSuggestion = {
  stage: InteractionStage
  isCurrentStage: boolean
  snapshot: LearningSnapshot
  offerCycle: boolean
  defaultTrainedOn: string
}

const stageAlreadyDone = (interaction: Interaction, stage: InteractionStage): boolean => {
  const currentStage = interaction.stages.find((candidate) => candidate.id === interaction.currentStageId)
  if (currentStage !== undefined && stage.order < currentStage.order) {
    return true
  }
  return interaction.stageCompletions.some((completion) => completion.stageId === stage.id)
}

const dateOnly = (isoInstant: string): string => isoInstant.slice(0, 10)

const minDate = (a: string, b: string): string => (a < b ? a : b)

/**
 * Ищет ближайший не отмеченный этап «Обучение преподавателей»/«Повышение квалификации», для которого есть
 * поток LMS с известным числом завершивших. Возвращает null, если предлагать нечего — так решают,
 * показывать ли предложение в карточке работы, ничего не меняя автоматически.
 */
export const findLmsStageSuggestion = (
  interaction: Interaction,
  teacherSnapshots: LearningSnapshot[],
  today: string
): LmsStageSuggestion | null => {
  const withCompletion = teacherSnapshots.filter(
    (snapshot) => snapshot.runKind === 'TEACHERS' && snapshot.completed !== null && snapshot.completed !== undefined
  )
  if (withCompletion.length === 0) {
    return null
  }
  const pendingStage = interaction.stages
    .filter((stage) => LMS_STAGE_NAMES.includes(stage.name) && !stageAlreadyDone(interaction, stage))
    .sort((a, b) => a.order - b.order)[0]
  if (pendingStage === undefined) {
    return null
  }
  const snapshot = [...withCompletion].sort((a, b) => b.changedAt.localeCompare(a.changedAt))[0]
  const defaultTrainedOn = minDate(snapshot.runEndsOn ?? dateOnly(snapshot.observedAt), today)
  return {
    stage: pendingStage,
    isCurrentStage: pendingStage.id === interaction.currentStageId,
    snapshot,
    offerCycle: pendingStage.name === 'Повышение квалификации',
    defaultTrainedOn
  }
}
