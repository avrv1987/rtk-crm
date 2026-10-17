import type { ReportKind, StatisticsRequest } from '../../shared/api/client'
import { terms } from '../../shared/ui/terms'
import { CardDialog } from '../interactions/cardUi'
import { groupingTitles, groupingsFor, seriesGroupingsFor, type ReportSelection } from './reportSelection'

type ReportGroupingDialogProps = {
  open: boolean
  onClose: () => void
  kind: ReportKind
  selection: ReportSelection
  line: boolean
  seriesBy: StatisticsRequest['seriesBy']
  problem: string | null
  update: (patch: Partial<ReportSelection>) => void
}

export const ReportGroupingDialog = ({ open, onClose, kind, selection, line, seriesBy, problem, update }: ReportGroupingDialogProps) => (
  <CardDialog open={open} title="Группировка диаграммы" onClose={onClose}>
    <div className="report-grouping">
      <div className="report-field">
        <label htmlFor="report-grouping">Группировка диаграммы</label>
        <select
          id="report-grouping"
          value={selection.groupBy}
          onChange={(event) => update({ groupBy: groupingsFor(kind).find((groupBy) => groupBy === event.target.value) ?? 'STAGE' })}
        >
          {groupingsFor(kind).map((groupBy) => <option key={groupBy} value={groupBy}>{groupingTitles[groupBy]}</option>)}
        </select>
      </div>
      {selection.groupBy === 'MONTH' && (
        <div className="report-field">
          <label htmlFor="report-chart-type">Вид отображения</label>
          <select
            id="report-chart-type"
            value={selection.chartType}
            onChange={(event) => update({ chartType: event.target.value === 'LINE' ? 'LINE' : 'BAR' })}
          >
            <option value="BAR">Столбцы</option>
            <option value="LINE">График (линия)</option>
          </select>
        </div>
      )}
      {line && (
        <div className="report-field">
          <label htmlFor="report-series">Линии</label>
          <select
            id="report-series"
            value={seriesBy ?? ''}
            onChange={(event) => update({ seriesBy: seriesGroupingsFor(kind).find((groupBy) => groupBy === event.target.value) ?? null })}
          >
            <option value="">Одна линия: всего</option>
            {seriesGroupingsFor(kind).map((groupBy) => (
              <option key={groupBy} value={groupBy}>Отдельно {groupingTitles[groupBy]}</option>
            ))}
          </select>
        </div>
      )}
      {problem !== null && <p className="reports__problem" role="alert">{problem}</p>}
    </div>
    <div className="report-dialog-actions">
      <button type="button" onClick={onClose}>{terms.done}</button>
    </div>
  </CardDialog>
)
