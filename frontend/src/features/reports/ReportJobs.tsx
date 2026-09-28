import type { ReportJob } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { InfoTip } from './ReportControls'
import { ErrorNotice } from './ReportErrorNotice'
import { groupingTitles, kindLabels } from './reportSelection'

export type JobsState =
  | { kind: 'loading' }
  | { kind: 'ready'; jobs: ReportJob[] }
  | { kind: 'failed'; error: unknown }

export type DownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading'; id: string }
  | { kind: 'failed'; id: string; error: unknown }

export const isFinished = (job: ReportJob) => job.status === 'SUCCEEDED' || job.status === 'FAILED'

const rowsText = (count: number) => {
  const tens = count % 100
  const ones = count % 10
  const word = tens >= 11 && tens <= 14 ? 'строк' : ones === 1 ? 'строка' : ones >= 2 && ones <= 4 ? 'строки' : 'строк'
  return `${count.toLocaleString('ru-RU')} ${word}`
}

export const jobStatusText = (job: ReportJob) => {
  if (job.status === 'PENDING') {
    return 'Ждёт очереди'
  }
  if (job.status === 'RUNNING') {
    return job.rowCount === null || job.rowCount === undefined ? 'Отбираем строки' : `Собираем файл: ${rowsText(job.rowCount)}`
  }
  if (job.status === 'SUCCEEDED') {
    return `Готово: ${rowsText(job.rowCount ?? 0)}`
  }
  return 'Не получилось'
}

const chartTitle = (job: ReportJob & { groupBy: NonNullable<ReportJob['groupBy']> }) => {
  if (job.chartType !== 'LINE') {
    return `Диаграмма ${groupingTitles[job.groupBy]}`
  }
  return job.seriesBy === null || job.seriesBy === undefined
    ? `График ${groupingTitles[job.groupBy]}`
    : `График ${groupingTitles[job.groupBy]}, линии ${groupingTitles[job.seriesBy]}`
}

export const jobTitle = (job: ReportJob) => (
  job.groupBy === null || job.groupBy === undefined
    ? `${kindLabels[job.kind]}, ${job.format}`
    : `${chartTitle({ ...job, groupBy: job.groupBy })} — ${kindLabels[job.kind]}, ${job.format}`
)

export const JobProgress = ({ job }: { job: ReportJob }) => (
  <div className="report-job__state">
    <span className={`report-job__status report-job__status--${job.status.toLowerCase()}`}>{jobStatusText(job)}</span>
    {!isFinished(job) && (
      <progress className="report-job__progress" max={100} value={job.progress} aria-label={`Готовность файла: ${job.progress}%`} />
    )}
    {job.status === 'FAILED' && job.error && (
      <>
        <span className="report-job__error">{job.error.message}</span>
        <SupportDetails code={job.error.code} />
      </>
    )}
  </div>
)

type ReportJobsProps = {
  jobsState: JobsState
  pollError: unknown
  downloadState: DownloadState
  onReload: () => void
  onDownload: (job: ReportJob) => void
}

export const ReportJobs = ({ jobsState, pollError, downloadState, onReload, onDownload }: ReportJobsProps) => (
  <section className="report-jobs" aria-labelledby="report-jobs-title">
    <div className="report-jobs__header">
      <h3 id="report-jobs-title">Мои выгрузки</h3>
      <InfoTip label="Мои выгрузки">
        Файлы строятся на сервере в фоне по тем же фильтрам и колонкам. Пока файл готовится, можно работать в других
        разделах; готовый файл остаётся здесь, пока его не удалят по сроку хранения. Время — московское.
      </InfoTip>
      <button type="button" className="reports__secondary" onClick={onReload} disabled={jobsState.kind === 'loading'}>
        Обновить список
      </button>
    </div>
    {pollError !== null && (
      <ErrorNotice error={pollError} message="Не удалось узнать, готов ли файл." onRetry={onReload} retryLabel="Обновить список" />
    )}
    {jobsState.kind === 'loading' && <p className="report-empty" role="status">Загружаем выгрузки…</p>}
    {jobsState.kind === 'failed' && (
      <ErrorNotice error={jobsState.error} message="Не удалось загрузить выгрузки." onRetry={onReload} />
    )}
    {jobsState.kind === 'ready' && jobsState.jobs.length === 0 && (
      <p className="report-empty">Выгрузок пока нет. Откройте отчёт и нажмите «Выгрузить XLSX» или «Выгрузить PDF».</p>
    )}
    {jobsState.kind === 'ready' && jobsState.jobs.length > 0 && (
      <ul className="report-jobs__list" aria-live="polite">
        {jobsState.jobs.map((job) => {
          const downloading = downloadState.kind === 'downloading' && downloadState.id === job.id
          return (
            <li key={job.id} className="report-job">
              <div className="report-job__main">
                <strong className="report-job__title">{jobTitle(job)}</strong>
                <span className="report-job__time">
                  Заказан {formatMoscowDateTime(job.createdAt)}
                  {job.finishedAt ? ` · завершён ${formatMoscowDateTime(job.finishedAt)}` : ''}
                </span>
                <JobProgress job={job} />
              </div>
              {job.resultReady && (
                <button type="button" onClick={() => onDownload(job)} disabled={downloading}>
                  {downloading ? 'Готовим скачивание…' : 'Скачать'}
                </button>
              )}
              {downloadState.kind === 'failed' && downloadState.id === job.id && (
                <ErrorNotice error={downloadState.error} message="Файл не скачан." onRetry={() => onDownload(job)} />
              )}
            </li>
          )
        })}
      </ul>
    )}
  </section>
)
