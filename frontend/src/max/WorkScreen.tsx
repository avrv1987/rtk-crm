import { useEffect, useRef, useState } from 'react'
import type { FeedGroup } from '../features/work/workView'
import { errorText, isAbort, type MaxApi, type MaxAppWork, type Problem } from './maxApi'
import { firstNonEmpty, groupOf, groupOfDue, groups, todayText, totalsOf } from './model'
import type { Bridge } from './startup'
import { Banner, Empty, OpenCrm, SkeletonRows, WorkRow, type WorkItem } from './ui'
import { useLoad } from './useLoad'

type Feed = MaxAppWork['feed']

/**
 * «Моя работа»: пять постоянных плиток-счётчиков (3 + 2, без прокрутки вбок) и список выбранной группы.
 * Первый запрос — без due: сервер сам открывает первую непустую группу, и первый экран не бывает ложно пустым.
 */
export const WorkScreen = ({ api, bridge, showTitle, refreshKey, onFatal, onOpenCard, onForm }: {
  api: MaxApi
  bridge: Bridge | undefined
  showTitle: boolean
  refreshKey: number
  onFatal: (problem: Problem) => void
  onOpenCard: (id: string) => void
  onForm: (screen: 'step' | 'comment', id: string) => void
}) => {
  // requested — что выбрал пользователь; null — «пусть выберет сервер» (только первый запрос).
  const [requested, setRequested] = useState<FeedGroup | null>(null)
  const [feed, setFeed] = useState<Feed | null>(null)
  const [more, setMore] = useState<{ items: WorkItem[]; busy: boolean; error: string | null }>({ items: [], busy: false, error: null })
  const moreAbort = useRef<AbortController | null>(null)
  const work = useLoad(requested ?? 'auto', (signal) => api.work(requested === null ? null : groupOf(requested).due, 0, signal), onFatal)

  const shown: FeedGroup | null = requested ?? (work.status === 'ready' ? groupOfDue(work.data.due) : null)

  useEffect(() => {
    if (work.status === 'ready') {
      setFeed(work.data.feed)
    }
  }, [work.status, work.data])

  // Новая группа или новый ответ — подгруженные «ещё» страницы относятся к старому списку.
  useEffect(() => {
    moreAbort.current?.abort()
    setMore({ items: [], busy: false, error: null })
  }, [work.data, requested])

  // После сохранения формы перечитываем ту же группу, которую видит пользователь, а не выбираем заново.
  const lastRefresh = useRef(refreshKey)
  useEffect(() => {
    if (refreshKey === lastRefresh.current) {
      return
    }
    lastRefresh.current = refreshKey
    if (requested === null && shown !== null) {
      setRequested(shown)
    } else {
      work.reload()
    }
  }, [refreshKey]) // eslint-disable-line react-hooks/exhaustive-deps

  const totals = feed === null ? null : totalsOf(feed)
  const now = Date.now()

  const loadMore = async (page: WorkItem[], total: number, size: number) => {
    if (shown === null) {
      return
    }
    const controller = new AbortController()
    moreAbort.current = controller
    setMore((current) => ({ ...current, busy: true, error: null }))
    try {
      const next = await api.work(groupOf(shown).due, Math.floor((page.length + more.items.length) / size), controller.signal)
      if (!controller.signal.aborted) {
        setFeed(next.feed)
        setMore((current) => ({ items: [...current.items, ...next.items.items].slice(0, total), busy: false, error: null }))
      }
    } catch (error) {
      if (!controller.signal.aborted && !isAbort(error)) {
        setMore((current) => ({ ...current, busy: false, error: errorText(error) }))
      }
    }
  }

  return (
    <section className="mx-screen" aria-labelledby="mx-work-title">
      <div className="mx-content">
        <h1 id="mx-work-title" className={showTitle ? 'mx-title' : 'mx-visually-hidden'}>Моя работа</h1>
        <p className="mx-sub">{todayText(now)}</p>
        {totals === null
          ? (
            <div className="mx-tiles" aria-hidden="true">
              {groups.map((group) => <div key={group.key} className="mx-tile mx-tile--skeleton"><span className="mx-sk mx-sk--num" /><span className="mx-sk mx-sk--w60" /></div>)}
            </div>
          )
          : (
            <div className="mx-tiles" role="group" aria-label="Срок следующего шага">
              {groups.map((group) => {
                const count = totals[group.key]
                const classes = ['mx-tile', count === 0 ? 'mx-tile--zero' : '', group.key === 'overdue' && count > 0 ? 'mx-tile--hot' : '']
                return (
                  <button key={group.key} type="button" className={classes.filter(Boolean).join(' ')} aria-pressed={shown === group.key}
                    data-group={group.key} onClick={() => setRequested(group.key)}>
                    <span className="mx-tile__count">{count}</span>
                    <span className="mx-tile__label">{group.tile}</span>
                  </button>
                )
              })}
            </div>
          )}
        {feed !== null && feed.resumeDue > 0 && (
          <Banner tone="warn">
            <b>Пора возобновить.</b> Приостановленных с наступившей датой возврата: {feed.resumeDue}. Вернуть их в работу можно в CRM.
          </Banner>
        )}
        <WorkList work={work} totals={totals} shown={shown} now={now} more={more} bridge={bridge}
          onRetry={work.reload} onGroup={setRequested} onOpenCard={onOpenCard} onForm={onForm}
          onMore={(page, total, size) => void loadMore(page, total, size)} />
      </div>
    </section>
  )
}

const WorkList = ({ work, totals, shown, now, more, bridge, onRetry, onGroup, onOpenCard, onForm, onMore }: {
  work: ReturnType<typeof useLoad<MaxAppWork>>
  totals: Record<FeedGroup, number> | null
  shown: FeedGroup | null
  now: number
  more: { items: WorkItem[]; busy: boolean; error: string | null }
  bridge: Bridge | undefined
  onRetry: () => void
  onGroup: (group: FeedGroup) => void
  onOpenCard: (id: string) => void
  onForm: (screen: 'step' | 'comment', id: string) => void
  onMore: (page: WorkItem[], total: number, size: number) => void
}) => {
  if (work.status === 'loading' || totals === null || shown === null) {
    return work.status === 'error'
      ? <Banner tone="error" action="Повторить" onAction={onRetry}>{errorText(work.error)}</Banner>
      : <SkeletonRows />
  }
  if (work.status === 'error') {
    return <Banner tone="error" action="Повторить" onAction={onRetry}>{errorText(work.error)}</Banner>
  }
  const first = firstNonEmpty(totals)
  if (first === null) {
    return (
      <Empty title="Всё сделано" text="Шагов со сроком нет. Новые появятся, когда вы или руководитель зададите шаг в карточке.">
        <OpenCrm bridge={bridge} hash="#/work" variant="secondary">Открыть CRM ↗</OpenCrm>
      </Empty>
    )
  }
  const page = work.data.items
  const items = [...page.items, ...more.items]
  const group = groupOf(shown)
  const urgent = totals.overdue + totals.today + totals.week + totals.noStep
  return (
    <>
      {page.total === 0
        ? (
          <Empty title={group.empty[0]} text={group.empty[1]}>
            <button type="button" className="mx-button mx-button--secondary" onClick={() => onGroup(first)}>
              Показать «{groupOf(first).tile}» · {totals[first]}
            </button>
          </Empty>
        )
        : (
          <>
            {shown === 'later' && urgent === 0 && (
              <Banner tone="ok"><b>Срочного нет.</b> Просроченных шагов, шагов на эту неделю и карточек без шага нет. Ниже — дальние сроки.</Banner>
            )}
            <h2 className="mx-visually-hidden">{group.title}</h2>
            <ul className="mx-list" data-group-list={shown}>
              {items.map((item) => (
                <WorkRow key={item.id} item={item} now={now} onOpen={() => onOpenCard(item.id)}
                  onStep={() => onForm('step', item.id)} onSetStep={() => onForm('comment', item.id)} />
              ))}
            </ul>
            {more.error !== null && <Banner tone="error" action="Повторить" onAction={() => onMore(page.items, page.total, page.size)}>{more.error}</Banner>}
            {items.length < page.total && (
              <button type="button" className="mx-button mx-button--secondary mx-button--block mx-more" disabled={more.busy}
                onClick={() => onMore(page.items, page.total, page.size)}>
                {more.busy ? 'Загружаем…' : `Показать ещё ${page.total - items.length}`}
              </button>
            )}
          </>
        )}
      <OpenCrm bridge={bridge} hash="#/work">Открыть CRM ↗</OpenCrm>
    </>
  )
}
