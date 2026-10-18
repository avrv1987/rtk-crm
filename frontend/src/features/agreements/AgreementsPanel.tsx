import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { apiClient, type Me, type Organization } from '../../shared/api/client'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { CardDialog, CardSection } from '../interactions/cardUi'
import {
  activityStatusLabels,
  agreementStatusLabels,
  agreementsApi,
  formatDate,
  formatInstant,
  formatPeriod,
  saveBlob,
  type ActivityKind,
  type ActivityStatus,
  type Agreement,
  type AgreementActivity,
  type AgreementActivityInput,
  type AgreementOptions,
  type AgreementStatus,
  type AgreementSummary
} from './agreementsApi'
import { agreementStatuses, daysInStatus, inputFromAgreement } from './agreementModel'
import { planKindLabels } from '../reports/signingPlan'
import { daysLabel } from '../work/workShared'
import { AgreementFiles } from './AgreementFiles'
import { AgreementForm } from './AgreementForm'
import { AgreementSupplements } from './AgreementSupplements'
import { AgreementTransitions } from './AgreementTransitions'
import { AgreementTermMark, CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'
import './agreements.css'

type AgreementsPanelProps = {
  organizationId: Organization['id']
  role: Me['role']
  initialAgreementId?: string
  onChanged?: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; error: unknown }

type Catalogs = {
  options: AgreementOptions
  kinds: ActivityKind[]
}

const activityStatuses: readonly ActivityStatus[] = ['PLANNED', 'IN_PROGRESS', 'DONE', 'CANCELLED']

const agreementTitle = (agreement: { number: string; concludedOn?: string | null }) => (
  `№ ${agreement.number}${agreement.concludedOn ? ` от ${formatDate(agreement.concludedOn)}` : ''}`
)

const volumeAndPeriod = (
  value: number | null | undefined,
  unit: string | null | undefined,
  start: string | null | undefined,
  end: string | null | undefined
) => {
  const parts = [
    value === null || value === undefined ? null : `${value}${unit ? ` ${unit}` : ''}`,
    start || end ? formatPeriod(start, end) : null
  ].filter((part) => part !== null)
  return parts.length === 0 ? 'Не указан' : parts.join('; ')
}

const orNull = (value: string) => (value.trim() === '' ? null : value.trim())
const numberOrNull = (value: string) => (value.trim() === '' ? null : Number(value))
const names = (items: { name: string }[]) => items.map((item) => item.name).join('; ')

export const AgreementsPanel = ({ organizationId, role, initialAgreementId, onChanged, onSessionExpired, onProfileUnavailable }: AgreementsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [list, setList] = useState<Load<AgreementSummary[]>>({ kind: 'loading' })
  const [catalogs, setCatalogs] = useState<Load<Catalogs> | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(initialAgreementId ?? null)
  const [creating, setCreating] = useState(false)
  const [statusFilter, setStatusFilter] = useState<AgreementStatus | ''>('')
  const canEdit = role === 'USER' || role === 'LEADER'
  const titleId = useId()
  const now = Date.now()

  const loadList = useCallback(async () => {
    setList((current) => (current.kind === 'ready' ? current : { kind: 'loading' }))
    try {
      setList({ kind: 'ready', value: await agreementsApi.list(organizationId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setList({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  const loadCatalogs = useCallback(async () => {
    setCatalogs((current) => (current?.kind === 'ready' ? current : { kind: 'loading' }))
    try {
      const [options, kinds] = await Promise.all([agreementsApi.options(organizationId), agreementsApi.listKinds(true)])
      setCatalogs({ kind: 'ready', value: { options, kinds } })
    } catch (error) {
      if (!handleAccessError(error)) {
        setCatalogs({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  useEffect(() => {
    void loadList()
  }, [loadList])

  useEffect(() => {
    if ((selectedId !== null || creating) && catalogs === null) {
      void loadCatalogs()
    }
  }, [catalogs, creating, loadCatalogs, selectedId])

  useEffect(() => {
    if (initialAgreementId !== undefined && list.kind === 'ready') {
      window.requestAnimationFrame(() => document.getElementById(`agreement-${initialAgreementId}`)?.scrollIntoView({ block: 'start' }))
    }
  }, [initialAgreementId, list.kind])

  const saved = (agreement: Agreement) => {
    setCreating(false)
    setSelectedId(agreement.id)
    void loadList()
    onChanged?.()
  }

  const visible = list.kind === 'ready'
    ? list.value.filter((agreement) => statusFilter === '' || agreement.status === statusFilter)
    : []

  return (
    <section className={`agreements${selectedId === null ? '' : ' agreements--has-selection'}`} aria-labelledby={titleId}>
      <div className="agreements__header">
        <h4 id={titleId}>Договоры с вузом</h4>
        <HintButton label="Как устроены договоры">
          Договор с вузом проходит согласование, корректировку и подписание; каждая смена статуса и каждая версия файла сохраняются
          в истории. К плану мероприятий привязываются взаимодействия вуза и подтверждающие документы; итоги — в отчёте «Реализация договоров».
        </HintButton>
        {list.kind === 'ready' && list.value.length > 0 && (
          <label className="agreements__filter">
            <span className="visually-hidden">Статус договора</span>
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as AgreementStatus | '')}>
              <option value="">Все статусы</option>
              {agreementStatuses.map((status) => <option key={status} value={status}>{agreementStatusLabels[status]}</option>)}
            </select>
          </label>
        )}
        {canEdit && (
          <button type="button" className="button--secondary agreements__add" onClick={() => { setCreating(true); void loadCatalogs() }}>
            Добавить договор
          </button>
        )}
      </div>
      {list.kind === 'loading' && <p role="status">Загружаем договоры…</p>}
      {list.kind === 'failed' && (
        <CommandError error={list.error} fallback="Не удалось загрузить договоры." onRetry={() => void loadList()} />
      )}
      {list.kind === 'ready' && list.value.length === 0 && <p>Договоров пока нет.</p>}
      {list.kind === 'ready' && list.value.length > 0 && visible.length === 0 && <p>Договоров с таким статусом нет.</p>}
      <div className="agreements__layout">
        {visible.length > 0 && (
          <ul className="agreements__list">
            {visible.map((agreement) => (
              <li key={agreement.id} id={`agreement-${agreement.id}`}>
                <button
                  type="button"
                  className={`agreements__item${agreement.id === selectedId ? ' agreements__item--selected' : ''}`}
                  aria-expanded={agreement.id === selectedId}
                  onClick={() => setSelectedId(agreement.id === selectedId ? null : agreement.id)}
                >
                  <span className="agreements__item-title">{agreementTitle(agreement)}</span>
                  <span className={`agreements__status agreements__status--${agreement.status.toLowerCase()}`}>
                    {agreementStatusLabels[agreement.status]}
                  </span>
                  <AgreementTermMark agreement={agreement} />
                  <span className="agreements__item-meta">
                    {`${daysLabel(daysInStatus(agreement.statusChangedAt, now))} в статусе · `}
                    {agreement.validUntil ? `до ${formatDate(agreement.validUntil)} · ` : ''}
                    {agreement.plannedKind && agreement.plannedOn
                      ? `план: ${planKindLabels[agreement.plannedKind].toLowerCase()} ${formatDate(agreement.plannedOn)} · `
                      : ''}
                    мероприятий: {agreement.activityCount} · подтверждений: {agreement.confirmationCount}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {selectedId !== null && (
          <div className="agreements__detail">
            <button type="button" className="button--secondary agreements__back" onClick={() => setSelectedId(null)}>← Все договоры</button>
            {catalogs?.kind === 'loading' && <p role="status">Загружаем справочники…</p>}
            {catalogs?.kind === 'failed' && (
              <CommandError error={catalogs.error} fallback="Не удалось загрузить справочники." onRetry={() => void loadCatalogs()} />
            )}
            {catalogs?.kind === 'ready' && (
              <AgreementDetail
                key={selectedId}
                agreementId={selectedId}
                organizationId={organizationId}
                catalogs={catalogs.value}
                canEdit={canEdit}
                onChanged={() => { void loadList(); onChanged?.() }}
                onRefreshCatalogs={() => void loadCatalogs()}
                handleAccessError={handleAccessError}
              />
            )}
          </div>
        )}
      </div>
      <CardDialog open={creating} title="Новый договор с вузом" onClose={() => setCreating(false)}>
        {catalogs?.kind === 'loading' && <p role="status">Загружаем справочники…</p>}
        {catalogs?.kind === 'failed' && (
          <CommandError error={catalogs.error} fallback="Не удалось загрузить справочники." onRetry={() => void loadCatalogs()} />
        )}
        {catalogs?.kind === 'ready' && (
          <AgreementForm
            organizationId={organizationId}
            options={catalogs.value.options}
            onSaved={saved}
            onCancel={() => setCreating(false)}
            handleAccessError={handleAccessError}
          />
        )}
      </CardDialog>
    </section>
  )
}

type AttachFromWorkProps = {
  agreement: Agreement
  options: AgreementOptions
  onSaved: (agreement: Agreement) => void
  handleAccessError: (error: unknown) => boolean
}

const AttachFromWork = ({ agreement, options, onSaved, handleAccessError }: AttachFromWorkProps) => {
  const [open, setOpen] = useState(false)
  const [fileId, setFileId] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  if (!open) {
    return <button type="button" className="button--secondary" onClick={() => setOpen(true)}>Из документов взаимодействий</button>
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload = { ...inputFromAgreement(agreement), fileAttachmentId: fileId }
    setSaving(true)
    setError(null)
    try {
      const result = await agreementsApi.update(agreement.id, payload, commandKey.keyFor(payload))
      commandKey.settle()
      setOpen(false)
      setFileId('')
      onSaved(result)
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="agreement-form agreement-form--inline" onSubmit={(event) => void submit(event)} aria-label="Версия из документов взаимодействий">
      <label>
        Проверенный документ из взаимодействий вуза
        <select value={fileId} required onChange={(event) => setFileId(event.target.value)}>
          <option value="">Выберите документ</option>
          {options.attachments.map((item) => (
            <option key={item.id} value={item.id}>{item.originalName} — {item.interactionTitle}, {item.stageName}</option>
          ))}
        </select>
      </label>
      {options.attachments.length === 0 && <p className="agreement-form__hint">Проверенных документов во взаимодействиях вуза пока нет.</p>}
      {error !== null && <CommandError error={error} fallback="Версия не добавлена." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving || fileId === ''}>{saving ? 'Добавляем…' : 'Добавить версию'}</button>
        <button type="button" className="button--secondary" disabled={saving} onClick={() => setOpen(false)}>Отмена</button>
      </div>
    </form>
  )
}

type AgreementDetailProps = {
  agreementId: string
  organizationId: string
  catalogs: Catalogs
  canEdit: boolean
  onChanged: () => void
  onRefreshCatalogs: () => void
  handleAccessError: (error: unknown) => boolean
}

type ActivityEditor = { mode: 'create' } | { mode: 'edit'; activity: AgreementActivity } | null

const AgreementDetail = ({
  agreementId,
  organizationId,
  catalogs,
  canEdit,
  onChanged,
  onRefreshCatalogs,
  handleAccessError
}: AgreementDetailProps) => {
  const [state, setState] = useState<Load<Agreement>>({ kind: 'loading' })
  const [editingAgreement, setEditingAgreement] = useState(false)
  const [editor, setEditor] = useState<ActivityEditor>(null)
  const [deleting, setDeleting] = useState<AgreementActivity | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [archiving, setArchiving] = useState(false)
  const deleteKey = useCommandKey()

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', value: await agreementsApi.get(agreementId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [agreementId, handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const openEditor = (next: ActivityEditor) => {
    onRefreshCatalogs()
    setEditor(next)
  }

  const refresh = () => {
    void load()
    onChanged()
  }

  const confirmDelete = async () => {
    if (deleting === null) {
      return
    }
    const target = deleting
    setDeleting(null)
    setActionError(null)
    try {
      await agreementsApi.deleteActivity(target.id, target.version, deleteKey.keyFor({ id: target.id, version: target.version }))
      deleteKey.settle()
      refresh()
    } catch (error) {
      deleteKey.settle(error)
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    }
  }

  const downloadArchive = async (agreement: Agreement) => {
    setArchiving(true)
    setActionError(null)
    try {
      saveBlob(await agreementsApi.archive({ agreementId: agreement.id }), `Подтверждения_${agreement.number.replace(/[\\/:*?"<>|]/g, '_')}.zip`)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    } finally {
      setArchiving(false)
    }
  }

  const downloadDocument = async (id: string, name: string) => {
    setActionError(null)
    try {
      saveBlob(await apiClient.downloadAttachment(id), name)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    }
  }

  if (state.kind === 'loading') {
    return <p role="status">Загружаем договор…</p>
  }
  if (state.kind === 'failed') {
    return <CommandError error={state.error} fallback="Не удалось открыть договор." onRetry={() => void load()} />
  }
  const agreement = state.value
  const confirmationCount = agreement.activities.reduce((sum, activity) => sum + activity.attachments.length, 0)
  const subject = [names(agreement.directions), names(agreement.programs), names(agreement.products)].filter((part) => part !== '').join('; ')
  const changed = (updated: Agreement) => {
    setState({ kind: 'ready', value: updated })
    onChanged()
  }

  return (
    <article className="agreement-detail" aria-label={`Договор ${agreementTitle(agreement)}`}>
      <div className="agreement-detail__header">
        <h5>Договор {agreementTitle(agreement)}</h5>
        <span className="agreement-detail__status">
          <span className={`agreements__status agreements__status--${agreement.status.toLowerCase()}`}>
            {agreementStatusLabels[agreement.status]}
          </span>
          <AgreementTermMark agreement={agreement} />
          <span className="agreement-activity__document-meta">
            {`${daysLabel(daysInStatus(agreement.statusChangedAt, Date.now()))} в статусе, с ${formatInstant(agreement.statusChangedAt)}`}
          </span>
        </span>
        <ActionMenu
          label={`Ещё действия с договором ${agreementTitle(agreement)}`}
          items={[
            ...(canEdit ? [{ label: 'Изменить реквизиты…', onSelect: () => { onRefreshCatalogs(); setEditingAgreement(true) } }] : []),
            ...(confirmationCount > 0 ? [{ label: `Скачать подтверждения архивом (${confirmationCount})`, onSelect: () => void downloadArchive(agreement) }] : [])
          ]}
        />
      </div>
      {canEdit && (
        <AgreementTransitions agreement={agreement} onDone={changed} handleAccessError={handleAccessError} />
      )}
      {archiving && <p role="status">Готовим архив подтверждений…</p>}
      <CardDialog open={editingAgreement} title="Реквизиты договора" onClose={() => setEditingAgreement(false)}>
        <AgreementForm
          organizationId={organizationId}
          agreement={agreement}
          options={catalogs.options}
          onSaved={(updated) => { setEditingAgreement(false); changed(updated) }}
          onCancel={() => setEditingAgreement(false)}
          handleAccessError={handleAccessError}
        />
      </CardDialog>
      <dl className="agreement-detail__fields">
        <div>
          <dt>Вуз</dt>
          <dd>{agreement.organizationName}</dd>
        </div>
        <div>
          <dt>Дата подписания</dt>
          <dd>{agreement.concludedOn ? formatDate(agreement.concludedOn) : 'Не подписан'}</dd>
        </div>
        <div>
          <dt>Действует до</dt>
          <dd>{agreement.validUntil ? formatDate(agreement.validUntil) : 'Не указано'}</dd>
        </div>
        <div>
          <dt>Отправлен на согласование</dt>
          <dd>{agreement.sentOn ? formatDate(agreement.sentOn) : 'Не отправлялся'}</dd>
        </div>
        <div>
          <dt>Подписант вуза</dt>
          <dd>{agreement.universitySigner?.name ?? 'Не выбран'}</dd>
        </div>
        <div>
          <dt>Подписант школы</dt>
          <dd>{agreement.schoolSigner?.name ?? 'Не выбран'}</dd>
        </div>
        <div>
          <dt>Стороны</dt>
          <dd>{agreement.parties ?? 'Не указаны'}</dd>
        </div>
        <div>
          <dt>План подписания или продления</dt>
          <dd>
            {agreement.plannedKind && agreement.plannedOn
              ? `${planKindLabels[agreement.plannedKind]}: ${formatDate(agreement.plannedOn)}`
              : 'Не запланировано'}
          </dd>
        </div>
        <div className="agreement-detail__wide">
          <dt>Предмет договора</dt>
          <dd>{subject === '' ? 'Не указан' : subject}</dd>
        </div>
        <div className="agreement-detail__wide">
          <dt>Связанные взаимодействия</dt>
          <dd>
            {agreement.interactions.length === 0 ? 'Не связаны' : (
              <ul className="agreement-detail__works">
                {agreement.interactions.map((item) => (
                  <li key={item.id}><a href={`#/organizations/${agreement.organizationId}/${item.id}`}>{item.title}</a></li>
                ))}
              </ul>
            )}
          </dd>
        </div>
      </dl>

      <AgreementFiles
        title="Файл договора"
        agreementId={agreement.id}
        currentFile={agreement.currentFile ?? null}
        files={agreement.files}
        canEdit={canEdit}
        onUploaded={() => void load()}
        handleAccessError={handleAccessError}
      >
        <AttachFromWork agreement={agreement} options={catalogs.options} onSaved={changed} handleAccessError={handleAccessError} />
      </AgreementFiles>

      <CardSection title="Дополнительные соглашения" count={agreement.supplements.length}>
        <AgreementSupplements
          agreementId={agreement.id}
          supplements={agreement.supplements}
          canEdit={canEdit}
          onChanged={() => void load()}
          handleAccessError={handleAccessError}
        />
      </CardSection>

      <CardSection title="История статусов" count={agreement.history.length}>
        {agreement.history.length === 0 ? <p>Переходов пока не было.</p> : (
          <ol className="agreement-history">
            {agreement.history.map((event) => (
              <li key={event.id}>
                <span className="agreement-history__move">
                  {event.fromStatus ? `${agreementStatusLabels[event.fromStatus]} → ` : ''}{agreementStatusLabels[event.toStatus]}
                </span>
                <span className="agreement-activity__document-meta">
                  {[formatInstant(event.occurredAt), event.actorName, event.interactionTitle ? `из взаимодействия «${event.interactionTitle}»` : null]
                    .filter((part) => part !== null && part !== '').join(' · ')}
                </span>
                {event.comment && <span className="agreement-files__comment">{event.comment}</span>}
              </li>
            ))}
          </ol>
        )}
      </CardSection>

      <section className="agreement-plan" aria-label="План мероприятий">
        <div className="agreement-plan__header">
          <h6>План мероприятий</h6>
          {canEdit && editor === null && (
            <button type="button" className="button--secondary" onClick={() => openEditor({ mode: 'create' })}>Добавить мероприятие</button>
          )}
        </div>
        {actionError !== null && <CommandError error={actionError} fallback="Действие не выполнено." />}
        <CardDialog
          open={editor !== null}
          title={editor?.mode === 'edit' ? 'Изменить мероприятие' : 'Новое мероприятие'}
          onClose={() => setEditor(null)}
        >
          {editor !== null && (
            <ActivityForm
              key={editor.mode === 'edit' ? editor.activity.id : 'create'}
              agreementId={agreement.id}
              activity={editor.mode === 'edit' ? editor.activity : undefined}
              catalogs={catalogs}
              onSaved={() => { setEditor(null); refresh() }}
              onCancel={() => setEditor(null)}
              handleAccessError={handleAccessError}
            />
          )}
        </CardDialog>
        {agreement.activities.length === 0 && editor === null && <p>Мероприятий пока нет.</p>}
        <ul className="agreement-plan__list">
          {agreement.activities.map((activity) => (
            <li key={activity.id} className="agreement-activity">
              <div className="agreement-activity__header">
                <span className="agreement-activity__kind">{activity.kindName}</span>
                <span className={`agreement-activity__status agreement-activity__status--${activity.status.toLowerCase()}`}>
                  {activityStatusLabels[activity.status]}
                </span>
              </div>
              <p className="agreement-activity__title">{activity.title}</p>
              <dl className="agreement-activity__facts">
                <div>
                  <dt>План</dt>
                  <dd>{volumeAndPeriod(activity.plannedVolume, activity.unit, activity.plannedStart, activity.plannedEnd)}</dd>
                </div>
                <div>
                  <dt>Факт</dt>
                  <dd>{volumeAndPeriod(activity.actualVolume, activity.unit, activity.actualStart, activity.actualEnd)}</dd>
                </div>
                <div>
                  <dt>Ответственный</dt>
                  <dd>{activity.responsibleName ?? 'Не назначен'}</dd>
                </div>
                <div>
                  <dt>Взаимодействия</dt>
                  <dd>{activity.interactions.length === 0 ? 'Не связаны' : activity.interactions.map((item) => item.title).join('; ')}</dd>
                </div>
              </dl>
              <div className="agreement-activity__documents">
                <span>Подтверждения:</span>
                {activity.attachments.length === 0 ? (
                  <span className="agreement-activity__missing">нет</span>
                ) : (
                  <ul>
                    {activity.attachments.map((item) => (
                      <li key={item.id}>
                        <button type="button" className="agreements-link" onClick={() => void downloadDocument(item.id, item.originalName)}>
                          {item.originalName}
                        </button>
                        <span className="agreement-activity__document-meta"> {formatInstant(item.createdAt)}, {item.stageName}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              {canEdit && editor === null && (
                <div className="agreement-activity__actions">
                  <button type="button" className="button--secondary" onClick={() => openEditor({ mode: 'edit', activity })}>Изменить</button>
                  <button type="button" className="button--danger" onClick={() => setDeleting(activity)}>Удалить</button>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
      <ConfirmDialog
        open={deleting !== null}
        title="Удалить мероприятие?"
        description={`«${deleting?.title ?? ''}» будет удалено из плана вместе со связями. Сами документы останутся во взаимодействиях вуза.`}
        confirmLabel="Удалить"
        onConfirm={() => void confirmDelete()}
        onCancel={() => setDeleting(null)}
      />
    </article>
  )
}

type ActivityFormProps = {
  agreementId: string
  activity?: AgreementActivity
  catalogs: Catalogs
  onSaved: () => void
  onCancel: () => void
  handleAccessError: (error: unknown) => boolean
}

const ActivityForm = ({ agreementId, activity, catalogs, onSaved, onCancel, handleAccessError }: ActivityFormProps) => {
  const kinds = catalogs.kinds.filter((kind) => !kind.archived || kind.id === activity?.kindId)
  const [kindId, setKindId] = useState(activity?.kindId ?? kinds[0]?.id ?? '')
  const [title, setTitle] = useState(activity?.title ?? '')
  const [unit, setUnit] = useState(activity?.unit ?? '')
  const [plannedVolume, setPlannedVolume] = useState(activity?.plannedVolume?.toString() ?? '')
  const [actualVolume, setActualVolume] = useState(activity?.actualVolume?.toString() ?? '')
  const [plannedStart, setPlannedStart] = useState(activity?.plannedStart ?? '')
  const [plannedEnd, setPlannedEnd] = useState(activity?.plannedEnd ?? '')
  const [actualStart, setActualStart] = useState(activity?.actualStart ?? '')
  const [actualEnd, setActualEnd] = useState(activity?.actualEnd ?? '')
  const [responsibleId, setResponsibleId] = useState(activity?.responsibleProfileId ?? '')
  const [status, setStatus] = useState<ActivityStatus>(activity?.status ?? 'PLANNED')
  const [interactionIds, setInteractionIds] = useState<string[]>(activity?.interactions.map((item) => item.id) ?? [])
  const [attachmentIds, setAttachmentIds] = useState<string[]>(activity?.attachments.map((item) => item.id) ?? [])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()
  const formId = useId()

  const toggle = (values: string[], id: string, checked: boolean) => (
    checked ? [...values, id] : values.filter((item) => item !== id)
  )

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const payload: AgreementActivityInput = {
      version: activity?.version ?? null,
      kindId,
      title: title.trim(),
      unit: orNull(unit),
      plannedVolume: numberOrNull(plannedVolume),
      actualVolume: numberOrNull(actualVolume),
      plannedStart: orNull(plannedStart),
      plannedEnd: orNull(plannedEnd),
      actualStart: orNull(actualStart),
      actualEnd: orNull(actualEnd),
      responsibleProfileId: orNull(responsibleId),
      status,
      interactionIds,
      attachmentIds
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor(payload)
      if (activity === undefined) {
        await agreementsApi.createActivity(agreementId, payload, key)
      } else {
        await agreementsApi.updateActivity(activity.id, payload, key)
      }
      commandKey.settle()
      onSaved()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={activity ? 'Изменение мероприятия' : 'Новое мероприятие'}>
      <div className="agreement-form__grid">
        <label className="agreement-form__wide">
          <span>Вид мероприятия<span className="required-mark" aria-hidden="true"> *</span></span>
          <select value={kindId} required onChange={(event) => setKindId(event.target.value)}>
            {kinds.map((kind) => <option key={kind.id} value={kind.id}>{kind.name}{kind.archived ? ' (в архиве)' : ''}</option>)}
          </select>
        </label>
        <label className="agreement-form__wide">
          <span>Мероприятие<span className="required-mark" aria-hidden="true"> *</span></span>
          <input value={title} required maxLength={300} onChange={(event) => setTitle(event.target.value)} />
        </label>
        <label>
          Объём по плану
          <input type="number" min={0} step={1} inputMode="numeric" value={plannedVolume} onChange={(event) => setPlannedVolume(event.target.value)} />
        </label>
        <label>
          Объём факт
          <input type="number" min={0} step={1} inputMode="numeric" value={actualVolume} onChange={(event) => setActualVolume(event.target.value)} />
        </label>
        <label>
          Единица
          <input value={unit} maxLength={50} placeholder="чел., ч, программ" onChange={(event) => setUnit(event.target.value)} />
        </label>
        <label>
          Статус
          <select value={status} onChange={(event) => setStatus(event.target.value as ActivityStatus)}>
            {activityStatuses.map((item) => <option key={item} value={item}>{activityStatusLabels[item]}</option>)}
          </select>
        </label>
        <label>
          План: начало
          <input type="date" value={plannedStart} max={plannedEnd || undefined} onChange={(event) => setPlannedStart(event.target.value)} />
        </label>
        <label>
          План: окончание
          <input type="date" value={plannedEnd} min={plannedStart || undefined} onChange={(event) => setPlannedEnd(event.target.value)} />
        </label>
        <label>
          Факт: начало
          <input type="date" value={actualStart} max={actualEnd || undefined} onChange={(event) => setActualStart(event.target.value)} />
        </label>
        <label>
          Факт: окончание
          <input type="date" value={actualEnd} min={actualStart || undefined} onChange={(event) => setActualEnd(event.target.value)} />
        </label>
        <label className="agreement-form__wide">
          Ответственный
          <select value={responsibleId} onChange={(event) => setResponsibleId(event.target.value)}>
            <option value="">Не назначен</option>
            {catalogs.options.responsibles.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
          </select>
        </label>
      </div>
      <fieldset className="agreement-form__choices">
        <legend>Связанные взаимодействия вуза</legend>
        {catalogs.options.interactions.length === 0 && <p>У вуза пока нет взаимодействий.</p>}
        <ul>
          {catalogs.options.interactions.map((item) => (
            <li key={item.id}>
              <label>
                <input
                  type="checkbox"
                  checked={interactionIds.includes(item.id)}
                  onChange={(event) => setInteractionIds((current) => toggle(current, item.id, event.target.checked))}
                />
                <span>{item.title}</span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      <fieldset className="agreement-form__choices" aria-describedby={`${formId}-documents`}>
        <legend>Подтверждающие документы</legend>
        <p id={`${formId}-documents`} className="agreement-form__hint">
          Документы загружаются во взаимодействие с вузом на нужном этапе и после проверки выбираются здесь. Списки обучившихся с ФИО не прикладывайте без решения оператора персональных данных: число обученных указывается объёмом.
        </p>
        {catalogs.options.attachments.length === 0 && <p>Проверенных документов во взаимодействиях вуза пока нет.</p>}
        <ul>
          {catalogs.options.attachments.map((item) => (
            <li key={item.id}>
              <label>
                <input
                  type="checkbox"
                  checked={attachmentIds.includes(item.id)}
                  onChange={(event) => setAttachmentIds((current) => toggle(current, item.id, event.target.checked))}
                />
                <span>{item.originalName}<span className="agreement-activity__document-meta"> — {item.interactionTitle}, {item.stageName}, {formatInstant(item.createdAt)}</span></span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      {error !== null && <CommandError error={error} fallback="Мероприятие не сохранено." />}
      <div className="agreement-form__actions">
        <button type="submit" disabled={saving || kindId === ''}>{saving ? 'Сохраняем…' : 'Сохранить мероприятие'}</button>
        <button type="button" className="button--secondary" onClick={onCancel} disabled={saving}>Отмена</button>
      </div>
    </form>
  )
}
