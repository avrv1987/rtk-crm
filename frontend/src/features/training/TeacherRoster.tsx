import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  apiClient,
  type Contact,
  type Interaction,
  type LearningSnapshot,
  type TeacherRoster as Roster,
  type TeacherRosterMember
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { OrganizationContacts } from '../interactions/OrganizationContacts'
import { contactRoleLabels } from '../interactions/workMarks'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf } from '../sources/sourceFormat'
import '../sources/sources.css'
import './teacherRoster.css'

type TeacherRosterProps = {
  interaction: Interaction
  contacts: Contact[] | null
  profileId: string
  canEdit: boolean
  onAddContact: () => void
  onContactsChanged: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; rosters: Roster[] }
  | { kind: 'failed'; requestId: string | undefined }

type Pending = { kind: 'idle' } | { kind: 'busy'; key: string } | { kind: 'failed'; key: string; error: unknown }

type Confirm = { kind: 'delete'; roster: Roster } | { kind: 'transfer'; roster: Roster; exportId: string; rows: number }

const statusLabel = (member: TeacherRosterMember) => {
  if (member.status === 'TRANSFERRED') {
    return `Передан LMS-команде ${formatDateTime(member.transferredAt)}`
  }
  if (member.status === 'EXPORTED') {
    return `В файле от ${formatDateTime(member.exportedAt)}, ещё не отмечен переданным`
  }
  return 'В списке, файл не выгружался'
}

const sameName = (left: string | null | undefined, right: string | null | undefined) => (
  (left ?? '').trim().toLowerCase() === (right ?? '').trim().toLowerCase()
)

const lmsSnapshot = (roster: Roster, snapshots: LearningSnapshot[]) => snapshots.find((snapshot) => (
  snapshot.runKind === 'TEACHERS'
  && sameName(snapshot.courseName, roster.lmsCourse)
  && (roster.lmsGroup === null || roster.lmsGroup === undefined || sameName(snapshot.groupName, roster.lmsGroup))
))

const offeredContacts = (contacts: Contact[], roster: Roster) => contacts
  .filter((contact) => !contact.inactive && contact.personalDataStatus === 'ACTIVE')
  .filter((contact) => !roster.members.some((member) => member.contactId === contact.id))
  .sort((left, right) => Number(right.role === 'TEACHER') - Number(left.role === 'TEACHER') || left.name.localeCompare(right.name, 'ru'))

export const TeacherRoster = ({
  interaction,
  contacts,
  profileId,
  canEdit,
  onAddContact,
  onContactsChanged,
  onSessionExpired,
  onProfileUnavailable
}: TeacherRosterProps) => {
  const [list, setList] = useState<ListState>({ kind: 'loading' })
  const [snapshots, setSnapshots] = useState<LearningSnapshot[]>([])
  const [course, setCourse] = useState('')
  const [group, setGroup] = useState('')
  const [pending, setPending] = useState<Pending>({ kind: 'idle' })
  const [notice, setNotice] = useState<string | null>(null)
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [modes, setModes] = useState<Record<string, 'PENDING' | 'ALL'>>({})
  const [confirm, setConfirm] = useState<Confirm | null>(null)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const load = useCallback(() => {
    let active = true
    apiClient.listTeacherRosters(interaction.id)
      .then((rosters) => {
        if (active) {
          setList({ kind: 'ready', rosters })
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setList({ kind: 'failed', requestId: requestIdOf(error) })
        }
      })
    apiClient.listInteractionLearningSnapshots(interaction.id)
      .then((items) => {
        if (active) {
          setSnapshots(items)
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setSnapshots([])
        }
      })
    return () => {
      active = false
    }
  }, [handled, interaction.id])

  useEffect(() => load(), [load])

  const replace = (roster: Roster) => setList((current) => current.kind !== 'ready' ? current : {
    kind: 'ready',
    rosters: current.rosters.map((item) => item.id === roster.id ? roster : item)
  })

  const run = async (key: string, action: () => Promise<void>) => {
    setPending({ kind: 'busy', key })
    setNotice(null)
    try {
      await action()
      setPending({ kind: 'idle' })
    } catch (error) {
      if (!handled(error)) {
        setPending({ kind: 'failed', key, error })
      }
    }
  }

  const failure = (key: string, fallback: string) => pending.kind === 'failed' && pending.key === key && (
    <div role="alert">
      <p className="source-error">{errorText(pending.error, fallback)}</p>
      {fieldErrors(pending.error).map((text) => <p key={text}>{text}</p>)}
      <SupportDetails requestId={requestIdOf(pending.error)} />
    </div>
  )
  const busy = (key: string) => pending.kind === 'busy' && pending.key === key

  const create = (event: FormEvent) => {
    event.preventDefault()
    void run('create', async () => {
      const roster = await apiClient.createTeacherRoster(interaction.id, { lmsCourse: course, lmsGroup: group.trim() || null })
      setList((current) => current.kind === 'ready' ? { kind: 'ready', rosters: [...current.rosters, roster] } : current)
      setCourse('')
      setGroup('')
      setNotice(`Список для курса «${roster.lmsCourse}» создан. Добавьте преподавателей.`)
    })
  }

  const download = (roster: Roster) => void run(`file:${roster.id}`, async () => {
    const file = await apiClient.exportTeacherRoster(roster.id, { mode: modes[roster.id] ?? 'PENDING' })
    saveFile(file.blob, file.fileName)
    setNotice(`Файл «${file.fileName}» скачан: преподавателей ${file.rows}${file.skipped > 0 ? `, не готовы и не вошли ${file.skipped}` : ''}. Передайте его LMS-команде и отметьте передачу.`)
    const rosters = await apiClient.listTeacherRosters(interaction.id)
    setList({ kind: 'ready', rosters })
  })

  const confirmed = () => {
    const action = confirm
    setConfirm(null)
    if (action?.kind === 'delete') {
      void run(`delete:${action.roster.id}`, async () => {
        await apiClient.deleteTeacherRoster(action.roster.id)
        setList((current) => current.kind === 'ready'
          ? { kind: 'ready', rosters: current.rosters.filter((item) => item.id !== action.roster.id) }
          : current)
        setNotice(`Список для курса «${action.roster.lmsCourse}» удалён.`)
      })
    }
    if (action?.kind === 'transfer') {
      void run(`transfer:${action.exportId}`, async () => {
        const result = await apiClient.markTeacherRosterExportTransferred(action.exportId)
        replace(result.roster)
        setNotice(result.marked > 0
          ? `Отмечено переданными LMS-команде: ${result.marked}.`
          : 'Преподаватели этого файла уже отмечены переданными или вошли в более новый файл.')
      })
    }
  }

  return (
    <section className="interaction-learning teacher-roster" aria-labelledby="teacher-roster-title">
      <h6 id="teacher-roster-title">Преподаватели на повышение квалификации: файл для LMS</h6>
      <p>
        Соберите преподавателей вуза на курс LMS, скачайте файл «Загрузка пользователей» и передайте его LMS-команде:
        CRM в LMS не пишет, пользователей импортирует администратор LMS. В файл попадают только фамилия, имя, отчество
        и электронная почта из контактов вуза.
      </p>
      {notice && <p role="status" className="teacher-roster__notice">{notice}</p>}
      {list.kind === 'loading' && <p role="status">Загружаем списки преподавателей…</p>}
      {list.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить списки преподавателей.</p>
          <SupportDetails requestId={list.requestId} />
        </div>
      )}
      {list.kind === 'ready' && list.rosters.length === 0 && <p>Списков пока нет.</p>}
      {list.kind === 'ready' && list.rosters.map((roster) => {
        const snapshot = lmsSnapshot(roster, snapshots)
        const candidates = contacts === null ? [] : offeredContacts(contacts, roster)
        const rosterContacts = (contacts ?? []).filter((contact) => roster.members.some((member) => member.contactId === contact.id))
        const choice = selected[roster.id] ?? ''
        return (
          <article key={roster.id} className="teacher-roster__card" aria-label={`Список: курс «${roster.lmsCourse}»`}>
            <div className="teacher-roster__head">
              <strong>Курс LMS «{roster.lmsCourse}»{roster.lmsGroup ? `, группа «${roster.lmsGroup}»` : ''}</strong>
              <span className="data-sources__hint">Создал(а) {roster.createdByName ?? 'сотрудник'} {formatDateTime(roster.createdAt)}</span>
            </div>
            <p className="teacher-roster__stats">
              <span>В списке: {roster.members.length}</span>
              <span>Готовы к файлу: {roster.readyCount}</span>
              <span>Передано LMS-команде: {roster.transferredCount}</span>
              <span>
                {snapshot
                  ? `В LMS записано: ${snapshot.participants} (синхронизация ${formatDateTime(snapshot.observedAt)})`
                  : 'В LMS: курс пока не сопоставлен или не синхронизирован'}
              </span>
            </p>
            {roster.members.length === 0 && <p>Преподавателей в списке нет.</p>}
            {roster.members.length > 0 && (
              <ul className="source-list teacher-roster__members">
                {roster.members.map((member) => (
                  <li key={member.contactId}>
                    <strong>{member.name}</strong>
                    <span>
                      {[member.position, member.role ? contactRoleLabels[member.role] : null].filter(Boolean).join(' · ') || 'Должность не указана'}
                    </span>
                    <span>Почта: {member.email ?? 'не указана'}</span>
                    <span className="data-sources__hint">
                      В файл: фамилия «{member.lastName}», имя «{member.firstName ?? '—'}», отчество «{member.middleName ?? '—'}»
                    </span>
                    <span>{statusLabel(member)}</span>
                    {member.problems.length > 0 && (
                      <ul className="teacher-roster__problems" aria-label="Не попадёт в файл">
                        {member.problems.map((problem) => <li key={problem}>{problem}</li>)}
                      </ul>
                    )}
                    {canEdit && member.status !== 'TRANSFERRED' && (
                      <div className="source-panel__row">
                        <button
                          type="button"
                          className="button--secondary"
                          disabled={busy(`remove:${roster.id}:${member.contactId}`)}
                          onClick={() => void run(`remove:${roster.id}:${member.contactId}`, async () => {
                            replace(await apiClient.removeTeacherRosterMember(roster.id, member.contactId))
                          })}
                        >
                          Исключить из списка
                        </button>
                      </div>
                    )}
                    {failure(`remove:${roster.id}:${member.contactId}`, 'Преподаватель не исключён.')}
                  </li>
                ))}
              </ul>
            )}
            {canEdit && roster.members.some((member) => member.problems.length > 0) && (
              <p className="data-sources__hint">
                ФИО контакта записывается как «Фамилия Имя Отчество». Исправьте почту или ФИО в карточке контакта ниже — следующий файл возьмёт новые данные.
              </p>
            )}
            {rosterContacts.length > 0 && (
              <details className="teacher-roster__contacts">
                <summary>Контакты преподавателей списка: исправить почту или ФИО</summary>
                <OrganizationContacts
                  organizationId={interaction.organizationId}
                  contacts={rosterContacts}
                  profileId={profileId}
                  canEdit={canEdit}
                  onChanged={() => {
                    onContactsChanged()
                    load()
                  }}
                  onSessionExpired={onSessionExpired}
                  onProfileUnavailable={onProfileUnavailable}
                />
              </details>
            )}
            {canEdit && (
              <div className="source-form">
                <label className="source-form__wide">
                  Добавить преподавателя из контактов вуза
                  <select value={choice} onChange={(event) => setSelected((current) => ({ ...current, [roster.id]: event.target.value }))}>
                    <option value="">Выберите контакт</option>
                    {candidates.map((contact) => (
                      <option key={contact.id} value={contact.id}>
                        {contact.name}{contact.role === 'TEACHER' ? ' (преподаватель)' : ''}{contact.email ? '' : ' — нет почты'}
                      </option>
                    ))}
                  </select>
                  {contacts !== null && candidates.length === 0 && <span>Все действующие контакты вуза уже в списке.</span>}
                </label>
                <div className="source-form__actions">
                  <button
                    type="button"
                    disabled={choice === '' || busy(`add:${roster.id}`)}
                    onClick={() => void run(`add:${roster.id}`, async () => {
                      replace(await apiClient.addTeacherRosterMember(roster.id, choice))
                      setSelected((current) => ({ ...current, [roster.id]: '' }))
                    })}
                  >
                    Добавить в список
                  </button>
                  <button type="button" className="button--secondary" onClick={onAddContact}>Новый контакт-преподаватель…</button>
                </div>
                {failure(`add:${roster.id}`, 'Преподаватель не добавлен.')}
              </div>
            )}
            {canEdit && roster.members.length > 0 && (
              <div className="source-form">
                <fieldset className="source-form__wide teacher-roster__mode">
                  <legend>Кого выгрузить в файл</legend>
                  <label className="source-form__check">
                    <input
                      type="radio"
                      name={`teacher-roster-mode-${roster.id}`}
                      checked={(modes[roster.id] ?? 'PENDING') === 'PENDING'}
                      onChange={() => setModes((current) => ({ ...current, [roster.id]: 'PENDING' }))}
                    />
                    Только ещё не переданных LMS-команде
                  </label>
                  <label className="source-form__check">
                    <input
                      type="radio"
                      name={`teacher-roster-mode-${roster.id}`}
                      checked={modes[roster.id] === 'ALL'}
                      onChange={() => setModes((current) => ({ ...current, [roster.id]: 'ALL' }))}
                    />
                    Всех готовых, включая переданных (повторная передача)
                  </label>
                </fieldset>
                <div className="source-form__actions">
                  <button type="button" disabled={busy(`file:${roster.id}`)} onClick={() => download(roster)}>
                    {busy(`file:${roster.id}`) ? 'Готовим файл…' : 'Скачать файл для LMS'}
                  </button>
                </div>
                {failure(`file:${roster.id}`, 'Файл не сформирован.')}
              </div>
            )}
            {roster.exports.length > 0 && (
              <div className="teacher-roster__exports">
                <strong>Выгрузки</strong>
                <ul className="source-list">
                  {roster.exports.map((item) => (
                    <li key={item.id}>
                      <span>Файл от {formatDateTime(item.exportedAt)}: преподавателей {item.rows}; скачал(а) {item.exportedByName ?? 'сотрудник'}</span>
                      {item.transferredAt
                        ? <span>Передано LMS-команде: {formatDateTime(item.transferredAt)}, отметил(а) {item.transferredByName ?? 'сотрудник'}</span>
                        : <span>Передача LMS-команде не отмечена</span>}
                      {canEdit && !item.transferredAt && (
                        <div className="source-panel__row">
                          <button
                            type="button"
                            className="button--secondary"
                            disabled={busy(`transfer:${item.id}`)}
                            onClick={() => setConfirm({ kind: 'transfer', roster, exportId: item.id, rows: item.rows })}
                          >
                            Отметить: передано LMS-команде
                          </button>
                        </div>
                      )}
                      {failure(`transfer:${item.id}`, 'Отметка не поставлена.')}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {canEdit && roster.exports.length === 0 && (
              <div className="source-panel__row">
                <button type="button" className="button--secondary" onClick={() => setConfirm({ kind: 'delete', roster })}>
                  Удалить список
                </button>
                {failure(`delete:${roster.id}`, 'Список не удалён.')}
              </div>
            )}
          </article>
        )
      })}
      {canEdit && (
        <form className="source-form teacher-roster__create" onSubmit={create}>
          <strong className="source-form__wide">Новый список на курс LMS</strong>
          <label>
            <span>Курс LMS<span className="required-mark" aria-hidden="true"> *</span></span>
            <input required maxLength={300} list="teacher-roster-courses" value={course} onChange={(event) => setCourse(event.target.value)} />
            <datalist id="teacher-roster-courses">
              {[...new Set(snapshots.filter((item) => item.runKind === 'TEACHERS').map((item) => item.courseName))].map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </label>
          <label>
            Группа LMS
            <input maxLength={300} value={group} onChange={(event) => setGroup(event.target.value)} />
          </label>
          <div className="source-form__actions">
            <button type="submit" disabled={busy('create')}>Создать список</button>
          </div>
          {failure('create', 'Список не создан.')}
        </form>
      )}
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.kind === 'delete' ? 'Удалить список?' : 'Отметить передачу LMS-команде?'}
        description={confirm?.kind === 'delete'
          ? `Список для курса «${confirm.roster.lmsCourse}» будет удалён. Контакты вуза останутся.`
          : `Отметка ставится преподавателям этого файла (${confirm?.kind === 'transfer' ? confirm.rows : 0}), ещё не отмеченным. Отметьте, когда файл действительно передан.`}
        confirmLabel={confirm?.kind === 'delete' ? 'Удалить список' : 'Отметить: передано'}
        onConfirm={confirmed}
        onCancel={() => setConfirm(null)}
      />
    </section>
  )
}
