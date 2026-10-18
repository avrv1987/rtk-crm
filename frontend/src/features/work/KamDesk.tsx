import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type InteractionListItem, type InteractionListParams, type ReminderDigest } from '../../shared/api/client'
import { StatTiles, type StatTile } from '../../shared/ui/StatTiles'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { ReminderAgreements } from './ReminderCenter'
import { StepCompletion } from './StepCompletion'
import { groupFeed } from './deadlines'
import { type FeedGroup, feedGroupsFor, type KamTile } from './workView'
import { type AccessHandlers, formatDateTime, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type KamDeskProps = AccessHandlers & {
  refreshKey: number
  licenseActive: boolean
  onChanged: () => void
  onShowSignals: () => void
  onLicenses: (year: number | null) => void
}

type DeskState =
  | { kind: 'loading' }
  | {
    kind: 'ready'
    counts: { overdue: number; week: number; noStep: number; licenses: number; signals: number | null }
    licenseYear: number
    agreements: ReminderDigest['expiringAgreements']
    agreementNoticeDays: number
    dated: InteractionListItem[]
    noStep: InteractionListItem[]
  }
  | { kind: 'failed'; requestId?: string }

const feedLimit = 5

const groupTitles: Record<FeedGroup, string> = {
  overdue: 'Просрочено',
  today: 'Сегодня',
  week: 'На этой неделе',
  later: 'Позже',
  noStep: 'Без следующего шага'
}

const groupLinks: Record<FeedGroup, string> = {
  overdue: '#/work?due=OVERDUE',
  today: '#/work?due=THIS_WEEK',
  week: '#/work?due=THIS_WEEK',
  later: '#/work',
  noStep: '#/work?due=NO_NEXT_STEP'
}

const total = async (query: InteractionListParams) => (await apiClient.listInteractions({ ...query, size: 1 })).total

export const KamDesk = ({ refreshKey, licenseActive, onChanged, onShowSignals, onLicenses, onSessionExpired, onProfileUnavailable }: KamDeskProps) => {
  const [state, setState] = useState<DeskState>({ kind: 'loading' })
  const [tile, setTile] = useState<KamTile | null>(null)
  const [expanded, setExpanded] = useState<ReadonlySet<FeedGroup>>(new Set())
  const [completing, setCompleting] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const [digest, overdue, week, dated, noStep, signals] = await Promise.all([
        apiClient.getReminders(),
        total({ due: 'OVERDUE' }),
        total({ due: 'THIS_WEEK' }),
        apiClient.listInteractions({ size: 100, sort: 'nextActionAt,asc' }),
        apiClient.listInteractions({ due: 'NO_NEXT_STEP', size: 100, sort: 'nextActionAt,asc' }),
        apiClient.listLmsSignals().then((result) => result.items.length, () => null)
      ])
      const licenses = await total({ licenseExpiresBy: digest.licenseExpiresBy, status: 'ALL' })
      if (version !== requestVersion.current) {
        return
      }
      setState({
        kind: 'ready',
        counts: { overdue, week, noStep: noStep.total, licenses, signals },
        licenseYear: digest.licenseExpiresBy,
        agreements: digest.expiringAgreements,
        agreementNoticeDays: digest.agreementNoticeDays,
        dated: dated.items,
        noStep: noStep.items
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
    return <p className="work-control__message" role="status">Собираем задачи по срокам…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="notice notice--error" role="alert">
        <p>Не удалось собрать задачи по срокам. Список взаимодействий ниже доступен.</p>
        <SupportDetails requestId={state.requestId} />
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const tiles: StatTile<KamTile>[] = [
    { key: 'OVERDUE', label: 'Просрочено', value: state.counts.overdue, tone: 'danger' },
    { key: 'THIS_WEEK', label: 'На этой неделе', value: state.counts.week, tone: 'warning' },
    { key: 'NO_NEXT_STEP', label: 'Без следующего шага', value: state.counts.noStep, tone: 'warning' },
    { key: 'LICENSES', label: `Лицензии истекают до ${state.licenseYear} года`, value: state.counts.licenses, tone: 'warning' },
    { key: 'AGREEMENTS', label: `Договоры истекают за ${state.agreementNoticeDays} дней`, value: state.agreements.length, tone: 'warning' },
    { key: 'LMS', label: state.counts.signals === null ? 'Сигналы LMS недоступны' : 'Сигналы LMS', value: state.counts.signals ?? 0, tone: 'warning' }
  ]
  const selected: KamTile | null = tile ?? (licenseActive ? 'LICENSES' : null)

  const selectTile = (key: KamTile | null) => {
    if (key === 'LMS') {
      onShowSignals()
      return
    }
    if (key === 'LICENSES' || (key === null && selected === 'LICENSES')) {
      setTile(null)
      onLicenses(key === null ? null : state.licenseYear)
      return
    }
    setTile(key)
  }

  const toggleGroup = (key: FeedGroup) => setExpanded((current) => {
    const next = new Set(current)
    if (!next.delete(key)) {
      next.add(key)
    }
    return next
  })

  const grouped = groupFeed(state.dated, state.noStep, Date.now())
  const visibleGroups = feedGroupsFor(tile).filter((key) => (grouped.get(key)?.length ?? 0) > 0)

  return (
    <>
      <StatTiles label="Сроки и лицензии" tiles={tiles} selected={selected} onSelect={selectTile} />

      <section className="desk-feed" aria-labelledby="desk-feed-title">
        <h2 id="desk-feed-title" className="desk-feed__heading">Задачи по срокам</h2>
        <p className="step-completion__done" role="status">{message}</p>
        {visibleGroups.length === 0 && tile !== 'AGREEMENTS' && (
          <p className="work-control__message">
            {tile === null ? 'Шагов со сроком нет. Задайте следующий шаг в карточке взаимодействия.' : 'По этой плитке задач нет.'}
          </p>
        )}
        {visibleGroups.map((key) => {
          const items = grouped.get(key) ?? []
          const shown = expanded.has(key) ? items : items.slice(0, feedLimit)
          return (
            <section key={key} className="desk-feed__group" aria-labelledby={`desk-feed-${key}`}>
              <h3 id={`desk-feed-${key}`} className={key === 'overdue' ? 'desk-feed__title--overdue' : undefined}>
                {groupTitles[key]} <span className="desk-feed__count">{items.length}</span>
              </h3>
              <ul className="desk-feed__list">
                {shown.map((item) => (
                  <li key={item.id} className="desk-feed__item">
                    <div className="desk-feed__step">
                      <strong>{item.nextAction?.trim() || 'Шаг не задан'}</strong>
                      <span>
                        <a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                        {' · '}
                        <a href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
                        {' · '}
                        {item.currentStageName}
                      </span>
                    </div>
                    {key === 'noStep' ? (
                      <>
                        <span className="status status--missing">{item.nextActionAt === null ? 'Срок не задан' : formatDateTime(item.nextActionAt)}</span>
                        <a className="button-link desk-feed__action" href={`#/organizations/${item.organizationId}/${item.id}`}>Задать шаг</a>
                      </>
                    ) : (
                      <>
                        <span className={`status status--${key === 'overdue' ? 'overdue' : 'planned'}`}>{formatDateTime(item.nextActionAt ?? '')}</span>
                        {completing !== item.id && (
                          <button type="button" className="button--secondary" onClick={() => {
                            setMessage('')
                            setCompleting(item.id)
                          }}>
                            Шаг выполнен
                          </button>
                        )}
                      </>
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
              {items.length > feedLimit && (
                <p className="desk-feed__more">
                  <button type="button" className="button--secondary" onClick={() => toggleGroup(key)}>
                    {expanded.has(key) ? 'Свернуть' : `Показать ещё ${items.length - feedLimit}`}
                  </button>
                  <a href={groupLinks[key]}>Показать все в списке</a>
                </p>
              )}
            </section>
          )
        })}
        {(tile === null || tile === 'AGREEMENTS') && state.agreements.length > 0 && (
          <section className="desk-feed__group" aria-labelledby="desk-feed-agreements">
            <h3 id="desk-feed-agreements">
              Договоры истекают <span className="desk-feed__count">{state.agreements.length}</span>
            </h3>
            <ReminderAgreements agreements={state.agreements} />
          </section>
        )}
      </section>
    </>
  )
}
