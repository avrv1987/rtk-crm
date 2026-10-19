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
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { CardDialog } from '../interactions/cardUi'
import { defaultRolloutSelection, diffRoutes, isEmptyDiff, refreshedRolloutSelection, routeFileName, routeToFile, type RouteDiff } from './workflowVersions'
import './adminUi.css'
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
  /** Сообщение об успехе показывает страница: окно при этом закрывается. */
  onNotify: (message: string) => void
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
    return 'Взаимодействия изменились после предпросмотра. Постройте предпросмотр заново.'
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
    return <p className="admin-form__hint">Этапы и переходы в этих версиях одинаковые.</p>
  }
  const rows: [string, string[]][] = [
    ['Добавлены этапы', diff.added.map((name) => `«${name}»`)],
    ['Удалены этапы', diff.removed.map((name) => `«${name}»`)],
    ['Переименованы', diff.renamed.map((item) => `«${item.from}» → «${item.to}»`)],
    ['Перемещены', diff.moved.map((name) => `«${name}»`)],
    ['Обязательность', diff.optionalChanged],
    ['Свойства этапов', diff.propertiesChanged],
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
  onNotify,
  onSessionExpired,
  onProfileUnavailable
}: WorkflowTemplateVersionsProps) => {
  const [state, setState] = useState<VersionsState>({ kind: 'loading' })
  const [compare, setCompare] = useState<{ from: number; to: number } | null>(null)
  const [rolloutVersion, setRolloutVersion] = useState<number | null>(null)
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

  const rolloutTitle = (number: number) => (
    number === latest ? `Обновить взаимодействия до версии ${number}` : `Вернуть взаимодействия к версии ${number}`
  )

  return (
    <CardDialog
      open
      title={rolloutVersion === null ? `История версий: «${template.name}»` : rolloutTitle(rolloutVersion)}
      onClose={onClose}
    >
      {rolloutVersion !== null ? (
        <WorkflowRolloutWizard
          key={rolloutVersion}
          template={template}
          targetVersion={rolloutVersion}
          onCancel={() => setRolloutVersion(null)}
          onDone={(updated) => {
            onNotify(`Обновлено взаимодействий: ${updated}. Изменение маршрута записано в историю каждого взаимодействия.`)
            onClose()
          }}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      ) : (
        <div className="workflow-versions">
          <div className="wv-lead">
            Версии шаблона
            <HintButton label="Как работают версии">
              Каждое сохранение шаблона — новая версия. Новые взаимодействия идут по последней версии, начатые остаются на своей,
              пока их не обновят.
              {role === 'ADMIN' && ' Взаимодействия команд переводят на новую версию руководители команд.'}
            </HintButton>
          </div>
          {state.kind === 'loading' && <p role="status">Загружаем версии…</p>}
          {state.kind === 'failed' && (
            <div className="organizations-message organizations-message--error" role="alert">
              <p>Не удалось загрузить версии шаблона.</p>
              <SupportDetails requestId={state.requestId} />
              <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
            </div>
          )}
          {state.kind === 'ready' && (
            <>
              <div className="admin-table-wrap">
                <table className="admin-table" aria-label="Версии шаблона">
                  <thead>
                    <tr>
                      <th scope="col">Версия</th>
                      <th scope="col">Что изменилось</th>
                      <th scope="col">Этапы</th>
                      <th scope="col"><span className="wt-hidden">Действия</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {versions.map((version) => {
                      const menu = [
                        { label: 'Скачать файл', onSelect: () => downloadVersion(template, version) },
                        ...(version.number !== latest
                          ? [{ label: canEdit ? 'Взять за основу' : 'Создать на основе', onSelect: () => onUseVersion(version) }]
                          : [])
                      ]
                      return (
                        <tr key={version.number}>
                          <td className="admin-table__cell-main">
                            <span className="admin-table__main">Версия {version.number}{version.number === latest ? ' — текущая' : ''}</span>
                            <span className="admin-table__sub">
                              {formatMoscowDateTime(version.createdAt)}
                              {version.authorName ? `, ${version.authorName}` : ''}
                              {version.works !== null ? `; идущих взаимодействий на ней: ${version.works}` : ''}
                            </span>
                          </td>
                          <td data-label="Что изменилось">{version.comment ?? 'Без комментария'}</td>
                          <td>
                            <details className="wv-stages">
                              <summary>Этапы ({version.stages.length})</summary>
                              <ol>
                                {[...version.stages].sort((left, right) => left.order - right.order).map((stage) => (
                                  <li key={stage.id}>{stage.name}{stage.optional ? ' (необязательный)' : ''}</li>
                                ))}
                              </ol>
                            </details>
                          </td>
                          <td className="admin-table__actions">
                            {role === 'LEADER' && worksOtherThan(version.number) > 0 && (
                              <button type="button" className="button--secondary admin-table__action" onClick={() => setRolloutVersion(version.number)}>
                                {rolloutTitle(version.number)}
                              </button>
                            )}
                            <ActionMenu label={`Действия: версия ${version.number}`} items={menu} />
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {versions.length > 1 && compare !== null && (
                <section className="workflow-versions__compare" aria-labelledby="workflow-versions-compare-title">
                  <h3 id="workflow-versions-compare-title">Сравнить версии</h3>
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
        </div>
      )}
    </CardDialog>
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
  const previousWorks = useRef<ReadonlyArray<{ id: string; noExit: boolean; landsOnFinal: boolean }>>([])
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
      const previous = previousWorks.current
      previousWorks.current = preview.works
      setPreviewState({ kind: 'ready', preview })
      setSelected((current) => current === null
        ? defaultRolloutSelection(preview.works)
        : refreshedRolloutSelection(current, previous, preview.works))
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
    <div className="workflow-rollout">
      <div className="wv-lead">
        <button type="button" className="button--secondary" disabled={applying} onClick={onCancel}>‹ К версиям</button>
        <HintButton label="Как обновить">
          <ol className="workflow-rollout__steps">
            <li>Проверьте, на какой этап перейдут взаимодействия. Если этапа больше нет, выберите, куда переходит взаимодействие.</li>
            <li>Отметьте взаимодействия. Те, у которых этапы меняли в карточке, по умолчанию не отмечены.</li>
            <li>Нажмите «Обновить». Каждое взаимодействие получит запись в истории.</li>
          </ol>
        </HintButton>
      </div>
      {previewState.kind === 'loading' && <p role="status">Считаем, что изменится…</p>}
      {previewState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{errorText(previewState.error)}</p>
          <SupportDetails requestId={previewState.error instanceof ApiError ? previewState.error.requestId : undefined} />
          <button type="button" className="button--secondary" onClick={() => void loadPreview()}>Повторить</button>
        </div>
      )}
      {preview !== null && preview.works.length === 0 && <p className="admin-form__hint">Все идущие взаимодействия команды уже на этой версии.</p>}
      {preview !== null && preview.works.length > 0 && (
        <>
          {preview.sources.map((source) => (
            <fieldset key={source.version} className="workflow-rollout__mapping">
              <legend>Взаимодействия на версии {source.version}: {source.works}</legend>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th scope="col">Этап версии {source.version}</th>
                    <th scope="col">Что с ним</th>
                    <th scope="col">Куда перейдут взаимодействия</th>
                    <th scope="col">Взаимодействий на этапе</th>
                  </tr>
                </thead>
                <tbody>
                  {source.stages.map((stage) => (
                    <tr key={stage.stageId} className={stage.match === 'REMOVED' ? 'workflow-rollout__removed' : undefined}>
                      <td className="admin-table__cell-main"><span className="admin-table__main">{stage.name}</span></td>
                      <td data-label="Что с ним">{matchLabels[stage.match]}</td>
                      <td data-label="Куда перейдут взаимодействия">
                        <select
                          aria-label={`Куда перейдут взаимодействия с этапа «${stage.name}»`}
                          value={stage.targetStageId}
                          onChange={(event) => setOverrides((current) => ({ ...current, [stage.stageId]: event.target.value }))}
                        >
                          {preview.targetStages.map((target) => (
                            <option key={target.id} value={target.id}>{target.name}</option>
                          ))}
                        </select>
                      </td>
                      <td data-label="Взаимодействий на этапе">{stage.works}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </fieldset>
          ))}
          <div className="wv-bar">
            <h3>Взаимодействия</h3>
            {stageMap.length > 0 && (
              <button type="button" className="button--secondary" onClick={() => setOverrides({})}>Вернуть предложенное сопоставление</button>
            )}
            <span className="page-title__spacer" />
            <button type="button" className="button--secondary" onClick={() => {
              applyKey.current = null
              setSelected(new Set(preview.works.map((work) => work.id)))
            }}>Отметить все</button>
            <button type="button" className="button--secondary" onClick={() => {
              applyKey.current = null
              setSelected(new Set())
            }}>Снять отметки</button>
          </div>
          <table className="admin-table">
            <thead>
              <tr>
                <th scope="col">Вуз и взаимодействие</th>
                <th scope="col">Сейчас</th>
                <th scope="col">После обновления</th>
              </tr>
            </thead>
            <tbody>
              {preview.works.map((work) => (
                <tr key={work.id}>
                  <td className="admin-table__cell-main">
                    <label className="wv-pick">
                      <input
                        type="checkbox"
                        aria-label={`Обновить взаимодействие «${work.title}»`}
                        checked={selected?.has(work.id) ?? false}
                        onChange={(event) => toggle(work.id, event.target.checked)}
                      />
                      <span>
                        <span className="admin-table__main">{work.organizationName}</span>
                        <span className="admin-table__sub">{work.title}</span>
                      </span>
                    </label>
                  </td>
                  <td data-label="Сейчас">Версия {work.sourceVersion}, этап «{work.currentStageName}»</td>
                  <td data-label="После обновления">
                    Этап «{work.targetStageName}»
                    {work.currentStageRemoved && <span className="workflow-rollout__flag">текущий этап исчезает</span>}
                    {work.edited && <span className="workflow-rollout__flag">этапы меняли в карточке</span>}
                    {work.noExit && <span className="workflow-rollout__flag">с этапа нельзя дойти до финала</span>}
                    {work.landsOnFinal && <span className="workflow-rollout__flag">окажется на финальном этапе</span>}
                    {work.changes.length > 0 && <span className="admin-table__sub">{work.changes}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {editedChosen && (
            <label className="admin-form__check wv-replace">
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
              <SupportDetails requestId={applyError instanceof ApiError ? applyError.requestId : undefined} />
              {applyError instanceof ApiError && applyError.status === 409 && (
                <button type="button" className="button--secondary" onClick={() => {
                  setApplyError(null)
                  void loadPreview()
                }}>Построить заново</button>
              )}
            </div>
          )}
        </>
      )}
      <div className="admin-form__actions wv-actions">
        <button type="button" className="button--secondary" disabled={applying} onClick={onCancel}>Отмена</button>
        {preview !== null && preview.works.length > 0 && (
          <button
            type="button"
            disabled={chosen.length === 0 || (editedChosen && !replaceLocal) || applying}
            onClick={() => void apply()}
          >
            {applying ? 'Обновляем…' : `Обновить выбранные взаимодействия (${chosen.length})`}
          </button>
        )}
      </div>
    </div>
  )
}
