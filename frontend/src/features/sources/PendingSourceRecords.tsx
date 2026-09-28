import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type Me,
  type Organization,
  type PendingSourceRecord
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf } from './sourceFormat'
import './sources.css'

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
  | { kind: 'done'; message: string }
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

export const PendingSourceRecords = ({ role, onSessionExpired, onProfileUnavailable }: PendingSourceRecordsProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})

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
      setCommand({ kind: 'done', message })
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
    return `Организация «${created.organizationName}» создана в вашей команде со статусом «Требует назначения»; `
      + `заявка ${created.result.record.status === 'APPLIED' ? 'применена' : 'ожидает разбора'}.`
  })

  const busy = command.kind === 'running'

  return (
    <section className="source-panel" aria-labelledby="pending-source-records-title">
      <div className="source-panel__row">
        <h2 id="pending-source-records-title">Заявки источников, ожидающие разбора</h2>
      </div>
      <p>
        {role === 'LEADER' &&
          'Заявки с сайта по вузам вашей команды и по вузам, которых ещё нет в CRM. Вуз, которого нет в CRM, можно сопоставить с организацией команды или создать из заявки.'}
        {role === 'ADMIN' &&
          'Заявки с сайта, требующие сопоставления вуза, разбирает руководитель команды. Здесь видны заявки, которые можно применить, создав организацию напрямую.'}
        {role !== 'LEADER' && role !== 'ADMIN' &&
          'Заявки с сайта по вашим вузам, которые не применены автоматически. Программы сопоставляет администратор CRM, новые вузы — руководитель команды.'}
      </p>
      {state.kind === 'loading' && <p role="status">Загружаем заявки источников…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить заявки источников.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {command.kind === 'done' && <p role="status">{command.message}</p>}
      {state.kind === 'ready' && state.records.length === 0 && <p>Заявок, ожидающих разбора, нет.</p>}
      {state.kind === 'ready' && state.records.length > 0 && (
        <ul className="source-list">
          {state.records.map((record) => {
            const draft = draftOf(record)
            return (
              <li key={record.id}>
                <strong>
                  {recordTypeLabels[record.recordType] ?? record.recordType}: {record.organizationName ?? 'вуз не указан'}
                  {record.organizationId || record.organizationName?.includes('нет в CRM') ? '' : ' (нет в CRM)'}
                </strong>
                <span>
                  {record.submittedAt ? `Подана ${formatDateTime(record.submittedAt)}` : 'Дата подачи не указана'}
                  {record.programName ? `; программа на сайте: ${record.programName}` : ''}
                  {record.crmOrganizationName ? `; вуз CRM: ${record.crmOrganizationName}` : ''}
                </span>
                <span className="source-reason">
                  Причина: {record.error ?? (record.organizationId ? 'программа не сопоставлена с каталогом CRM' : 'вуз не сопоставлен с организацией CRM')}
                </span>
                {record.canResolve && (
                  <div className="source-form">
                    <label>
                      Организация команды
                      <select value={draft.organizationId} onChange={(event) => update(record, { organizationId: event.target.value })}>
                        <option value="">Выберите организацию</option>
                        {state.organizations.map((organization) => (
                          <option key={organization.id} value={organization.id}>{organization.name}</option>
                        ))}
                      </select>
                    </label>
                    <div className="source-form__actions">
                      <button type="button" disabled={busy || draft.organizationId === ''} onClick={() => void resolve(record)}>
                        Сопоставить
                      </button>
                    </div>
                    <label>
                      Название новой организации
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
                    <div className="source-form__actions">
                      <button type="button" className="button--secondary" disabled={busy || draft.name.trim() === ''} onClick={() => void create(record)}>
                        Создать организацию
                      </button>
                    </div>
                  </div>
                )}
                {command.kind === 'failed' && command.id === record.id && (
                  <div role="alert">
                    <p className="source-error">{errorText(command.error, 'Действие не выполнено.')}</p>
                    {fieldErrors(command.error).map((text) => <p key={text}>{text}</p>)}
                    <SupportDetails requestId={requestIdOf(command.error)} />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
