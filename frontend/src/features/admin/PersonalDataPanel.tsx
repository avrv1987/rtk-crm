import { useCallback, useState, type FormEvent } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AnonymizationResult,
  type SubjectContact,
  type SubjectExportFormat,
  type SubjectLearner,
  type SubjectMention,
  type SubjectQuery,
  type SubjectSearchResult
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { commandErrorMessage, formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import { saveFile, today } from './saveFile'
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

type AnonymizeState =
  | { kind: 'idle' }
  | { kind: 'confirming'; idempotencyKey: string }
  | { kind: 'running'; idempotencyKey: string }
  | { kind: 'done'; result: AnonymizationResult }
  | { kind: 'failed'; idempotencyKey: string; error: unknown }

type ActionError = { id: string; error: unknown }

const emptyDraft: QueryDraft = { name: '', email: '', phone: '', snils: '', otherSpellings: '' }
const emptySelection: Selection = { contactIds: [], profileIds: [], attachmentIds: [], learnerIds: [] }

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

const placeLabels: Record<SubjectMention['place'], string> = {
  COMMENT: 'Комментарий',
  PLAN_HISTORY: 'Следующий шаг в истории',
  NEXT_ACTION: 'Текущий следующий шаг',
  TITLE: 'Название карточки'
}

const recordTypeLabels: Record<string, string> = {
  partnership_request: 'Заявка вуза на партнёрство',
  learning_application: 'Заявка на обучение',
  moodle_course: 'Курс Moodle',
  moodle_group: 'Группа курса Moodle'
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
    return 'Обезличить можно только профиль с закрытым доступом. Сначала заблокируйте сотрудника в «Профилях CRM».'
  }
  return commandErrorMessage(error)
}

const ErrorBox = ({ error }: { error: unknown }) => (
  <div className="interaction-command-error" role="alert">
    <p>{errorText(error)}</p>
    {error instanceof ApiError && <p className="request-id">Request ID: {error.requestId}</p>}
  </div>
)

export const PersonalDataPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [draft, setDraft] = useState<QueryDraft>(emptyDraft)
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })
  const [selection, setSelection] = useState<Selection>(emptySelection)
  const [edit, setEdit] = useState<ContactEdit | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<ActionError | null>(null)
  const [anonymize, setAnonymize] = useState<AnonymizeState>({ kind: 'idle' })
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<string | undefined | null>(null)

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const runSearch = async (query: SubjectQuery) => {
    setSearch({ kind: 'searching' })
    setSelection(emptySelection)
    setEdit(null)
    setActionError(null)
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
    setExporting(true)
    setExportError(null)
    try {
      const blob = await apiClient.exportPersonalData(query, format)
      saveFile(blob, `Сведения о субъекте ПДн ${today()}.${format === 'PDF' ? 'pdf' : 'json'}`)
    } catch (error) {
      if (!handleError(error)) {
        setExportError(requestIdOf(error))
      }
    } finally {
      setExporting(false)
    }
  }

  const beginEdit = (contact: SubjectContact) => {
    setActionError(null)
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

  const restrict = async (contact: SubjectContact, restricted: boolean) => {
    setBusyId(contact.id)
    setActionError(null)
    try {
      replaceContact(await apiClient.restrictPersonalDataContact(
        contact.id,
        { version: contact.version, restricted },
        createIdempotencyKey()
      ))
    } catch (error) {
      if (!handleError(error)) {
        setActionError({ id: contact.id, error })
      }
    } finally {
      setBusyId(null)
    }
  }

  const restrictLearner = async (learner: SubjectLearner, restricted: boolean) => {
    setBusyId(learner.id)
    setActionError(null)
    try {
      replaceLearner(await apiClient.restrictPersonalDataLearner(
        learner.id,
        { version: learner.version, restricted },
        createIdempotencyKey()
      ))
    } catch (error) {
      if (!handleError(error)) {
        setActionError({ id: learner.id, error })
      }
    } finally {
      setBusyId(null)
    }
  }

  const runAnonymize = async (query: SubjectQuery, idempotencyKey: string) => {
    setAnonymize({ kind: 'running', idempotencyKey })
    try {
      const result = await apiClient.anonymizePersonalData({ subject: query, ...selection }, idempotencyKey)
      setAnonymize({ kind: 'done', result })
      await runSearch(query)
    } catch (error) {
      if (!handleError(error)) {
        setAnonymize({ kind: 'failed', idempotencyKey, error })
      }
    }
  }

  const renderContact = (contact: SubjectContact) => {
    const anonymized = contact.status === 'ANONYMIZED'
    const busy = busyId === contact.id
    if (edit !== null && edit.contact.id === contact.id) {
      return (
        <form className="security-form" onSubmit={(event) => void saveEdit(event)} aria-label={`Уточнение контакта ${contact.name}`}>
          <label>
            ФИО
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
          <div className="security-actions">
            <button type="submit" disabled={edit.saving || edit.draft.name.trim() === ''}>
              {edit.saving ? 'Сохраняем…' : 'Сохранить'}
            </button>
            <button type="button" className="button--secondary" disabled={edit.saving} onClick={() => setEdit(null)}>
              Отмена
            </button>
          </div>
        </form>
      )
    }
    return (
      <>
        <div className="security-item__title">
          <h4>{contact.name}</h4>
          <span className={`security-badge security-badge--${contact.status.toLowerCase()}`}>{statusLabels[contact.status]}</span>
        </div>
        <dl className="security-fields">
          <div>
            <dt>Организация</dt>
            <dd>{contact.organizationName}</dd>
          </div>
          {contact.position && (
            <div>
              <dt>Должность</dt>
              <dd>{contact.position}</dd>
            </div>
          )}
          <div>
            <dt>Почта</dt>
            <dd>{contact.email ?? '—'}</dd>
          </div>
          <div>
            <dt>Телефон</dt>
            <dd>{contact.phone ?? '—'}</dd>
          </div>
          <div>
            <dt>Связан с работами</dt>
            <dd>{contact.interactionsCount}</dd>
          </div>
          <div>
            <dt>Изменён</dt>
            <dd>{formatDateTime(contact.updatedAt)}</dd>
          </div>
        </dl>
        {!anonymized && (
          <div className="security-actions">
            <button type="button" className="button--secondary" disabled={busy} onClick={() => beginEdit(contact)}>
              Уточнить
            </button>
            <button
              type="button"
              className="button--secondary"
              disabled={busy}
              onClick={() => void restrict(contact, contact.status !== 'RESTRICTED')}
            >
              {contact.status === 'RESTRICTED' ? 'Снять ограничение' : 'Ограничить обработку'}
            </button>
            <label className="security-check">
              <input
                type="checkbox"
                checked={selection.contactIds.includes(contact.id)}
                onChange={() => setSelection({ ...selection, contactIds: toggle(selection.contactIds, contact.id) })}
              />
              Обезличить
            </label>
          </div>
        )}
        {actionError?.id === contact.id && <ErrorBox error={actionError.error} />}
      </>
    )
  }

  const renderLearner = (learner: SubjectLearner) => {
    const anonymized = learner.status === 'ANONYMIZED'
    const busy = busyId === learner.id
    return (
      <>
        <div className="security-item__title">
          <h4>Слушатель {learner.id.slice(0, 8)}</h4>
          <span className={`security-badge security-badge--${learner.status.toLowerCase()}`}>
            {learnerStatusLabels[learner.status]}
          </span>
        </div>
        <dl className="security-fields">
          <div>
            <dt>Анкета</dt>
            <dd>{anonymized ? 'обезличена' : `заполнено полей: ${learner.filledFields} из 30`}</dd>
          </div>
          <div>
            <dt>Потоки</dt>
            <dd>
              {learner.enrolments.length === 0
                ? '—'
                : learner.enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`).join('; ')}
            </dd>
          </div>
          <div>
            <dt>Изменена</dt>
            <dd>{formatDateTime(learner.updatedAt)}</dd>
          </div>
        </dl>
        {!anonymized && (
          <div className="security-actions">
            <button
              type="button"
              className="button--secondary"
              disabled={busy}
              onClick={() => void restrictLearner(learner, learner.status !== 'RESTRICTED')}
            >
              {learner.status === 'RESTRICTED' ? 'Снять ограничение' : 'Ограничить обработку'}
            </button>
            <label className="security-check">
              <input
                type="checkbox"
                checked={selection.learnerIds.includes(learner.id)}
                onChange={() => setSelection({ ...selection, learnerIds: toggle(selection.learnerIds, learner.id) })}
              />
              Обезличить анкету
            </label>
          </div>
        )}
        {actionError?.id === learner.id && <ErrorBox error={actionError.error} />}
      </>
    )
  }

  const renderResult = (query: SubjectQuery, result: SubjectSearchResult) => {
    const selectedCount = selection.contactIds.length + selection.profileIds.length + selection.attachmentIds.length
      + selection.learnerIds.length
    const nothingFound = result.contacts.length + result.profiles.length + result.mentions.length
      + result.attachments.length + result.sourceRecords.length + result.learners.length === 0
    const canAnonymize = selectedCount > 0 || result.mentions.length > 0 || result.sourceRecords.length > 0
    const running = anonymize.kind === 'running'
    return (
      <div className="security-result">
        <div className="security-actions">
          <button type="button" className="button--secondary" disabled={exporting} onClick={() => void exportSubject(query, 'PDF')}>
            Выгрузить сведения PDF
          </button>
          <button type="button" className="button--secondary" disabled={exporting} onClick={() => void exportSubject(query, 'JSON')}>
            Выгрузить сведения JSON
          </button>
          {exporting && <p role="status">Готовим файл…</p>}
        </div>
        {exportError !== null && (
          <div className="organizations-message organizations-message--error" role="alert">
            <p>Не удалось выгрузить сведения. Повторите попытку.</p>
            {exportError && <p className="request-id">Request ID: {exportError}</p>}
          </div>
        )}
        {result.truncated && (
          <p className="security-warning" role="status">Найдено слишком много записей, показана часть. Уточните условия поиска.</p>
        )}
        {nothingFound && <p className="organizations-message">По этим данным ничего не найдено.</p>}

        {result.contacts.length > 0 && (
          <section aria-labelledby="subject-contacts-title">
            <h3 id="subject-contacts-title">Контакты организаций: {result.contacts.length}</h3>
            <ul className="security-list">
              {result.contacts.map((contact) => (
                <li key={contact.id} className="security-item">{renderContact(contact)}</li>
              ))}
            </ul>
          </section>
        )}

        {result.profiles.length > 0 && (
          <section aria-labelledby="subject-profiles-title">
            <h3 id="subject-profiles-title">Профили сотрудников: {result.profiles.length}</h3>
            <ul className="security-list">
              {result.profiles.map((profile) => (
                <li key={profile.id} className="security-item">
                  <div className="security-item__title">
                    <h4>{profile.displayName}</h4>
                    <span className="security-badge">
                      {profile.anonymized ? 'Обезличен' : profile.active ? 'Доступ открыт' : profile.pendingActivation ? 'Ожидает активации' : 'Доступ закрыт'}
                    </span>
                  </div>
                  {profile.login && <p className="security-muted">Логин: {profile.login}</p>}
                  {!profile.anonymized && (profile.active ? (
                    <p className="security-muted">Обезличить можно после блокировки в «Профилях CRM».</p>
                  ) : (
                    <label className="security-check">
                      <input
                        type="checkbox"
                        checked={selection.profileIds.includes(profile.id)}
                        onChange={() => setSelection({ ...selection, profileIds: toggle(selection.profileIds, profile.id) })}
                      />
                      Обезличить профиль и имя в истории
                    </label>
                  ))}
                </li>
              ))}
            </ul>
          </section>
        )}

        {result.mentions.length > 0 && (
          <section aria-labelledby="subject-mentions-title">
            <h3 id="subject-mentions-title">Упоминания в карточках: {result.mentions.length}</h3>
            <ul className="security-list">
              {result.mentions.map((mention, index) => (
                <li key={`${mention.interactionId}-${mention.place}-${index}`} className="security-item">
                  <p className="security-muted">
                    {placeLabels[mention.place]} · {mention.organizationName} · «{mention.interactionTitle}»
                    {mention.occurredAt && ` · ${formatDateTime(mention.occurredAt)}`}
                  </p>
                  <p className="security-quote">{mention.text}</p>
                </li>
              ))}
            </ul>
          </section>
        )}

        {result.attachments.length > 0 && (
          <section aria-labelledby="subject-files-title">
            <h3 id="subject-files-title">Файлы с данными в названии: {result.attachments.length}</h3>
            <ul className="security-list">
              {result.attachments.map((attachment) => (
                <li key={attachment.id} className="security-item">
                  <div className="security-item__title">
                    <h4>{attachment.fileName}</h4>
                    <span className="security-muted">{sizeLabel(attachment.sizeBytes)}</span>
                  </div>
                  <p className="security-muted">
                    {attachment.organizationName} · «{attachment.interactionTitle}» · {formatDateTime(attachment.createdAt)}
                  </p>
                  <label className="security-check">
                    <input
                      type="checkbox"
                      checked={selection.attachmentIds.includes(attachment.id)}
                      onChange={() => setSelection({ ...selection, attachmentIds: toggle(selection.attachmentIds, attachment.id) })}
                    />
                    Удалить файл
                  </label>
                </li>
              ))}
            </ul>
          </section>
        )}

        {result.sourceRecords.length > 0 && (
          <section aria-labelledby="subject-sources-title">
            <h3 id="subject-sources-title">Записи источников: {result.sourceRecords.length}</h3>
            <ul className="security-list">
              {result.sourceRecords.map((record) => (
                <li key={record.id} className="security-item">
                  <p>
                    {record.source === 'WEBSITE' ? 'Сайт ИТ Школы' : 'LMS Moodle'} · {recordTypeLabels[record.recordType] ?? record.recordType} · {record.externalId}
                  </p>
                  <p className="security-muted">
                    {record.organizationName ?? 'Вуз не определён'} · статус: {recordStatusLabels[record.status] ?? record.status}
                    {record.submittedAt && ` · ${formatDateTime(record.submittedAt)}`}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        )}

        {result.learners.length > 0 && (
          <section aria-labelledby="subject-learners-title">
            <h3 id="subject-learners-title">Анкеты слушателей: {result.learners.length}</h3>
            <p className="security-muted">
              Значения анкеты на экране не показываются, они входят в выгрузку сведений. Уточнить данные по обращению
              субъекта может оператор зачисления в анкете слушателя.
            </p>
            <ul className="security-list">
              {result.learners.map((learner) => (
                <li key={learner.id} className="security-item">{renderLearner(learner)}</li>
              ))}
            </ul>
          </section>
        )}

        {!nothingFound && (
          <div className="security-danger-zone">
            <p>
              Обезличивание заменяет ФИО, почту и телефон субъекта маркером «Контакт обезличен» или «Сотрудник обезличен»
              в выбранных контактах и профилях, комментариях, следующих шагах и записях источников. История работ
              сохраняется, выбранные файлы удаляются, заказанные файлы отчётов удаляются. У выбранных анкет слушателей
              удаляются все поля, зачисления остаются в статистике. Действие необратимо.
            </p>
            <button
              type="button"
              className="button--danger"
              disabled={!canAnonymize || running}
              onClick={() => setAnonymize({ kind: 'confirming', idempotencyKey: createIdempotencyKey() })}
            >
              {running ? 'Обезличиваем…' : 'Обезличить'}
            </button>
          </div>
        )}
        {anonymize.kind === 'failed' && (
          <>
            <ErrorBox error={anonymize.error} />
            <button type="button" onClick={() => void runAnonymize(query, anonymize.idempotencyKey)}>Повторить</button>
          </>
        )}
        <ConfirmDialog
          open={anonymize.kind === 'confirming'}
          title="Обезличить данные субъекта?"
          description={`Будут обезличены: контактов ${selection.contactIds.length}, профилей ${selection.profileIds.length}, анкет слушателей ${selection.learnerIds.length}, упоминания и записи источников с этими данными; удалено файлов: ${selection.attachmentIds.length}. Восстановить данные будет нельзя.`}
          confirmLabel="Обезличить"
          onConfirm={() => {
            if (anonymize.kind === 'confirming') {
              void runAnonymize(query, anonymize.idempotencyKey)
            }
          }}
          onCancel={() => setAnonymize({ kind: 'idle' })}
        />
      </div>
    )
  }

  return (
    <section className="security-panel" aria-labelledby="personal-data-title" aria-busy={search.kind === 'searching'}>
      <div className="security-panel__header">
        <div>
          <p className="eyebrow">Безопасность</p>
          <h2 id="personal-data-title">Субъект персональных данных</h2>
        </div>
      </div>
      <p className="security-panel__intro">
        Запрос субъекта: найдите его данные в контактах, профилях, комментариях, названиях файлов и записях источников,
        выгрузите сведения, уточните, ограничьте обработку или обезличьте. Анкеты слушателей находятся только по точному
        совпадению почты, телефона, СНИЛС или фамилии и имени. Каждое действие записывается в журнал; сами условия
        поиска в журнал не попадают.
      </p>
      <form className="security-form" onSubmit={submit} aria-label="Поиск данных субъекта">
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
        <label className="security-form__wide">
          Другие написания (через запятую)
          <input
            value={draft.otherSpellings}
            maxLength={2000}
            placeholder="Например, И. Петрова, Petrova"
            onChange={(event) => setDraft({ ...draft, otherSpellings: event.target.value })}
          />
        </label>
        <div className="security-actions security-form__wide">
          <button
            type="submit"
            disabled={search.kind === 'searching' || Object.values(draft).every((value) => value.trim() === '')}
          >
            {search.kind === 'searching' ? 'Ищем…' : 'Найти'}
          </button>
        </div>
      </form>
      {anonymize.kind === 'done' && (
        <p className="security-success" role="status">
          Обезличено контактов: {anonymize.result.contacts}, профилей: {anonymize.result.profiles}, анкет слушателей:
          {' '}{anonymize.result.learners}, упоминаний:
          {' '}{anonymize.result.mentions}, записей источников: {anonymize.result.sourceRecords}, служебных записей:
          {' '}{anonymize.result.technicalRecords}; удалено файлов: {anonymize.result.attachmentsDeleted}, файлов отчётов:
          {' '}{anonymize.result.reportFilesDeleted}.
        </p>
      )}
      {search.kind === 'failed' && <ErrorBox error={search.error} />}
      {search.kind === 'ready' && renderResult(search.query, search.result)}
    </section>
  )
}
