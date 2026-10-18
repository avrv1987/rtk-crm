import { useCallback, useEffect, useRef, useState } from 'react'
import { pluralForm } from '../../shared/format/plural'
import type { Me } from '../../shared/api/client'
import { formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { saveFile } from '../admin/saveFile'
import { agreementsApi, type AgreementPeriodQuery, type AgreementPeriodReport as Report, type AgreementRegistryRow } from '../agreements/agreementsApi'
import { agreementStatusLabels, approvalText, periodDefaults } from '../agreements/agreementModel'
import { ApprovalMark } from '../agreements/agreementUi'
import { type AccessHandlers, daysLabel, formatDate, handledAccessError } from '../work/workShared'
import { reportMeta } from './ReportCatalog'
import { PeriodPicker } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { ExportMenu } from './ReportExportMenu'
import { ReportHeader } from './ReportHeader'
import { pageHints } from './reportHints'
import './reports.css'

// Дробное число дней читается «21,5 дня», целое склоняется по числу.
const averageDaysWord = (days: number | null | undefined) =>
  days === null || days === undefined ? 'дней' : Number.isInteger(days) ? pluralForm(days, 'день', 'дня', 'дней') : 'дня'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; result: Report; query: AgreementPeriodQuery }
  | { kind: 'failed'; error: unknown }

const agreementHref = (row: AgreementRegistryRow) => `#/organizations/${row.organizationId}?agreement=${row.id}`

const RowsTable = ({ title, rows, empty }: { title: string; rows: AgreementRegistryRow[]; empty: string }) => (
  rows.length === 0 ? <p className="agreement-period__empty"><strong>{title}:</strong> {empty}</p> : (
    <div className="reports__table-scroll" role="region" aria-label={title} tabIndex={0}>
      <table>
        <caption>{`${title} (${rows.length})`}</caption>
        <thead>
          <tr>
            <th scope="col">Вуз</th>
            <th scope="col">Договор</th>
            <th scope="col">Статус</th>
            <th scope="col">В статусе</th>
            <th scope="col">Отправлен</th>
            <th scope="col">Согласование</th>
            <th scope="col">Подписан</th>
            <th scope="col">Действует до</th>
            <th scope="col">КАМ</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <th scope="row">{row.organizationName}</th>
              <td><a href={agreementHref(row)}>№ {row.number}</a></td>
              <td>{agreementStatusLabels[row.status]}</td>
              <td>{daysLabel(row.daysInStatus)}</td>
              <td>{row.sentOn ? formatDate(row.sentOn) : '—'}</td>
              <td>{row.approval ? <>{approvalText(row.approval)} <ApprovalMark approval={row.approval} /></> : '—'}</td>
              <td>{row.concludedOn ? formatDate(row.concludedOn) : '—'}</td>
              <td>{row.validUntil ? formatDate(row.validUntil) : '—'}</td>
              <td>{row.managerName ?? 'Без ответственного КАМ'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
)

export const AgreementPeriodReport = ({ role, onSessionExpired, onProfileUnavailable }: AccessHandlers & { role: Me['role'] }) => {
  const initial = periodDefaults(todayInMoscow())
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [download, setDownload] = useState<{ format: 'XLSX' | 'PDF' | null; error: unknown }>({ format: null, error: null })
  const version = useRef(0)
  const invalid = from === '' || to === '' || from > to

  const load = useCallback(async (query: AgreementPeriodQuery) => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const result = await agreementsApi.periodReport(query)
      if (current === version.current) {
        setState({ kind: 'ready', result, query })
      }
    } catch (error) {
      if (current !== version.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    if (invalid) {
      return
    }
    const timer = window.setTimeout(() => void load({ from, to }), 300)
    return () => window.clearTimeout(timer)
  }, [from, invalid, load, to])

  const save = async (format: 'XLSX' | 'PDF') => {
    if (state.kind !== 'ready') {
      return
    }
    setDownload({ format, error: null })
    try {
      const blob = await agreementsApi.periodReportFile(state.query, format)
      saveFile(blob, `Договоры_за_период_${state.result.from}_${state.result.to}.${format.toLowerCase()}`)
      setDownload({ format: null, error: null })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setDownload({ format: null, error })
      }
    }
  }

  const meta = reportMeta(role, 'agreements-period')
  const ready = state.kind === 'ready' && download.format === null

  return (
    <section className="reports report-page signing-plan" aria-labelledby="report-page-title" aria-busy={state.kind === 'loading'}>
      <ReportHeader
        id="report-page-title"
        group={meta.group}
        title={meta.title}
        hint={`${pageHints['agreements-period']} Данные: ${meta.data}.`}
        actions={(
          <ExportMenu
            disabled={!ready}
            items={[{ label: 'XLSX (Excel)', onSelect: () => void save('XLSX') }, { label: 'PDF', onSelect: () => void save('PDF') }]}
          />
        )}
      />
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
      {state.kind === 'loading' && <p role="status">Собираем отчёт по договорам…</p>}
      {state.kind === 'failed' && (
        <ErrorNotice error={state.error} message="Не удалось собрать отчёт по договорам." onRetry={() => void load({ from, to })} />
      )}
      {state.kind === 'ready' && (
        <>
          <ul className="kam-review__tiles">
            <li><strong>{state.result.totals.transitions}</strong> переходов статусов</li>
            <li><strong>{state.result.totals.signed}</strong> подписано</li>
            <li><strong>{state.result.totals.terminated}</strong> расторгнуто</li>
            <li>
              <strong>{state.result.totals.averageApprovalDays === null || state.result.totals.averageApprovalDays === undefined
                ? '—'
                : state.result.totals.averageApprovalDays.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}</strong>
              {` ${averageDaysWord(state.result.totals.averageApprovalDays)} в среднем от отправки до подписания (договоров: ${state.result.totals.approvalSamples})`}
            </li>
            <li><strong>{state.result.totals.longReview}</strong> согласование затянулось</li>
          </ul>
          {state.result.transitions.length === 0 ? (
            <p className="agreement-period__empty"><strong>Переходы статусов:</strong> за период переходов не было.</p>
          ) : (
            <div className="reports__table-scroll" role="region" aria-label="Переходы статусов" tabIndex={0}>
              <table>
                <caption>{`Переходы статусов (${state.result.transitions.length})`}</caption>
                <thead>
                  <tr>
                    <th scope="col">Когда</th>
                    <th scope="col">Вуз</th>
                    <th scope="col">Договор</th>
                    <th scope="col">Переход</th>
                    <th scope="col">Кто</th>
                    <th scope="col">Комментарий</th>
                  </tr>
                </thead>
                <tbody>
                  {state.result.transitions.map((row) => (
                    <tr key={`${row.agreementId}:${row.occurredAt}:${row.toStatus}`}>
                      <td>{formatMoscowDateTime(row.occurredAt)}</td>
                      <th scope="row">{row.organizationName}</th>
                      <td>№ {row.number}</td>
                      <td>{row.fromStatus ? `${agreementStatusLabels[row.fromStatus]} → ` : ''}{agreementStatusLabels[row.toStatus]}</td>
                      <td>{row.actorName ?? '—'}</td>
                      <td>{row.comment ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <RowsTable title="Подписанные" rows={state.result.signed} empty="в периоде договоры не подписывались." />
          <RowsTable title="Расторгнутые" rows={state.result.terminated} empty="в периоде договоры не расторгались." />
          <RowsTable title="Согласование затянулось" rows={state.result.longReview} empty="таких договоров нет." />
          <ul className="reports__notes">
            {state.result.notes.map((note) => <li key={note}>{note}</li>)}
          </ul>
          <p className="reports__footer">
            <span>Сформировано: {formatMoscowDateTime(state.result.generatedAt)}</span>
            {download.format !== null && <span role="status">Готовим {download.format}…</span>}
          </p>
          {download.error !== null && <ErrorNotice error={download.error} message="Не удалось скачать файл отчёта." />}
        </>
      )}
    </section>
  )
}
