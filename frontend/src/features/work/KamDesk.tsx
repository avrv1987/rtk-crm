import { useCallback, useEffect, useRef, useState } from 'react'
import {
  apiClient,
  type InteractionIssue,
  type InteractionListParams,
  type PageInteraction,
  type ReminderDigest,
  type ReminderFeed,
  type WorkNews
} from '../../shared/api/client'
import { WorkNewsFeed } from './WorkNewsFeed'
import { readNoContactDays, saveNoContactDays } from './contactMark'
import { StatTiles, type StatTile } from '../../shared/ui/StatTiles'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { ReminderAgreements } from './ReminderCenter'
import { StepCompletion } from './StepCompletion'
import { isIssueOverdue, issueTitle, myIssueHref } from '../issues/issueModel'
import { type FeedGroupData, feedGroups, type PausedGroups, pausedGroups, shownOf } from './deadlines'
import { browserStorage, type FeedGroup, feedGroupLinks, feedGroupsFor, type KamTile } from './workView'
import { type AccessHandlers, daysLabel, formatDate, formatDateTime, handledAccessError, requestIdOf, todayIso } from './workShared'
import './workControl.css'

type KamDeskProps = AccessHandlers & {
  profileId: string
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
    counts: { licenses: number; signals: number | null }
    feed: ReminderFeed
    licenseYear: number
    agreements: ReminderDigest['expiringAgreements']
    agreementNoticeDays: number
    groups: Map<FeedGroup, FeedGroupData>
    issues: InteractionIssue[]
    issuesTotal: number
    paused: PausedGroups
    news: WorkNews | null
    noContact: PageInteraction
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

const total = async (query: InteractionListParams) => (await apiClient.listInteractions({ ...query, size: 1 })).total

const page = (due: InteractionListParams['due']) => apiClient.listInteractions({ due, size: 100, sort: 'nextActionAt,asc' })

export const KamDesk = ({ profileId, refreshKey, licenseActive, onChanged, onShowSignals, onLicenses, onSessionExpired, onProfileUnavailable }: KamDeskProps) => {
  const [state, setState] = useState<DeskState>({ kind: 'loading' })
  const [tile, setTile] = useState<KamTile | null>(null)
  const [expanded, setExpanded] = useState<ReadonlySet<FeedGroup>>(new Set())
  const [completing, setCompleting] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [noContactDays, setNoContactDays] = useState(() => readNoContactDays(browserStorage()))
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const [digest, overdue, todayPage, week, later, noStep, signals, issues, paused, news, noContact] = await Promise.all([
        apiClient.getReminders(),
        page('OVERDUE'),
        page('TODAY'),
        page('THIS_WEEK'),
        page('LATER'),
        page('NO_NEXT_STEP'),
        apiClient.listLmsSignals().then((result) => result.items.length, () => null),
        apiClient.listIssues({ responsible: profileId, size: 100 }),
        apiClient.listInteractions({ status: 'PAUSED', size: 100, sort: 'nextActionAt,asc' }),
        apiClient.getWorkNews().catch(() => null),
        apiClient.listInteractions({ noContactDays, size: 100, sort: 'updatedAt,asc' })
      ])
      const licenses = await total({ licenseExpiresBy: digest.licenseExpiresBy, status: 'ALL' })
      if (version !== requestVersion.current) {
        return
      }
      setState({
        kind: 'ready',
        counts: { licenses, signals },
        feed: digest.feed,
        licenseYear: digest.licenseExpiresBy,
        agreements: digest.expiringAgreements,
        agreementNoticeDays: digest.agreementNoticeDays,
        groups: feedGroups({ overdue, today: todayPage, week, later, noStep }, digest.feed, Date.now()),
        issues: issues.items,
        issuesTotal: issues.total,
        paused: pausedGroups(paused.items, Date.now()),
        news,
        noContact
      })
    } catch (error) {
      if (version !== requestVersion.current || handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        return
      }
      setState({ kind: 'failed', requestId: requestIdOf(error) })
    }
  }, [noContactDays, onProfileUnavailable, onSessionExpired, profileId])

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

  const today = todayIso()
  const tiles: StatTile<KamTile>[] = [
    { key: 'OVERDUE', label: 'Просрочено', value: state.feed.overdue, tone: 'danger' },
    { key: 'THIS_WEEK', label: 'На этой неделе', value: state.feed.thisWeek, tone: 'warning' },
    { key: 'NO_NEXT_STEP', label: 'Без следующего шага', value: state.feed.noNextStep, tone: 'warning' },
    { key: 'LICENSES', label: `Лицензии истекают до ${state.licenseYear} года`, value: state.counts.licenses, tone: 'warning' },
    { key: 'AGREEMENTS', label: `Договоры истекают за ${state.agreementNoticeDays} дней`, value: state.agreements.length, tone: 'warning' },
    { key: 'LMS', label: state.counts.signals === null ? 'Сигналы LMS недоступны' : 'Сигналы LMS', value: state.counts.signals, tone: 'warning' },
    {
      key: 'ISSUES',
      label: 'Мои проблемы и риски',
      value: state.issuesTotal,
      tone: state.issues.some((issue) => isIssueOverdue(issue, today)) ? 'danger' : 'warning'
    },
    { key: 'RESUME', label: 'Вернуться к работе', value: state.feed.resumeDue + state.feed.resumeNoDate, tone: 'warning' },
    { key: 'NO_CONTACT', label: `Нет контакта больше ${daysLabel(noContactDays)}`, value: state.noContact.total, tone: 'warning' },
    { key: 'NEWS', label: state.news === null ? 'Новое по вузам недоступно' : 'Новое по моим вузам', value: state.news?.unviewed ?? null, tone: 'warning' }
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

  const visibleGroups = feedGroupsFor(tile).filter((key) => state.groups.has(key))

  return (
    <>
      <StatTiles label="Сроки и лицензии" tiles={tiles} selected={selected} onSelect={selectTile} />

      <section className="desk-feed" aria-labelledby="desk-feed-title">
        <h2 id="desk-feed-title" className="desk-feed__heading">Задачи по срокам</h2>
        <p className="step-completion__done" role="status">{message}</p>
        {visibleGroups.length === 0 && feedGroupsFor(tile).length > 0 && (
          <p className="work-control__message">
            {tile === null ? 'Шагов со сроком нет. Задайте следующий шаг в карточке взаимодействия.' : 'По этой плитке задач нет.'}
          </p>
        )}
        {visibleGroups.map((key) => {
          const { items, total: groupTotal } = state.groups.get(key) ?? { items: [], total: 0 }
          const shown = expanded.has(key) ? items : items.slice(0, feedLimit)
          const shownText = shownOf(shown.length, groupTotal)
          return (
            <section key={key} className="desk-feed__group" aria-labelledby={`desk-feed-${key}`}>
              <h3 id={`desk-feed-${key}`} className={key === 'overdue' ? 'desk-feed__title--overdue' : undefined}>
                {groupTitles[key]} <span className="desk-feed__count">{groupTotal}</span>
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
                    {key === 'noStep' || !item.nextAction?.trim() ? (
                      <>
                        <span className={`status status--${key === 'overdue' ? 'overdue' : 'missing'}`}>{item.nextActionAt === null ? 'Срок не задан' : formatDateTime(item.nextActionAt)}</span>
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
              {(items.length > feedLimit || shownText !== null) && (
                <p className="desk-feed__more">
                  {shownText !== null && <span>{shownText}</span>}
                  {items.length > feedLimit && (
                    <button type="button" className="button--secondary" onClick={() => toggleGroup(key)}>
                      {expanded.has(key) ? 'Свернуть' : `Показать ещё ${items.length - feedLimit}`}
                    </button>
                  )}
                  <a href={feedGroupLinks[key]}>Показать все в списке</a>
                </p>
              )}
            </section>
          )
        })}
        {(tile === null || tile === 'ISSUES') && <MyIssues issues={state.issues} total={state.issuesTotal} profileId={profileId} today={today} />}
        {(tile === null || tile === 'NEWS') && state.news !== null && (
          <WorkNewsFeed
            news={state.news}
            onViewed={(news) => setState((current) => current.kind === 'ready' ? { ...current, news } : current)}
            onSessionExpired={onSessionExpired}
            onProfileUnavailable={onProfileUnavailable}
          />
        )}
        {(tile === null || tile === 'NO_CONTACT') && (
          <NoContactFeed
            page={state.noContact}
            days={noContactDays}
            onDaysChange={(days) => {
              saveNoContactDays(browserStorage(), days)
              setNoContactDays(days)
            }}
          />
        )}
        {(tile === null || tile === 'RESUME') && <PausedFeed paused={state.paused} feed={state.feed} />}
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

const MyIssues = ({ issues, total, profileId, today }: { issues: InteractionIssue[]; total: number; profileId: string; today: string }) => {
  if (issues.length === 0) {
    return null
  }
  const shown = shownOf(Math.min(issues.length, feedLimit), total)
  return (
    <section className="desk-feed__group" aria-labelledby="desk-feed-issues">
      <h3 id="desk-feed-issues">Мои проблемы и риски <span className="desk-feed__count">{total}</span></h3>
      <ul className="desk-feed__list">
        {issues.slice(0, feedLimit).map((issue) => (
          <li key={issue.id} className="desk-feed__item">
            <div className="desk-feed__step">
              <strong><a href={myIssueHref(issue, profileId)}>{issue.description}</a></strong>
              <span>{issueTitle(issue)} · {issue.organizationName} · {issue.interactionTitle}</span>
            </div>
            <span className={`status status--${isIssueOverdue(issue, today) ? 'overdue' : 'planned'}`}>
              {issue.dueOn === null ? 'Срок не указан' : `${isIssueOverdue(issue, today) ? 'Просрочено: ' : 'До '}${formatDate(issue.dueOn)}`}
            </span>
          </li>
        ))}
      </ul>
      <p className="desk-feed__more">
        {shown !== null && <span>{shown}</span>}
        <a href={`#/issues?${new URLSearchParams({ responsible: profileId }).toString()}`}>Все мои проблемы и риски в реестре</a>
      </p>
    </section>
  )
}

const noContactDayOptions = [14, 30, 45, 60, 90]

// Порог N выбирает сам КАМ (по умолчанию 30); «последний контакт» — отметка «был контакт» или дата из данных
// взаимодействия, у нового взаимодействия без контакта срок идёт от создания.
const NoContactFeed = ({ page, days, onDaysChange }: { page: PageInteraction; days: number; onDaysChange: (days: number) => void }) => {
  const options = noContactDayOptions.includes(days) ? noContactDayOptions : [...noContactDayOptions, days].sort((a, b) => a - b)
  return (
    <section className="desk-feed__group" aria-labelledby="desk-feed-no-contact">
      <h3 id="desk-feed-no-contact">Нет контакта с вузом <span className="desk-feed__count">{page.total}</span></h3>
      <label className="desk-feed__threshold">
        Дольше
        <select value={days} onChange={(event) => onDaysChange(Number(event.target.value))}>
          {options.map((value) => <option key={value} value={value}>{daysLabel(value)}</option>)}
        </select>
      </label>
      {page.items.length === 0 ? (
        <p className="work-control__message">Со всеми вузами был контакт за {daysLabel(days)}.</p>
      ) : (
        <ul className="desk-feed__list">
          {page.items.slice(0, feedLimit).map((item) => (
            <li key={item.id} className="desk-feed__item">
              <div className="desk-feed__step">
                <strong><a href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a></strong>
                <span><a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a> · {item.currentStageName}</span>
              </div>
              <span className="status status--missing">
                {item.lastContactAt === null ? 'Контакт не записан' : `Последний контакт ${formatDate(todayIso(new Date(item.lastContactAt)))}`}
              </span>
            </li>
          ))}
        </ul>
      )}
      {page.total > 0 && (
        <p className="desk-feed__more">
          {shownOf(Math.min(page.items.length, feedLimit), page.total) !== null && <span>{shownOf(Math.min(page.items.length, feedLimit), page.total)}</span>}
          <a href={`#/work?noContactDays=${days}`}>Показать все в списке</a>
        </p>
      )}
    </section>
  )
}

// Строки приостановленных — из одного запроса до 100 штук, итоги — серверные (ReminderDigest.feed).
const pausedSections = (paused: PausedGroups, feed: ReminderFeed) => [
  { key: 'due', title: 'Вернуться к работе', items: paused.due, total: Math.max(feed.resumeDue, paused.due.length), action: 'Возобновить' },
  { key: 'noDate', title: 'Без даты возврата', items: paused.noDate, total: Math.max(feed.resumeNoDate, paused.noDate.length), action: 'Открыть' }
].filter((section) => section.total > 0)

const PausedFeed = ({ paused, feed }: { paused: PausedGroups; feed: ReminderFeed }) => (
  <>
    {pausedSections(paused, feed).map((section) => (
      <section key={section.key} className="desk-feed__group" aria-labelledby={`desk-feed-paused-${section.key}`}>
        <h3 id={`desk-feed-paused-${section.key}`}>{section.title} <span className="desk-feed__count">{section.total}</span></h3>
        <ul className="desk-feed__list">
          {section.items.map((item) => (
            <li key={item.id} className="desk-feed__item">
              <div className="desk-feed__step">
                <strong>Приостановлено: {item.marks.statusReason?.trim() || 'причина не указана'}</strong>
                <span>
                  <a href={`#/organizations/${item.organizationId}`}>{item.organizationName}</a>
                  {' · '}
                  <a href={`#/organizations/${item.organizationId}/${item.id}`}>{item.title}</a>
                </span>
              </div>
              <span className={`status status--${section.key === 'due' ? 'overdue' : 'missing'}`}>
                {item.nextActionAt === null ? 'Дата не задана' : `Вернуться ${formatDateTime(item.nextActionAt)}`}
              </span>
              <a className="button-link desk-feed__action" href={`#/organizations/${item.organizationId}/${item.id}`}>{section.action}</a>
            </li>
          ))}
        </ul>
        {shownOf(section.items.length, section.total) !== null && (
          <p className="desk-feed__more">
            <span>{shownOf(section.items.length, section.total)}</span>
            <a href="#/work?status=PAUSED">Показать все в списке</a>
          </p>
        )}
      </section>
    ))}
  </>
)
