import { useId } from 'react'
import type { StatisticsResult } from '../../shared/api/client'
import { axisStep, countTitles, kindTitles, monthTitles, unspecifiedLabel } from './StatisticsChart'
import { groupingTitles } from './reportSelection'
import './reportExtras.css'

type Line = {
  key: string
  label: string
  values: number[]
  unspecified: boolean
}

const plotTop = 36
const plotHeight = 260
const labelsHeight = 44
const maxMonthLabels = 6
const maxPointLabels = 36
const leftPercent = 3
const widthPercent = 94

export const chartLines = (result: StatisticsResult): Line[] => (
  result.seriesBy === null || result.seriesBy === undefined
    ? [{ key: 'total', label: countTitles[result.kind], values: result.items.map((item) => item.count), unspecified: false }]
    : result.series.map((series) => ({ key: series.key, label: series.label, values: series.counts, unspecified: series.unspecified }))
)

const monthLabel = (key: string) => `${key.slice(5, 7)}.${key.slice(0, 4)}`

type LineChartProps = {
  result: StatisticsResult
}

export const LineChart = ({ result }: LineChartProps) => {
  const id = useId()
  const lines = chartLines(result)
  const count = result.items.length
  const max = lines.reduce((value, line) => Math.max(value, ...line.values), 0)
  const step = axisStep(max)
  const axisMax = Math.max(step, Math.ceil(max / step) * step)
  const ticks = Array.from({ length: axisMax / step + 1 }, (_, index) => index * step)
  const plotBottom = plotTop + plotHeight
  const x = (index: number) => `${leftPercent + (index + 0.5) * widthPercent / count}%`
  const y = (value: number) => plotBottom - value / axisMax * plotHeight
  const every = Math.max(1, Math.ceil(count / maxMonthLabels))
  const pointLabels = count * lines.length <= maxPointLabels
  const series = result.seriesBy !== null && result.seriesBy !== undefined
  let colorIndex = 0
  const colored = lines.map((line) => ({
    ...line,
    className: line.unspecified ? 'line-chart__series--unspecified' : `line-chart__series--${colorIndex++ % 5}`
  }))
  const title = `${kindTitles[result.kind]} — график ${groupingTitles[result.groupBy]}${series ? `, линии ${groupingTitles[result.seriesBy!]}` : ''}`
  const caption = [
    `Показатель: ${countTitles[result.kind].toLowerCase()} за месяц; всего в выборке: ${result.total}; шкала начинается с нуля.`,
    `${monthTitles[result.kind]} по московскому времени; месяцы периода без строк показаны нулём.`,
    ...(series
      ? [`Линии ${groupingTitles[result.seriesBy!]}; «${unspecifiedLabel}» (серая пунктирная линия) — строки без значения, это не ноль.`]
      : []),
    ...(result.seriesBy === 'PRODUCT'
      ? ['Строка с несколькими ИТ-продуктами учтена в линии каждого из них, поэтому сумма линий может быть больше итога.']
      : []),
    ...(pointLabels ? [] : ['Точек слишком много для подписей: значения — в таблице основания под графиком.'])
  ]

  return (
    <figure className="statistics-chart line-chart">
      <figcaption>
        <strong>{title}</strong>
        <ul className="reports__notes">
          {[...caption, ...result.notes].map((line) => <li key={line}>{line}</li>)}
        </ul>
      </figcaption>
      {series && (
        <ul className="line-chart__legend">
          {colored.map((line) => (
            <li key={line.key} className={line.unspecified ? 'statistics-chart__label--unspecified' : undefined}>
              <span className={`line-chart__swatch ${line.className}`} aria-hidden="true" />
              {line.label}
            </li>
          ))}
        </ul>
      )}
      <svg
        className="statistics-chart__svg"
        width="100%"
        height={plotBottom + labelsHeight}
        role="img"
        aria-labelledby={`${id}-title ${id}-desc`}
      >
        <title id={`${id}-title`}>{title}</title>
        <desc id={`${id}-desc`}>
          {`Линейный график по месяцам, шкала от нуля до ${axisMax}. `}
          {lines.map((line) => `${line.label}: ${result.items.map((item, index) => `${item.label} — ${line.values[index]}`).join(', ')}`).join('; ')}
        </desc>
        <text x="0" y="14" className="statistics-chart__axis-title">{countTitles[result.kind]}, шкала от нуля</text>
        {ticks.map((tick) => (
          <g key={tick}>
            <line
              x1={`${leftPercent}%`}
              x2="100%"
              y1={y(tick)}
              y2={y(tick)}
              className={tick === 0 ? 'statistics-chart__zero' : 'statistics-chart__grid'}
            />
            <text x="0" y={y(tick) - 4} className="statistics-chart__tick">{tick}</text>
          </g>
        ))}
        {count === 0 && (
          <text x="8" y={plotTop + 24} className="statistics-chart__tick">Нет строк, удовлетворяющих фильтрам</text>
        )}
        {result.items.map((item, index) => index % every === 0 && (
          <text key={item.key} x={x(index)} y={plotBottom + 20} textAnchor="middle" className="statistics-chart__tick">
            {monthLabel(item.key)}
          </text>
        ))}
        <text x="100%" y={plotBottom + 38} textAnchor="end" className="statistics-chart__tick">Месяц</text>
        {colored.map((line) => (
          <g key={line.key} className={`line-chart__line ${line.className}`}>
            {line.values.slice(1).map((value, index) => (
              <line key={index} x1={x(index)} y1={y(line.values[index])} x2={x(index + 1)} y2={y(value)} />
            ))}
            {line.values.map((value, index) => (
              <g key={result.items[index].key}>
                <title>{`${line.label}, ${result.items[index].label}: ${value}`}</title>
                <circle cx={x(index)} cy={y(value)} r="4.5" />
                {pointLabels && (
                  <text x={x(index)} y={y(value) - 9} textAnchor="middle" className="statistics-chart__value">{value}</text>
                )}
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
              {series && <th scope="col">Всего за месяц</th>}
            </tr>
          </thead>
          <tbody>
            {result.items.map((item, index) => (
              <tr key={item.key}>
                <th scope="row">{item.label}</th>
                {lines.map((line) => <td key={line.key}>{line.values[index]}</td>)}
                {series && <td>{item.count}</td>}
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Всего строк в выборке</th>
              {lines.slice(series ? 0 : 1).map((line) => <td key={line.key} />)}
              <td>{result.total}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </figure>
  )
}
