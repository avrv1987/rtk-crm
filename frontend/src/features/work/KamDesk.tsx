import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type InteractionListItem, type InteractionListParams, type LmsSignal } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { type DeadlineGroup, deadlineGroup } from './deadlines'
import { LmsSignalList, lmsSignalsSectionId } from './LmsSignals'
import { StepCompletion } from './StepCompletion'
import { type AccessHandlers, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type KamDeskProps = AccessHandlers & {
  activeQuery: string
  refreshKey: number
  onChanged: () => void
}

type Tile = { key: string; label: string; href: string; count: number | null; tone: 'danger' | 'warning' | 'plain' }

type DeskState =
  | { kind: 'loading' }
  | { kind: 'ready'; tiles: Tile[]; feed: InteractionListItem[]; signals: LmsSignal[] | null }
  | { kind: 'failed'; requestId?: string }

const feedLimit = 5

const groups: { key: DeadlineGroup; title: string; href: string }[] = [
  { key: 'overdue', title: 'Просрочено', href: '#/work?due=OVERDUE' },
  { key: 'today', title: 'Сегодня', href: '#/work?due=THIS_WEEK' },
  { key: 'week', title: 'На этой неделе', href: '#/work?due=THIS_WEEK' },
  { key: 'later', title: 'Позже', href: '#/work' }
]

const total = async (query: InteractionListParams) => (await apiClient.listInteractions({ ...query, size: 1 })).total

export const KamDesk = ({ activeQuery, refreshKey, onChanged, onSessionExpired, onProfileUnavailable }: KamDeskProps) => {
  const [state, setState] = useState<DeskState>({ kind: 'loading' })
  const [completing, setCompleting] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const [digest, overdue, week, noStep, feed, signals] = await Promise.all([
        apiClient.getReminders(),
        total({ due: 'OVERDUE' }),
        total({ due: 'THIS_WEEK' }),
        total({ due: 'NO_NEXT_STEP' }),
        apiClient.listInteractions({ size: 100, sort: 'nextActionAt,asc' }),
        apiClient.listLmsSignals().then((result) => result.items, () => null)
      ])
      const licenses = await total({ licenseExpiresBy: digest.licenseExpiresBy, status: 'ALL' })
      if (version !== requestVersion.current) {
        return
      }
      setState({
        kind: 'ready',
        tiles: [
          { key: 'due=OVERDUE', label: 'Просрочено', href: '#/work?due=OVERDUE', count: overdue, tone: 'danger' },
          { key: 'due=THIS_WEEK', label: 'Шаги на этой неделе', href: '#/work?due=THIS_WEEK', count: week, tone: 'plain' },
          { key: 'due=NO_NEXT_STEP', label: 'Без следующего шага', href: '#/work?due=NO_NEXT_STEP', count: noStep, tone: 'warning' },
          {
            key: `license=${digest.licenseExpiresBy}&status=ALL`,
            label: `Лицензии истекают до ${digest.licenseExpiresBy} года`,
            href: `#/work?license=${digest.licenseExpiresBy}&status=ALL`,
            count: licenses,
            tone: 'warning'
          }
        ],
        feed: feed.items.filter((item) => item.nextActionAt !== null),
        signals
      })
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void load()
    return () => {
      requestVersion.current += 1
    }
  }, [load, refreshKey])

  if (state.kind === 'loading') {
    return <p className="work-control__message" role="status">Собираем рабочий стол…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="notice notice--error" role="alert">
        <p>Не удалось собрать рабочий стол. Список работ ниже доступен.</p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const now = Date.now()
  const grouped = new Map<DeadlineGroup, InteractionListItem[]>()
  for (const item of state.feed) {
    const key = deadlineGroup(item.nextActionAt ?? '', now)
    grouped.set(key, [...(grouped.get(key) ?? []), item])
  }

  return (
    <>
      <ul className="desk-tiles" aria-label="Сроки и лицензии">
        {state.tiles.map((tile) => (
          <li key={tile.key}>
            <a
              className={`desk-tile${tile.count ? ` desk-tile--${tile.tone}` : ''}`}
              href={tile.href}
              aria-current={activeQuery === tile.key ? 'true' : undefined}
            >
              <span className="desk-tile__value">{tile.count}</span>
              <span className="desk-tile__label">{tile.label}</span>
            </a>
          </li>
        ))}
        <li>
          <button
            type="button"
            className={`desk-tile${state.signals?.length ? ' desk-tile--warning' : ''}`}
            onClick={() => document.getElementById(lmsSignalsSectionId)?.scrollIntoView({ block: 'start' })}
          >
            <span className="desk-tile__value">{state.signals === null ? '—' : state.signals.length}</span>
            <span className="desk-tile__label">{state.signals === null ? 'Сигналы LMS недоступны' : 'Сигналы LMS'}</span>
          </button>
        </li>
      </ul>

      <section className="desk-feed" aria-labelledby="desk-feed-title">
        <div className="team-indicators__header">
          <h2 id="desk-feed-title">Задачи по срокам</h2>
          <button type="button" className="button--secondary" onClick={() => {
            const heading = document.getElementById('work-results-title')
            heading?.scrollIntoView({ block: 'start' })
            heading?.focus()
          }}>
            Все работы списком
          </button>
        </div>
        <p className="step-completion__done" role="status">{message}</p>
        {state.feed.length === 0 && <p className="work-control__message">Шагов со сроком нет. Задайте следующий шаг в карточке работы.</p>}
        {state.signals !== null && state.signals.length > 0 && (
          <section id={lmsSignalsSectionId} className="desk-feed__group" aria-labelledby="desk-feed-lms">
            <h3 id="desk-feed-lms">Сигналы LMS <span className="desk-feed__count">{state.signals.length}</span></h3>
            <LmsSignalList signals={state.signals} limit={feedLimit} />
          </section>
        )}
        {groups.map((group) => {
          const items = grouped.get(group.key) ?? []
          if (items.length === 0) {
            return null
          }
          return (
            <section key={group.key} className="desk-feed__group" aria-labelledby={`desk-feed-${group.key}`}>
              <h3 id={`desk-feed-${group.key}`} className={group.key === 'overdue' ? 'desk-feed__title--overdue' : undefined}>
                {group.title} <span className="desk-feed__count">{items.length}</span>
              </h3>
              <ul className="desk-feed__list">
                {items.slice(0, feedLimit).map((item) => (
                  <li key={item.id} className="desk-feed__item">
                    <div className="desk-feed__step">
                      <strong>{item.nextAction?.trim() || 'Шаг не задан'}</strong>
                      <span>
                        <a href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
                        {' · '}
                        <a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                        {' · '}
                        {item.currentStageName}
                      </span>
                    </div>
                    <span className={`status status--${group.key === 'overdue' ? 'overdue' : 'planned'}`}>
                      {formatDateTime(item.nextActionAt ?? '')}
                    </span>
                    {completing !== item.id && (
                      <button type="button" className="button--secondary" onClick={() => {
                        setMessage('')
                        setCompleting(item.id)
                      }}>
                        Шаг выполнен
                      </button>
                    )}
                    {completing === item.id && (
                      <div className="desk-feed__completion">
                        <StepCompletion
                          interaction={item}
                          startOpen
                          onCancel={() => setCompleting(null)}
                          onCompleted={() => {
                            setCompleting(null)
                            setMessage(`Шаг «${item.nextAction?.trim() || item.title}» отмечен выполненным и записан в историю.`)
                            void load()
                            onChanged()
                          }}
                          onReload={() => {
                            setCompleting(null)
                            void load()
                          }}
                          onSessionExpired={onSessionExpired}
                          onProfileUnavailable={onProfileUnavailable}
                        />
                      </div>
                    )}
                  </li>
                ))}
              </ul>
              {items.length > feedLimit && <a href={group.href}>Показать все в списке</a>}
            </section>
          )
        })}
      </section>
    </>
  )
}
