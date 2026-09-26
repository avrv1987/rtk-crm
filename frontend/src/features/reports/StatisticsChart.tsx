import { useId } from 'react'
import type { StatisticsGroupBy, StatisticsResult } from '../../shared/api/client'
import { groupingTitles } from './reportSelection'

type Bar = {
  key: string
  label: string
  count: number
  unspecified: boolean
}

const unspecifiedLabel = 'Не указано'

const mayBeUnspecified: ReadonlySet<StatisticsGroupBy> = new Set<StatisticsGroupBy>(['DIRECTION', 'PROGRAM', 'PRODUCT', 'MANAGER'])

const groupingHeaders: Record<StatisticsGroupBy, string> = {
  STAGE: 'Этап',
  ORGANIZATION: 'Вуз',
  DIRECTION: 'ИТ-направление',
  PROGRAM: 'ИТ-программа',
  PRODUCT: 'ИТ-продукт',
  MANAGER: 'Ответственный',
  MONTH: 'Месяц'
}

const kindTitles: Record<StatisticsResult['kind'], string> = {
  PORTFOLIO: 'Портфель взаимодействий: текущее состояние',
  EVENTS: 'События взаимодействий за период',
  DEMAND: 'Востребованность программ'
}

const countTitles: Record<StatisticsResult['kind'], string> = {
  PORTFOLIO: 'Число взаимодействий',
  EVENTS: 'Число событий',
  DEMAND: 'Число заявок'
}

const monthTitles: Record<StatisticsResult['kind'], string> = {
  PORTFOLIO: 'Месяц создания взаимодействия',
  EVENTS: 'Месяц события',
  DEMAND: 'Месяц подачи заявки'
}

const labelLineLength = 32
const maxLabelLines = 3
const labelLineHeight = 16
const axisHeight = 48
const barHeight = 14
const barGap = 26
const barAreaPercent = 86

export const chartBars = (result: StatisticsResult): Bar[] => [
  ...result.items.map((item) => ({ key: item.key, label: item.label, count: item.count, unspecified: false })),
  ...(mayBeUnspecified.has(result.groupBy)
    ? [{ key: 'unspecified', label: unspecifiedLabel, count: result.unknownCount, unspecified: true }]
    : [])
]

const axisStep = (max: number) => {
  const raw = Math.max(1, Math.ceil(max / 5))
  let magnitude = 1
  while (magnitude * 10 <= raw) {
    magnitude *= 10
  }
  return [1, 2, 5, 10].map((factor) => factor * magnitude).find((step) => step >= raw) ?? 10 * magnitude
}

const labelLines = (label: string) => {
  const lines: string[] = []
  for (const word of label.split(/\s+/).filter((part) => part.length > 0)) {
    const last = lines.at(-1)
    if (last !== undefined && last.length + word.length + 1 <= labelLineLength) {
      lines[lines.length - 1] = `${last} ${word}`
    } else {
      lines.push(word)
    }
  }
  if (lines.length <= maxLabelLines) {
    return lines
  }
  const kept = lines.slice(0, maxLabelLines)
  kept[maxLabelLines - 1] = `${kept[maxLabelLines - 1].slice(0, labelLineLength - 1)}…`
  return kept
}

const captionLines = (result: StatisticsResult) => [
  `Показатель: ${countTitles[result.kind].toLowerCase()} в группе; всего в выборке: ${result.total}; шкала начинается с нуля.`,
  ...(result.groupBy === 'PRODUCT'
    ? ['Строка с несколькими ИТ-продуктами учтена в каждом из них, поэтому сумма столбцов может быть больше итога.']
    : []),
  ...(mayBeUnspecified.has(result.groupBy)
    ? [`«${unspecifiedLabel}» (штриховка) — строки без значения группировки: ${result.unknownCount}; это не нулевое значение.`]
    : []),
  ...(result.groupBy === 'MONTH'
    ? [`${monthTitles[result.kind]} по московскому времени; месяцы периода без строк показаны нулём.`]
    : [])
]

type StatisticsChartProps = {
  result: StatisticsResult
}

export const StatisticsChart = ({ result }: StatisticsChartProps) => {
  const id = useId()
  const bars = chartBars(result)
  const rows = bars.reduce<{ bar: Bar; lines: string[]; top: number }[]>((placed, bar) => {
    const previous = placed.at(-1)
    const top = previous === undefined ? axisHeight : previous.top + previous.lines.length * labelLineHeight + barGap
    return [...placed, { bar, lines: labelLines(bar.label), top }]
  }, [])
  const lastRow = rows.at(-1)
  const max = bars.reduce((value, bar) => Math.max(value, bar.count), 0)
  const step = axisStep(max)
  const axisMax = Math.max(step, Math.ceil(max / step) * step)
  const ticks = Array.from({ length: axisMax / step + 1 }, (_, index) => index * step)
  const percent = (value: number) => value / axisMax * barAreaPercent
  const height = lastRow === undefined ? axisHeight + 40 : lastRow.top + lastRow.lines.length * labelLineHeight + barGap + 8
  const title = `${kindTitles[result.kind]} — ${groupingTitles[result.groupBy]}`
  const countTitle = countTitles[result.kind]
  const groupHeader = result.kind === 'EVENTS' && result.groupBy === 'MANAGER'
    ? 'Ответственный на момент события'
    : groupingHeaders[result.groupBy]

  return (
    <figure className="statistics-chart">
      <figcaption>
        <strong>{title}</strong>
        <ul className="reports__notes">
          {[...captionLines(result), ...result.notes].map((line) => <li key={line}>{line}</li>)}
        </ul>
      </figcaption>
      <svg
        className="statistics-chart__svg"
        width="100%"
        height={height}
        role="img"
        aria-labelledby={`${id}-title ${id}-desc`}
      >
        <title id={`${id}-title`}>{title}</title>
        <desc id={`${id}-desc`}>
          {`Горизонтальные столбцы, шкала от нуля до ${axisMax}. `}
          {bars.length === 0
            ? 'Нет строк, удовлетворяющих фильтрам.'
            : bars.map((bar) => `${bar.label}: ${bar.count}`).join('; ')}
        </desc>
        <defs>
          <pattern id={`${id}-hatch`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="6" height="6" className="statistics-chart__unspecified-fill" />
            <line x1="0" y1="0" x2="0" y2="6" className="statistics-chart__unspecified-line" />
          </pattern>
        </defs>
        <text x="0" y="14" className="statistics-chart__axis-title">{countTitle}, шкала от нуля</text>
        {ticks.map((tick, index) => (
          <g key={tick}>
            <line
              x1={`${percent(tick)}%`}
              x2={`${percent(tick)}%`}
              y1={axisHeight - 8}
              y2={height - 4}
              className={tick === 0 ? 'statistics-chart__zero' : 'statistics-chart__grid'}
            />
            <text
              x={`${percent(tick)}%`}
              y={axisHeight - 14}
              textAnchor={index === 0 ? 'start' : 'middle'}
              className="statistics-chart__tick"
            >
              {tick}
            </text>
          </g>
        ))}
        {bars.length === 0 && (
          <text x="8" y={axisHeight + 24} className="statistics-chart__tick">Нет строк, удовлетворяющих фильтрам</text>
        )}
        {rows.map(({ bar, lines, top }) => {
          const barTop = top + lines.length * labelLineHeight + 4
          return (
            <g key={bar.key}>
              <title>{`${bar.label}: ${bar.count}`}</title>
              <text x="4" y={top + 12} className={bar.unspecified ? 'statistics-chart__label statistics-chart__label--unspecified' : 'statistics-chart__label'}>
                {lines.map((line, index) => <tspan key={index} x="4" dy={index === 0 ? 0 : labelLineHeight}>{line}</tspan>)}
              </text>
              {bar.count > 0 && (
                <rect
                  x="0"
                  y={barTop}
                  width={`${percent(bar.count)}%`}
                  height={barHeight}
                  className={bar.unspecified ? 'statistics-chart__bar--unspecified' : 'statistics-chart__bar'}
                  fill={bar.unspecified ? `url(#${id}-hatch)` : undefined}
                />
              )}
              <text x={`${percent(bar.count)}%`} dx="6" y={barTop + 12} className="statistics-chart__value">{bar.count}</text>
            </g>
          )
        })}
      </svg>
      <div className="reports__table-scroll" role="region" aria-label="Таблица основания диаграммы" tabIndex={0}>
        <table>
          <caption>Таблица основания диаграммы</caption>
          <thead>
            <tr>
              <th scope="col">{groupHeader}</th>
              <th scope="col">{countTitle}</th>
            </tr>
          </thead>
          <tbody>
            {bars.map((bar) => (
              <tr key={bar.key}>
                <th scope="row" className={bar.unspecified ? 'statistics-chart__label--unspecified' : undefined}>{bar.label}</th>
                <td>{bar.count}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Всего строк в выборке</th>
              <td>{result.total}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </figure>
  )
}
