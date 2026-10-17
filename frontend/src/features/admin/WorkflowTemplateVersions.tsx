import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Me,
  type WorkflowRolloutPreview,
  type WorkflowStageMatch,
  type WorkflowTemplate,
  type WorkflowTemplateVersion
} from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { diffRoutes, isEmptyDiff, routeFileName, routeToFile, type RouteDiff } from './workflowVersions'
import './workflowVersions.css'

type SessionHandlers = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type WorkflowTemplateVersionsProps = SessionHandlers & {
  template: WorkflowTemplate
  role: Me['role']
  canEdit: boolean
  onUseVersion: (version: WorkflowTemplateVersion) => void
  onClose: () => void
}

type VersionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; versions: WorkflowTemplateVersion[] }
  | { kind: 'failed'; requestId?: string }

const matchLabels: Record<WorkflowStageMatch, string> = {
  SAME: 'без изменений',
  RENAMED: 'переименован',
  BY_NAME: 'найден по названию',
  MANUAL: 'выбрано вручную',
  REMOVED: 'этапа нет в новой версии'
}

const handleSessionError = (error: unknown, handlers: SessionHandlers) => {
  if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
    handlers.onSessionExpired()
    return true
  }
  if (error instanceof ApiError && error.code === 'CRM_PROFILE_REQUIRED') {
    handlers.onProfileUnavailable(error.requestId)
    return true
  }
  return false
}

const errorText = (error: unknown) => {
  if (error instanceof ApiError && error.status === 409) {
    return 'Работы изменились после предпросмотра. Постройте предпросмотр заново.'
  }
  if (error instanceof ApiError && error.status === 403) {
    return 'Это действие недоступно в вашей роли.'
  }
  const fieldMessages = error instanceof ApiError ? Object.values(error.fieldErrors ?? {}) : []
  if (fieldMessages.length > 0) {
    return fieldMessages.join(' ')
  }
  return error instanceof ApiError ? error.message : 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

const downloadVersion = (template: WorkflowTemplate, version: WorkflowTemplateVersion) => {
  const file = routeToFile({ name: version.name, stages: version.stages, transitions: version.transitions }, version.number)
  const url = URL.createObjectURL(new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = routeFileName(template.name, version.number)
  link.click()
  URL.revokeObjectURL(url)
}

const DiffList = ({ diff }: { diff: RouteDiff }) => {
  if (isEmptyDiff(diff)) {
    return <p>Этапы и переходы в этих версиях одинаковые.</p>
  }
  const rows: [string, string[]][] = [
    ['Добавлены этапы', diff.added.map((name) => `«${name}»`)],
    ['Удалены этапы', diff.removed.map((name) => `«${name}»`)],
    ['Переименованы', diff.renamed.map((item) => `«${item.from}» → «${item.to}»`)],
    ['Перемещены', diff.moved.map((name) => `«${name}»`)],
    ['Обязательность', diff.optionalChanged],
    ['Новые переходы', diff.transitionsAdded],
    ['Убраны переходы', diff.transitionsRemoved],
    ['Комментарий при переходе', diff.commentChanged]
  ]
  return (
    <dl className="workflow-versions__diff">
      {rows.filter(([, items]) => items.length > 0).map(([title, items]) => (
        <div key={title}>
          <dt>{title}</dt>
          <dd>{items.join('; ')}</dd>
        </div>
      ))}
    </dl>
  )
}

export const WorkflowTemplateVersions = ({
  template,
  role,
  canEdit,
  onUseVersion,
  onClose,
  onSessionExpired,
  onProfileUnavailable
}: WorkflowTemplateVersionsProps) => {
  const [state, setState] = useState<VersionsState>({ kind: 'loading' })
  const [compare, setCompare] = useState<{ from: number; to: number } | null>(null)
  const [rolloutVersion, setRolloutVersion] = useState<number | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const handlers = useMemo(() => ({ onSessionExpired, onProfileUnavailable }), [onSessionExpired, onProfileUnavailable])

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      const versions = await apiClient.listWorkflowTemplateVersions(template.id)
      setState({ kind: 'ready', versions })
      setCompare((current) => current ?? (versions.length > 1
        ? { from: versions[1].number, to: versions[0].number }
        : null))
    } catch (error) {
      if (!handleSessionError(error, handlers)) {
        setState({ kind: 'failed', requestId: error instanceof ApiError ? error.requestId : undefined })
      }
    }
  }, [handlers, template.id])

  useEffect(() => {
    void load()
  }, [load])

  const versions = state.kind === 'ready' ? state.versions : []
  const latest = versions[0]?.number ?? 0
  const byNumber = new Map(versions.map((version) => [version.number, version]))
  const compareFrom = compare === null ? undefined : byNumber.get(compare.from)
  const compareTo = compare === null ? undefined : byNumber.get(compare.to)
  const worksOtherThan = (number: number) => versions
    .filter((version) => version.number !== number)
    .reduce((sum, version) => sum + (version.works ?? 0), 0)

  return (
    <section className="workflow-versions" aria-labelledby="workflow-versions-title">
      <div className="workflow-versions__header">
        <h3 id="workflow-versions-title">История версий: «{template.name}»</h3>
        <button type="button" onClick={onClose}>Закрыть</button>
      </div>
      <p className="workflow-versions__intro">
        Каждое сохранение шаблона — новая версия. Новые работы идут по последней версии, начатые остаются на своей,
        пока их не обновят.
      </p>
      {role === 'ADMIN' && <p className="notice">Работы команд переводят на новую версию руководители команд.</p>}
      {message !== null && <p className="notice" role="status">{message}</p>}
      {state.kind === 'loading' && <p role="status">Загружаем версии…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить версии шаблона.</p>
          {state.requestId && <p className="request-id">Request ID: {state.requestId}</p>}
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          <ol className="workflow-versions__list" aria-label="Версии шаблона">
            {versions.map((version) => (
              <li key={version.number} className="workflow-versions__item">
                <div>
                  <strong>Версия {version.number}{version.number === latest ? ' — текущая' : ''}</strong>
                  <p>
                    {formatMoscowDateTime(version.createdAt)}
                    {version.authorName ? `, ${version.authorName}` : ''}
                    {version.works !== null ? `; идущих работ на ней: ${version.works}` : ''}
                  </p>
                  <p>{version.comment ?? 'Без комментария'}</p>
                  <details>
                    <summary>Этапы ({version.stages.length})</summary>
                    <ol>
                      {[...version.stages].sort((left, right) => left.order - right.order).map((stage) => (
                        <li key={stage.id}>{stage.name}{stage.optional ? ' (необязательный)' : ''}</li>
                      ))}
                    </ol>
                  </details>
                </div>
                <div className="workflow-templates__actions">
                  <button type="button" className="button--secondary" onClick={() => downloadVersion(template, version)}>
                    Скачать файл
                  </button>
                  {version.number !== latest && (
                    <button type="button" className="button--secondary" onClick={() => onUseVersion(version)}>
                      {canEdit ? 'Взять за основу' : 'Создать на основе'}
                    </button>
                  )}
                  {role === 'LEADER' && worksOtherThan(version.number) > 0 && (
                    <button type="button" onClick={() => setRolloutVersion(version.number)}>
                      {version.number === latest ? `Обновить работы до версии ${version.number}` : `Вернуть работы к версии ${version.number}`}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ol>
          {versions.length > 1 && compare !== null && (
            <section className="workflow-versions__compare" aria-labelledby="workflow-versions-compare-title">
              <h4 id="workflow-versions-compare-title">Сравнить версии</h4>
              <div className="workflow-versions__compare-controls">
                <label>
                  Было
                  <select value={compare.from} onChange={(event) => setCompare({ ...compare, from: Number(event.target.value) })}>
                    {versions.map((version) => <option key={version.number} value={version.number}>Версия {version.number}</option>)}
                  </select>
                </label>
                <label>
                  Стало
                  <select value={compare.to} onChange={(event) => setCompare({ ...compare, to: Number(event.target.value) })}>
                    {versions.map((version) => <option key={version.number} value={version.number}>Версия {version.number}</option>)}
                  </select>
                </label>
              </div>
              {compareFrom !== undefined && compareTo !== undefined && (
                <DiffList diff={diffRoutes(
                  { name: compareFrom.name, stages: compareFrom.stages, transitions: compareFrom.transitions },
                  { name: compareTo.name, stages: compareTo.stages, transitions: compareTo.transitions }
                )} />
              )}
            </section>
          )}
        </>
      )}
      {rolloutVersion !== null && (
        <WorkflowRolloutWizard
          key={rolloutVersion}
          template={template}
          targetVersion={rolloutVersion}
          onCancel={() => setRolloutVersion(null)}
          onDone={(updated) => {
            setRolloutVersion(null)
            setMessage(`Обновлено работ: ${updated}. Изменение маршрута записано в историю каждой работы.`)
            void load()
          }}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      )}
    </section>
  )
}

type WizardProps = SessionHandlers & {
  template: WorkflowTemplate
  targetVersion: number
  onCancel: () => void
  onDone: (updated: number) => void
}

type PreviewState =
  | { kind: 'loading' }
  | { kind: 'ready'; preview: WorkflowRolloutPreview }
  | { kind: 'failed'; error: unknown }

const WorkflowRolloutWizard = ({ template, targetVersion, onCancel, onDone, onSessionExpired, onProfileUnavailable }: WizardProps) => {
  const [previewState, setPreviewState] = useState<PreviewState>({ kind: 'loading' })
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [selected, setSelected] = useState<Set<string> | null>(null)
  const [replaceLocal, setReplaceLocal] = useState(false)
  const [applying, setApplying] = useState(false)
  const [applyError, setApplyError] = useState<unknown>(null)
  const applyKey = useRef<string | null>(null)
  const handlers = useMemo(() => ({ onSessionExpired, onProfileUnavailable }), [onSessionExpired, onProfileUnavailable])
  const stageMap = useMemo(
    () => Object.entries(overrides).map(([fromStageId, toStageId]) => ({ fromStageId, toStageId })),
    [overrides]
  )

  const loadPreview = useCallback(async () => {
    setPreviewState({ kind: 'loading' })
    applyKey.current = null
    try {
      const preview = await apiClient.previewWorkflowRollout(template.id, targetVersion, { stageMap })
      setPreviewState({ kind: 'ready', preview })
      setSelected((current) => {
        const ids = new Set(preview.works.map((work) => work.id))
        return current === null
          ? new Set(preview.works.filter((work) => !work.edited).map((work) => work.id))
          : new Set([...current].filter((id) => ids.has(id)))
      })
    } catch (error) {
      if (!handleSessionError(error, handlers)) {
        setPreviewState({ kind: 'failed', error })
      }
    }
  }, [handlers, stageMap, targetVersion, template.id])

  useEffect(() => {
    void loadPreview()
  }, [loadPreview])

  const preview = previewState.kind === 'ready' ? previewState.preview : null
  const chosen = preview === null || selected === null ? [] : preview.works.filter((work) => selected.has(work.id))
  const editedChosen = chosen.some((work) => work.edited)
  const rollback = preview !== null && preview.sources.every((source) => source.version > targetVersion)

  const toggle = (id: string, checked: boolean) => {
    applyKey.current = null
    setApplyError(null)
    setSelected((current) => {
      const next = new Set(current ?? [])
      if (checked) {
        next.add(id)
      } else {
        next.delete(id)
      }
      return next
    })
  }

  const apply = async () => {
    if (chosen.length === 0 || (editedChosen && !replaceLocal) || applying) {
      return
    }
    const key = applyKey.current ?? (applyKey.current = createIdempotencyKey())
    setApplying(true)
    setApplyError(null)
    try {
      const result = await apiClient.rollOutWorkflowVersion(template.id, targetVersion, {
        stageMap,
        works: chosen.map((work) => ({ id: work.id, version: work.version })),
        replaceLocalChanges: replaceLocal
      }, key)
      onDone(result.updated)
    } catch (error) {
      if (!handleSessionError(error, handlers)) {
        setApplyError(error)
      }
    } finally {
      setApplying(false)
    }
  }

  return (
    <section className="workflow-rollout" aria-labelledby="workflow-rollout-title">
      <h3 id="workflow-rollout-title">{rollback ? `Вернуть работы к версии ${targetVersion}` : `Обновить работы до версии ${targetVersion}`}</h3>
      <ol className="workflow-rollout__steps">
        <li>Проверьте, на какой этап перейдут работы. Если этапа больше нет, выберите, куда переходит работа.</li>
        <li>Отметьте работы. Работы, у которых этапы меняли в карточке, по умолчанию не отмечены.</li>
        <li>Нажмите «Обновить». Каждая работа получит запись в истории.</li>
      </ol>
      {previewState.kind === 'loading' && <p role="status">Считаем, что изменится…</p>}
      {previewState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{errorText(previewState.error)}</p>
          <button type="button" onClick={() => void loadPreview()}>Повторить</button>
        </div>
      )}
      {preview !== null && preview.works.length === 0 && <p>Все идущие работы команды уже на этой версии.</p>}
      {preview !== null && preview.works.length > 0 && (
        <>
          {preview.sources.map((source) => (
            <fieldset key={source.version} className="workflow-rollout__mapping">
              <legend>Работы на версии {source.version}: {source.works}</legend>
              <div className="workflow-rollout__table">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Этап версии {source.version}</th>
                      <th scope="col">Что с ним</th>
                      <th scope="col">Куда перейдут работы</th>
                      <th scope="col">Работ на этапе</th>
                    </tr>
                  </thead>
                  <tbody>
                    {source.stages.map((stage) => (
                      <tr key={stage.stageId} className={stage.match === 'REMOVED' ? 'workflow-rollout__removed' : undefined}>
                        <td>{stage.name}</td>
                        <td>{matchLabels[stage.match]}</td>
                        <td>
                          <select
                            aria-label={`Куда перейдут работы с этапа «${stage.name}»`}
                            value={stage.targetStageId}
                            onChange={(event) => setOverrides((current) => ({ ...current, [stage.stageId]: event.target.value }))}
                          >
                            {preview.targetStages.map((target) => (
                              <option key={target.id} value={target.id}>{target.name}</option>
                            ))}
                          </select>
                        </td>
                        <td>{stage.works}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </fieldset>
          ))}
          {stageMap.length > 0 && (
            <button type="button" className="button--secondary" onClick={() => setOverrides({})}>Вернуть предложенное сопоставление</button>
          )}
          <h4>Работы</h4>
          <div className="workflow-templates__actions">
            <button type="button" className="button--secondary" onClick={() => {
              applyKey.current = null
              setSelected(new Set(preview.works.map((work) => work.id)))
            }}>Отметить все</button>
            <button type="button" className="button--secondary" onClick={() => {
              applyKey.current = null
              setSelected(new Set())
            }}>Снять отметки</button>
          </div>
          <div className="workflow-rollout__table">
            <table>
              <thead>
                <tr>
                  <th scope="col"><span className="workflow-rollout__hidden">Обновить</span></th>
                  <th scope="col">Вуз и работа</th>
                  <th scope="col">Сейчас</th>
                  <th scope="col">После обновления</th>
                </tr>
              </thead>
              <tbody>
                {preview.works.map((work) => (
                  <tr key={work.id}>
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Обновить работу «${work.title}»`}
                        checked={selected?.has(work.id) ?? false}
                        onChange={(event) => toggle(work.id, event.target.checked)}
                      />
                    </td>
                    <td>
                      <strong>{work.organizationName}</strong>
                      <span className="workflow-rollout__work-title">{work.title}</span>
                    </td>
                    <td>Версия {work.sourceVersion}, этап «{work.currentStageName}»</td>
                    <td>
                      Этап «{work.targetStageName}»
                      {work.currentStageRemoved && <span className="workflow-rollout__flag">текущий этап исчезает</span>}
                      {work.edited && <span className="workflow-rollout__flag">этапы меняли в карточке</span>}
                      {work.changes.length > 0 && <span className="workflow-rollout__changes">{work.changes}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {editedChosen && (
            <label className="workflow-template-editor__checkbox">
              <input type="checkbox" checked={replaceLocal} onChange={(event) => {
                applyKey.current = null
                setReplaceLocal(event.target.checked)
              }} />
              Заменить этапы, изменённые в карточке, этапами версии {targetVersion}
            </label>
          )}
          {applyError !== null && (
            <div className="interaction-command-error" role="alert">
              <p>{errorText(applyError)}</p>
              {applyError instanceof ApiError && <p className="request-id">Request ID: {applyError.requestId}</p>}
              {applyError instanceof ApiError && applyError.status === 409 && (
                <button type="button" onClick={() => {
                  setApplyError(null)
                  void loadPreview()
                }}>Построить заново</button>
              )}
            </div>
          )}
        </>
      )}
      <div className="workflow-templates__actions">
        {preview !== null && preview.works.length > 0 && (
          <button
            type="button"
            disabled={chosen.length === 0 || (editedChosen && !replaceLocal) || applying}
            onClick={() => void apply()}
          >
            {applying ? 'Обновляем…' : `Обновить выбранные работы (${chosen.length})`}
          </button>
        )}
        <button type="button" className="button--secondary" disabled={applying} onClick={onCancel}>Отмена</button>
      </div>
    </section>
  )
}
