import { apiClient } from '../../shared/api/client.ts'
import type { components } from '../../shared/api/openapi'

export type InteractionKind = components['schemas']['InteractionKind']
export type InteractionKindRequest = components['schemas']['InteractionKindRequest']
export type InteractionKindRule = components['schemas']['InteractionKindRule']
export type InteractionKindRuleRequest = components['schemas']['InteractionKindRuleRequest']
export type InteractionKindResolution = components['schemas']['InteractionKindResolution']
export type KindOrganizationType = NonNullable<InteractionKindRule['organizationType']>

const path = (value: string) => encodeURIComponent(value)

export const interactionKindsApi = {
  available: () => apiClient.request<InteractionKind[]>('/api/interaction-kinds'),
  resolve: (query: { organizationId: string; kindId?: string; programId?: string; productIds?: string[] }) => {
    const params = new URLSearchParams({ organizationId: query.organizationId })
    if (query.kindId) params.set('kindId', query.kindId)
    if (query.programId) params.set('programId', query.programId)
    query.productIds?.forEach((id) => params.append('productId', id))
    return apiClient.request<InteractionKindResolution>(`/api/interaction-kinds/resolution?${params.toString()}`)
  },
  managed: () => apiClient.request<InteractionKind[]>('/api/admin/interaction-kinds'),
  create: (payload: InteractionKindRequest, key: string) => apiClient.command<InteractionKind>('/api/admin/interaction-kinds', payload, key),
  update: (id: string, payload: InteractionKindRequest, key: string) => (
    apiClient.command<InteractionKind>(`/api/admin/interaction-kinds/${path(id)}`, payload, key, 'PATCH')
  ),
  setTeamTemplate: (id: string, templateId: string | null, key: string) => (
    apiClient.command<InteractionKind>(`/api/admin/interaction-kinds/${path(id)}/team-template`, { templateId }, key, 'PUT')
  ),
  rules: () => apiClient.request<InteractionKindRule[]>('/api/admin/interaction-kind-rules'),
  createRule: (payload: InteractionKindRuleRequest, key: string) => (
    apiClient.command<InteractionKindRule>('/api/admin/interaction-kind-rules', payload, key)
  ),
  updateRule: (id: string, payload: InteractionKindRuleRequest, key: string) => (
    apiClient.command<InteractionKindRule>(`/api/admin/interaction-kind-rules/${path(id)}`, payload, key, 'PATCH')
  ),
  deleteRule: (id: string, version: number, key: string) => apiClient.command<void>(
    `/api/admin/interaction-kind-rules/${path(id)}?${new URLSearchParams({ version: version.toString() })}`, undefined, key, 'DELETE'
  )
}

export const organizationTypeLabels: Record<KindOrganizationType, string> = {
  UNIVERSITY: 'Вуз',
  SCHOOL: 'Школа',
  COLLEGE: 'Колледж',
  OPEN_ENROLLMENT: 'Открытый набор'
}

/** Условия правила одной строкой: «Вуз · программа «Python» · продукт «1С»»; пустые условия не показываются. */
export const ruleConditions = (rule: Pick<InteractionKindRule, 'organizationType' | 'programName' | 'productName'>): string => [
  rule.organizationType ? organizationTypeLabels[rule.organizationType] : null,
  rule.programName ? `программа «${rule.programName}»` : null,
  rule.productName ? `продукт «${rule.productName}»` : null
].filter((part): part is string => part !== null).join(' · ')

type RuleConditions = Pick<InteractionKindRule, 'organizationType' | 'programId' | 'productId' | 'priority'>

const compatible = (left: string | null | undefined, right: string | null | undefined) => (left ?? null) === null
  || (right ?? null) === null || left === right

// Правила с тем же приоритетом, которые могут подойти одной и той же карточке: каждое условие совпадает или пусто
// хотя бы у одного из правил. Между ними решает число условий, затем порядок вида — форма об этом предупреждает (Н-15).
export const overlappingRules = <T extends RuleConditions & { id: string }>(draft: RuleConditions & { id: string | null }, rules: T[]): T[] =>
  rules.filter((rule) => rule.id !== draft.id && rule.priority === draft.priority
    && compatible(rule.organizationType, draft.organizationType)
    && compatible(rule.programId, draft.programId)
    && compatible(rule.productId, draft.productId))

// Точный двойник (те же условия): «подойдут оба» здесь неверно — сервер такое правило не сохранит (Н-А5).
export const sameConditions = (left: RuleConditions, right: RuleConditions): boolean =>
  (left.organizationType ?? null) === (right.organizationType ?? null)
  && (left.programId ?? null) === (right.programId ?? null) && (left.productId ?? null) === (right.productId ?? null)

const kindSourceText: Record<InteractionKindResolution['kindSource'], string> = {
  SELECTED: 'выбран',
  RULE: 'подобран по правилу',
  FIRST_ACTIVE: 'вид по умолчанию'
}

const templateSourceText: Record<InteractionKindResolution['templateSource'], string> = {
  TEAM_KIND: 'шаблон команды для вида',
  KIND: 'шаблон вида',
  DEFAULT: 'шаблон по умолчанию'
}

/** Подсказка формы создания: какой вид и маршрут получит карточка, если шаблон не выбран вручную. */
export const resolutionHint = (resolution: InteractionKindResolution): string => (
  `Вид «${resolution.kindName}» (${kindSourceText[resolution.kindSource]}), маршрут «${resolution.templateName}» — `
  + `${templateSourceText[resolution.templateSource]}.`
)

/** Подпись пустого варианта в выборе вида: что подставит сервер, если вид не выбран. */
export const automaticKindLabel = (resolution: InteractionKindResolution | null): string => (
  resolution === null || resolution.kindSource === 'SELECTED'
    ? 'Подобрать по правилам'
    : `По правилам: ${resolution.kindName}`
)

/** Шаблон, который действует для вида в команде пользователя: свой командный, иначе шаблон вида. */
export const effectiveKindTemplate = (kind: Pick<InteractionKind, 'teamTemplateName' | 'templateName'>): string => (
  kind.teamTemplateName ?? kind.templateName ?? 'шаблон по умолчанию'
)

/** Операция смены обязательности для будущего этапа: переключает текущее значение. */
export const optionalityOperation = (stage: { id: string; optional: boolean }) => ({
  type: 'SET_OPTIONAL' as const,
  id: stage.id,
  optional: !stage.optional
})

export const optionalityLabel = (stage: { optional: boolean }) => (
  stage.optional ? 'Этап станет обязательным.' : 'Этап станет необязательным: его можно будет пропустить с комментарием.'
)
