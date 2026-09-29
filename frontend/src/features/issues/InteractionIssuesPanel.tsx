import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Interaction,
  type InteractionIssue,
  type InteractionIssueList
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { RequiredMark } from '../interactions/cardUi'
import { commandErrorText, formatDate, formatDateTime, handledAccessError, requestIdOf, todayIso } from '../work/workShared'
import {
  type IssueKind,
  type IssueLevel,
  isIssueOverdue,
  issueKindLabels,
  issueLevelLabels,
  issueLevels,
  issueTitle
} from './issueModel'
import './issues.css'

type InteractionIssuesPanelProps = {
  interaction: Interaction
  canEdit: boolean
  onChanged: (interaction: Interaction) => void
  onRefresh: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; list: InteractionIssueList }
  | { kind: 'failed'; error: unknown }

type Editor =
  | { mode: 'create' }
  | { mode: 'edit'; issue: InteractionIssue }
  | { mode: 'resolve'; issue: InteractionIssue }

type FormValues = {
  kind: IssueKind
  description: string
  riskLevel: IssueLevel | ''
  responsibleId: string
  dueOn: string
  resolution: string
}

type CommandState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'failed'; error: unknown }

const emptyValues = (kind: IssueKind): FormValues => ({
  kind,
  description: '',
  riskLevel: kind === 'RISK' ? 'MEDIUM' : '',
  responsibleId: '',
  dueOn: '',
  resolution: ''
})

const valuesOf = (issue: InteractionIssue): FormValues => ({
  kind: issue.kind,
  description: issue.description,
  riskLevel: issue.riskLevel ?? '',
  responsibleId: issue.responsibleId,
  dueOn: issue.dueOn ?? '',
  resolution: ''
})

export const InteractionIssuesPanel = ({
  interaction,
  canEdit,
  onChanged,
  onRefresh,
  onSessionExpired,
  onProfileUnavailable
}: InteractionIssuesPanelProps) => {
  const [listState, setListState] = useState<ListState>({ kind: 'loading' })
  const [showResolved, setShowResolved] = useState(false)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [values, setValues] = useState<FormValues>(() => emptyValues('PROBLEM'))
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [notice, setNotice] = useState<string | null>(null)
  const commandKey = useRef<string | null>(null)
  const requestVersion = useRef(0)
  const handlers = { onSessionExpired, onProfileUnavailable }

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const list = await apiClient.listInteractionIssues(interaction.id)
      if (version === requestVersion.current) {
        setListState({ kind: 'ready', list })
      }
    } catch (error) {
      if (version === requestVersion.current && !handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setListState({ kind: 'failed', error })
      }
    }
  }, [interaction.id, onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
  }, [load, interaction.version])

  const open = (next: Exclude<Editor, { mode: 'create' }>) => {
    commandKey.current = null
    setCommand({ kind: 'idle' })
    setNotice(null)
    setEditor(next)
    setValues(valuesOf(next.issue))
  }

  const startCreate = (kind: IssueKind) => {
    commandKey.current = null
    setCommand({ kind: 'idle' })
    setNotice(null)
    setEditor({ mode: 'create' })
    setValues(emptyValues(kind))
  }

  const change = (next: Partial<FormValues>) => {
    commandKey.current = null
    setCommand({ kind: 'idle' })
    setValues((current) => ({ ...current, ...next }))
  }

  const cancel = () => {
    commandKey.current = null
    setCommand({ kind: 'idle' })
    setEditor(null)
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (editor === null || command.kind === 'saving') {
      return
    }
    setCommand({ kind: 'saving' })
    const key = commandKey.current ?? (commandKey.current = createIdempotencyKey())
    const payload = {
      version: interaction.version,
      kind: values.kind,
      description: values.description,
      riskLevel: values.kind === 'RISK' && values.riskLevel !== '' ? values.riskLevel : null,
      responsibleId: values.responsibleId || null,
      dueOn: values.dueOn || null
    }
    try {
      const updated = editor.mode === 'create'
        ? await apiClient.createInteractionIssue(interaction.id, payload, key)
        : editor.mode === 'edit'
          ? await apiClient.updateInteractionIssue(interaction.id, editor.issue.id, payload, key)
          : await apiClient.resolveInteractionIssue(
            interaction.id,
            editor.issue.id,
            { version: interaction.version, resolution: values.resolution },
            key
          )
      commandKey.current = null
      setCommand({ kind: 'idle' })
      setNotice(editor.mode === 'create'
        ? `${issueKindLabels[values.kind]} добавлен${values.kind === 'PROBLEM' ? 'а' : ''}.`
        : editor.mode === 'edit' ? 'Изменения сохранены.' : 'Отмечено решённым.')
      setEditor(null)
      onChanged(updated)
    } catch (error) {
      if (!handledAccessError(error, handlers)) {
        setCommand({ kind: 'failed', error })
      }
    }
  }

  const items = listState.kind === 'ready' ? listState.list.items : []
  const openItems = items.filter((issue) => issue.status === 'OPEN')
  const resolvedCount = items.length - openItems.length
  const shown = showResolved ? items : openItems
  const options = listState.kind === 'ready' ? listState.list.responsibleOptions : []
  const editedIssue = editor !== null && editor.mode !== 'create' ? editor.issue : null
  const responsibleMissing = editedIssue !== null && !options.some((option) => option.id === editedIssue.responsibleId)
  const today = todayIso()
  const saving = command.kind === 'saving'

  return (
    <div className="issues">
      {notice !== null && <p className="issues__notice" role="status">{notice}</p>}
      {canEdit && editor === null && (
        <div className="issues__actions">
          <button type="button" onClick={() => startCreate('PROBLEM')}>Добавить проблему</button>
          <button type="button" className="button--secondary" onClick={() => startCreate('RISK')}>Добавить риск</button>
        </div>
      )}

      {editor !== null && (
        <form className="issues__form" onSubmit={(event) => void submit(event)}>
          <h3>
            {editor.mode === 'create' && (values.kind === 'PROBLEM' ? 'Новая проблема' : 'Новый риск')}
            {editor.mode === 'edit' && `Изменить: ${issueTitle(editor.issue).toLowerCase()}`}
            {editor.mode === 'resolve' && 'Отметить решённым'}
          </h3>
          {editor.mode === 'resolve' ? (
            <>
              <p className="issues__quote">{editor.issue.description}</p>
              <label>
                <span>Как решили<RequiredMark /></span>
                <textarea
                  value={values.resolution}
                  maxLength={1000}
                  required
                  onChange={(event) => change({ resolution: event.target.value })}
                />
              </label>
            </>
          ) : (
            <>
              {editor.mode === 'create' && (
                <fieldset className="segmented">
                  <legend>Вид</legend>
                  {(['PROBLEM', 'RISK'] as const).map((kind) => (
                    <label key={kind} className="segmented__option">
                      <input
                        type="radio"
                        name="issue-kind"
                        checked={values.kind === kind}
                        onChange={() => change({ kind, riskLevel: kind === 'RISK' ? values.riskLevel || 'MEDIUM' : '' })}
                      />
                      <span>{issueKindLabels[kind]}</span>
                    </label>
                  ))}
                </fieldset>
              )}
              <label>
                <span>Описание<RequiredMark /></span>
                <textarea
                  value={values.description}
                  maxLength={1000}
                  required
                  onChange={(event) => change({ description: event.target.value })}
                />
              </label>
              {values.kind === 'RISK' && (
                <label>
                  <span>Уровень риска<RequiredMark /></span>
                  <select
                    value={values.riskLevel}
                    required
                    onChange={(event) => change({ riskLevel: issueLevels.find((level) => level === event.target.value) ?? '' })}
                  >
                    {issueLevels.map((level) => <option key={level} value={level}>{issueLevelLabels[level]}</option>)}
                  </select>
                </label>
              )}
              <label>
                Ответственный
                <select value={values.responsibleId} onChange={(event) => change({ responsibleId: event.target.value })}>
                  {editor.mode === 'create' && <option value="">Ответственный за вуз</option>}
                  {responsibleMissing && editedIssue !== null && (
                    <option value={editedIssue.responsibleId}>{editedIssue.responsibleName}</option>
                  )}
                  {options.map((option) => <option key={option.id} value={option.id}>{option.displayName}</option>)}
                </select>
              </label>
              <label>
                Срок решения
                <input type="date" value={values.dueOn} onChange={(event) => change({ dueOn: event.target.value })} />
              </label>
            </>
          )}
          <div className="issues__form-actions">
            <button type="submit" disabled={saving}>{saving ? 'Сохраняем…' : editor.mode === 'resolve' ? 'Решено' : 'Сохранить'}</button>
            <button type="button" className="button--secondary" disabled={saving} onClick={cancel}>Отмена</button>
          </div>
          {command.kind === 'failed' && (
            <div className="notice notice--error" role="alert">
              <p>{commandErrorText(command.error, 'сохранить')}</p>
              <SupportDetails requestId={requestIdOf(command.error)} code={command.error instanceof ApiError ? command.error.code : undefined} />
              {command.error instanceof ApiError && command.error.status === 409 && (
                <button type="button" className="button--secondary" onClick={onRefresh}>Обновить карточку</button>
              )}
            </div>
          )}
        </form>
      )}

      {listState.kind === 'loading' && <p className="issues__empty" role="status">Загружаем проблемы и риски…</p>}
      {listState.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Не удалось загрузить проблемы и риски.</p>
          <SupportDetails requestId={requestIdOf(listState.error)} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {listState.kind === 'ready' && (
        <>
          <div className="issues__toolbar">
            <p>Открытых: {openItems.length}</p>
            {resolvedCount > 0 && (
              <label className="checkbox-field">
                <input type="checkbox" checked={showResolved} onChange={(event) => setShowResolved(event.target.checked)} />
                Показать решённые ({resolvedCount})
              </label>
            )}
          </div>
          {shown.length === 0 ? (
            <p className="issues__empty">Открытых проблем и рисков нет.</p>
          ) : (
            <ul className="issues__list">
              {shown.map((issue) => (
                <li key={issue.id} className={`issues__item${issue.status === 'RESOLVED' ? ' issues__item--resolved' : ''}`}>
                  <div className="issues__head">
                    <span className={`status status--${issue.status === 'RESOLVED' ? 'planned' : issue.riskLevel === 'MEDIUM' ? 'missing' : 'overdue'}`}>
                      {issueTitle(issue)}
                    </span>
                    {issue.status === 'RESOLVED' && <span className="status status--planned">Решена</span>}
                    {isIssueOverdue(issue, today) && <span className="status status--overdue">Срок прошёл</span>}
                  </div>
                  <p className="issues__description">{issue.description}</p>
                  <dl className="issues__facts">
                    <div>
                      <dt>Ответственный</dt>
                      <dd>{issue.responsibleName}</dd>
                    </div>
                    <div>
                      <dt>Срок</dt>
                      <dd>{issue.dueOn === null ? 'не указан' : formatDate(issue.dueOn)}</dd>
                    </div>
                    <div>
                      <dt>Отметил</dt>
                      <dd>{issue.createdByName}, {formatDateTime(issue.createdAt)}</dd>
                    </div>
                    {issue.status === 'RESOLVED' && (
                      <div>
                        <dt>Решил</dt>
                        <dd>{issue.resolvedByName}{issue.resolvedAt === null ? '' : `, ${formatDateTime(issue.resolvedAt)}`}</dd>
                      </div>
                    )}
                    {issue.resolution !== null && (
                      <div className="issues__resolution">
                        <dt>Как решили</dt>
                        <dd>{issue.resolution}</dd>
                      </div>
                    )}
                  </dl>
                  {canEdit && issue.status === 'OPEN' && editor === null && (
                    <div className="issues__item-actions">
                      <button type="button" className="button--secondary" onClick={() => open({ mode: 'edit', issue })}>Изменить</button>
                      <button type="button" className="button--secondary" onClick={() => open({ mode: 'resolve', issue })}>Решено</button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="issues__hint">Кто и когда добавил, изменил или решил запись, видно во вкладке «История».</p>
        </>
      )}
    </div>
  )
}
