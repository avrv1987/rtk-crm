import type { ReactNode } from 'react'
import { InfoTip } from './ReportControls'

type ReportHeaderProps = {
  id: string
  group: string
  title: string
  hint?: ReactNode
  saved?: string
  actions?: ReactNode
}

// Шапка любого отчёта: крошки «Отчёты / группа», заголовок, «?» и справа выгрузка и «⋯».
export const ReportHeader = ({ id, group, title, hint, saved, actions }: ReportHeaderProps) => (
  <header className="report-header">
    <nav className="report-header__crumbs" aria-label="Путь">
      <a href="#/reports">Отчёты</a>
      <span aria-hidden="true"> / </span>
      <span>{group}</span>
    </nav>
    <div className="report-header__row">
      <h2 id={id} className="reports__section-title">{title}</h2>
      {hint !== undefined && <InfoTip label={title}>{hint}</InfoTip>}
      {saved !== undefined && <span className="report-builder__saved">Сохранённый отчёт «{saved}»</span>}
      {actions !== undefined && <div className="report-header__actions">{actions}</div>}
    </div>
  </header>
)
