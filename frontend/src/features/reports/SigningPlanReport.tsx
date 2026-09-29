import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type SigningPlan, type SigningPlanQuery, type SigningPlanTotals } from '../../shared/api/client'
import { formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { saveFile } from '../admin/saveFile'
import { type AccessHandlers, formatDate, handledAccessError } from '../work/workShared'
import { ErrorNotice } from './ReportErrorNotice'
import { currentPeriod, periodTitle, planKindLabels, planSourceLabels, planStateLabels, quarterLabel, stateCounts, yearOptions } from './signingPlan'
import './reports.css'

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; result: SigningPlan; query: SigningPlanQuery }
  | { kind: 'failed'; error: unknown }

const TotalsTable = ({ title, nameTitle, totals, withTeam }: { title: string; nameTitle: string; totals: SigningPlanTotals[]; withTeam: boolean }) => (
  <div className="reports__table-scroll" role="region" aria-label={title} tabIndex={0}>
    <table>
      <caption>{title}</caption>
      <thead>
        <tr>
          <th scope="col">{nameTitle}</th>
          {withTeam && <th scope="col">Команда</th>}
          <th scope="col">Подписаний</th>
          <th scope="col">Продлений</th>
          <th scope="col">Выполнено</th>
          <th scope="col">Просрочено</th>
          <th scope="col">Впереди</th>
        </tr>
      </thead>
      <tbody>
        {totals.map((item) => (
          <tr key={item.id ?? item.name}>
            <th scope="row">{item.name}</th>
            {withTeam && <td>{item.teamName}</td>}
            <td>{item.signing}</td>
            <td>{item.renewal}</td>
            <td>{item.done}</td>
            <td className={item.overdue > 0 ? 'signing-plan__cell--overdue' : undefined}>{item.overdue}</td>
            <td>{item.upcoming}</td>
          </tr>
        ))}
      </tbody>
    </table>
  </div>
)

export const SigningPlanReport = ({ onSessionExpired, onProfileUnavailable }: AccessHandlers) => {
  const today = todayInMoscow()
  const initial = currentPeriod(today)
  const [year, setYear] = useState(initial.year)
  const [scope, setScope] = useState<string>(String(initial.quarter))
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [download, setDownload] = useState<{ format: 'XLSX' | 'PDF' | null; error: unknown }>({ format: null, error: null })
  const version = useRef(0)

  const load = useCallback(async (query: SigningPlanQuery) => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const result = await apiClient.getSigningPlan(query)
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

  const query = (): SigningPlanQuery => (scope === 'YEAR' ? { year } : { year, quarter: Number(scope) })

  useEffect(() => {
    void load(scope === 'YEAR' ? { year } : { year, quarter: Number(scope) })
  }, [load, scope, year])

  const save = async (format: 'XLSX' | 'PDF') => {
    if (state.kind !== 'ready') {
      return
    }
    setDownload({ format, error: null })
    try {
      const blob = await apiClient.downloadSigningPlan(state.query, format)
      saveFile(blob, `План_подписаний_${state.result.from}_${state.result.to}.${format.toLowerCase()}`)
      setDownload({ format: null, error: null })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setDownload({ format: null, error })
      }
    }
  }

  const counts = state.kind === 'ready' ? stateCounts(state.result.rows) : null
  const signing = state.kind === 'ready' ? state.result.rows.filter((row) => row.kind === 'SIGNING').length : 0

  return (
    <section className="reports__statistics signing-plan" aria-labelledby="signing-plan-title" aria-busy={state.kind === 'loading'}>
      <h3 id="signing-plan-title">План подписаний и продлений</h3>
      <p className="reports__hint">
        Соглашения вузов вашей области, у которых подписание или продление запланировано на выбранный период. Плановую дату
        указывают в карточке вуза, в блоке «Соглашения»; действующее соглашение без плановой даты попадает сюда продлением
        на дату окончания срока действия.
      </p>
      <form className="reports__order" onSubmit={(event) => event.preventDefault()}>
        <label>
          Год
          <select value={year} onChange={(event) => setYear(Number(event.target.value))}>
            {yearOptions(initial.year).map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        <label>
          Период
          <select value={scope} onChange={(event) => setScope(event.target.value)}>
            {[1, 2, 3, 4].map((quarter) => <option key={quarter} value={quarter}>{quarterLabel(quarter)}</option>)}
            <option value="YEAR">Весь год</option>
          </select>
        </label>
      </form>
      {state.kind === 'loading' && <p role="status">Собираем план подписаний и продлений…</p>}
      {state.kind === 'failed' && (
        <ErrorNotice error={state.error} message="Не удалось собрать план подписаний и продлений." onRetry={() => void load(query())} />
      )}
      {state.kind === 'ready' && counts !== null && (
        <>
          <p className="signing-plan__summary" aria-live="polite">
            <strong>{periodTitle(state.result.year, state.result.quarter ?? undefined)}:</strong>
            {` подписаний — ${signing}, продлений — ${state.result.rows.length - signing}; выполнено — ${counts.done}, `}
            <span className={counts.overdue > 0 ? 'signing-plan__cell--overdue' : undefined}>{`просрочено — ${counts.overdue}`}</span>
            {`, впереди — ${counts.upcoming}.`}
          </p>
          {state.result.rows.length === 0 ? (
            <p>В этом периоде нет плановых подписаний и продлений.</p>
          ) : (
            <>
              <TotalsTable title="Итоги по КАМ" nameTitle="КАМ" totals={state.result.byManager} withTeam />
              <TotalsTable title="Итоги по командам" nameTitle="Команда" totals={state.result.byTeam} withTeam={false} />
              <div className="reports__table-scroll" role="region" aria-label="Строки плана подписаний и продлений" tabIndex={0}>
                <table>
                  <caption>{`Строки плана (${state.result.rows.length})`}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Вуз</th>
                      <th scope="col">КАМ</th>
                      <th scope="col">Соглашение</th>
                      <th scope="col">Вид</th>
                      <th scope="col">Плановая дата</th>
                      <th scope="col">Статус</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.result.rows.map((row) => (
                      <tr key={row.agreementId}>
                        <th scope="row"><a href={`#/organizations/${row.organizationId}`}>{row.organizationName}</a></th>
                        <td>{row.managerName ?? 'Без ответственного КАМ'}<span className="signing-plan__sub">{row.teamName}</span></td>
                        <td>{row.agreementNumber}</td>
                        <td>{planKindLabels[row.kind]}</td>
                        <td>
                          {formatDate(row.plannedOn)}
                          <span className="signing-plan__sub">{planSourceLabels[row.source]}</span>
                        </td>
                        <td>
                          <span className={`signing-plan__state signing-plan__state--${row.state.toLowerCase()}`}>{planStateLabels[row.state]}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
          <ul className="reports__notes">
            {state.result.notes.map((note) => <li key={note}>{note}</li>)}
          </ul>
          <p className="reports__actions">
            <span>Сформировано: {formatMoscowDateTime(state.result.generatedAt)}</span>
            <button type="button" className="button--secondary" disabled={download.format !== null} onClick={() => void save('XLSX')}>
              {download.format === 'XLSX' ? 'Готовим XLSX…' : 'Скачать XLSX'}
            </button>
            <button type="button" className="button--secondary" disabled={download.format !== null} onClick={() => void save('PDF')}>
              {download.format === 'PDF' ? 'Готовим PDF…' : 'Скачать PDF'}
            </button>
          </p>
          {download.error !== null && <ErrorNotice error={download.error} message="Не удалось скачать файл плана." />}
        </>
      )}
    </section>
  )
}
