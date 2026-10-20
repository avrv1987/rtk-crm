import { useCallback, useEffect, useRef, useState } from 'react'
import type { Me } from '../../shared/api/client'
import { formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { pluralForm } from '../../shared/format/plural'
import { periodDefaults } from '../agreements/agreementModel'
import { peopleApi, type HourUnit, type PeopleReport } from '../people/peopleApi'
import { hourUnitShort } from '../people/peopleForms'
import { type AccessHandlers, handledAccessError } from '../work/workShared'
import { reportMeta } from './ReportCatalog'
import { PeriodPicker } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { ReportHeader } from './ReportHeader'
import { pageHints } from './reportHints'
import './reports.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; result: PeopleReport }
  | { kind: 'failed'; error: unknown }

type Row = PeopleReport['teaching']['byOrganization'][number]

const hours = (value: number, unit: HourUnit) => `${value.toLocaleString('ru-RU')} ${hourUnitShort[unit]}`

const HoursTable = ({ title, column, rows, unit }: { title: string; column: string; rows: Row[]; unit: HourUnit }) => (
  rows.length === 0 ? null : (
    <div className="reports__table-scroll" role="region" aria-label={title} tabIndex={0}>
      <table>
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">{column}</th>
            <th scope="col">Записей</th>
            <th scope="col">Часы</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <th scope="row">{row.title}</th>
              <td>{row.records}</td>
              <td>{hours(row.hours, unit)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
)

export const PeopleReportPage = ({ role, onSessionExpired, onProfileUnavailable }: AccessHandlers & { role: Me['role'] }) => {
  const initial = periodDefaults(todayInMoscow())
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [state, setState] = useState<State>({ kind: 'loading' })
  const version = useRef(0)
  const invalid = from === '' || to === '' || from > to

  const load = useCallback(async () => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const result = await peopleApi.report(from, to)
      if (current === version.current) {
        setState({ kind: 'ready', result })
      }
    } catch (error) {
      if (current !== version.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [from, onProfileUnavailable, onSessionExpired, to])

  useEffect(() => {
    if (invalid) {
      return
    }
    const timer = window.setTimeout(() => void load(), 300)
    return () => window.clearTimeout(timer)
  }, [invalid, load])

  const meta = reportMeta(role, 'people')

  return (
    <section className="reports report-page" aria-labelledby="report-page-title" aria-busy={state.kind === 'loading'}>
      <ReportHeader id="report-page-title" group={meta.group} title={meta.title} hint={`${pageHints.people} Данные: ${meta.data}.`} />
      <form className="reports__order report-toolbar__row" onSubmit={(event) => event.preventDefault()}>
        <PeriodPicker
          from={from}
          to={to}
          required
          onChange={(nextFrom, nextTo) => {
            setFrom(nextFrom)
            setTo(nextTo)
          }}
        />
      </form>
      {invalid && <p className="field-error" role="alert">Укажите период: начало не позже конца.</p>}
      {state.kind === 'loading' && <p role="status">Собираем отчёт «Люди и часы»…</p>}
      {state.kind === 'failed' && <ErrorNotice error={state.error} message="Не удалось собрать отчёт." onRetry={() => void load()} />}
      {state.kind === 'ready' && (() => {
        const { teaching, internships, qualifications } = state.result
        return (
          <>
            <h3>Преподавание сотрудниками</h3>
            <ul className="kam-review__tiles">
              <li><strong>{teaching.records}</strong> {pluralForm(teaching.records, 'запись', 'записи', 'записей')}</li>
              <li><strong>{hours(teaching.hours, teaching.unit)}</strong> всего</li>
              <li><strong>{hours(teaching.confirmedHours, teaching.unit)}</strong> подтверждено вузами</li>
            </ul>
            <HoursTable title="По вузам" column="Вуз" rows={teaching.byOrganization} unit={teaching.unit} />
            <HoursTable title="По исполнителям" column="Исполнитель" rows={teaching.byPerformer} unit={teaching.unit} />
            <HoursTable title="По месяцам начала" column="Месяц" rows={teaching.byMonth} unit={teaching.unit} />

            <h3>Стажировки</h3>
            <ul className="kam-review__tiles">
              <li><strong>{internships.interns}</strong> {pluralForm(internships.interns, 'стажёр', 'стажёра', 'стажёров')} (студентов: {internships.students}, преподавателей: {internships.teachers})</li>
              <li><strong>{internships.completed}</strong> завершили, трудоустроены: {internships.employed}</li>
              <li><strong>{hours(internships.internHours, internships.unit)}</strong> часов стажёров</li>
              <li><strong>{hours(internships.mentorHours, internships.unit)}</strong> часов наставников</li>
            </ul>
            <HoursTable title="Стажировки по вузам" column="Вуз" rows={internships.byOrganization} unit={internships.unit} />
            <HoursTable title="Стажировки по местам" column="Подразделение или исполнитель" rows={internships.byPlace} unit={internships.unit} />

            <h3>Повышение квалификации преподавателей</h3>
            <ul className="kam-review__tiles">
              <li><strong>{qualifications.people}</strong> {pluralForm(qualifications.people, 'запись', 'записи', 'записей')}, часов: {hours(qualifications.hours, qualifications.unit)}</li>
              <li><strong>{qualifications.certified}</strong> аттестованы, не аттестованы: {qualifications.notCertified}, обучаются: {qualifications.inProgress}</li>
              <li><strong>{qualifications.frdoSubmitted}</strong> переданы в ФРДО, ждут передачи: {qualifications.frdoPending}</li>
              <li>
                <strong>{qualifications.frdoDeadlineDays === null ? '—' : qualifications.frdoOverdue}</strong>
                {qualifications.frdoDeadlineDays === null ? ' просрочка не считается: срок не задан' : ` просрочено (срок ${qualifications.frdoDeadlineDays} дн.)`}
              </li>
            </ul>
            <HoursTable title="Повышение квалификации по вузам" column="Вуз" rows={qualifications.byOrganization} unit={qualifications.unit} />
            <HoursTable title="По программам" column="Программа" rows={qualifications.byProgram} unit={qualifications.unit} />
            <ul className="reports__notes">
              {state.result.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
            <p className="reports__footer"><span>Сформировано: {formatMoscowDateTime(state.result.generatedAt)}</span></p>
          </>
        )
      })()}
    </section>
  )
}
