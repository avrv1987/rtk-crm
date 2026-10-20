import { useState } from 'react'
import type { WorkControl } from '../../shared/api/client'
import { Segmented } from '../../shared/ui/Segmented'
import { WorkRow } from './ControlMatrix'
import {
  axisOptions,
  cellText,
  cellTone,
  flatRows,
  type MatrixAxis,
  type MatrixCell,
  type MatrixRoute,
  stageMatrix
} from './cycleModel'
import './cycleViews.css'

type CellPick = (axis: MatrixAxis, rowId: string, stage: string, route: MatrixRoute, severalRoutes: boolean) => void

const CellValue = ({ value, label, stuckDays, onPick }: {
  value: MatrixCell
  label: string
  stuckDays: number
  onPick?: () => void
}) => {
  const tone = cellTone(value)
  if (value.works === 0) {
    return <span className="cycle-cell cycle-cell--empty" aria-label={`${label}: нет`}>·</span>
  }
  const content = (
    <>
      <strong>{value.works}</strong>
      <span className="cycle-cell__sub">{`вузов ${value.organizations}`}</span>
      {value.overdue > 0 && <span className="cycle-cell__mark">{`просроч. ${value.overdue}`}</span>}
      {value.noStep > 0 && <span className="cycle-cell__mark">{`без шага ${value.noStep}`}</span>}
      {value.stuck > 0 && <span className="cycle-cell__mark">{`> ${stuckDays} дн. ${value.stuck}`}</span>}
    </>
  )
  const text = `${label}: ${cellText(value, stuckDays)}`
  return onPick === undefined
    ? <span className={`cycle-cell cycle-cell--${tone}`} title={text} aria-label={text}>{content}</span>
    : <button type="button" className={`cycle-cell cycle-cell--${tone}`} title={`${text}. Открыть списком`} aria-label={`${text}. Открыть списком`} onClick={onPick}>{content}</button>
}

const RouteTable = ({ route, axis, stuckDays, severalRoutes, onPick }: {
  route: MatrixRoute
  axis: MatrixAxis
  stuckDays: number
  severalRoutes: boolean
  onPick: CellPick
}) => (
  <div className="cycle-matrix__scroll">
    <table className="cycle-matrix__table">
      <thead>
        <tr>
          <th scope="col">{axis === 'PROGRAM' ? 'ИТ-программа' : 'Продукт'}</th>
          {route.stages.map((stage, index) => route.former[index]
            ? <th key={stage} scope="col" title={`${stage} — этапа нет в текущей версии маршрута`}>{stage} <small>(прежний этап)</small></th>
            : route.card[index]
              ? <th key={stage} scope="col" title={`${stage} — этап добавлен в маршрут отдельных взаимодействий`}>{stage} <small>(этап карточки)</small></th>
              : <th key={stage} scope="col" title={stage}><span>{route.numbers[index]}</span> {stage}</th>)}
          <th scope="col">Всего</th>
        </tr>
      </thead>
      <tbody>
        {route.rows.map((row) => (
          <tr key={row.key}>
            <th scope="row">{row.label}</th>
            {row.cells.map((value, index) => (
              <td key={route.stages[index]}>
                <CellValue
                  value={value}
                  label={`${row.label}, ${route.stages[index]}`}
                  stuckDays={stuckDays}
                  onPick={row.id === null ? undefined : () => onPick(axis, row.id as string, route.stages[index], route, severalRoutes)}
                />
              </td>
            ))}
            <td><CellValue value={row.total} label={`${row.label}, всего`} stuckDays={stuckDays} /></td>
          </tr>
        ))}
      </tbody>
      <tfoot>
        <tr>
          <th scope="row">Всего</th>
          {route.totals.map((value, index) => (
            <td key={route.stages[index]}><CellValue value={value} label={`${route.stages[index]}, всего`} stuckDays={stuckDays} /></td>
          ))}
          <td><CellValue value={route.total} label="Всего по маршруту" stuckDays={stuckDays} /></td>
        </tr>
      </tfoot>
    </table>
  </div>
)

// Телефон: таблица на 13 этапов не помещается, поэтому каждая программа — свёрнутая карточка со списком непустых этапов.
const RouteCards = ({ route, axis, stuckDays, severalRoutes, onPick }: {
  route: MatrixRoute
  axis: MatrixAxis
  stuckDays: number
  severalRoutes: boolean
  onPick: CellPick
}) => (
  <ul className="cycle-cards">
    {route.rows.map((row) => (
      <li key={row.key}>
        <details className={`cycle-card cycle-card--${cellTone(row.total)}`}>
          <summary>
            <strong>{row.label}</strong>
            <span className="control-muted">{cellText(row.total, stuckDays)}</span>
          </summary>
          <ol className="cycle-card__stages">
            {row.cells.map((value, index) => value.works === 0 ? null : (
              <li key={route.stages[index]} value={index + 1}>
                <span className="cycle-card__stage">{route.stages[index]}{route.former[index] ? ' (прежний этап)' : route.card[index] ? ' (этап карточки)' : ''}</span>
                <CellValue
                  value={value}
                  label={`${row.label}, ${route.stages[index]}`}
                  stuckDays={stuckDays}
                  onPick={row.id === null ? undefined : () => onPick(axis, row.id as string, route.stages[index], route, severalRoutes)}
                />
              </li>
            ))}
          </ol>
        </details>
      </li>
    ))}
  </ul>
)

export const CycleStageMatrix = ({ control, stuckDays, periodEnd, kindNames, compact, onPick }: {
  control: WorkControl
  stuckDays: number
  // Дата конца прошедшего периода: этап в ячейках — на эту дату.
  periodEnd: string | null
  kindNames: (ids: string[]) => string
  compact: boolean
  onPick: CellPick
}) => {
  const [axis, setAxis] = useState<MatrixAxis>('PROGRAM')
  const routes = stageMatrix(control, axis, stuckDays)
  const severalRoutes = routes.length > 1
  return (
    <section className="cycle-matrix" aria-label="Программы и этапы">
      <div className="cycle-matrix__head">
        <Segmented label="Строки матрицы" options={axisOptions} value={axis} onChange={setAxis} />
        <p className="control-muted">
          {`В ячейке — взаимодействия и вузы на этапе${periodEnd === null ? '' : ` на ${periodEnd}`}. Красная — есть просрочки, жёлтая — без шага`
            + ` или на этапе дольше ${stuckDays} дн. Нажмите на число — откроется список с этим отбором.`
            + (axis === 'PRODUCT' ? ' Взаимодействие с несколькими продуктами входит в строку каждого; «Всего» считает его один раз.' : '')}
        </p>
      </div>
      {routes.length === 0 && <p className="control-empty">По этим фильтрам взаимодействий нет.</p>}
      {routes.map((route) => (
        <div key={route.key} className="cycle-matrix__route">
          {severalRoutes && (
            <h3>{`Маршрут «${route.name}»`}{route.kindIds.length > 0 && <span className="control-muted">{`Виды: ${kindNames(route.kindIds)}`}</span>}</h3>
          )}
          {compact
            ? <RouteCards route={route} axis={axis} stuckDays={stuckDays} severalRoutes={severalRoutes} onPick={onPick} />
            : <RouteTable route={route} axis={axis} stuckDays={stuckDays} severalRoutes={severalRoutes} onPick={onPick} />}
        </div>
      ))}
    </section>
  )
}

export const ControlRows = ({ control, withPeriod, byTeams }: { control: WorkControl; withPeriod: boolean; byTeams: boolean }) => {
  const rows = flatRows(control)
  return rows.length === 0 ? <p className="control-empty">По этим фильтрам взаимодействий нет.</p> : (
    <table className="control-table control-rows">
      <thead>
        <tr>
          <th scope="col">КАМ</th>
          <th scope="col">Вуз и взаимодействие</th>
          <th scope="col">Этап</th>
          <th scope="col">Следующий шаг</th>
          <th scope="col">Договор</th>
          <th scope="col">Обучение (LMS)</th>
          <th scope="col">Проблемы и отметки</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(({ item, managerName, teamName }) => (
          <WorkRow
            key={item.interactionId}
            item={item}
            withPeriod={withPeriod}
            manager={`${managerName ?? 'Требует назначения'}${byTeams && teamName ? ` · ${teamName}` : ''}`}
          />
        ))}
      </tbody>
    </table>
  )
}
