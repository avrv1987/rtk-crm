import { useId, useState } from 'react'
import type { Interaction, InteractionEvent, InteractionStage } from '../../shared/api/client'
import { formatCompletionDate } from './StageCompletionPanel'
import { formatMoscowDateTime } from './moscowTime'
import { routeWindow } from './workCardModel'

export type StagePathStatus = 'passed' | 'current' | 'completed' | 'skipped' | 'future'

export type StagePathItem = {
  stage: InteractionStage
  status: StagePathStatus
  enteredAt?: string
  completedOn?: string
}

const dayMilliseconds = 24 * 60 * 60 * 1000

export const daysLabel = (days: number) => {
  const lastTwo = days % 100
  const last = days % 10
  if (last === 1 && lastTwo !== 11) {
    return `${days} день`
  }
  if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
    return `${days} дня`
  }
  return `${days} дней`
}

export const daysSince = (value: string) => Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / dayMilliseconds))

export const stagePath = (interaction: Interaction, events: InteractionEvent[] | undefined): StagePathItem[] => {
  const enteredAt = new Map<InteractionStage['id'], string>()
  events?.forEach((event) => {
    if ((event.type === 'CREATED' || event.type === 'TRANSITIONED') && event.toStageId) {
      enteredAt.set(event.toStageId, event.occurredAt)
    }
  })
  const completedOn = new Map(interaction.stageCompletions.map((completion) => [completion.stageId, completion.completedOn]))
  const currentOrder = interaction.stages.find((stage) => stage.id === interaction.currentStageId)?.order ?? 0
  return interaction.stages.map((stage) => {
    const entered = enteredAt.get(stage.id)
    const completed = completedOn.get(stage.id)
    if (stage.id === interaction.currentStageId) {
      return { stage, status: 'current', enteredAt: entered, completedOn: completed }
    }
    if (completed !== undefined) {
      return { stage, status: 'completed', enteredAt: entered, completedOn: completed }
    }
    if (entered !== undefined) {
      return { stage, status: 'passed', enteredAt: entered }
    }
    return { stage, status: events !== undefined && stage.order < currentOrder ? 'skipped' : 'future' }
  })
}

export const stagePathLabel = (item: StagePathItem) => {
  if (item.status === 'current') {
    const completedNote = item.completedOn === undefined ? '' : `; отмечен выполненным ${formatCompletionDate(item.completedOn)}`
    if (item.enteredAt === undefined) {
      return `Текущий этап${completedNote}`
    }
    return `Текущий этап: ${daysLabel(daysSince(item.enteredAt))} на этапе${completedNote}`
  }
  if (item.status === 'completed' && item.completedOn !== undefined) {
    return `Выполнен ${formatCompletionDate(item.completedOn)}`
  }
  if (item.status === 'passed') {
    return item.enteredAt === undefined ? 'Пройден' : `Пройден; вход ${formatMoscowDateTime(item.enteredAt)}`
  }
  return item.status === 'skipped' ? 'Пропущен' : 'Впереди'
}

type RouteDisclosureProps = {
  interaction: Interaction
  items: StagePathItem[]
  canEdit: boolean
  onCompleteStage: () => void
  onEditStages: () => void
  onReturnToStage?: () => void
}

export const RouteDisclosure = ({ interaction, items, canEdit, onCompleteStage, onEditStages, onReturnToStage }: RouteDisclosureProps) => {
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const currentIndex = items.findIndex((item) => item.status === 'current')
  const visible = routeWindow(items.length, currentIndex)
  const stageNames = new Map(interaction.stages.map((stage) => [stage.id, stage.name]))
  const fromCurrent = interaction.transitions.filter((transition) => transition.fromStageId === interaction.currentStageId)
  const route = interaction.route

  return (
    <section className="work-route" aria-labelledby="work-route-title">
      <div className="work-route__summary">
        <h4 id="work-route-title" className="work-route__title">
          Маршрут{interaction.kind ? <span className="work-route__kind"> · {interaction.kind.name}</span> : null}
        </h4>

        <div className="work-route__bar" aria-hidden="true">
          {items.map((item) => (
            <span key={item.stage.id} className={`work-route__segment work-route__segment--${item.status}`} title={item.stage.name} />
          ))}
        </div>
        <ol className="work-route__window" aria-label="Текущий этап и соседние">
          {visible.hiddenBefore > 0 && <li className="work-route__more">ещё {visible.hiddenBefore}</li>}
          {items.slice(visible.from, visible.to + 1).map((item, offset) => (
            <li
              key={item.stage.id}
              className={`work-route__stage work-route__stage--${item.status}`}
              aria-current={item.status === 'current' ? 'step' : undefined}
              title={stagePathLabel(item)}
            >
              {visible.from + offset + 1}. {item.stage.name}
            </li>
          ))}
          {visible.hiddenAfter > 0 && <li className="work-route__more">ещё {visible.hiddenAfter}</li>}
        </ol>
        <button
          type="button"
          className="button--secondary work-route__toggle"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? 'Свернуть маршрут ▴' : 'Весь маршрут ▾'}
        </button>
      </div>
      <div id={panelId} className="work-route__panel" hidden={!open}>
        {route && (
          <p className="work-card__route-version">
            По шаблону «{route.templateName}», версия {route.version}
            {route.latestVersion > route.version ? ` (есть версия ${route.latestVersion}; обновляет руководитель)` : ''}
          </p>
        )}
        <ol className="interaction-path" aria-label="Все этапы маршрута">
          {items.map((item, index) => (
            <li
              key={item.stage.id}
              className={`interaction-path__stage interaction-path__stage--${item.status}`}
              aria-current={item.status === 'current' ? 'step' : undefined}
            >
              <span className="interaction-path__number" aria-hidden="true">{index + 1}</span>
              <span className="interaction-path__name">{item.stage.name}</span>
              <span className="interaction-path__status">{stagePathLabel(item)}</span>
              {item.stage.optional && <span className="interaction-path__optional">Необязательный</span>}
            </li>
          ))}
        </ol>
        <div className="interaction-stage-transitions">
          <h6>Переходы из текущего этапа</h6>
          {fromCurrent.length === 0 ? (
            <p>Из текущего этапа переходы не заданы.</p>
          ) : (
            <ul>
              {fromCurrent.map((transition) => (
                <li key={transition.toStageId}>
                  → {stageNames.get(transition.toStageId) ?? 'Недоступный этап'}
                  {transition.commentRequired && <span>Комментарий обязателен</span>}
                </li>
              ))}
            </ul>
          )}
          <details className="work-card__rules">
            <summary>Все правила переходов ({interaction.transitions.length})</summary>
            {interaction.transitions.length === 0 ? (
              <p>Для этого взаимодействия переходы не заданы.</p>
            ) : (
              <ul>
                {interaction.transitions.map((transition) => (
                  <li key={`${transition.fromStageId}:${transition.toStageId}`}>
                    {stageNames.get(transition.fromStageId) ?? 'Недоступный этап'} → {stageNames.get(transition.toStageId) ?? 'Недоступный этап'}
                    {transition.commentRequired && <span>Комментарий обязателен</span>}
                  </li>
                ))}
              </ul>
            )}
          </details>
        </div>
        {canEdit && (
          <div className="work-card__inline-actions">
            <button
              type="button"
              className="button--secondary"
              title="Этап сделан, но взаимодействие остаётся на текущем этапе: для работ, которые идут параллельно."
              onClick={onCompleteStage}
            >
              Отметить этап выполненным
            </button>
            {onReturnToStage !== undefined && (
              <button type="button" className="button--secondary" onClick={onReturnToStage}>Вернуться на этап…</button>
            )}
            <button type="button" className="button--secondary" onClick={onEditStages}>Изменить этапы</button>
          </div>
        )}
      </div>
    </section>
  )
}
