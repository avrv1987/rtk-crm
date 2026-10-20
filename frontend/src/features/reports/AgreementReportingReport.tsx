import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { Me, Organization } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { pluralForm } from '../../shared/format/plural'
import { saveFile } from '../admin/saveFile'
import { loadOrganizations } from '../agreements/AgreementConfirmationsPanel'
import {
  reportingApi,
  type AgreementReport,
  type AgreementReportFormat,
  type AgreementReportQuery,
  type ReportingSettingsView
} from '../agreements/reportingApi'
import { formatMoney, formatQuantities, reportTable, thresholdMetLabel } from '../agreements/reportingModel'
import { type AccessHandlers, handledAccessError } from '../work/workShared'
import { reportMeta } from './ReportCatalog'
import { PeriodPicker } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { ExportMenu } from './ReportExportMenu'
import { ReportHeader } from './ReportHeader'
import { pageHints } from './reportHints'
import '../agreements/agreementReporting.css'
import './reports.css'

type Setup =
  | { kind: 'loading' }
  | { kind: 'ready'; settings: ReportingSettingsView; organizations: Organization[] }
  | { kind: 'failed'; error: unknown }

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; result: AgreementReport; query: AgreementReportQuery }
  | { kind: 'failed'; error: unknown }

type Group = AgreementReport['byOrganization'][number]

const GroupTable = ({ title, groups }: { title: string; groups: Group[] }) => (
  groups.length === 0 ? null : (
    <div className="reporting-table" role="region" aria-label={title} tabIndex={0}>
      <table>
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Название</th>
            <th scope="col">Мероприятий</th>
            <th scope="col">Охват</th>
            <th scope="col">Количество (план / факт)</th>
            <th scope="col">Стоимость, ₽</th>
            <th scope="col">Оценка по плану, ₽</th>
            <th scope="col">Оценка по факту, ₽</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((group) => (
            <tr key={`${group.id ?? 'none'}:${group.name}`}>
              <th scope="row">{group.name}</th>
              <td className="reporting-table__number">{group.activities}</td>
              <td className="reporting-table__number">{group.participants}</td>
              <td>{formatQuantities(group.quantities)}</td>
              <td className="reporting-table__number">{formatMoney(group.cost)}</td>
              <td className="reporting-table__number">{formatMoney(group.plannedValuation)}</td>
              <td className="reporting-table__number">{formatMoney(group.actualValuation)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
)

const ThresholdTable = ({ threshold }: { threshold: AgreementReport['threshold'] }) => {
  const rows = threshold.total === null || threshold.total === undefined ? threshold.rows : [...threshold.rows, threshold.total]
  return (
    <div className="reporting-table" role="region" aria-label="Свод против порога" tabIndex={0}>
      <table>
        <caption>
          Свод по исполнителям против порога{threshold.percent === null ? '' : ` ${String(threshold.percent).replace('.', ',')} %`}
          {threshold.baseLabel ? ` (база — ${threshold.baseLabel})` : ''}
        </caption>
        <thead>
          <tr>
            <th scope="col">Исполнитель</th>
            <th scope="col">Оценка по плану, ₽</th>
            <th scope="col">Оценка по факту, ₽</th>
            <th scope="col">База, ₽</th>
            <th scope="col">Требуется, ₽</th>
            <th scope="col">План достигает</th>
            <th scope="col">Факт достигает</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${row.executorId ?? 'none'}:${index}`}>
              <th scope="row">{row.name}</th>
              <td className="reporting-table__number">{formatMoney(row.plannedValuation)}</td>
              <td className="reporting-table__number">{formatMoney(row.actualValuation)}</td>
              <td className="reporting-table__number">{formatMoney(row.base)}</td>
              <td className="reporting-table__number">{formatMoney(row.required)}</td>
              <td>{thresholdMetLabel(row.plannedMet)}</td>
              <td>{thresholdMetLabel(row.actualMet)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export const AgreementReportingReport = ({ role, onSessionExpired, onProfileUnavailable }: AccessHandlers & { role: Me['role'] }) => {
  const [setup, setSetup] = useState<Setup>({ kind: 'loading' })

  const loadSetup = useCallback(async () => {
    setSetup({ kind: 'loading' })
    try {
      const [settings, organizations] = await Promise.all([reportingApi.settings(), loadOrganizations()])
      setSetup({ kind: 'ready', settings, organizations })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setSetup({ kind: 'failed', error })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void loadSetup()
  }, [loadSetup])

  const meta = reportMeta(role, 'agreement-reporting')
  const hint = `${pageHints['agreement-reporting']} Данные: ${meta.data}.`

  if (setup.kind !== 'ready') {
    return (
      <section className="reports report-page" aria-labelledby="report-page-title" aria-busy={setup.kind === 'loading'}>
        <ReportHeader id="report-page-title" group={meta.group} title={meta.title} hint={hint} />
        {setup.kind === 'loading' && <p role="status">Загружаем настройки отчёта…</p>}
        {setup.kind === 'failed' && <ErrorNotice error={setup.error} message="Не удалось загрузить настройки отчёта." onRetry={() => void loadSetup()} />}
      </section>
    )
  }
  return (
    <ReportBody
      settings={setup.settings}
      organizations={setup.organizations}
      title={meta.title}
      group={meta.group}
      hint={hint}
      onSessionExpired={onSessionExpired}
      onProfileUnavailable={onProfileUnavailable}
    />
  )
}

type ReportBodyProps = AccessHandlers & {
  settings: ReportingSettingsView
  organizations: Organization[]
  title: string
  group: string
  hint: string
}

const ReportBody = ({ settings, organizations, title, group, hint, onSessionExpired, onProfileUnavailable }: ReportBodyProps) => {
  const [from, setFrom] = useState(settings.currentPeriod.from)
  const [to, setTo] = useState(settings.currentPeriod.to)
  const [organizationId, setOrganizationId] = useState('')
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [download, setDownload] = useState<{ format: AgreementReportFormat | null; error: unknown }>({ format: null, error: null })
  const version = useRef(0)
  const organizationFieldId = useId()
  const invalid = from === '' || to === '' || from > to

  const load = useCallback(async (query: AgreementReportQuery) => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const result = await reportingApi.report(query)
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
    const timer = window.setTimeout(() => void load({ from, to, organizationId }), 300)
    return () => window.clearTimeout(timer)
  }, [from, invalid, load, organizationId, to])

  const save = async (format: AgreementReportFormat) => {
    if (state.kind !== 'ready') {
      return
    }
    setDownload({ format, error: null })
    try {
      const blob = await reportingApi.reportFile(state.query, format)
      saveFile(blob, `Отчёт_по_соглашениям_${state.result.from}_${state.result.to}.${format.toLowerCase()}`)
      setDownload({ format: null, error: null })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setDownload({ format: null, error })
      }
    }
  }

  const ready = state.kind === 'ready' && download.format === null
  const table = state.kind === 'ready' ? reportTable(state.result, settings.availableColumns) : null

  return (
    <section className="reports report-page" aria-labelledby="report-page-title" aria-busy={state.kind === 'loading'}>
      <ReportHeader
        id="report-page-title"
        group={group}
        title={title}
        hint={hint}
        actions={(
          <ExportMenu
            disabled={!ready}
            items={[
              { label: 'XLSX (Excel)', onSelect: () => void save('XLSX') },
              { label: 'PDF', onSelect: () => void save('PDF') },
              { label: 'JSON', onSelect: () => void save('JSON') }
            ]}
          />
        )}
      />
      <form className="reports__order report-toolbar__row" onSubmit={(event) => event.preventDefault()}>
        <PeriodPicker
          from={from}
          to={to}
          required
          hint={`По умолчанию — текущий период по настройке: ${settings.currentPeriod.label}.`}
          onChange={(nextFrom, nextTo) => {
            setFrom(nextFrom)
            setTo(nextTo)
          }}
        />
        <div className="report-field">
          <label htmlFor={organizationFieldId}>Вуз</label>
          <select id={organizationFieldId} value={organizationId} onChange={(event) => setOrganizationId(event.target.value)}>
            <option value="">Все доступные</option>
            {organizations.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </div>
      </form>
      {invalid && <p className="field-error" role="alert">Укажите период: начало не позже конца.</p>}
      {state.kind === 'loading' && <p role="status">Собираем отчёт по соглашениям…</p>}
      {state.kind === 'failed' && (
        <ErrorNotice error={state.error} message="Не удалось собрать отчёт по соглашениям." onRetry={() => void load({ from, to, organizationId })} />
      )}
      {state.kind === 'ready' && table !== null && (
        <>
          <ul className="kam-review__tiles">
            <li><strong>{state.result.totals.organizations}</strong> {pluralForm(state.result.totals.organizations, 'вуз', 'вуза', 'вузов')}</li>
            <li><strong>{state.result.totals.activities}</strong> {pluralForm(state.result.totals.activities, 'мероприятие', 'мероприятия', 'мероприятий')}</li>
            <li><strong>{state.result.totals.participants}</strong> {pluralForm(state.result.totals.participants, 'участник', 'участника', 'участников')} (охват)</li>
            <li><strong>{formatMoney(state.result.totals.cost)}</strong> ₽ стоимость</li>
            <li><strong>{formatMoney(state.result.totals.plannedValuation)}</strong> ₽ оценка по плану</li>
            <li><strong>{formatMoney(state.result.totals.actualValuation)}</strong> ₽ оценка по факту</li>
            {state.result.totals.withoutRate > 0 && <li><strong>{state.result.totals.withoutRate}</strong> {pluralForm(state.result.totals.withoutRate, 'мероприятие', 'мероприятия', 'мероприятий')} без подходящей ставки</li>}
          </ul>
          {table.rows.length === 0 ? <p className="report-empty">За период мероприятий нет.</p> : (
            <div className="reports__table-scroll" role="region" aria-label="Мероприятия" tabIndex={0}>
              <table>
                <caption>{`Мероприятия (${table.rows.length})`}</caption>
                <thead>
                  <tr>
                    {table.headers.map((header) => <th key={header.code} scope="col">{header.title}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((row, index) => (
                    <tr key={index}>
                      {row.map((cell, column) => (
                        <td key={table.headers[column].code} className={table.headers[column].money ? 'reporting-table__number' : undefined}>{cell}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <GroupTable title="Свод по вузам" groups={state.result.byOrganization} />
          <GroupTable title="Свод по видам мероприятий" groups={state.result.byKind} />
          <GroupTable title="Свод по исполнителям" groups={state.result.byExecutor} />
          <GroupTable title="Свод по уровням образования" groups={state.result.byLevel} />
          {state.result.threshold.enabled && <ThresholdTable threshold={state.result.threshold} />}
          {state.result.notes.length > 0 && (
            <ul className="reports__notes">
              {state.result.notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
          )}
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
