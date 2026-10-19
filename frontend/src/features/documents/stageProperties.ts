import type { components } from '../../shared/api/openapi'

export type StageLmsRole = components['schemas']['StageLmsRole']
export type StageDocument = components['schemas']['StageDocument']
export type DocumentKind = components['schemas']['DocumentKind']

/** Свойства этапа шаблона и карточки (V59): договор, роль для LMS, обязательные документы. */
export type StageProperties = {
  requiresAgreement: boolean
  lmsRole: StageLmsRole | null
  documentKinds: string[]
}

export const noStageProperties = (): StageProperties => ({ requiresAgreement: false, lmsRole: null, documentKinds: [] })

export const lmsRoles: readonly StageLmsRole[] = ['TEACHER_TRAINING', 'CLASSES', 'QUALIFICATION']

export const lmsRoleLabels: Record<StageLmsRole, string> = {
  TEACHER_TRAINING: 'Обучение преподавателей',
  CLASSES: 'Идут занятия',
  QUALIFICATION: 'Повышение квалификации'
}

/** Свойства из ответа API: у старых ответов и файлов полей может не быть. */
export type StagePropertiesInput = {
  requiresAgreement?: boolean | null
  lmsRole?: StageLmsRole | null
  documentKinds?: readonly string[] | null
}

export const stagePropertiesOf = (stage: StagePropertiesInput | undefined): StageProperties => ({
  requiresAgreement: stage?.requiresAgreement === true,
  lmsRole: stage?.lmsRole ?? null,
  documentKinds: [...(stage?.documentKinds ?? [])]
})

export const documentKindName = (kinds: readonly DocumentKind[], code: string): string => (
  kinds.find((kind) => kind.code === code)?.name ?? code
)

/** Виды для выбора в новом документе: только действующие; текущий вид документа остаётся в списке, даже если в архиве. */
export const selectableDocumentKinds = (kinds: readonly DocumentKind[], current?: string): DocumentKind[] => (
  kinds.filter((kind) => !kind.archived || kind.code === current)
)

export const missingDocuments = (checklist: readonly StageDocument[]): string[] => (
  checklist.filter((document) => !document.attached).map((document) => document.name)
)

// Тот же текст сервер ставит в начало комментария перехода (InteractionService.missingDocumentsText).
export const missingDocumentsText = (checklist: readonly StageDocument[]): string | null => {
  const missing = missingDocuments(checklist)
  return missing.length === 0 ? null : `Не хватает документов: ${missing.join(', ')}.`
}

/**
 * Подстановка в комментарий перехода вперёд: если документов не хватает и комментарий ещё не начат с перечня, перечень
 * ставится в начало. Назад и при полном комплекте комментарий не меняется.
 */
export const transitionCommentWithDocuments = (
  comment: string,
  checklist: readonly StageDocument[],
  forward: boolean
): string => {
  const prefix = forward ? missingDocumentsText(checklist) : null
  if (prefix === null || comment.startsWith(prefix)) {
    return comment
  }
  return comment.trim().length === 0 ? `${prefix} ` : `${prefix} ${comment}`
}

/** Комментарий к переходу без документов содержателен, только если после перечня написано объяснение. */
export const documentsCommentExplained = (comment: string, checklist: readonly StageDocument[]): boolean => {
  const prefix = missingDocumentsText(checklist)
  return prefix === null || comment.trim().replace(prefix, '').trim().length > 0
}

/** Короткая подпись свойств этапа для списков: «договор · LMS: Идут занятия · документов: 2». */
export const stagePropertiesSummary = (properties: StageProperties): string => [
  properties.requiresAgreement ? 'нужен действующий договор' : null,
  properties.lmsRole === null ? null : `LMS: ${lmsRoleLabels[properties.lmsRole]}`,
  properties.documentKinds.length === 0 ? null : `обязательных документов: ${properties.documentKinds.length}`
].filter((part): part is string => part !== null).join(' · ')
