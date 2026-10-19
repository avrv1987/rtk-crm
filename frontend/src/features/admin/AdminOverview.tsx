import type { BackupStatus, CrmEventCounts, DataSource, LearningCoverage } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { coverageText } from '../control/overviewModel'
import { backupText } from './backupStatus'

export type AdminSummary = {
  pendingProfiles?: number
  sources?: DataSource[]
  crmEvents?: CrmEventCounts
  backup?: BackupStatus
  coverage?: LearningCoverage
  failed: boolean
}

type SourceState = { label: string; tone: 'ok' | 'warning' | 'danger' | 'muted' }

const sourceState = (source: DataSource): SourceState => {
  if (!source.configured) {
    return { label: 'не настроен', tone: 'muted' }
  }
  if (source.lastRun?.status === 'FAILED') {
    return { label: 'ошибка', tone: 'danger' }
  }
  if (source.stale === true) {
    return { label: 'устарели', tone: 'warning' }
  }
  return { label: 'норма', tone: 'ok' }
}

export const problemRecordsOf = (sources: DataSource[] | undefined) => (
  sources?.reduce((sum, source) => sum + source.problemCount, 0)
)

const noData = 'нет данных'

const runStateLabel = { FRESH: 'данные свежие', STALE: 'данные устарели', NO_DATA: 'наблюдений нет' } as const

// Какая часть аналитики обучения сейчас неполна и что проверить (разбор §5.4).
const CoverageDetails = ({ coverage }: { coverage: LearningCoverage }) => (
  <>
    <p>{coverageText(coverage)}</p>
    <p className="admin-overview__hint">
      {`Ожидаемые потоки — сопоставленные запуски занятий студентов Moodle, которые идут сегодня. Устаревшими считаются`
        + ` данные старше ${coverage.staleAfterHours} ч. Курсы, которые в CRM ещё не приходили, сюда не попадают.`
        + (coverage.unappliedRecords !== null && coverage.unappliedRecords !== undefined && coverage.unappliedRecords > 0
          ? ` Записей Moodle ждут сопоставления или с ошибкой: ${coverage.unappliedRecords}.` : '')}
    </p>
    {coverage.runs.length > 0 && (
      <ul className="admin-overview__runs">
        {coverage.runs.map((run) => (
          <li key={run.mappingId}>
            {`${run.organizationName} · ${run.programName} · ${run.courseName}: ${runStateLabel[run.state]}`
              + (run.lastObservedAt ? `, последнее наблюдение ${formatMoscowDateTime(run.lastObservedAt)}` : '')}
          </li>
        ))}
      </ul>
    )}
  </>
)

export const AdminOverview = ({ summary, onRetry }: { summary: AdminSummary | null; onRetry: () => void }) => {
  if (summary === null) {
    return <p role="status">Собираем сводку…</p>
  }
  const problems = problemRecordsOf(summary.sources)
  return (
    <section className="admin-overview" aria-label="Сводка администрирования">
      {summary.failed && (
        <div className="notice notice--error" role="alert">
          <p>Часть сводки не загрузилась.</p>
          <button type="button" className="button--secondary" onClick={onRetry}>Повторить</button>
        </div>
      )}
      <div className="admin-overview__grid">
        <div className="admin-overview__card">
          <h2>Ожидают активации: {summary.pendingProfiles ?? noData}</h2>
          <p>Сотрудники вошли в первый раз, им нужны роль и команда.</p>
          <a className="button-link" href="#/admin/employees?status=PENDING">Показать ожидающих</a>
        </div>
        <div className="admin-overview__card">
          <h2>Записи для разбора: {problems ?? noData}</h2>
          <p>Заявки сайта и курсы Moodle, которые не сопоставились сами.</p>
          <a className="button-link" href="#/admin/sources?tab=review">Разобрать</a>
        </div>
        <div className="admin-overview__card">
          <h2>Источники данных</h2>
          {summary.sources === undefined ? <p>{noData}</p> : (
            <ul className="admin-overview__sources">
              {summary.sources.map((source) => {
                const state = sourceState(source)
                return (
                  <li key={source.source} className={`admin-overview__source admin-overview__source--${state.tone}`}>
                    {source.title}: {state.label}
                  </li>
                )
              })}
            </ul>
          )}
          <a className="button-link" href="#/admin/sources">Подробнее</a>
        </div>
        <div className="admin-overview__card">
          <h2>Резервная копия</h2>
          {summary.backup === undefined ? <p>{noData}</p> : (
            <p className={backupText(summary.backup).stale ? 'admin-overview__warning' : undefined}>{backupText(summary.backup).text}</p>
          )}
          <a className="button-link" href="#/help?section=help-admin-ops">Как восстановить</a>
        </div>
        <div className="admin-overview__card">
          <h2>События для CRM заказчика</h2>
          <p>
            {summary.crmEvents === undefined
              ? noData
              : `В очереди ${summary.crmEvents.queued} · отправлено ${summary.crmEvents.sent} · с ошибкой ${summary.crmEvents.error}`}
          </p>
          <a className="button-link" href="#/admin/sources?tab=crm">Очередь событий</a>
        </div>
        <div className="admin-overview__card admin-overview__card--wide">
          <h2>Полнота данных обучения</h2>
          {summary.coverage === undefined ? <p>{noData}</p> : <CoverageDetails coverage={summary.coverage} />}
          <a className="button-link" href="#/admin/sources?tab=review">Сопоставления и записи Moodle</a>
        </div>
      </div>
    </section>
  )
}
