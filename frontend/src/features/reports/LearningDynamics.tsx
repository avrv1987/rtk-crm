import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { apiClient, type LearningDynamics, type LearningDynamicsQuery } from '../../shared/api/client'
import { formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { saveFile } from '../admin/saveFile'
import { type AccessHandlers, handledAccessError } from '../work/workShared'
import { ErrorNotice } from './ReportErrorNotice'
import { axisStep } from './StatisticsChart'
import './reports.css'

type Metric = 'participants' | 'completed'
type SeriesBy = LearningDynamics['seriesBy']

export type LearningDynamicsReportProps = AccessHandlers & {
  organizationIds?: string[]
  programIds?: string[]
}

type Line = { key: string; label: string; values: (number | null)[] }

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; result: LearningDynamics; query: LearningDynamicsQuery }
  | { kind: 'failed'; error: unknown }

const maxLines = 5
const plotTop = 36
const plotHeight = 240
const labelsHeight = 44
const leftPercent = 3
const widthPercent = 94
const metricTitles: Record<Metric, string> = { participants: 'Обучающиеся', completed: 'Завершили' }
const seriesTitles: Record<SeriesBy, string> = { ORGANIZATION: 'по вузам', PROGRAM: 'по ИТ-программам' }
const monthLabel = (key: string) => `${key.slice(5, 7)}.${key.slice(2, 4)}`
const cell = (value: number | null | undefined) => (value === null || value === undefined ? 'нет данных' : value.toLocaleString('ru-RU'))

const DynamicsChart = ({ result, metric }: { result: LearningDynamics; metric: Metric }) => {
  const id = useId()
  const lines: Line[] = result.series.slice(0, maxLines).map((series) => ({
    key: series.key,
    label: series.label,
    values: metric === 'participants' ? series.participants : series.completed.map((value) => value ?? null)
  }))
  const count = result.months.length
  const max = lines.reduce((value, line) => Math.max(value, ...line.values.map((item) => item ?? 0)), 0)
  const step = axisStep(max)
  const axisMax = Math.max(step, Math.ceil(max / step) * step)
  const ticks = Array.from({ length: axisMax / step + 1 }, (_, index) => index * step)
  const plotBottom = plotTop + plotHeight
  const x = (index: number) => `${leftPercent + (index + 0.5) * widthPercent / count}%`
  const y = (value: number) => plotBottom - value / axisMax * plotHeight
  const every = Math.max(1, Math.ceil(count / 6))
  const pointLabels = count * lines.length <= 36
  const title = `${metricTitles[metric]} (Moodle) — график по месяцам, линии ${seriesTitles[result.seriesBy]}`

  return (
    <figure className="statistics-chart line-chart">
      <figcaption>
        <strong>{title}</strong>
        <ul className="reports__notes">
          <li>Шкала начинается с нуля; месяц без потоков с данными показан нулём.</li>
          {metric === 'completed' && <li>«Нет данных» — Moodle не отслеживает завершение, такие точки не рисуются.</li>}
          {result.series.length > maxLines && (
            <li>{`Показаны ${maxLines} линий с наибольшим числом обучающихся в последнем месяце из ${result.series.length}; остальные — в таблице строк и в файле.`}</li>
          )}
        </ul>
      </figcaption>
      <ul className="line-chart__legend">
        {lines.map((line, index) => (
          <li key={line.key}>
            <span className={`line-chart__swatch line-chart__series--${index}`} aria-hidden="true" />
            {line.label}
          </li>
        ))}
      </ul>
      <svg className="statistics-chart__svg" width="100%" height={plotBottom + labelsHeight} role="img" aria-labelledby={`${id}-title ${id}-desc`}>
        <title id={`${id}-title`}>{title}</title>
        <desc id={`${id}-desc`}>
          {`Линейный график по месяцам, шкала от нуля до ${axisMax}. `}
          {lines.map((line) => `${line.label}: ${result.months.map((month, index) => `${month.label} — ${cell(line.values[index])}`).join(', ')}`).join('; ')}
        </desc>
        <text x="0" y="14" className="statistics-chart__axis-title">{metricTitles[metric]}, шкала от нуля</text>
        {ticks.map((tick) => (
          <g key={tick}>
            <line x1={`${leftPercent}%`} x2="100%" y1={y(tick)} y2={y(tick)} className={tick === 0 ? 'statistics-chart__zero' : 'statistics-chart__grid'} />
            <text x="0" y={y(tick) - 4} className="statistics-chart__tick">{tick}</text>
          </g>
        ))}
        {lines.length === 0 && (
          <text x="8" y={plotTop + 24} className="statistics-chart__tick">Нет наблюдений, удовлетворяющих фильтрам</text>
        )}
        {result.months.map((month, index) => index % every === 0 && (
          <text key={month.key} x={x(index)} y={plotBottom + 20} textAnchor="middle" className="statistics-chart__tick">{monthLabel(month.key)}</text>
        ))}
        <text x="100%" y={plotBottom + 38} textAnchor="end" className="statistics-chart__tick">Месяц</text>
        {lines.map((line, lineIndex) => (
          <g key={line.key} className={`line-chart__line line-chart__series--${lineIndex}`}>
            {line.values.slice(1).map((value, index) => {
              const previous = line.values[index]
              return value !== null && previous !== null
                ? <line key={index} x1={x(index)} y1={y(previous)} x2={x(index + 1)} y2={y(value)} />
                : null
            })}
            {line.values.map((value, index) => value !== null && (
              <g key={result.months[index].key}>
                <title>{`${line.label}, ${result.months[index].label}: ${value}`}</title>
                <circle cx={x(index)} cy={y(value)} r="4.5" />
                {pointLabels && <text x={x(index)} y={y(value) - 9} textAnchor="middle" className="statistics-chart__value">{value}</text>}
              </g>
            ))}
          </g>
        ))}
      </svg>
      <div className="reports__table-scroll" role="region" aria-label="Таблица основания графика" tabIndex={0}>
        <table>
          <caption>Таблица основания графика</caption>
          <thead>
            <tr>
              <th scope="col">Месяц</th>
              {lines.map((line) => <th key={line.key} scope="col">{line.label}</th>)}
              <th scope="col">Всего за месяц</th>
            </tr>
          </thead>
          <tbody>
            {result.months.map((month, index) => (
              <tr key={month.key}>
                <th scope="row">{month.label}</th>
                {lines.map((line) => <td key={line.key}>{cell(line.values[index])}</td>)}
                <td>{cell(metric === 'participants' ? result.totalParticipants[index] : result.totalCompleted[index])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  )
}

export const LearningDynamicsReport = ({ organizationIds, programIds, onSessionExpired, onProfileUnavailable }: LearningDynamicsReportProps) => {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [seriesBy, setSeriesBy] = useState<SeriesBy>('ORGANIZATION')
  const [metric, setMetric] = useState<Metric>('participants')
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [download, setDownload] = useState<{ format: 'XLSX' | 'PDF' | null; error: unknown }>({ format: null, error: null })
  const version = useRef(0)
  const today = todayInMoscow()

  const load = useCallback(async (query: LearningDynamicsQuery) => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const result = await apiClient.getLearningDynamics(query)
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

  const query = (): LearningDynamicsQuery => ({
    from: from || undefined,
    to: to || undefined,
    seriesBy,
    organizationIds,
    programIds
  })
  const latestQuery = useRef(query)
  latestQuery.current = query

  const filterKey = `${(organizationIds ?? []).join(',')}|${(programIds ?? []).join(',')}`
  useEffect(() => {
    void load(latestQuery.current())
  }, [load, filterKey])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    void load(query())
  }

  const save = async (format: 'XLSX' | 'PDF') => {
    if (state.kind !== 'ready') {
      return
    }
    setDownload({ format, error: null })
    try {
      const blob = await apiClient.downloadLearningDynamics(state.query, format)
      saveFile(blob, `Динамика_обучения_${state.result.from}_${state.result.to}.${format.toLowerCase()}`)
      setDownload({ format: null, error: null })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setDownload({ format: null, error })
      }
    }
  }

  const periodProblem = from !== '' && to !== '' && from > to ? 'Дата окончания периода раньше даты начала.' : null

  return (
    <section className="reports__statistics" aria-labelledby="report-page-title" aria-busy={state.kind === 'loading'}>
      <p className="reports__hint">
        Обучающиеся и завершившие по месяцам по истории наблюдений Moodle — вузы и ИТ-программы вашей области. Без периода — последние 12 месяцев.
      </p>
      <form className="reports__order" onSubmit={submit}>
        <label>
          Начало периода
          <input type="date" value={from} max={to || today} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label>
          Конец периода
          <input type="date" value={to} min={from || undefined} max={today} onChange={(event) => setTo(event.target.value)} />
        </label>
        <label>
          Линии
          <select value={seriesBy} onChange={(event) => setSeriesBy(event.target.value === 'PROGRAM' ? 'PROGRAM' : 'ORGANIZATION')}>
            <option value="ORGANIZATION">по вузам</option>
            <option value="PROGRAM">по ИТ-программам</option>
          </select>
        </label>
        <label>
          Показатель
          <select value={metric} onChange={(event) => setMetric(event.target.value === 'completed' ? 'completed' : 'participants')}>
            <option value="participants">Обучающиеся</option>
            <option value="completed">Завершили</option>
          </select>
        </label>
        <button type="submit" disabled={periodProblem !== null || state.kind === 'loading'}>Показать</button>
      </form>
      {periodProblem && <p className="reports__problem" role="alert">{periodProblem}</p>}
      {state.kind === 'loading' && <p role="status">Собираем динамику обучения…</p>}
      {state.kind === 'failed' && (
        <ErrorNotice error={state.error} message="Не удалось собрать динамику обучения." onRetry={() => void load(query())} />
      )}
      {state.kind === 'ready' && (
        <>
          <DynamicsChart result={state.result} metric={metric} />
          <ul className="reports__notes">
            {state.result.notes.map((note) => <li key={note}>{note}</li>)}
          </ul>
          <details className="reports__group">
            <summary>{`Строки по вузам и ИТ-программам (${state.result.rows.length})`}</summary>
            <div className="reports__table-scroll" role="region" aria-label="Строки динамики обучения" tabIndex={0}>
              <table>
                <thead>
                  <tr>
                    <th scope="col">Месяц</th>
                    <th scope="col">Вуз</th>
                    <th scope="col">ИТ-программа</th>
                    <th scope="col">Потоков с данными</th>
                    <th scope="col">Обучающиеся (Moodle)</th>
                    <th scope="col">Завершили (Moodle)</th>
                    <th scope="col">Доля завершивших, %</th>
                  </tr>
                </thead>
                <tbody>
                  {state.result.rows.map((row) => (
                    <tr key={`${row.month}-${row.organizationId}-${row.programId}`}>
                      <td>{row.monthLabel}</td>
                      <td>{row.organizationName}</td>
                      <td>{row.programName}</td>
                      <td>{row.runs}</td>
                      <td>{cell(row.participants)}</td>
                      <td>{cell(row.completed)}</td>
                      <td>{cell(row.completionPercent)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
          <p className="reports__footer">
            <span>Сформировано: {formatMoscowDateTime(state.result.generatedAt)}</span>
            <button type="button" className="button--secondary" disabled={download.format !== null} onClick={() => void save('XLSX')}>
              {download.format === 'XLSX' ? 'Готовим XLSX…' : 'Скачать XLSX'}
            </button>
            <button type="button" className="button--secondary" disabled={download.format !== null} onClick={() => void save('PDF')}>
              {download.format === 'PDF' ? 'Готовим PDF…' : 'Скачать PDF'}
            </button>
          </p>
          {download.error !== null && <ErrorNotice error={download.error} message="Не удалось скачать файл динамики обучения." />}
        </>
      )}
    </section>
  )
}
