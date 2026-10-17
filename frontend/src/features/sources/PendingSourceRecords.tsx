import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type Me,
  type Organization,
  type PendingSourceRecord
} from '../../shared/api/client'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { SourceReviewTable, type ReviewRow } from './SourceReviewTable'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf } from './sourceFormat'
import './sourcePage.css'

type PendingSourceRecordsProps = {
  role: Me['role']
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; records: PendingSourceRecord[]; organizations: Organization[] }
  | { kind: 'failed'; requestId: string | undefined }

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; id: string }
  | { kind: 'failed'; id: string; error: unknown }

type Draft = {
  organizationId: string
  name: string
  type: 'UNIVERSITY' | 'COLLEGE' | 'SCHOOL'
}

const recordTypeLabels: Record<string, string> = {
  partnership_request: 'Заявка вуза на партнёрство',
  learning_application: 'Заявка на обучение'
}

const organizationPageSize = 100

const loadOrganizations = async () => {
  const organizations: Organization[] = []
  for (let page = 0; ; page += 1) {
    const result = await apiClient.listOrganizations({ page, size: organizationPageSize, sort: 'name,asc' })
    organizations.push(...result.items)
    if (result.items.length < organizationPageSize) {
      return organizations
    }
  }
}

const hints = {
  LEADER: 'Заявки с сайта по вузам вашей команды и по вузам, которых ещё нет в CRM. Вуз, которого нет в CRM, можно сопоставить с вузом команды или создать из заявки.',
  ADMIN: 'Заявки с сайта, требующие сопоставления вуза, разбирает руководитель команды. Здесь видны заявки, которые можно применить, создав вуз напрямую.',
  OTHER: 'Заявки с сайта по вашим вузам, которые не применены автоматически. Программы сопоставляет администратор CRM, новые вузы — руководитель команды.'
}

const reasonOf = (record: PendingSourceRecord) => (
  record.error ?? (record.organizationId ? 'программа не сопоставлена с каталогом CRM' : 'вуз не сопоставлен с организацией CRM')
)

const rowOf = (record: PendingSourceRecord): ReviewRow => ({
  id: record.id,
  title: `${record.organizationName ?? 'вуз не указан'}${record.organizationId || record.organizationName?.includes('нет в CRM') ? '' : ' (нет в CRM)'}`,
  subtitle: recordTypeLabels[record.recordType] ?? record.recordType,
  program: record.programName ?? null,
  stream: record.submittedAt ? formatDateTime(record.submittedAt) : 'не указана',
  state: 'Требует разбора',
  stateNote: `Причина: ${reasonOf(record)}${record.crmOrganizationName ? `; вуз CRM: ${record.crmOrganizationName}` : ''}`,
  danger: false,
  actionable: record.canResolve
})

export const PendingSourceRecords = ({ role, onSessionExpired, onProfileUnavailable }: PendingSourceRecordsProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})
  const [expanded, setExpanded] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const load = useCallback(async () => {
    try {
      const [records, organizations] = await Promise.all([
        apiClient.listPendingSourceRecords(),
        role === 'LEADER' ? loadOrganizations() : Promise.resolve([])
      ])
      setState({ kind: 'ready', records, organizations })
    } catch (error) {
      if (!handled(error)) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [handled, role])

  useEffect(() => {
    void load()
  }, [load])

  const draftOf = (record: PendingSourceRecord): Draft => (
    drafts[record.id] ?? { organizationId: '', name: record.organizationName ?? '', type: 'UNIVERSITY' }
  )

  const update = (record: PendingSourceRecord, patch: Partial<Draft>) => {
    setDrafts((current) => ({ ...current, [record.id]: { ...draftOf(record), ...patch } }))
  }

  const run = async (record: PendingSourceRecord, action: () => Promise<string>) => {
    setCommand({ kind: 'running', id: record.id })
    try {
      const message = await action()
      setCommand({ kind: 'idle' })
      setNotice(message)
      setExpanded(null)
      await load()
    } catch (error) {
      if (!handled(error)) {
        setCommand({ kind: 'failed', id: record.id, error })
      }
    }
  }

  const resolve = (record: PendingSourceRecord) => run(record, async () => {
    const result = await apiClient.resolvePendingSourceRecord(record.id, draftOf(record).organizationId)
    return `Заявка ${result.record.externalId} сопоставлена${result.record.status === 'APPLIED' ? ' и применена' : `: ${result.record.error ?? ''}`}.`
      + (result.reappliedCount > 0 ? ` Применено ещё заявок этого вуза: ${result.reappliedCount}.` : '')
  })

  const create = (record: PendingSourceRecord) => run(record, async () => {
    const draft = draftOf(record)
    const created = await apiClient.createOrganizationFromSourceRecord(record.id, { name: draft.name, type: draft.type })
    return `Вуз «${created.organizationName}» создан в вашей команде со статусом «Требует назначения»; `
      + `заявка ${created.result.record.status === 'APPLIED' ? 'применена' : 'ожидает разбора'}.`
  })

  const busy = command.kind === 'running'
  const hint = role === 'LEADER' ? hints.LEADER : role === 'ADMIN' ? hints.ADMIN : hints.OTHER

  const renderForm = (row: ReviewRow) => {
    if (state.kind !== 'ready') {
      return null
    }
    const record = state.records.find((item) => item.id === row.id)
    if (record === undefined) {
      return null
    }
    const draft = draftOf(record)
    return (
      <div className="src-section">
        <div className="src-record-form">
          <label>
            Вуз CRM
            <select value={draft.organizationId} onChange={(event) => update(record, { organizationId: event.target.value })}>
              <option value="">Выберите вуз</option>
              {state.organizations.map((organization) => (
                <option key={organization.id} value={organization.id}>{organization.name}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="src-actions">
          <button type="button" disabled={busy || draft.organizationId === ''} onClick={() => void resolve(record)}>
            {busy && command.id === record.id ? 'Применяем…' : 'Сопоставить и применить'}
          </button>
        </div>
        <details>
          <summary>Вуза нет в CRM — создать вуз</summary>
          <div className="src-record-form">
            <label>
              Название
              <input maxLength={300} value={draft.name} onChange={(event) => update(record, { name: event.target.value })} />
            </label>
            <label>
              Тип
              <select value={draft.type} onChange={(event) => update(record, { type: event.target.value as Draft['type'] })}>
                <option value="UNIVERSITY">Вуз</option>
                <option value="COLLEGE">Колледж (СПО)</option>
                <option value="SCHOOL">Школа</option>
              </select>
            </label>
            <div className="src-actions">
              <button type="button" className="button--secondary" disabled={busy || draft.name.trim() === ''} onClick={() => void create(record)}>
                Создать вуз
              </button>
            </div>
          </div>
        </details>
        {command.kind === 'failed' && command.id === record.id && (
          <div role="alert">
            <p className="src-error">{errorText(command.error, 'Действие не выполнено.')}</p>
            {fieldErrors(command.error).map((text) => <p key={text} className="src-error">{text}</p>)}
            <SupportDetails requestId={requestIdOf(command.error)} />
          </div>
        )}
      </div>
    )
  }

  return (
    <section className="source-panel" aria-labelledby="pending-source-records-title">
      <div className="source-panel__row">
        <h2 id="pending-source-records-title">Заявки с сайта, ожидающие разбора</h2>
        <HintButton label="Что это за заявки">{hint}</HintButton>
      </div>
      {state.kind === 'loading' && <p role="status">Загружаем заявки с сайта…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить заявки с сайта.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {state.kind === 'ready' && state.records.length === 0 && <EmptyState text="Заявок, ожидающих разбора, нет." />}
      {state.kind === 'ready' && state.records.length > 0 && (
        <SourceReviewTable
          rows={state.records.map(rowOf)}
          ariaLabel="Заявки с сайта, ожидающие разбора"
          titleHeader="Вуз на сайте"
          streamHeader="Подана"
          expandedId={expanded}
          onToggle={(id) => setExpanded((current) => (current === id ? null : id))}
          renderForm={renderForm}
        />
      )}
      <Toast message={notice} onClose={() => setNotice(null)} />
    </section>
  )
}
