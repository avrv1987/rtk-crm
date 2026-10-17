import { useState } from 'react'
import {
  ApiError,
  apiClient,
  type RunKind,
  type SourceMappingOptions,
  type SourceRecord,
  type Team
} from '../../shared/api/client'
import { shiftCalendarDate } from '../../shared/format/datetime'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { crmRecordTypeLabels } from './crmExchange'
import { SourceReviewTable, type ReviewRow } from './SourceReviewTable'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf } from './sourceFormat'
import { filterReviewRecords, reviewSourceCounts, type ReviewFilterSource } from './sourceReview'
import './sourcePage.css'

type SourceReviewTabProps = {
  records: SourceRecord[]
  options: SourceMappingOptions
  teams: Team[]
  onReload: () => Promise<void>
  onNotice: (message: string) => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Mapping = {
  organizationId: string
  contactId: string
  programId: string
  runStartsOn: string
  runLastDay: string
  runKind: RunKind
  newOrganizationName: string
  newOrganizationTeamId: string
}

type Command =
  | { kind: 'idle' }
  | { kind: 'running'; target: string }
  | { kind: 'failed'; target: string; error: unknown }

const recordTypeLabels: Record<string, string> = {
  partnership_request: 'Заявка вуза на партнёрство',
  learning_application: 'Заявка на обучение',
  paid_order: 'Оплаченная заявка (сайт)',
  moodle_course: 'Курс Moodle',
  moodle_group: 'Группа курса Moodle',
  ...crmRecordTypeLabels
}

const sourceNames: Record<string, string> = {
  WEBSITE: 'сайт',
  MOODLE: 'Moodle',
  CUSTOMER_CRM: 'CRM заказчика',
  LABOR_MARKET: 'рынок труда'
}

const recordStatusLabels: Record<SourceRecord['status'], string> = {
  APPLIED: 'Применена',
  NEEDS_MAPPING: 'Требует сопоставления',
  FAILED: 'Ошибка',
  SKIPPED: 'Пропущена'
}

const runKindLabels: Record<RunKind, string> = {
  STUDENTS: 'Занятия студентов',
  TEACHERS: 'Обучение преподавателей'
}

const isPaidOrder = (record: SourceRecord) => record.recordType === 'paid_order'
const isMoodle = (record: SourceRecord) => record.source === 'MOODLE'
const isCrm = (record: SourceRecord) => record.source === 'CUSTOMER_CRM'

const needsOrganization = (record: SourceRecord) => (
  record.status === 'NEEDS_MAPPING' && !record.organizationId && !isPaidOrder(record)
)

const needsContact = (record: SourceRecord) => (
  record.recordType === 'crm_contact' && record.status === 'NEEDS_MAPPING' && Boolean(record.organizationId)
    && (record.candidates?.length ?? 0) > 0
)

const needsProgram = (record: SourceRecord) => (
  record.status === 'NEEDS_MAPPING' && (isMoodle(record) || Boolean(record.programName)) && !record.programId
)

const needsRun = (record: SourceRecord) => isMoodle(record) && record.status === 'NEEDS_MAPPING'

const changedLabel = (record: SourceRecord) => (
  isMoodle(record) ? 'Наблюдение' : isPaidOrder(record) ? 'Получена CRM' : isCrm(record) ? 'Изменена в CRM заказчика' : 'Изменена на сайте'
)

const rowOf = (record: SourceRecord): ReviewRow => ({
  id: record.id,
  title: record.recordType === 'crm_contact' ? record.contactName ?? 'ФИО не указано' : record.organizationName ?? 'Вуз не указан',
  subtitle: `${recordTypeLabels[record.recordType] ?? record.recordType} · ${sourceNames[record.source] ?? record.source}`,
  program: record.programName ?? null,
  stream: isMoodle(record)
    ? `${runKindLabels[record.suggestedRunKind ?? 'STUDENTS']}, даты не заданы`
    : record.streamNo !== null && record.streamNo !== undefined ? `Поток № ${record.streamNo}` : null,
  state: recordStatusLabels[record.status],
  stateNote: record.error ?? null,
  danger: record.status === 'FAILED'
})

const sourceFilters: { value: ReviewFilterSource; label: string }[] = [
  { value: 'ALL', label: 'Все' },
  { value: 'WEBSITE', label: 'Сайт' },
  { value: 'MOODLE', label: 'Moodle' },
  { value: 'CUSTOMER_CRM', label: 'CRM заказчика' }
]

export const SourceReviewTab = ({ records, options, teams, onReload, onNotice, onSessionExpired, onProfileUnavailable }: SourceReviewTabProps) => {
  const [command, setCommand] = useState<Command>({ kind: 'idle' })
  const [mappings, setMappings] = useState<Record<string, Mapping>>({})
  const [expanded, setExpanded] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [source, setSource] = useState<ReviewFilterSource>('ALL')

  const counts = reviewSourceCounts(records)
  const visible = filterReviewRecords(records, search, source)
  const busy = command.kind === 'running'

  const mappingOf = (record: SourceRecord): Mapping => (
    mappings[record.id] ?? {
      organizationId: '',
      contactId: '',
      programId: '',
      runStartsOn: '',
      runLastDay: '',
      runKind: record.suggestedRunKind ?? 'STUDENTS',
      newOrganizationName: record.organizationName ?? '',
      newOrganizationTeamId: ''
    }
  )

  const updateMapping = (record: SourceRecord, patch: Partial<Mapping>) => {
    setMappings((current) => ({ ...current, [record.id]: { ...mappingOf(record), ...patch } }))
  }

  const fail = (record: SourceRecord, error: unknown) => {
    if (!accessHandled(error, onSessionExpired, onProfileUnavailable)) {
      setCommand({ kind: 'failed', target: record.id, error })
    }
  }

  const applyRecord = async (record: SourceRecord) => {
    const mapping = mappingOf(record)
    setCommand({ kind: 'running', target: record.id })
    try {
      const result = await apiClient.applySourceRecord(record.id, {
        organizationId: mapping.organizationId === '' ? null : mapping.organizationId,
        programId: mapping.programId === '' ? null : mapping.programId,
        runStartsOn: needsRun(record) && mapping.runStartsOn !== '' ? mapping.runStartsOn : null,
        runEndsOn: needsRun(record) && mapping.runLastDay !== '' ? shiftCalendarDate(mapping.runLastDay, 1) : null,
        runKind: needsRun(record) ? mapping.runKind : null,
        contactId: needsContact(record) && mapping.contactId !== '' ? mapping.contactId : null
      })
      setCommand({ kind: 'idle' })
      onNotice(
        (result.record.status === 'APPLIED'
          ? `Запись ${result.record.externalId} применена.`
          : `Запись ${result.record.externalId}: ${recordStatusLabels[result.record.status].toLowerCase()}. ${result.record.error ?? ''}`)
        + (result.reappliedCount > 0 ? ` Сопоставление применило ещё записей: ${result.reappliedCount}.` : '')
      )
    } catch (error) {
      fail(record, error)
    }
    await onReload()
  }

  const createOrganization = async (record: SourceRecord) => {
    const mapping = mappingOf(record)
    setCommand({ kind: 'running', target: record.id })
    try {
      const created = await apiClient.createOrganizationFromAdminSourceRecord(record.id, {
        name: mapping.newOrganizationName,
        teamId: mapping.newOrganizationTeamId === '' ? null : mapping.newOrganizationTeamId
      })
      setCommand({ kind: 'idle' })
      onNotice(
        `Вуз «${created.organizationName}» создан со статусом «Требует назначения»; запись ${created.result.record.externalId}: `
        + `${recordStatusLabels[created.result.record.status].toLowerCase()}.`
        + (created.result.reappliedCount > 0 ? ` Применено ещё записей этого вуза: ${created.result.reappliedCount}.` : '')
      )
    } catch (error) {
      fail(record, error)
    }
    await onReload()
  }

  const renderForm = (row: ReviewRow) => {
    const record = records.find((item) => item.id === row.id)
    if (record === undefined) {
      return null
    }
    const mapping = mappingOf(record)
    const missingOrganization = needsOrganization(record) && mapping.organizationId === ''
    const missingContact = needsContact(record) && mapping.contactId === ''
    const missingProgram = isMoodle(record) && needsProgram(record) && mapping.programId === ''
    const missingRun = needsRun(record) && (mapping.runStartsOn === '' || mapping.runLastDay === '')
    const incomplete = missingOrganization || missingContact || missingProgram || missingRun
    const running = command.kind === 'running' && command.target === record.id
    return (
      <div className="src-section">
        <p className="src-muted">
          Внешний ID: {record.externalId}
          {record.organizationExternalId ? `, ID вуза у источника: ${record.organizationExternalId}` : ''}. {changedLabel(record)}: {formatDateTime(record.externalUpdatedAt)}.
        </p>
        <div className="src-record-form">
          {needsOrganization(record) && (
            <label>
              Вуз CRM
              <select value={mapping.organizationId} onChange={(event) => updateMapping(record, { organizationId: event.target.value })}>
                <option value="">Выберите вуз</option>
                {(record.candidates?.length ?? 0) > 0 && (
                  <optgroup label="Подходящие по данным CRM заказчика">
                    {record.candidates?.map((option) => <option key={`candidate-${option.id}`} value={option.id}>{option.name}</option>)}
                  </optgroup>
                )}
                <optgroup label="Все организации">
                  {options.organizations.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
                </optgroup>
              </select>
            </label>
          )}
          {needsContact(record) && (
            <label>
              Контакт организации
              <select value={mapping.contactId} onChange={(event) => updateMapping(record, { contactId: event.target.value })}>
                <option value="">Выберите контакт</option>
                {record.candidates?.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
              </select>
            </label>
          )}
          {needsProgram(record) && (
            <label>
              Программа CRM
              <select value={mapping.programId} onChange={(event) => updateMapping(record, { programId: event.target.value })}>
                <option value="">{isMoodle(record) ? 'Выберите программу' : 'Искать по названию'}</option>
                {options.programs.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
              </select>
            </label>
          )}
          {needsRun(record) && (
            <>
              <label>
                Вид потока
                <select value={mapping.runKind} onChange={(event) => updateMapping(record, { runKind: event.target.value as RunKind })}>
                  <option value="STUDENTS">Занятия студентов</option>
                  <option value="TEACHERS">Обучение преподавателей</option>
                </select>
                {record.suggestedRunKind === 'TEACHERS' && (
                  <span className="src-field__note">Выбрано по названию курса: он помечен как курс преподавателей (ППС).</span>
                )}
              </label>
              <label>
                Начало потока
                <input type="date" value={mapping.runStartsOn} onChange={(event) => updateMapping(record, { runStartsOn: event.target.value })} />
              </label>
              <label>
                Последний день потока
                <input
                  type="date"
                  min={mapping.runStartsOn === '' ? undefined : mapping.runStartsOn}
                  value={mapping.runLastDay}
                  onChange={(event) => updateMapping(record, { runLastDay: event.target.value })}
                />
              </label>
            </>
          )}
        </div>
        <div className="src-actions">
          <button type="button" onClick={() => void applyRecord(record)} disabled={busy || incomplete}>
            {running ? 'Применяем…' : record.status === 'NEEDS_MAPPING' ? 'Сопоставить и применить' : 'Применить повторно'}
          </button>
          {incomplete && (
            <span className="src-muted">
              {isMoodle(record)
                ? 'Выберите вуз и программу и укажите даты потока, чтобы применить запись.'
                : missingContact
                  ? 'Выберите контакт организации, чтобы применить запись.'
                  : 'Выберите вуз, чтобы применить запись.'}
            </span>
          )}
        </div>
        {needsOrganization(record) && !isMoodle(record) && record.recordType !== 'crm_contact' && (
          <details>
            <summary>Вуза нет в CRM — создать вуз</summary>
            <div className="src-record-form">
              <label>
                Название
                <input maxLength={300} value={mapping.newOrganizationName} onChange={(event) => updateMapping(record, { newOrganizationName: event.target.value })} />
              </label>
              <label>
                Команда
                <select value={mapping.newOrganizationTeamId} onChange={(event) => updateMapping(record, { newOrganizationTeamId: event.target.value })}>
                  <option value="">Выберите команду</option>
                  {teams.map((team) => <option key={team.id} value={team.id}>{team.name}</option>)}
                </select>
              </label>
              <div className="src-actions">
                <button
                  type="button"
                  className="button--secondary"
                  onClick={() => void createOrganization(record)}
                  disabled={busy || mapping.newOrganizationName.trim() === '' || mapping.newOrganizationTeamId === ''}
                >
                  Создать вуз
                </button>
              </div>
            </div>
          </details>
        )}
        {command.kind === 'failed' && command.target === record.id && (
          <div role="alert">
            <p className="src-error">{errorText(command.error, 'Запись не применена.')}</p>
            {fieldErrors(command.error).map((text) => <p key={text} className="src-error">{text}</p>)}
            <SupportDetails requestId={requestIdOf(command.error)} code={command.error instanceof ApiError ? command.error.code : undefined} />
          </div>
        )}
      </div>
    )
  }

  if (records.length === 0) {
    return <EmptyState text="Записей, требующих сопоставления или завершившихся ошибкой, нет." />
  }

  return (
    <div className="src-section">
      <div className="src-toolbar">
        <input
          type="search"
          aria-label="Поиск по записям для разбора"
          placeholder="Вуз, курс, программа или внешний ID"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Segmented
          label="Источник"
          value={source}
          onChange={setSource}
          options={sourceFilters.map((item) => ({ ...item, label: `${item.label} (${counts[item.value]})` }))}
        />
        <HintButton label="Что такое записи для разбора">
          Заявки сайта, курсы и группы Moodle, организации и контакты CRM заказчика, которые не сопоставились сами или завершились
          ошибкой. Разберите запись: выберите вуз и программу, и она применится.
        </HintButton>
      </div>
      {visible.length === 0 ? (
        <EmptyState text="По такому запросу записей нет." />
      ) : (
        <SourceReviewTable
          rows={visible.map(rowOf)}
          ariaLabel="Записи источников для разбора"
          titleHeader="Вуз или курс источника"
          streamHeader="Вид и даты потока"
          expandedId={expanded}
          onToggle={(id) => setExpanded((current) => (current === id ? null : id))}
          renderForm={renderForm}
        />
      )}
    </div>
  )
}
