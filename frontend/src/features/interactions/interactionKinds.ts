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
  /** Для отборов: и отключённые виды, их карточки остаются (аудит видов, С-2). */
  forFilters: () => apiClient.request<InteractionKind[]>('/api/interaction-kinds?includeInactive=true'),
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

/** Текст подтверждения отключения вида: сколько у него идущих карточек и что с ними будет (аудит видов, С-2). */
export const kindDisableWarning = (name: string, interactionCount: number): string => (
  `Незавершённых взаимодействий вида «${name}»: ${interactionCount}. Они сохранят вид и маршрут, отбор по виду останется`
  + ' в «Моей работе», «Контроле» и отчётах с пометкой «не действует». Новые карточки этот вид не получат, его правила перестанут'
  + ' действовать.'
)

/** Подпись вида в отборах: отключённый вид помечен, но остаётся — по нему ещё есть карточки. */
export const kindFilterLabel = (kind: Pick<InteractionKind, 'name' | 'active'>): string => (
  kind.active ? kind.name : `${kind.name} (не действует)`
)

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

type RuleForNotes = RuleConditions & Pick<InteractionKindRule, 'id' | 'kindName' | 'organizationType' | 'programName' | 'productName'>
  & { active?: boolean; kindActive?: boolean }

// Правило a подходит каждой карточке, которой подходит b: каждое условие a пусто или совпадает с условием b.
const coversField = (a: string | null | undefined, b: string | null | undefined) => (a ?? null) === null || a === b
const covers = (a: RuleConditions, b: RuleConditions) => coversField(a.organizationType, b.organizationType)
  && coversField(a.programId, b.programId) && coversField(a.productId, b.productId)

/**
 * Предупреждения формы правила о пересечениях с действующими правилами любого приоритета (аудит видов, Н-4): называет
 * правило, которое выиграет, и правило, которое никогда не сработает.
 */
export const ruleOverlapNotes = (draft: RuleConditions & { id: string | null; active?: boolean }, rules: RuleForNotes[]):
  { id: string; text: string }[] => {
  if (draft.active === false) return []
  return rules
    .filter((rule) => rule.id !== draft.id && rule.active !== false && rule.kindActive !== false
      && compatible(rule.organizationType, draft.organizationType)
      && compatible(rule.programId, draft.programId)
      && compatible(rule.productId, draft.productId))
    .map((rule) => {
      const name = `«${ruleConditions(rule)} → ${rule.kindName}»`
      if (rule.priority === draft.priority) {
        return {
          id: rule.id,
          text: sameConditions(rule, draft)
            ? `Правило с теми же условиями уже есть: ${name}. Измените условия или правьте существующее правило.`
            : `Пересекается с правилом ${name} того же приоритета: подойдут оба — выиграет правило с большим числом условий,`
              + ' при равенстве — вид выше в справочнике.'
        }
      }
      const draftWins = draft.priority < rule.priority
      if (draftWins && covers(draft, rule)) {
        return { id: rule.id, text: `Правило ${name} (приоритет ${rule.priority}) перестанет срабатывать: это правило с приоритетом ${draft.priority} перекрывает его полностью.` }
      }
      if (!draftWins && covers(rule, draft)) {
        return { id: rule.id, text: `Это правило никогда не сработает: его полностью перекрывает ${name} с приоритетом ${rule.priority}.` }
      }
      return {
        id: rule.id,
        text: `Пересекается с правилом ${name} (приоритет ${rule.priority}): где подойдут оба, выиграет `
          + (draftWins ? `это правило (приоритет ${draft.priority}).` : `${name}.`)
      }
    })
}

/** Ручной маршрут не совпадает с маршрутом, который дал бы вид: предупреждение формы создания (аудит видов). */
export const manualRouteMismatch = (resolution: InteractionKindResolution | null, manualTemplateId: string, manualTemplateName: string | null):
  string | null => (
  resolution === null || manualTemplateId === '' || manualTemplateId === resolution.templateId
    ? null
    : `Вид «${resolution.kindName}» ведёт по маршруту «${resolution.templateName}», а выбран «${manualTemplateName ?? 'другой шаблон'}». `
      + 'Карточка получит вид, но пойдёт по выбранному маршруту.'
)

/** Подпись маршрута после смены вида: этапы остались от маршрута прежнего вида (аудит видов). */
export const routeKindNote = (kindName: string | null | undefined,
  route: { templateName: string; fromPreviousKind: boolean } | null | undefined): string | null => (
  kindName && route?.fromPreviousKind
    ? `Вид сменён на «${kindName}», а маршрут «${route.templateName}» остался от прежнего вида: этапы не меняются.`
    : null
)

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

/** Какой маршрут получит новый цикл: по виду (как в форме создания) или выбранный вручную шаблон (аудит видов). */
export const cycleRouteHint = (resolution: InteractionKindResolution | null, manualTemplateName: string | null): string | null => {
  if (manualTemplateName !== null) {
    return `Маршрут «${manualTemplateName}» — выбран вручную${resolution === null ? '' : `; вид «${resolution.kindName}»`}.`
  }
  return resolution === null ? null : resolutionHint(resolution)
}

/** Подпись пустого варианта в выборе вида: что подставит сервер, если вид не выбран. */
export const automaticKindLabel = (resolution: InteractionKindResolution | null): string => (
  resolution === null || resolution.kindSource === 'SELECTED'
    ? 'Подобрать по правилам'
    : `По правилам: ${resolution.kindName}`
)

/** Этапы шаблона одной строкой в порядке маршрута: руководитель видит, что выбирает (аудит видов). */
export const templateStagesLine = (template: { stages: readonly { name: string; order: number }[] } | undefined): string | null => (
  template === undefined || template.stages.length === 0
    ? null
    : `Этапы: ${[...template.stages].sort((left, right) => left.order - right.order).map((stage) => stage.name).join(' → ')}`
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
