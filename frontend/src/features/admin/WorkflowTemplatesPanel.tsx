import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Me,
  type PageWorkflowTemplate,
  type WorkflowTemplate,
  type WorkflowTemplateCreate,
  type WorkflowTransitionInput
} from '../../shared/api/client'

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
  name: string
  optional: boolean
}

type TransitionDraft = WorkflowTransitionInput

type TemplateDraft = {
  name: string
  stages: StageDraft[]
  transitions: TransitionDraft[]
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
  transitions: []
})

const draftFromTemplate = (template: WorkflowTemplate): TemplateDraft => {
  const stages = [...template.stages]
    .sort((left, right) => left.order - right.order)
    .map((stage) => ({ name: stage.name, optional: stage.optional }))
  const orderById = new Map(template.stages.map((stage) => [stage.id, stage.order]))
  const transitions = template.transitions.flatMap((transition) => {
    const fromOrder = orderById.get(transition.fromStageId)
    const toOrder = orderById.get(transition.toStageId)
    return fromOrder === undefined || toOrder === undefined
      ? []
      : [{ fromOrder, toOrder, commentRequired: transition.commentRequired }]
  })
  return { name: template.name, stages, transitions }
}

const payloadFromDraft = (draft: TemplateDraft): WorkflowTemplateCreate => ({
  name: draft.name.trim(),
  stages: draft.stages.map((stage, order) => ({
    name: stage.name.trim(),
    order,
    optional: stage.optional
  })),
  transitions: draft.transitions
})

const isDraftValid = (draft: TemplateDraft) => {
  if (draft.name.trim().length === 0 || draft.stages.length === 0 || draft.stages.some((stage) => stage.name.trim().length === 0)) {
    return false
  }
  const edgeKeys = new Set<string>()
  const edges = new Map<number, number[]>()
  for (const transition of draft.transitions) {
    if (
      transition.fromOrder < 0
      || transition.toOrder < 0
      || transition.fromOrder >= draft.stages.length
      || transition.toOrder >= draft.stages.length
      || transition.fromOrder === transition.toOrder
      || (Math.abs(transition.fromOrder - transition.toOrder) !== 1 && !transition.commentRequired)
      || !edgeKeys.add(`${transition.fromOrder}:${transition.toOrder}`)
    ) {
      return false
    }
    const next = edges.get(transition.fromOrder) ?? []
    next.push(transition.toOrder)
    edges.set(transition.fromOrder, next)
  }
  if (draft.stages.some((_, order) => order < draft.stages.length - 1 && !edges.has(order))) {
    return false
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
  return reachable.size === draft.stages.length
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
  if (error instanceof ApiError) {
    return 'Операция не выполнена. Проверьте структуру этапов и переходов, затем повторите попытку.'
  }
  return 'Не удалось связаться с сервисом. Черновик сохранён; повторите попытку позже.'
}

const StructuredApiError = ({ error }: { error: unknown }) => {
  if (!(error instanceof ApiError)) {
    return null
  }
  const fieldErrors = Object.entries(error.fieldErrors ?? {})
  return (
    <div className="structured-api-error">
      <p>Код: {error.code}</p>
      <p>{error.message}</p>
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map(([field, message]) => <li key={field}>{field}: {message}</li>)}
        </ul>
      )}
    </div>
  )
}

const templateScopeLabel = (template: WorkflowTemplate) => {
  if (template.defaultTemplate) {
    return 'Шаблон по умолчанию'
  }
  return template.teamId === null ? 'Общий шаблон' : 'Шаблон команды'
}

export const WorkflowTemplatesPanel = ({ role, onSessionExpired, onProfileUnavailable }: WorkflowTemplatesPanelProps) => {
  const canManageTemplates = role === 'LEADER' || role === 'ADMIN'
  const [templatesState, setTemplatesState] = useState<TemplatesState>({ kind: 'loading' })
  const [pageIndex, setPageIndex] = useState(0)
  const [editingTemplate, setEditingTemplate] = useState<WorkflowTemplate | null>(null)
  const [draft, setDraft] = useState<TemplateDraft>(emptyDraft)
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)
  const [commandState, setCommandState] = useState<CommandState>({ kind: 'idle' })
  const listRequestVersion = useRef(0)
  const saveKey = useRef<string | null>(null)

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
    setDraft(updater)
  }

  const beginCreate = () => {
    saveKey.current = null
    setEditingTemplate(null)
    setPendingDelete(null)
    setCommandState({ kind: 'idle' })
    setDraft(emptyDraft())
  }

  const beginCopy = (template: WorkflowTemplate) => {
    saveKey.current = null
    setEditingTemplate(null)
    setPendingDelete(null)
    setCommandState({ kind: 'idle' })
    setDraft({ ...draftFromTemplate(template), name: `${template.name} — копия команды` })
  }

  const beginEdit = (template: WorkflowTemplate) => {
    saveKey.current = null
    setEditingTemplate(template)
    setPendingDelete(null)
    setCommandState({ kind: 'idle' })
    setDraft(draftFromTemplate(template))
  }

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
    if (!isDraftValid(draft) || commandState.kind === 'saving') {
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
      setEditingTemplate(template)
      setDraft(draftFromTemplate(template))
      setCommandState({ kind: 'idle' })
      replaceTemplate(template)
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
      if (editingTemplate?.id === pendingDelete.template.id) {
        beginCreate()
      } else {
        setPendingDelete(null)
        setCommandState({ kind: 'idle' })
      }
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
    updateDraft((current) => ({
      ...current,
      stages: current.stages.filter((_, stageIndex) => stageIndex !== index),
      transitions: current.transitions
        .filter((transition) => transition.fromOrder !== index && transition.toOrder !== index)
        .map((transition) => ({
          ...transition,
          fromOrder: transition.fromOrder > index ? transition.fromOrder - 1 : transition.fromOrder,
          toOrder: transition.toOrder > index ? transition.toOrder - 1 : transition.toOrder
        }))
    }))
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

  if (!canManageTemplates) {
    return null
  }

  const draftValid = isDraftValid(draft)
  const commandError = commandState.kind === 'failed' ? commandState.error : undefined
  const hasConflict = commandError instanceof ApiError && commandError.status === 409

  return (
    <section className="workflow-templates" aria-labelledby="workflow-templates-title" aria-busy={templatesState.kind === 'loading'}>
      <div className="workflow-templates__header">
        <div>
          <p className="eyebrow">Процессы</p>
          <h2 id="workflow-templates-title">Шаблоны этапов</h2>
        </div>
        {templatesState.kind === 'ready' && <p className="workflow-templates__total">Всего: {templatesState.page.total}</p>}
      </div>
      <p className="workflow-templates__intro">
        {role === 'LEADER'
          ? 'Доступны шаблоны вашей команды и базовый шаблон, который можно только скопировать в шаблон команды. Сервер дополнительно проверяет область каждой операции.'
          : 'Доступны общие шаблоны. Сервер дополнительно проверяет область каждой операции.'}
      </p>

      {templatesState.kind === 'loading' && (
        <p className="organizations-message" role="status">Загружаем шаблоны этапов…</p>
      )}
      {templatesState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить управляемые шаблоны этапов.</p>
          {templatesState.requestId && <p className="request-id">Request ID: {templatesState.requestId}</p>}
          <button type="button" onClick={() => void loadTemplates(pageIndex)}>Повторить</button>
        </div>
      )}
      {templatesState.kind === 'ready' && (
        <>
          {templatesState.page.items.length === 0 ? (
            <p className="organizations-message">В доступной области шаблонов пока нет.</p>
          ) : (
            <ul className="workflow-templates__list" aria-label="Шаблоны этапов">
              {templatesState.page.items.map((template) => (
                <li key={template.id} className="workflow-templates__item">
                  <div>
                    <strong>{template.name}</strong>
                    <p>{templateScopeLabel(template)}; этапов: {template.stages.length}; переходов: {template.transitions.length}; версия: {template.version}</p>
                  </div>
                  <div className="workflow-templates__actions">
                    {role === 'LEADER' && template.teamId === null ? (
                      <button type="button" disabled={commandState.kind === 'saving'} onClick={() => beginCopy(template)}>
                        Создать на основе базового
                      </button>
                    ) : (
                      <button type="button" disabled={commandState.kind === 'saving'} onClick={() => beginEdit(template)}>Редактировать</button>
                    )}
                    {role === 'LEADER' && template.teamId === null ? (
                      <p className="workflow-templates__delete-reason">Базовый шаблон меняет администратор.</p>
                    ) : template.defaultTemplate ? (
                      <p className="workflow-templates__delete-reason">Шаблон по умолчанию удалять нельзя.</p>
                    ) : (
                      <button
                        type="button"
                        className="workflow-templates__delete"
                        disabled={commandState.kind === 'saving'}
                        onClick={() => {
                          setPendingDelete({ template, idempotencyKey: null })
                          setCommandState({ kind: 'idle' })
                        }}
                      >
                        Удалить
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
          {templatesState.page.total > templatesState.page.size && (
            <nav className="workflow-templates__pagination" aria-label="Страницы шаблонов этапов">
              <button
                type="button"
                disabled={templatesState.page.page === 0 || commandState.kind === 'saving'}
                onClick={() => setPageIndex((current) => Math.max(0, current - 1))}
              >
                Предыдущая
              </button>
              <p>Страница {templatesState.page.page + 1} из {Math.ceil(templatesState.page.total / templatesState.page.size)}</p>
              <button
                type="button"
                disabled={(templatesState.page.page + 1) * templatesState.page.size >= templatesState.page.total || commandState.kind === 'saving'}
                onClick={() => setPageIndex((current) => current + 1)}
              >
                Следующая
              </button>
            </nav>
          )}
        </>
      )}

      <form className="workflow-template-editor" onSubmit={(event) => void submit(event)}>
        <div className="workflow-template-editor__header">
          <h3>{editingTemplate === null ? 'Новый шаблон' : `Редактирование: ${editingTemplate.name}`}</h3>
          <button type="button" disabled={commandState.kind === 'saving'} onClick={beginCreate}>Новый шаблон</button>
        </div>
        <label>
          Название шаблона
          <input
            value={draft.name}
            maxLength={200}
            required
            onChange={(event) => updateDraft((current) => ({ ...current, name: event.target.value }))}
          />
        </label>
        <section className="workflow-template-editor__section" aria-labelledby="workflow-template-stages-title">
          <div className="workflow-template-editor__section-header">
            <h4 id="workflow-template-stages-title">Этапы</h4>
            <button type="button" disabled={commandState.kind === 'saving'} onClick={addStage}>Добавить этап</button>
          </div>
          <ol className="workflow-template-editor__stages">
            {draft.stages.map((stage, index) => (
              <li key={index}>
                <span>{index + 1}</span>
                <label>
                  Название
                  <input
                    value={stage.name}
                    maxLength={200}
                    required
                    onChange={(event) => updateStage(index, { name: event.target.value })}
                  />
                </label>
                <label className="workflow-template-editor__checkbox">
                  <input
                    type="checkbox"
                    checked={stage.optional}
                    onChange={(event) => updateStage(index, { optional: event.target.checked })}
                  />
                  Необязательный
                </label>
                <div className="workflow-template-editor__stage-actions">
                  <button type="button" disabled={index === 0 || commandState.kind === 'saving'} onClick={() => moveStage(index, index - 1)}>Выше</button>
                  <button type="button" disabled={index === draft.stages.length - 1 || commandState.kind === 'saving'} onClick={() => moveStage(index, index + 1)}>Ниже</button>
                  <button type="button" className="workflow-templates__delete" disabled={draft.stages.length <= 1 || commandState.kind === 'saving'} onClick={() => removeStage(index)}>Удалить</button>
                </div>
              </li>
            ))}
          </ol>
        </section>
        <section className="workflow-template-editor__section" aria-labelledby="workflow-template-transitions-title">
          <div className="workflow-template-editor__section-header">
            <h4 id="workflow-template-transitions-title">Явные переходы</h4>
            <button type="button" disabled={draft.stages.length < 2 || commandState.kind === 'saving'} onClick={addTransition}>Добавить переход</button>
          </div>
          {draft.transitions.length === 0 && <p>Переходы отсутствуют.</p>}
          <ol className="workflow-template-editor__transitions">
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
                <label className="workflow-template-editor__checkbox">
                  <input
                    type="checkbox"
                    checked={transition.commentRequired}
                    onChange={(event) => updateTransition(index, { commentRequired: event.target.checked })}
                  />
                  Нужен комментарий
                </label>
                <button type="button" className="workflow-templates__delete" disabled={commandState.kind === 'saving'} onClick={() => removeTransition(index)}>Удалить</button>
              </li>
            ))}
          </ol>
        </section>
        {!draftValid && (
          <p className="workflow-template-editor__validation" role="status">
            Укажите название каждого этапа и явные переходы так, чтобы все этапы были достижимы из первого, у каждого этапа кроме последнего был выход, а переход через этап (пропуск или возврат) требовал комментарий.
          </p>
        )}
        <button type="submit" disabled={commandState.kind === 'saving' || !draftValid}>
          {commandState.kind === 'saving' && commandState.action !== 'delete'
            ? 'Сохраняем…'
            : editingTemplate === null ? 'Создать шаблон' : 'Сохранить шаблон'}
        </button>
        {commandState.kind === 'failed' && commandState.action !== 'delete' && (
          <div className="interaction-command-error" role="alert">
            <p>{commandMessage(commandState.error)}</p>
            <StructuredApiError error={commandState.error} />
            {commandState.error instanceof ApiError && <p className="request-id">Request ID: {commandState.error.requestId}</p>}
            {hasConflict && <button type="button" onClick={() => void refreshAfterConflict()}>Обновить список</button>}
          </div>
        )}
      </form>

      {pendingDelete !== null && (
        <section className="workflow-template-delete-confirmation" role="alertdialog" aria-labelledby="workflow-template-delete-title">
          <h3 id="workflow-template-delete-title">Удалить шаблон «{pendingDelete.template.name}»?</h3>
          <p>Новые взаимодействия больше не смогут выбрать этот шаблон.</p>
          {commandState.kind === 'failed' && commandState.action === 'delete' && (
            <div className="interaction-command-error" role="alert">
              <p>{commandMessage(commandState.error)}</p>
              <StructuredApiError error={commandState.error} />
              {commandState.error instanceof ApiError && <p className="request-id">Request ID: {commandState.error.requestId}</p>}
            </div>
          )}
          <div className="workflow-template-delete-confirmation__actions">
            {!hasConflict && (
              <button type="button" className="workflow-templates__delete" disabled={commandState.kind === 'saving'} onClick={() => void confirmDelete()}>
                {commandState.kind === 'saving' ? 'Удаляем…' : 'Подтвердить удаление'}
              </button>
            )}
            {hasConflict && <button type="button" onClick={() => void refreshAfterConflict()}>Обновить список</button>}
            <button
              type="button"
              disabled={commandState.kind === 'saving'}
              onClick={() => {
                setPendingDelete(null)
                setCommandState({ kind: 'idle' })
              }}
            >
              Отмена
            </button>
          </div>
        </section>
      )}
    </section>
  )
}
