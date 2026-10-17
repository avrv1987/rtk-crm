import type { Me, WorkflowTemplate } from '../../shared/api/client'

type TemplateLike = Pick<WorkflowTemplate, 'teamId' | 'defaultTemplate'> & { id: string }

export type RowMenuKey = 'history' | 'default' | 'delete'

export type RowMenuEntry = { key: RowMenuKey; label: string; danger?: boolean }

export type TemplateRowActions = {
  /** Главное действие строки: правка своего шаблона или копия базового (руководитель). */
  primary: 'edit' | 'copy'
  primaryLabel: string
  menu: RowMenuEntry[]
  /** Серая пометка вместо недоступного «Удалить». */
  note: string | null
}

export const baseTemplateNote = 'Базовый шаблон меняет администратор.'
export const defaultTemplateNote = 'Шаблон по умолчанию удалять нельзя.'

/** Администратор правит только общие шаблоны, руководитель — только шаблоны своей команды. */
export const canEditTemplate = (role: Me['role'], template: Pick<WorkflowTemplate, 'teamId'>) => (
  role === 'ADMIN' ? template.teamId === null : template.teamId !== null
)

export const defaultActionLabel = (
  role: Me['role'],
  template: TemplateLike,
  teamDefaultTemplateId: string | null
): string | null => {
  if (role === 'ADMIN') {
    return template.defaultTemplate || template.teamId !== null ? null : 'Сделать шаблоном по умолчанию'
  }
  if (template.teamId === null) {
    return template.defaultTemplate && teamDefaultTemplateId !== null ? 'Вернуть общий шаблон по умолчанию' : null
  }
  return template.id === teamDefaultTemplateId ? null : 'Сделать шаблоном команды по умолчанию'
}

export const templateScopeLabel = (template: TemplateLike, teamDefaultTemplateId: string | null) => {
  if (template.id === teamDefaultTemplateId) {
    return 'Шаблон команды по умолчанию: новые взаимодействия команды создаются по нему'
  }
  if (template.defaultTemplate) {
    return teamDefaultTemplateId === null ? 'Шаблон по умолчанию' : 'Общий шаблон по умолчанию (команда использует свой)'
  }
  return template.teamId === null ? 'Общий шаблон' : 'Шаблон команды'
}

/** Шаблон, по которому реально создаются новые взаимодействия (у руководителя — свой, если задан). */
export const isEffectiveDefault = (template: TemplateLike, teamDefaultTemplateId: string | null) => (
  template.id === teamDefaultTemplateId || (template.defaultTemplate && teamDefaultTemplateId === null)
)

export const templateRowActions = (
  role: Me['role'],
  template: TemplateLike,
  teamDefaultTemplateId: string | null
): TemplateRowActions => {
  const baseForLeader = role === 'LEADER' && template.teamId === null
  const menu: RowMenuEntry[] = [{ key: 'history', label: 'История версий' }]
  const makeDefault = defaultActionLabel(role, template, teamDefaultTemplateId)
  if (makeDefault !== null) {
    menu.push({ key: 'default', label: makeDefault })
  }
  const note = baseForLeader ? baseTemplateNote : template.defaultTemplate ? defaultTemplateNote : null
  if (note === null) {
    menu.push({ key: 'delete', label: 'Удалить', danger: true })
  }
  return {
    primary: baseForLeader ? 'copy' : 'edit',
    primaryLabel: baseForLeader ? 'Создать на основе базового' : 'Редактировать',
    menu,
    note
  }
}
