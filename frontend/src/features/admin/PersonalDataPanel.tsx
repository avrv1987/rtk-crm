import { getModules } from '../../shared/modules'
import { Fragment, useCallback, useState, type FormEvent, type ReactNode } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AnonymizationResult,
  type PersonalDataReason,
  type SubjectContact,
  type SubjectExportFormat,
  type SubjectLearner,
  type SubjectMention,
  type SubjectQuery,
  type SubjectSearchResult
} from '../../shared/api/client'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { Toast } from '../../shared/ui/Toast'
import { terms } from '../../shared/ui/terms'
import { CardDialog } from '../interactions/cardUi'
import { commandErrorMessage, formatDateTime, handledSessionError, type SessionHandlers } from './adminShared'
import { saveFile, today } from './saveFile'
import './adminUi.css'
import './adminSecurity.css'
import './security.css'

type QueryDraft = {
  name: string
  email: string
  phone: string
  snils: string
  otherSpellings: string
}

type SearchState =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | { kind: 'ready'; query: SubjectQuery; result: SubjectSearchResult }
  | { kind: 'failed'; error: unknown }

type ContactDraft = {
  name: string
  position: string
  email: string
  phone: string
}

type ContactEdit = {
  contact: SubjectContact
  draft: ContactDraft
  saving: boolean
  error?: unknown
  idempotencyKey: string
}

type Selection = {
  contactIds: string[]
  profileIds: string[]
  attachmentIds: string[]
  learnerIds: string[]
}

type ReasonDraft = { reason: PersonalDataReason | ''; comment: string }

type AnonymizeState =
  | { kind: 'idle' }
  | { kind: 'previewing' }
  | { kind: 'confirming'; preview: AnonymizationResult; draft: ReasonDraft; idempotencyKey: string; running: boolean; error?: unknown }
  | { kind: 'failed'; error: unknown }

type RestrictTarget =
  | { kind: 'contact'; contact: SubjectContact; restricted: boolean }
  | { kind: 'learner'; learner: SubjectLearner; title: string; restricted: boolean }

type RestrictState = { target: RestrictTarget; draft: ReasonDraft; saving: boolean; error?: unknown; idempotencyKey: string }

const emptyDraft: QueryDraft = { name: '', email: '', phone: '', snils: '', otherSpellings: '' }
const emptySelection: Selection = { contactIds: [], profileIds: [], attachmentIds: [], learnerIds: [] }
const emptyReason: ReasonDraft = { reason: '', comment: '' }

const reasonLabels: Record<PersonalDataReason, string> = {
  SUBJECT_REQUEST: 'Обращение субъекта ПДн',
  CONSENT_WITHDRAWN: 'Отзыв согласия',
  PURPOSE_ACHIEVED: 'Достигнута цель обработки',
  UNLAWFUL_PROCESSING: 'Выявлена неправомерная обработка',
  REGULATOR_REQUEST: 'Требование Роскомнадзора',
  OTHER: 'Другое (опишите в комментарии)'
}

const reasonReady = (draft: ReasonDraft) => draft.reason !== '' && (draft.reason !== 'OTHER' || draft.comment.trim() !== '')

const reasonPayload = (draft: ReasonDraft) => ({
  reason: draft.reason as PersonalDataReason,
  comment: draft.comment.trim() || null
})

const previewLines = (preview: AnonymizationResult) => [
  ['Контакты', preview.contacts],
  ['Профили сотрудников', preview.profiles],
  ['Анкеты слушателей', preview.learners],
  ['Упоминания в карточках взаимодействий', preview.mentions],
  ['Имена файлов — будут переименованы', preview.attachmentsRenamed],
  ['Записи источников', preview.sourceRecords],
  ['Служебные записи: журнал, снимки команд и импорта', preview.technicalRecords],
  ['Файлы вложений — будут удалены', preview.attachmentsDeleted],
  ['Готовые отчёты — будут удалены', preview.reportFilesDeleted]
] as const

const statusLabels: Record<SubjectContact['status'], string> = {
  ACTIVE: 'Обрабатывается',
  RESTRICTED: 'Обработка ограничена',
  ANONYMIZED: 'Обезличен'
}

const learnerStatusLabels: Record<SubjectLearner['status'], string> = {
  ACTIVE: 'Обрабатывается',
  RESTRICTED: 'Обработка ограничена',
  ANONYMIZED: 'Обезличена'
}

const statusChip: Record<SubjectContact['status'], string> = {
  ACTIVE: 'admin-chip--ok',
  RESTRICTED: 'admin-chip--warn',
  ANONYMIZED: ''
}

const placeLabels: Record<SubjectMention['place'], string> = {
  COMMENT: 'Комментарий',
  PLAN_HISTORY: 'Следующий шаг в истории',
  NEXT_ACTION: 'Текущий следующий шаг',
  TITLE: 'Название карточки',
  AGENT_PLAN: 'План записи ИИ-агента'
}

const recordTypeLabels: Record<string, string> = {
  partnership_request: 'Заявка вуза на партнёрство',
  learning_application: 'Заявка на обучение',
  moodle_course: 'Курс Moodle',
  moodle_group: 'Группа курса Moodle',
  crm_organization: 'Организация из CRM заказчика',
  crm_contact: 'Контакт из CRM заказчика'
}

const recordStatusLabels: Record<string, string> = {
  APPLIED: 'применена',
  NEEDS_MAPPING: 'требует сопоставления',
  FAILED: 'ошибка',
  SKIPPED: 'пропущена'
}

const queryOf = (draft: QueryDraft): SubjectQuery => ({
  name: draft.name.trim() || null,
  email: draft.email.trim() || null,
  phone: draft.phone.trim() || null,
  snils: draft.snils.trim() || null,
  otherSpellings: draft.otherSpellings.trim() || null
})

const toggle = (values: string[], value: string) => (
  values.includes(value) ? values.filter((item) => item !== value) : [...values, value]
)

const sizeLabel = (bytes: number) => (
  bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} КБ` : `${(bytes / 1024 / 1024).toFixed(1)} МБ`
)

const errorText = (error: unknown) => {
  if (error instanceof ApiError && error.code === 'PERSONAL_DATA_ANONYMIZED') {
    return 'Данные уже обезличены и больше не изменяются.'
  }
  if (error instanceof ApiError && error.code === 'PROFILE_ACTIVE') {
    return 'Обезличить можно только профиль с закрытым доступом. Сначала заблокируйте сотрудника в разделе «Сотрудники».'
  }
  return commandErrorMessage(error)
}

const ErrorBox = ({ error }: { error: unknown }) => (
  <div className="interaction-command-error" role="alert">
    <p>{errorText(error)}</p>
    <SupportDetails requestId={error instanceof ApiError ? error.requestId : undefined} />
  </div>
)

const ReasonFields = ({ draft, disabled, onChange }: { draft: ReasonDraft; disabled: boolean; onChange: (draft: ReasonDraft) => void }) => (
  <>
    <label>
      Основание *
      <select
        value={draft.reason}
        required
        disabled={disabled}
        onChange={(event) => onChange({ ...draft, reason: event.target.value as ReasonDraft['reason'] })}
      >
        <option value="">Выберите основание</option>
        {(Object.keys(reasonLabels) as PersonalDataReason[]).map((reason) => (
          <option key={reason} value={reason}>{reasonLabels[reason]}</option>
        ))}
      </select>
    </label>
    <label>
      Комментарий{draft.reason === 'OTHER' ? ' *' : ''}
      <textarea
        value={draft.comment}
        maxLength={500}
        rows={2}
        disabled={disabled}
        placeholder="Например, номер и дата обращения — без ФИО"
        onChange={(event) => onChange({ ...draft, comment: event.target.value })}
      />
    </label>
    <p className="admin-form__hint">Основание и комментарий записываются в журнал.</p>
  </>
)

const Group = ({ id, title, count, open, children }: { id: string; title: string; count: number; open: boolean; children: ReactNode }) => (
  <details className="pd-group" open={open}>
    <summary><h3 id={id}>{title}: {count}</h3></summary>
    {children}
  </details>
)

export const PersonalDataPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [draft, setDraft] = useState<QueryDraft>(emptyDraft)
  const [moreOpen, setMoreOpen] = useState(false)
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })
  const [selection, setSelection] = useState<Selection>(emptySelection)
  const [edit, setEdit] = useState<ContactEdit | null>(null)
  const [restriction, setRestriction] = useState<RestrictState | null>(null)
  const [anonymize, setAnonymize] = useState<AnonymizeState>({ kind: 'idle' })
  const [outcome, setOutcome] = useState<AnonymizationResult | null>(null)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<unknown>(null)
  const [mailTo, setMailTo] = useState<string | null>(null)
  const [mailNotice, setMailNotice] = useState<string | null>(null)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const runSearch = async (query: SubjectQuery) => {
    setSearch({ kind: 'searching' })
    setSelection(emptySelection)
    setEdit(null)
    try {
      setSearch({ kind: 'ready', query, result: await apiClient.searchPersonalData(query) })
    } catch (error) {
      if (!handleError(error)) {
        setSearch({ kind: 'failed', error })
      }
    }
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setAnonymize({ kind: 'idle' })
    setOutcome(null)
    void runSearch(queryOf(draft))
  }

  const replaceContact = (contact: SubjectContact) => {
    setSearch((current) => (
      current.kind === 'ready'
        ? {
            ...current,
            result: {
              ...current.result,
              contacts: current.result.contacts.map((item) => (item.id === contact.id ? contact : item))
            }
          }
        : current
    ))
  }

  const replaceLearner = (learner: SubjectLearner) => {
    setSearch((current) => (
      current.kind === 'ready'
        ? {
            ...current,
            result: {
              ...current.result,
              learners: current.result.learners.map((item) => (item.id === learner.id ? learner : item))
            }
          }
        : current
    ))
  }

  const exportSubject = async (query: SubjectQuery, format: SubjectExportFormat) => {
    if (exporting) {
      return
    }
    setExporting(true)
    setExportError(null)
    try {
      const blob = await apiClient.exportPersonalData(query, format)
      saveFile(blob, `Сведения о субъекте ПДн ${today()}.${format === 'PDF' ? 'pdf' : 'json'}`)
    } catch (error) {
      if (!handleError(error)) {
        setExportError(error)
      }
    } finally {
      setExporting(false)
    }
  }

  // Те же сведения PDF уходят письмом на адрес, который субъект указал в запросе; вложение в журнале почты не хранится.
  const mailSubject = async (event: FormEvent, query: SubjectQuery) => {
    event.preventDefault()
    if (exporting || mailTo === null) {
      return
    }
    setExporting(true)
    setExportError(null)
    setMailNotice(null)
    try {
      const row = await apiClient.sendSubjectReportByMail(mailTo.trim(), query)
      setMailNotice(row.status === 'SENT'
        ? `Сведения отправлены на ${row.recipient ?? 'указанный адрес'}${row.transport === 'STUB' ? ' (заглушка: письмо в журнале почты, наружу не ушло)' : ''}.`
        : `Письмо поставлено в очередь${row.error ? `: ${row.error}` : ''}. Статус — в «Администрирование → Почта».`)
      setMailTo(null)
    } catch (error) {
      if (!handleError(error)) {
        setExportError(error)
      }
    } finally {
      setExporting(false)
    }
  }

  const beginEdit = (contact: SubjectContact) => {
    setEdit({
      contact,
      draft: {
        name: contact.name,
        position: contact.position ?? '',
        email: contact.email ?? '',
        phone: contact.phone ?? ''
      },
      saving: false,
      idempotencyKey: createIdempotencyKey()
    })
  }

  const changeEdit = (changes: Partial<ContactDraft>) => {
    setEdit((current) => (
      current === null
        ? current
        : { ...current, draft: { ...current.draft, ...changes }, error: undefined, idempotencyKey: createIdempotencyKey() }
    ))
  }

  const saveEdit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (edit === null || edit.saving) {
      return
    }
    setEdit({ ...edit, saving: true, error: undefined })
    try {
      const updated = await apiClient.rectifyPersonalDataContact(edit.contact.id, {
        version: edit.contact.version,
        name: edit.draft.name.trim(),
        position: edit.draft.position.trim() || null,
        email: edit.draft.email.trim() || null,
        phone: edit.draft.phone.trim() || null
      }, edit.idempotencyKey)
      replaceContact(updated)
      setEdit(null)
    } catch (error) {
      if (!handleError(error)) {
        setEdit((current) => (current === null ? current : { ...current, saving: false, error }))
      }
    }
  }

  const openRestriction = (target: RestrictTarget) => {
    setRestriction({ target, draft: emptyReason, saving: false, idempotencyKey: createIdempotencyKey() })
  }

  const submitRestriction = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (restriction === null || restriction.saving || !reasonReady(restriction.draft)) {
      return
    }
    const { target, draft, idempotencyKey } = restriction
    setRestriction({ ...restriction, saving: true, error: undefined })
    try {
      if (target.kind === 'contact') {
        replaceContact(await apiClient.restrictPersonalDataContact(
          target.contact.id,
          { version: target.contact.version, restricted: target.restricted, ...reasonPayload(draft) },
          idempotencyKey
        ))
      } else {
        replaceLearner(await apiClient.restrictPersonalDataLearner(
          target.learner.id,
          { version: target.learner.version, restricted: target.restricted, ...reasonPayload(draft) },
          idempotencyKey
        ))
      }
      setRestriction(null)
    } catch (error) {
      if (!handleError(error)) {
        setRestriction((current) => (current === null ? current : { ...current, saving: false, error }))
      }
    }
  }

  // Сначала предпросмотр: администратор видит, сколько записей каких видов изменится, и только потом подтверждает.
  const startAnonymize = async (query: SubjectQuery) => {
    setAnonymize({ kind: 'previewing' })
    try {
      const preview = await apiClient.previewPersonalDataAnonymization({ subject: query, ...selection })
      setAnonymize({ kind: 'confirming', preview, draft: emptyReason, idempotencyKey: createIdempotencyKey(), running: false })
    } catch (error) {
      if (!handleError(error)) {
        setAnonymize({ kind: 'failed', error })
      }
    }
  }

  const runAnonymize = async (event: FormEvent<HTMLFormElement>, query: SubjectQuery) => {
    event.preventDefault()
    if (anonymize.kind !== 'confirming' || anonymize.running || !reasonReady(anonymize.draft)) {
      return
    }
    const current = anonymize
    setAnonymize({ ...current, running: true, error: undefined })
    try {
      const result = await apiClient.anonymizePersonalData(
        { subject: query, ...selection, ...reasonPayload(current.draft) },
        current.idempotencyKey
      )
      setAnonymize({ kind: 'idle' })
      setOutcome(result)
      await runSearch(query)
    } catch (error) {
      if (!handleError(error)) {
        setAnonymize({ ...current, running: false, error })
      }
    }
  }

  const renderContacts = (contacts: SubjectContact[]) => (
    <div className="admin-table-wrap">
      <table className="admin-table" aria-label="Контакты организаций">
        <thead>
          <tr>
            <th scope="col">Контакт</th>
            <th scope="col">Почта</th>
            <th scope="col">Телефон</th>
            <th scope="col">Взаимодействий</th>
            <th scope="col">Статус</th>
            <th scope="col"><span className="pd-hidden">Действия</span></th>
          </tr>
        </thead>
        <tbody>
          {contacts.map((contact) => {
            const anonymized = contact.status === 'ANONYMIZED'
            return (
              <Fragment key={contact.id}>
                <tr>
                  <td className="admin-table__cell-main">
                    <span className="admin-table__main">{contact.name}</span>
                    <span className="admin-table__sub">{[contact.organizationName, contact.position].filter(Boolean).join(' · ')}</span>
                  </td>
                  <td data-label="Почта">{contact.email ?? '—'}</td>
                  <td data-label="Телефон">{contact.phone ?? '—'}</td>
                  <td data-label="Взаимодействий">{contact.interactionsCount}</td>
                  <td data-label="Статус">
                    <span>
                      <span className={`admin-chip ${statusChip[contact.status]}`}>{statusLabels[contact.status]}</span>
                      <span className="admin-table__sub">Изменён {formatDateTime(contact.updatedAt)}</span>
                    </span>
                  </td>
                  <td className="admin-table__actions">
                    {!anonymized && (
                      <div className="pd-row-actions">
                        <label className="pd-check">
                          <input
                            type="checkbox"
                            checked={selection.contactIds.includes(contact.id)}
                            onChange={() => setSelection({ ...selection, contactIds: toggle(selection.contactIds, contact.id) })}
                          />
                          Обезличить
                        </label>
                        <ActionMenu
                          label={`Действия: ${contact.name}`}
                          items={[
                            { label: 'Уточнить', onSelect: () => beginEdit(contact) },
                            {
                              label: contact.status === 'RESTRICTED' ? 'Снять ограничение' : 'Ограничить обработку',
                              onSelect: () => openRestriction({ kind: 'contact', contact, restricted: contact.status !== 'RESTRICTED' })
                            }
                          ]}
                        />
                      </div>
                    )}
                  </td>
                </tr>
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )

  const renderLearners = (learners: SubjectLearner[]) => (
    <div className="admin-table-wrap">
      <table className="admin-table" aria-label="Анкеты слушателей">
        <thead>
          <tr>
            <th scope="col">Слушатель</th>
            <th scope="col">Анкета</th>
            <th scope="col">Статус</th>
            <th scope="col"><span className="pd-hidden">Действия</span></th>
          </tr>
        </thead>
        <tbody>
          {learners.map((learner, index) => {
            const anonymized = learner.status === 'ANONYMIZED'
            return (
              <Fragment key={learner.id}>
                <tr>
                  <td className="admin-table__cell-main">
                    <span className="admin-table__main">Слушатель №{index + 1}</span>
                    <span className="admin-table__sub">
                      {learner.enrolments.length === 0
                        ? 'Потоки: —'
                        : learner.enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`).join('; ')}
                    </span>
                  </td>
                  <td data-label="Анкета">{anonymized ? 'обезличена' : `заполнено полей: ${learner.filledFields} из 30`}</td>
                  <td data-label="Статус">
                    <span>
                      <span className={`admin-chip ${statusChip[learner.status]}`}>{learnerStatusLabels[learner.status]}</span>
                      <span className="admin-table__sub">Изменена {formatDateTime(learner.updatedAt)}</span>
                    </span>
                  </td>
                  <td className="admin-table__actions">
                    {!anonymized && (
                      <div className="pd-row-actions">
                        <label className="pd-check">
                          <input
                            type="checkbox"
                            checked={selection.learnerIds.includes(learner.id)}
                            onChange={() => setSelection({ ...selection, learnerIds: toggle(selection.learnerIds, learner.id) })}
                          />
                          Обезличить анкету
                        </label>
                        <ActionMenu
                          label={`Действия: слушатель №${index + 1}`}
                          items={[{
                            label: learner.status === 'RESTRICTED' ? 'Снять ограничение' : 'Ограничить обработку',
                            onSelect: () => openRestriction({
                              kind: 'learner', learner, title: `слушатель №${index + 1}`, restricted: learner.status !== 'RESTRICTED'
                            })
                          }]}
                        />
                      </div>
                    )}
                  </td>
                </tr>
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )

  const renderResult = (query: SubjectQuery, result: SubjectSearchResult) => {
    const selectedCount = selection.contactIds.length + selection.profileIds.length + selection.attachmentIds.length
      + selection.learnerIds.length
    const counts = [
      ['contacts', result.contacts.length],
      ['profiles', result.profiles.length],
      ['mentions', result.mentions.length],
      ['attachments', result.attachments.length],
      ['sources', result.sourceRecords.length],
      ['learners', result.learners.length]
    ] as const
    const total = counts.reduce((sum, [, count]) => sum + count, 0)
    const nothingFound = total === 0
    const firstKey = counts.find(([, count]) => count > 0)?.[0]
    // Мало записей — все группы раскрыты; много — только первая, остальные по заголовку.
    const isOpen = (key: string) => total <= 20 || key === firstKey
    const previewing = anonymize.kind === 'previewing'
    return (
      <div className="pd-result">
        {result.truncated && (
          <p className="security-warning" role="status">Найдено слишком много записей, показана часть. Уточните условия поиска.</p>
        )}
        {nothingFound && <EmptyState text="По этим данным ничего не найдено." />}

        {result.contacts.length > 0 && (
          <Group id="subject-contacts-title" title="Контакты организаций" count={result.contacts.length} open={isOpen('contacts')}>
            {renderContacts(result.contacts)}
          </Group>
        )}

        {result.profiles.length > 0 && (
          <Group id="subject-profiles-title" title="Профили сотрудников" count={result.profiles.length} open={isOpen('profiles')}>
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label="Профили сотрудников">
                <thead>
                  <tr>
                    <th scope="col">Сотрудник</th>
                    <th scope="col">Состояние</th>
                    <th scope="col">Обезличивание</th>
                  </tr>
                </thead>
                <tbody>
                  {result.profiles.map((profile) => (
                    <tr key={profile.id}>
                      <td className="admin-table__cell-main">
                        <span className="admin-table__main">{profile.displayName}</span>
                        {profile.login && <span className="admin-table__sub">Логин: {profile.login}</span>}
                      </td>
                      <td data-label="Состояние">
                        <span className={`admin-chip ${profile.active ? 'admin-chip--ok' : profile.pendingActivation ? 'admin-chip--warn' : ''}`}>
                          {profile.anonymized ? 'Обезличен' : profile.active ? 'Доступ открыт' : profile.pendingActivation ? 'Ожидает активации' : 'Доступ закрыт'}
                        </span>
                      </td>
                      <td className="admin-table__actions">
                        {!profile.anonymized && (profile.active ? (
                          <span className="admin-table__sub">Обезличить можно после блокировки в разделе «Сотрудники».</span>
                        ) : (
                          <label className="pd-check">
                            <input
                              type="checkbox"
                              checked={selection.profileIds.includes(profile.id)}
                              onChange={() => setSelection({ ...selection, profileIds: toggle(selection.profileIds, profile.id) })}
                            />
                            Обезличить профиль и имя в истории
                          </label>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Group>
        )}

        {result.mentions.length > 0 && (
          <Group id="subject-mentions-title" title="Упоминания в карточках" count={result.mentions.length} open={isOpen('mentions')}>
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label="Упоминания в карточках">
                <thead>
                  <tr>
                    <th scope="col">Где</th>
                    <th scope="col">Вуз и карточка</th>
                    <th scope="col">Текст</th>
                  </tr>
                </thead>
                <tbody>
                  {result.mentions.map((mention, index) => (
                    <tr key={`${mention.interactionId}-${mention.place}-${index}`}>
                      <td className="admin-table__cell-main">
                        <span className="admin-table__main">{placeLabels[mention.place]}</span>
                        {mention.occurredAt && <span className="admin-table__sub">{formatDateTime(mention.occurredAt)}</span>}
                      </td>
                      <td data-label="Вуз и карточка">
                        <span>
                          {mention.organizationName}
                          <span className="admin-table__sub">«{mention.interactionTitle}»</span>
                        </span>
                      </td>
                      <td data-label="Текст"><span className="pd-quote">{mention.text}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Group>
        )}

        {result.attachments.length > 0 && (
          <Group id="subject-files-title" title="Файлы с данными в названии" count={result.attachments.length} open={isOpen('attachments')}>
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label="Файлы с данными в названии">
                <thead>
                  <tr>
                    <th scope="col">Файл</th>
                    <th scope="col">Размер</th>
                    <th scope="col"><span className="pd-hidden">Удаление</span></th>
                  </tr>
                </thead>
                <tbody>
                  {result.attachments.map((attachment) => (
                    <tr key={attachment.id}>
                      <td className="admin-table__cell-main">
                        <span className="admin-table__main">{attachment.fileName}</span>
                        <span className="admin-table__sub">
                          {attachment.organizationName} · «{attachment.interactionTitle}» · {formatDateTime(attachment.createdAt)}
                        </span>
                      </td>
                      <td data-label="Размер">{sizeLabel(attachment.sizeBytes)}</td>
                      <td className="admin-table__actions">
                        <label className="pd-check">
                          <input
                            type="checkbox"
                            checked={selection.attachmentIds.includes(attachment.id)}
                            onChange={() => setSelection({ ...selection, attachmentIds: toggle(selection.attachmentIds, attachment.id) })}
                          />
                          Удалить файл
                        </label>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Group>
        )}

        {result.sourceRecords.length > 0 && (
          <Group id="subject-sources-title" title="Записи источников" count={result.sourceRecords.length} open={isOpen('sources')}>
            <div className="admin-table-wrap">
              <table className="admin-table" aria-label="Записи источников">
                <thead>
                  <tr>
                    <th scope="col">Запись</th>
                    <th scope="col">Вуз</th>
                    <th scope="col">Статус</th>
                  </tr>
                </thead>
                <tbody>
                  {result.sourceRecords.map((record) => (
                    <tr key={record.id}>
                      <td className="admin-table__cell-main">
                        <span className="admin-table__main">
                          {record.source === 'WEBSITE' ? 'Сайт ИТ Школы' : record.source === 'CUSTOMER_CRM' ? 'CRM заказчика' : 'LMS Moodle'} · {recordTypeLabels[record.recordType] ?? record.recordType}
                        </span>
                        <span className="admin-table__sub">{record.externalId}</span>
                      </td>
                      <td data-label="Вуз">{record.organizationName ?? 'Вуз не определён'}</td>
                      <td data-label="Статус">
                        <span>
                          статус: {recordStatusLabels[record.status] ?? record.status}
                          {record.submittedAt && <span className="admin-table__sub">{formatDateTime(record.submittedAt)}</span>}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Group>
        )}

        {result.learners.length > 0 && (
          <Group id="subject-learners-title" title="Анкеты слушателей" count={result.learners.length} open={isOpen('learners')}>
            <p className="admin-form__hint">
              Значения анкеты на экране не показываются, они входят в выгрузку сведений. Уточнить данные по обращению
              субъекта может оператор зачисления в анкете слушателя.
            </p>
            {renderLearners(result.learners)}
          </Group>
        )}

        {!nothingFound && (
          <div className="pd-danger">
            <button
              type="button"
              className="button--danger"
              disabled={selectedCount === 0 || previewing}
              onClick={() => void startAnonymize(query)}
            >
              {previewing ? 'Считаем…' : 'Обезличить…'}
            </button>
            <p>Сначала CRM покажет, что изменится, и спросит основание.</p>
            <HintButton label="Что делает обезличивание">
              Обезличивание заменяет ФИО целиком, почту и телефон отмеченных субъектов маркером «Контакт обезличен» или
              «Сотрудник обезличен» только в их записях: в контакте или профиле, во взаимодействиях, вложениях, записях
              источников и журнале их вузов. Отдельные слова ФИО не заменяются, записи других вузов не меняются. Отмеченные
              файлы удаляются. Готовые отчёты удаляются только у тех, кто видел эти вузы, — владелец увидит пометку
              «сформирован до обезличивания». У отмеченных анкет слушателей удаляются все поля, зачисления остаются
              в статистике. Действие необратимо.
            </HintButton>
            {selectedCount > 0 && <span className="admin-table__sub">Отмечено для обезличивания: {selectedCount}</span>}
          </div>
        )}
        {anonymize.kind === 'failed' && <ErrorBox error={anonymize.error} />}
        <CardDialog
          open={anonymize.kind === 'confirming'}
          title="Обезличить данные субъекта?"
          onClose={() => {
            if (anonymize.kind === 'confirming' && !anonymize.running) {
              setAnonymize({ kind: 'idle' })
            }
          }}
        >
          {anonymize.kind === 'confirming' && (
            <form className="admin-form" aria-label="Подтверждение обезличивания" onSubmit={(event) => void runAnonymize(event, query)}>
              <p>Будет изменено:</p>
              <ul className="pd-preview">
                {previewLines(anonymize.preview).map(([label, count]) => (
                  <li key={label}>{label}: <strong>{count}</strong></li>
                ))}
              </ul>
              <p className="admin-form__hint">
                Меняются только записи отмеченных субъектов и их вузов. Восстановить данные будет нельзя.
              </p>
              <ReasonFields
                draft={anonymize.draft}
                disabled={anonymize.running}
                onChange={(draft) => setAnonymize({ ...anonymize, draft, error: undefined, idempotencyKey: createIdempotencyKey() })}
              />
              {anonymize.error !== undefined && <ErrorBox error={anonymize.error} />}
              <div className="admin-form__actions">
                <button type="button" className="button--secondary" disabled={anonymize.running} onClick={() => setAnonymize({ kind: 'idle' })}>
                  Отмена
                </button>
                <button type="submit" className="button--danger" disabled={anonymize.running || !reasonReady(anonymize.draft)}>
                  {anonymize.running ? 'Обезличиваем…' : 'Обезличить'}
                </button>
              </div>
            </form>
          )}
        </CardDialog>
      </div>
    )
  }

  const searchEmpty = Object.values(draft).every((value) => value.trim() === '')

  return (
    <section className="admin-page" aria-labelledby="personal-data-title" aria-busy={search.kind === 'searching'}>
      <div className="page-title">
        <h2 id="personal-data-title">Персональные данные</h2>
        <HintButton label="О разделе">
          Запрос субъекта: найдите его данные в контактах, профилях, комментариях, названиях файлов и записях источников,
          выгрузите сведения, уточните, ограничьте обработку или обезличьте.{getModules().enrolment && ' Анкеты слушателей находятся только по точному совпадению почты, телефона, СНИЛС или фамилии и имени.'} Каждое действие записывается в журнал; сами условия
          поиска в журнал не попадают.
        </HintButton>
        <span className="page-title__spacer" />
        {search.kind === 'ready' && (
          <ActionMenu
            label="Выгрузить сведения о субъекте"
            toggle={`${terms.export} ▾`}
            items={[
              { label: 'Выгрузить сведения PDF', onSelect: () => void exportSubject(search.query, 'PDF') },
              { label: 'Выгрузить сведения JSON', onSelect: () => void exportSubject(search.query, 'JSON') },
              ...(getModules().mail === true ? [{ label: 'Отправить сведения на почту субъекта', onSelect: () => setMailTo('') }] : [])
            ]}
          />
        )}
      </div>
      {exporting && <p className="admin-form__hint" role="status">Готовим файл…</p>}
      {exportError !== null && <ErrorBox error={exportError} />}
      {mailNotice !== null && <p className="admin-form__hint" role="status">{mailNotice}</p>}
      {search.kind === 'ready' && mailTo !== null && (
        <form className="admin-form" aria-label="Отправить сведения на почту субъекта" onSubmit={(event) => void mailSubject(event, search.query)}>
          <label>
            Почта субъекта из его запроса
            <input type="email" required maxLength={254} value={mailTo} onChange={(event) => setMailTo(event.target.value)} />
          </label>
          <div className="security-actions">
            <button type="submit" disabled={exporting}>Отправить PDF</button>
            <button type="button" className="button--secondary" onClick={() => setMailTo(null)}>Отмена</button>
          </div>
        </form>
      )}

      <form className="admin-form pd-search" onSubmit={submit} aria-label="Поиск данных субъекта">
        <label>
          ФИО
          <input value={draft.name} maxLength={200} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
        </label>
        <label>
          Почта
          <input
            type="email"
            value={draft.email}
            maxLength={200}
            onChange={(event) => setDraft({ ...draft, email: event.target.value })}
          />
        </label>
        <label>
          Телефон
          <input
            type="tel"
            value={draft.phone}
            maxLength={200}
            onChange={(event) => setDraft({ ...draft, phone: event.target.value })}
          />
        </label>
        <button type="submit" disabled={search.kind === 'searching' || searchEmpty}>
          {search.kind === 'searching' ? 'Ищем…' : 'Найти'}
        </button>
        <details className="admin-more" open={moreOpen} onToggle={(event) => setMoreOpen(event.currentTarget.open)}>
          <summary>Дополнительно</summary>
          <div className="pd-search__more">
            <label>
              СНИЛС
              <input
                inputMode="numeric"
                value={draft.snils}
                maxLength={20}
                placeholder="000-000-000 00"
                onChange={(event) => setDraft({ ...draft, snils: event.target.value })}
              />
            </label>
            <label>
              Другие написания (через запятую)
              <input
                value={draft.otherSpellings}
                maxLength={2000}
                placeholder="Например, И. Петрова, Petrova"
                onChange={(event) => setDraft({ ...draft, otherSpellings: event.target.value })}
              />
            </label>
          </div>
        </details>
      </form>
      {search.kind === 'failed' && <ErrorBox error={search.error} />}
      {search.kind === 'ready' && renderResult(search.query, search.result)}

      <CardDialog
        open={edit !== null}
        title={edit === null ? '' : `Уточнение контакта ${edit.contact.name}`}
        onClose={() => {
          if (edit?.saving !== true) {
            setEdit(null)
          }
        }}
      >
        {edit !== null && (
          <form className="admin-form" onSubmit={(event) => void saveEdit(event)} aria-label={`Уточнение контакта ${edit.contact.name}`}>
            <label>
              ФИО *
              <input
                value={edit.draft.name}
                maxLength={200}
                required
                disabled={edit.saving}
                onChange={(event) => changeEdit({ name: event.target.value })}
              />
            </label>
            <label>
              Должность
              <input
                value={edit.draft.position}
                maxLength={200}
                disabled={edit.saving}
                onChange={(event) => changeEdit({ position: event.target.value })}
              />
            </label>
            <label>
              Почта
              <input
                type="email"
                value={edit.draft.email}
                maxLength={320}
                disabled={edit.saving}
                onChange={(event) => changeEdit({ email: event.target.value })}
              />
            </label>
            <label>
              Телефон
              <input
                type="tel"
                value={edit.draft.phone}
                maxLength={50}
                disabled={edit.saving}
                onChange={(event) => changeEdit({ phone: event.target.value })}
              />
            </label>
            {edit.error !== undefined && <ErrorBox error={edit.error} />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={edit.saving} onClick={() => setEdit(null)}>
                Отмена
              </button>
              <button type="submit" disabled={edit.saving || edit.draft.name.trim() === ''}>
                {edit.saving ? 'Сохраняем…' : 'Сохранить'}
              </button>
            </div>
          </form>
        )}
      </CardDialog>

      <CardDialog
        open={restriction !== null}
        title={restriction === null ? '' : `${restriction.target.restricted ? 'Ограничить обработку' : 'Снять ограничение'}: ${
          restriction.target.kind === 'contact' ? restriction.target.contact.name : restriction.target.title}`}
        onClose={() => {
          if (restriction?.saving !== true) {
            setRestriction(null)
          }
        }}
      >
        {restriction !== null && (
          <form className="admin-form" aria-label="Основание действия с персональными данными" onSubmit={(event) => void submitRestriction(event)}>
            <p>
              {!restriction.target.restricted
                ? 'Обработка данных возобновится.'
                : restriction.target.kind === 'contact'
                  ? 'Контакт останется в истории, но его нельзя будет связать с новыми взаимодействиями.'
                  : 'Анкета останется в статистике, но оператор зачисления не сможет её открыть или изменить.'}
            </p>
            <ReasonFields
              draft={restriction.draft}
              disabled={restriction.saving}
              onChange={(draft) => setRestriction({ ...restriction, draft, error: undefined, idempotencyKey: createIdempotencyKey() })}
            />
            {restriction.error !== undefined && <ErrorBox error={restriction.error} />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={restriction.saving} onClick={() => setRestriction(null)}>
                Отмена
              </button>
              <button type="submit" disabled={restriction.saving || !reasonReady(restriction.draft)}>
                {restriction.saving ? 'Сохраняем…' : restriction.target.restricted ? 'Ограничить обработку' : 'Снять ограничение'}
              </button>
            </div>
          </form>
        )}
      </CardDialog>

      <Toast
        persistent
        message={outcome === null ? null : (
          <>
            Обезличено контактов: {outcome.contacts}, профилей: {outcome.profiles}, анкет слушателей:
            {' '}{outcome.learners}, упоминаний:
            {' '}{outcome.mentions}, переименовано файлов: {outcome.attachmentsRenamed}, записей источников:
            {' '}{outcome.sourceRecords}, служебных записей:
            {' '}{outcome.technicalRecords}; удалено файлов: {outcome.attachmentsDeleted}, файлов отчётов:
            {' '}{outcome.reportFilesDeleted}.
          </>
        )}
        onClose={() => setOutcome(null)}
      />
    </section>
  )
}
