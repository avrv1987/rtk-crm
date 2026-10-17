import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Me,
  type PageWorkflowTemplate,
  type WorkflowTemplate,
  type WorkflowTemplateCreate,
  type WorkflowTemplateVersion,
  type WorkflowTransitionInput
} from '../../shared/api/client'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Pagination } from '../../shared/ui/Pagination'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { CardDialog } from '../interactions/cardUi'
import { WorkflowTemplateVersions } from './WorkflowTemplateVersions'
import { canEditTemplate, isEffectiveDefault, templateRowActions, templateScopeLabel, type RowMenuKey } from './workflowTemplateRows'
import { parseRouteFile } from './workflowVersions'
import './adminUi.css'
import './workflowVersions.css'

type WorkflowTemplatesPanelProps = {
  role: Me['role']
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type TemplatesState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: PageWorkflowTemplate }
  | { kind: 'failed'; requestId?: string }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'saving'; action: 'create' | 'update' | 'delete' }
  | { kind: 'failed'; action: 'create' | 'update' | 'delete'; error: unknown }

type StageDraft = {
  id?: string
  name: string
  optional: boolean
}

type TransitionDraft = WorkflowTransitionInput

type TemplateDraft = {
  name: string
  stages: StageDraft[]
  transitions: TransitionDraft[]
  comment: string
}

type PendingDelete = {
  template: WorkflowTemplate
  idempotencyKey: string | null
}

const templatesPageSize = 25
const templatesSort = 'name,asc' as const

const emptyDraft = (): TemplateDraft => ({
  name: '',
  stages: [{ name: '', optional: false }],
  transitions: [],
  comment: ''
})

const draftFromTemplate = (template: Pick<WorkflowTemplate, 'name' | 'stages' | 'transitions'>): TemplateDraft => {
  const stages = [...template.stages]
    .sort((left, right) => left.order - right.order)
    .map((stage) => ({ id: stage.id, name: stage.name, optional: stage.optional }))
  const orderById = new Map(template.stages.map((stage) => [stage.id, stage.order]))
  const transitions = template.transitions.flatMap((transition) => {
    const fromOrder = orderById.get(transition.fromStageId)
    const toOrder = orderById.get(transition.toStageId)
    return fromOrder === undefined || toOrder === undefined
      ? []
      : [{ fromOrder, toOrder, commentRequired: transition.commentRequired }]
  })
  return { name: template.name, stages, transitions, comment: '' }
}

const payloadFromDraft = (draft: TemplateDraft): WorkflowTemplateCreate => ({
  name: draft.name.trim(),
  stages: draft.stages.map((stage, order) => ({
    name: stage.name.trim(),
    order,
    optional: stage.optional,
    ...(stage.id === undefined ? {} : { id: stage.id })
  })),
  transitions: draft.transitions,
  ...(draft.comment.trim().length === 0 ? {} : { comment: draft.comment.trim() })
})

const stageTitle = (draft: TemplateDraft, order: number) => `«${draft.stages[order]?.name.trim() || `этап ${order + 1}`}»`

const draftProblem = (draft: TemplateDraft): string | null => {
  if (draft.name.trim().length === 0) {
    return 'Укажите название шаблона.'
  }
  if (draft.stages.length === 0) {
    return 'Добавьте хотя бы один этап.'
  }
  const unnamed = draft.stages.findIndex((stage) => stage.name.trim().length === 0)
  if (unnamed >= 0) {
    return `Укажите название этапа ${unnamed + 1}.`
  }
  const edgeKeys = new Set<string>()
  const edges = new Map<number, number[]>()
  for (const transition of draft.transitions) {
    const route = `${stageTitle(draft, transition.fromOrder)} → ${stageTitle(draft, transition.toOrder)}`
    if (
      transition.fromOrder < 0
      || transition.toOrder < 0
      || transition.fromOrder >= draft.stages.length
      || transition.toOrder >= draft.stages.length
      || transition.fromOrder === transition.toOrder
    ) {
      return `Переход ${route} должен вести в другой существующий этап.`
    }
    if (Math.abs(transition.fromOrder - transition.toOrder) !== 1 && !transition.commentRequired) {
      return `Переход ${route} пропускает этапы или ведёт назад: отметьте «Нужен комментарий» или удалите переход.`
    }
    if (!edgeKeys.add(`${transition.fromOrder}:${transition.toOrder}`)) {
      return `Переход ${route} указан дважды.`
    }
    const next = edges.get(transition.fromOrder) ?? []
    next.push(transition.toOrder)
    edges.set(transition.fromOrder, next)
  }
  const deadEnd = draft.stages.findIndex((_, order) => order < draft.stages.length - 1 && !edges.has(order))
  if (deadEnd >= 0) {
    return `У этапа ${stageTitle(draft, deadEnd)} нет выхода: добавьте переход из него в следующий этап.`
  }
  const reachable = new Set<number>()
  const pending = [0]
  while (pending.length > 0) {
    const order = pending.pop()
    if (order !== undefined && !reachable.has(order)) {
      reachable.add(order)
      pending.push(...(edges.get(order) ?? []))
    }
  }
  const unreachable = draft.stages.findIndex((_, order) => !reachable.has(order))
  return unreachable >= 0
    ? `Этап ${stageTitle(draft, unreachable)} недостижим из первого этапа ${stageTitle(draft, 0)}: добавьте переход в него из предыдущего этапа.`
    : null
}

const withoutStage = (draft: TemplateDraft, index: number) => {
  const reorder = (order: number) => (order > index ? order - 1 : order)
  const kept = draft.transitions.filter((transition) => transition.fromOrder !== index && transition.toOrder !== index)
  const keys = new Set(kept.map((transition) => `${transition.fromOrder}:${transition.toOrder}`))
  const bridges: TransitionDraft[] = []
  for (const incoming of draft.transitions.filter((transition) => transition.toOrder === index)) {
    for (const outgoing of draft.transitions.filter((transition) => transition.fromOrder === index)) {
      const key = `${incoming.fromOrder}:${outgoing.toOrder}`
      if (incoming.fromOrder !== outgoing.toOrder && !keys.has(key)) {
        keys.add(key)
        const adjacent = Math.abs(reorder(incoming.fromOrder) - reorder(outgoing.toOrder)) === 1
        bridges.push({
          fromOrder: incoming.fromOrder,
          toOrder: outgoing.toOrder,
          commentRequired: !adjacent || (incoming.commentRequired && outgoing.commentRequired)
        })
      }
    }
  }
  const names = bridges.map((bridge) => `${stageTitle(draft, bridge.fromOrder)} → ${stageTitle(draft, bridge.toOrder)}`)
  return {
    draft: {
      ...draft,
      stages: draft.stages.filter((_, stageIndex) => stageIndex !== index),
      transitions: [...kept, ...bridges].map((transition) => ({
        ...transition,
        fromOrder: reorder(transition.fromOrder),
        toOrder: reorder(transition.toOrder)
      }))
    },
    notice: names.length === 0
      ? `Этап ${stageTitle(draft, index)} удалён.`
      : `Этап ${stageTitle(draft, index)} удалён; соседние этапы соединены: ${names.join(', ')}.`
  }
}

const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

const isUnauthenticated = (error: unknown) => (
  error instanceof ApiError && error.code === 'UNAUTHENTICATED'
)

const isProfileUnavailable = (error: unknown): error is ApiError => (
  error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED'
)

const commandMessage = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return 'Шаблон уже изменён. Черновик сохранён; обновите список и сверьте актуальную версию перед повторной отправкой.'
  }
  if (error instanceof ApiError && error.status === 403) {
    return 'Операция недоступна в текущей роли или области команды.'
  }
  const fieldMessages = error instanceof ApiError ? Object.values(error.fieldErrors ?? {}) : []
  if (fieldMessages.length > 0) {
    return fieldMessages.join(' ')
  }
  if (error instanceof ApiError) {
    return 'Операция не выполнена. Проверьте структуру этапов и переходов, затем повторите попытку.'
  }
  return 'Не удалось связаться с сервисом. Черновик сохранён; повторите попытку позже.'
}

const errorCode = (error: unknown) => (error instanceof ApiError ? error.code : undefined)

export const WorkflowTemplatesPanel = ({ role, onSessionExpired, onProfileUnavailable }: WorkflowTemplatesPanelProps) => {
  const canManageTemplates = role === 'LEADER' || role === 'ADMIN'
  const [templatesState, setTemplatesState] = useState<TemplatesState>({ kind: 'loading' })
  const [pageIndex, setPageIndex] = useState(0)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingTemplate, setEditingTemplate] = useState<WorkflowTemplate | null>(null)
  const [draft, setDraft] = useState<TemplateDraft>(emptyDraft)
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const [stageNotice, setStageNotice] = useState<string | null>(null)
  const [historyTemplate, setHistoryTemplate] = useState<WorkflowTemplate | null>(null)
  const [importNotice, setImportNotice] = useState<{ text: string; error: boolean } | null>(null)
  const [defaultState, setDefaultState] = useState<{ templateId: string; saving: boolean; error?: unknown; key: string } | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const listRequestVersion = useRef(0)
  const saveKey = useRef<string | null>(null)
  const importInput = useRef<HTMLInputElement>(null)

  const loadTemplates = useCallback(async (requestedPage: number): Promise<PageWorkflowTemplate | null> => {
    const requestVersion = ++listRequestVersion.current
    setTemplatesState({ kind: 'loading' })
    try {
      const page = await apiClient.listWorkflowTemplates({
        page: requestedPage,
        size: templatesPageSize,
        sort: templatesSort
      })
      if (requestVersion === listRequestVersion.current) {
        setTemplatesState({ kind: 'ready', page })
        return page
      }
      return null
    } catch (error) {
      if (requestVersion !== listRequestVersion.current) {
        return null
      }
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return null
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return null
      }
      setTemplatesState({ kind: 'failed', requestId: requestIdOf(error) })
      return null
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    if (!canManageTemplates) {
      return
    }
    void loadTemplates(pageIndex)
    return () => {
      listRequestVersion.current += 1
    }
  }, [canManageTemplates, loadTemplates, pageIndex])

  const updateDraft = (updater: (current: TemplateDraft) => TemplateDraft) => {
    saveKey.current = null
    setCommandState({ kind: 'idle' })
    setStageNotice(null)
    setDraft(updater)
  }

  const makeDefault = async (template: WorkflowTemplate) => {
    if (defaultState?.saving === true || commandState.kind === 'saving') {
      return
    }
    const key = defaultState?.templateId === template.id ? defaultState.key : createIdempotencyKey()
    setDefaultState({ templateId: template.id, saving: true, key })
    try {
      await apiClient.makeWorkflowTemplateDefault(template.id, template.version, key)
      setDefaultState(null)
      setToast(role === 'ADMIN'
        ? `«${template.name}» — общий шаблон по умолчанию для новых взаимодействий.`
        : template.teamId === null
          ? 'Новые взаимодействия команды снова создаются по общему шаблону по умолчанию.'
          : `«${template.name}» — шаблон команды по умолчанию для новых взаимодействий.`)
      await loadTemplates(pageIndex)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setDefaultState({ templateId: template.id, saving: false, error, key })
    }
  }

  const openEditor = (template: WorkflowTemplate | null, next: TemplateDraft) => {
    saveKey.current = null
    setEditingTemplate(template)
    setPendingDelete(null)
    setCommandState({ kind: 'idle' })
    setStageNotice(null)
    setImportNotice(null)
    setDraft(next)
    setEditorOpen(true)
  }

  const closeEditor = () => {
    if (commandState.kind === 'saving') {
      return
    }
    setEditorOpen(false)
    setEditingTemplate(null)
    setCommandState({ kind: 'idle' })
  }

  const versionToDraft = (template: WorkflowTemplate, version: WorkflowTemplateVersion) => {
    setHistoryTemplate(null)
    if (canEditTemplate(role, template)) {
      openEditor(template, { ...draftFromTemplate(version), name: template.name, comment: `Возврат к версии ${version.number}` })
    } else {
      openEditor(null, { ...draftFromTemplate(version), name: `${template.name} — копия команды` })
    }
  }

  const importFile = async (file: File | undefined) => {
    if (file === undefined) {
      return
    }
    const parsed = parseRouteFile(await file.text())
    if ('error' in parsed) {
      setImportNotice({ text: parsed.error, error: true })
      return
    }
    updateDraft((current) => ({
      name: editingTemplate === null ? parsed.file.name : current.name,
      stages: parsed.file.stages.map((stage) => ({
        ...(stage.key === undefined ? {} : { id: stage.key }),
        name: stage.name,
        optional: stage.optional
      })),
      transitions: parsed.file.transitions.map((transition) => ({
        fromOrder: transition.from,
        toOrder: transition.to,
        commentRequired: transition.commentRequired
      })),
      comment: `Загружено из файла «${file.name}»`
    }))
    setImportNotice({
      text: `Из файла загружено этапов: ${parsed.file.stages.length}, переходов: ${parsed.file.transitions.length}. `
        + (editingTemplate === null
          ? 'Проверьте и нажмите «Создать шаблон».'
          : `Проверьте и нажмите «Сохранить шаблон» — появится новая версия «${editingTemplate.name}».`),
      error: false
    })
  }

  const beginCreate = () => openEditor(null, emptyDraft())

  const beginCopy = (template: WorkflowTemplate) => (
    openEditor(null, { ...draftFromTemplate(template), name: `${template.name} — копия команды` })
  )

  const beginEdit = (template: WorkflowTemplate) => openEditor(template, draftFromTemplate(template))

  const replaceTemplate = (template: WorkflowTemplate) => {
    setTemplatesState((current) => {
      if (current.kind !== 'ready') {
        return current
      }
      const hasTemplate = current.page.items.some((item) => item.id === template.id)
      return {
        kind: 'ready',
        page: {
          ...current.page,
          total: current.page.total + (hasTemplate ? 0 : 1),
          items: hasTemplate
            ? current.page.items.map((item) => item.id === template.id ? template : item)
            : [...current.page.items, template]
        }
      }
    })
  }

  const refreshAfterConflict = async () => {
    const target = editingTemplate ?? pendingDelete?.template
    if (target === undefined) {
      return
    }
    const action = editingTemplate === null ? 'delete' : 'update'
    try {
      const template = await apiClient.getWorkflowTemplate(target.id)
      saveKey.current = null
      setEditingTemplate((current) => current?.id === template.id ? template : current)
      setPendingDelete((current) => current?.template.id === template.id
        ? { ...current, template, idempotencyKey: null }
        : current)
      setCommandState({ kind: 'idle' })
      replaceTemplate(template)
      void loadTemplates(pageIndex)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCommandState({ kind: 'failed', action, error })
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draftProblem(draft) !== null || commandState.kind === 'saving') {
      return
    }
    const payload = payloadFromDraft(draft)
    const idempotencyKey = saveKey.current ?? (saveKey.current = createIdempotencyKey())
    const action = editingTemplate === null ? 'create' : 'update'
    setCommandState({ kind: 'saving', action })
    try {
      const template = editingTemplate === null
        ? await apiClient.createWorkflowTemplate(payload, idempotencyKey)
        : await apiClient.updateWorkflowTemplate(editingTemplate.id, { ...payload, version: editingTemplate.version }, idempotencyKey)
      saveKey.current = null
      replaceTemplate(template)
      setCommandState({ kind: 'idle' })
      setEditorOpen(false)
      setEditingTemplate(null)
      setToast(action === 'create' ? `Шаблон «${template.name}» создан.` : `Шаблон «${template.name}» сохранён.`)
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCommandState({ kind: 'failed', action, error })
    }
  }

  const confirmDelete = async () => {
    if (pendingDelete === null || commandState.kind === 'saving') {
      return
    }
    const idempotencyKey = pendingDelete.idempotencyKey ?? createIdempotencyKey()
    if (pendingDelete.idempotencyKey === null) {
      setPendingDelete({ ...pendingDelete, idempotencyKey })
    }
    setCommandState({ kind: 'saving', action: 'delete' })
    try {
      await apiClient.deleteWorkflowTemplate(pendingDelete.template.id, pendingDelete.template.version, idempotencyKey)
      setTemplatesState((current) => (
        current.kind === 'ready'
          ? {
              kind: 'ready',
              page: {
                ...current.page,
                total: Math.max(0, current.page.total - 1),
                items: current.page.items.filter((template) => template.id !== pendingDelete.template.id)
              }
            }
          : current
      ))
      setToast(`Шаблон «${pendingDelete.template.name}» удалён.`)
      setPendingDelete(null)
      setCommandState({ kind: 'idle' })
    } catch (error) {
      if (isUnauthenticated(error)) {
        onSessionExpired()
        return
      }
      if (isProfileUnavailable(error)) {
        onProfileUnavailable(error.requestId)
        return
      }
      setCommandState({ kind: 'failed', action: 'delete', error })
    }
  }

  const addStage = () => {
    updateDraft((current) => ({
      ...current,
      stages: [...current.stages, { name: '', optional: false }]
    }))
  }

  const updateStage = (index: number, update: Partial<StageDraft>) => {
    updateDraft((current) => ({
      ...current,
      stages: current.stages.map((stage, stageIndex) => stageIndex === index ? { ...stage, ...update } : stage)
    }))
  }

  const removeStage = (index: number) => {
    if (draft.stages.length <= 1) {
      return
    }
    const result = withoutStage(draft, index)
    updateDraft(() => result.draft)
    setStageNotice(result.notice)
  }

  const moveStage = (from: number, to: number) => {
    if (to < 0 || to >= draft.stages.length) {
      return
    }
    updateDraft((current) => {
      const stages = [...current.stages]
      const [stage] = stages.splice(from, 1)
      stages.splice(to, 0, stage)
      const mappedOrder = (order: number) => {
        if (order === from) {
          return to
        }
        if (from < to && order > from && order <= to) {
          return order - 1
        }
        if (to < from && order >= to && order < from) {
          return order + 1
        }
        return order
      }
      return {
        ...current,
        stages,
        transitions: current.transitions.map((transition) => ({
          ...transition,
          fromOrder: mappedOrder(transition.fromOrder),
          toOrder: mappedOrder(transition.toOrder)
        }))
      }
    })
  }

  const addTransition = () => {
    if (draft.stages.length < 2) {
      return
    }
    const used = new Set(draft.transitions.map((transition) => `${transition.fromOrder}:${transition.toOrder}`))
    for (let fromOrder = 0; fromOrder < draft.stages.length; fromOrder += 1) {
      for (let toOrder = 0; toOrder < draft.stages.length; toOrder += 1) {
        if (fromOrder !== toOrder && !used.has(`${fromOrder}:${toOrder}`)) {
          updateDraft((current) => ({
            ...current,
            transitions: [...current.transitions, { fromOrder, toOrder, commentRequired: false }]
          }))
          return
        }
      }
    }
  }

  const updateTransition = (index: number, update: Partial<TransitionDraft>) => {
    updateDraft((current) => ({
      ...current,
      transitions: current.transitions.map((transition, transitionIndex) => (
        transitionIndex === index ? { ...transition, ...update } : transition
      ))
    }))
  }

  const removeTransition = (index: number) => {
    updateDraft((current) => ({
      ...current,
      transitions: current.transitions.filter((_, transitionIndex) => transitionIndex !== index)
    }))
  }

  const runMenuAction = (key: RowMenuKey, template: WorkflowTemplate) => {
    if (key === 'history') {
      setHistoryTemplate(template)
    } else if (key === 'default') {
      void makeDefault(template)
    } else if (commandState.kind !== 'saving') {
      setPendingDelete({ template, idempotencyKey: null })
      setCommandState({ kind: 'idle' })
    }
  }

  if (!canManageTemplates) {
    return null
  }

  const saving = commandState.kind === 'saving'
  const problem = draftProblem(draft)
  const teamDefaultTemplateId = templatesState.kind === 'ready' ? templatesState.page.teamDefaultTemplateId : null
  const commandError = commandState.kind === 'failed' ? commandState.error : undefined
  const hasConflict = commandError instanceof ApiError && commandError.status === 409
  const deleteFailed = commandState.kind === 'failed' && commandState.action === 'delete'
  const editorFailed = commandState.kind === 'failed' && commandState.action !== 'delete'

  return (
    <section className="admin-page" aria-labelledby="workflow-templates-title" aria-busy={templatesState.kind === 'loading'}>
      <div className="page-title">
        <h2 id="workflow-templates-title">Шаблоны этапов</h2>
        <HintButton label="О разделе">
          {role === 'LEADER'
            ? 'Доступны шаблоны вашей команды и базовый шаблон, который можно только скопировать в шаблон команды. Шаблон команды по умолчанию подставляется в новые взаимодействия команды, если КАМ не выбрал другой.'
            : 'Доступны общие шаблоны. Шаблон по умолчанию подставляется в новые взаимодействия всех команд, у которых нет своего шаблона по умолчанию.'}
        </HintButton>
        {templatesState.kind === 'ready' && <span className="page-title__count">Всего: {templatesState.page.total}</span>}
        <span className="page-title__spacer" />
        <button type="button" disabled={saving} onClick={beginCreate}>Новый шаблон</button>
      </div>

      {defaultState?.saving === true && <p className="admin-form__hint" role="status">Сохраняем…</p>}
      {defaultState?.error !== undefined && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(defaultState.error)}</p>
          <SupportDetails
            requestId={defaultState.error instanceof ApiError ? defaultState.error.requestId : undefined}
            code={errorCode(defaultState.error)}
          />
        </div>
      )}
      {deleteFailed && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(commandState.error)}</p>
          <SupportDetails
            requestId={requestIdOf(commandState.error)}
            code={errorCode(commandState.error)}
          />
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" onClick={() => setPendingDelete(null)}>Отмена</button>
            {hasConflict
              ? <button type="button" onClick={() => void refreshAfterConflict()}>Обновить список</button>
              : <button type="button" className="button--secondary" onClick={() => void confirmDelete()}>Повторить удаление</button>}
          </div>
        </div>
      )}

      {templatesState.kind === 'loading' && (
        <p className="organizations-message" role="status">Загружаем шаблоны этапов…</p>
      )}
      {templatesState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить управляемые шаблоны этапов.</p>
          <SupportDetails requestId={templatesState.requestId} />
          <button type="button" className="button--secondary" onClick={() => void loadTemplates(pageIndex)}>Повторить</button>
        </div>
      )}
      {templatesState.kind === 'ready' && (
        <>
          {templatesState.page.items.length === 0 ? (
            <EmptyState
              text="В доступной области шаблонов пока нет."
              action={<button type="button" className="button--secondary" onClick={beginCreate}>Новый шаблон</button>}
            />
          ) : (
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label="Шаблоны этапов">
                <thead>
                  <tr>
                    <th scope="col">Название</th>
                    <th scope="col">Этапов</th>
                    <th scope="col">Переходов</th>
                    <th scope="col">Версия</th>
                    <th scope="col"><span className="wt-hidden">Действия</span></th>
                  </tr>
                </thead>
                <tbody>
                  {templatesState.page.items.map((template) => {
                    const row = templateRowActions(role, template, teamDefaultTemplateId)
                    return (
                      <tr key={template.id}>
                        <td className="admin-table__cell-main">
                          <span className="admin-table__main">{template.name}</span>
                          {' '}
                          {isEffectiveDefault(template, teamDefaultTemplateId) && <span className="admin-chip admin-chip--ok">По умолчанию</span>}
                          <span className="admin-table__sub">{templateScopeLabel(template, teamDefaultTemplateId)}</span>
                        </td>
                        <td data-label="Этапов">{template.stages.length}</td>
                        <td data-label="Переходов">{template.transitions.length}</td>
                        <td data-label="Версия">{template.routeVersion}</td>
                        <td className="admin-table__actions">
                          <button
                            type="button"
                            className="button--secondary admin-table__action"
                            disabled={saving}
                            onClick={() => (row.primary === 'copy' ? beginCopy(template) : beginEdit(template))}
                          >
                            {row.primaryLabel}
                          </button>
                          <ActionMenu
                            label={`Действия: ${template.name}`}
                            header={row.note === null ? undefined : <span className="wt-note">{row.note}</span>}
                            items={row.menu.map((entry) => ({
                              label: entry.label,
                              danger: entry.danger,
                              onSelect: () => runMenuAction(entry.key, template)
                            }))}
                          />
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
          <Pagination
            label="Страницы шаблонов этапов"
            page={templatesState.page.page}
            size={templatesState.page.size}
            total={templatesState.page.total}
            disabled={saving}
            onChange={setPageIndex}
          />
        </>
      )}

      {historyTemplate !== null && (
        <WorkflowTemplateVersions
          key={historyTemplate.id}
          template={historyTemplate}
          role={role}
          canEdit={canEditTemplate(role, historyTemplate)}
          onUseVersion={(version) => versionToDraft(historyTemplate, version)}
          onClose={() => setHistoryTemplate(null)}
          onNotify={setToast}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      )}

      <CardDialog
        open={editorOpen}
        title={editingTemplate === null ? 'Новый шаблон' : `Редактирование: ${editingTemplate.name}`}
        onClose={closeEditor}
      >
        <form className="admin-form wt-editor" onSubmit={(event) => void submit(event)}>
          <div className="wt-editor__import">
            <button type="button" className="button--secondary" disabled={saving} onClick={() => importInput.current?.click()}>
              Загрузить этапы из файла
            </button>
            <input
              ref={importInput}
              type="file"
              accept="application/json,.json"
              hidden
              onChange={(event) => {
                void importFile(event.target.files?.[0])
                event.target.value = ''
              }}
            />
          </div>
          {importNotice !== null && (
            <p className={importNotice.error ? 'notice notice--error' : 'notice'} role="status">{importNotice.text}</p>
          )}
          <label>
            Название шаблона *
            <input
              value={draft.name}
              maxLength={200}
              required
              onChange={(event) => updateDraft((current) => ({ ...current, name: event.target.value }))}
            />
          </label>
          <section className="wt-editor__section" aria-labelledby="workflow-template-stages-title">
            <div className="wt-editor__section-header">
              <h3 id="workflow-template-stages-title">Этапы</h3>
              <button type="button" className="button--secondary" disabled={saving} onClick={addStage}>Добавить этап</button>
            </div>
            <ol className="wt-editor__rows wt-editor__stages">
              {draft.stages.map((stage, index) => (
                <li key={index}>
                  <span className="wt-editor__number">{index + 1}</span>
                  <label>
                    Название *
                    <input
                      value={stage.name}
                      maxLength={200}
                      required
                      onChange={(event) => updateStage(index, { name: event.target.value })}
                    />
                  </label>
                  <label className="admin-form__check">
                    <input
                      type="checkbox"
                      checked={stage.optional}
                      onChange={(event) => updateStage(index, { optional: event.target.checked })}
                    />
                    Необязательный
                  </label>
                  <div className="wt-editor__row-actions">
                    <button type="button" className="button--secondary" aria-label={`Выше: этап ${index + 1}`} disabled={index === 0 || saving} onClick={() => moveStage(index, index - 1)}>Выше</button>
                    <button type="button" className="button--secondary" aria-label={`Ниже: этап ${index + 1}`} disabled={index === draft.stages.length - 1 || saving} onClick={() => moveStage(index, index + 1)}>Ниже</button>
                    <button type="button" className="button--secondary" aria-label={`Удалить этап ${index + 1}`} disabled={draft.stages.length <= 1 || saving} onClick={() => removeStage(index)}>Удалить</button>
                  </div>
                </li>
              ))}
            </ol>
            {stageNotice !== null && <p className="notice" role="status">{stageNotice}</p>}
          </section>
          <section className="wt-editor__section" aria-labelledby="workflow-template-transitions-title">
            <div className="wt-editor__section-header">
              <h3 id="workflow-template-transitions-title">Явные переходы</h3>
              <button type="button" className="button--secondary" disabled={draft.stages.length < 2 || saving} onClick={addTransition}>Добавить переход</button>
            </div>
            {draft.transitions.length === 0 && <p className="admin-form__hint">Переходы отсутствуют.</p>}
            <ol className="wt-editor__rows wt-editor__transitions">
              {draft.transitions.map((transition, index) => (
                <li key={index}>
                  <label>
                    Из этапа
                    <select value={transition.fromOrder} onChange={(event) => updateTransition(index, { fromOrder: Number(event.target.value) })}>
                      {draft.stages.map((stage, order) => <option key={order} value={order}>{order + 1}. {stage.name || 'Без названия'}</option>)}
                    </select>
                  </label>
                  <label>
                    В этап
                    <select value={transition.toOrder} onChange={(event) => updateTransition(index, { toOrder: Number(event.target.value) })}>
                      {draft.stages.map((stage, order) => <option key={order} value={order}>{order + 1}. {stage.name || 'Без названия'}</option>)}
                    </select>
                  </label>
                  <label className="admin-form__check">
                    <input
                      type="checkbox"
                      checked={transition.commentRequired}
                      onChange={(event) => updateTransition(index, { commentRequired: event.target.checked })}
                    />
                    Нужен комментарий
                  </label>
                  <div className="wt-editor__row-actions">
                    <button type="button" className="button--secondary" aria-label={`Удалить переход ${index + 1}`} disabled={saving} onClick={() => removeTransition(index)}>Удалить</button>
                  </div>
                </li>
              ))}
            </ol>
          </section>
          <label>
            Что изменилось (видно в истории версий)
            <input
              value={draft.comment}
              maxLength={1000}
              onChange={(event) => updateDraft((current) => ({ ...current, comment: event.target.value }))}
            />
          </label>
          {problem !== null && <p className="admin-form__hint" role="status">{problem}</p>}
          {editorFailed && (
            <div className="interaction-command-error" role="alert">
              <p>{commandMessage(commandState.error)}</p>
              <SupportDetails requestId={requestIdOf(commandState.error)} code={errorCode(commandState.error)} />
              {hasConflict && <button type="button" className="button--secondary" onClick={() => void refreshAfterConflict()}>Обновить список</button>}
            </div>
          )}
          <div className="admin-form__actions wt-editor__actions">
            <button type="button" className="button--secondary" disabled={saving} onClick={closeEditor}>Отмена</button>
            <button type="submit" disabled={saving || problem !== null}>
              {commandState.kind === 'saving' && commandState.action !== 'delete'
                ? 'Сохраняем…'
                : editingTemplate === null ? 'Создать шаблон' : 'Сохранить шаблон'}
            </button>
          </div>
        </form>
      </CardDialog>

      <ConfirmDialog
        open={pendingDelete !== null && commandState.kind === 'idle'}
        title={`Удалить шаблон «${pendingDelete?.template.name ?? ''}»?`}
        description="Новые взаимодействия больше не смогут выбрать этот шаблон."
        confirmLabel="Подтвердить удаление"
        onConfirm={() => void confirmDelete()}
        onCancel={() => setPendingDelete(null)}
      />
      <Toast message={toast} onClose={() => setToast(null)} />
    </section>
  )
}
