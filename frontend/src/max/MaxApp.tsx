import { useCallback, useEffect, useMemo, useState } from 'react'
import { formatMoscowDateTime } from '../shared/format/datetime'
import { MaxApiError, createMaxApi, startupProblem, type Due, type MaxApi, type MaxAppCard, type MaxAppWork, type TeamIndicators } from './maxApi'
import { initialStack, moscowDateTime, nextStepErrors, pop, push, type Bridge, type Route } from './startup'

type Props = {
  initData: string | null
  bridge: Bridge | undefined
}

const dueTabs: { due: Due; label: string; count: (feed: MaxAppWork['feed']) => number }[] = [
  { due: 'OVERDUE', label: 'Просрочено', count: (feed) => feed.overdue },
  { due: 'TODAY', label: 'Сегодня', count: (feed) => feed.today },
  { due: 'THIS_WEEK', label: 'На неделе', count: (feed) => feed.thisWeek },
  { due: 'NO_NEXT_STEP', label: 'Без шага', count: (feed) => feed.noNextStep },
  { due: 'LATER', label: 'Позже', count: (feed) => feed.later }
]

const errorText = (error: unknown) => (error instanceof MaxApiError ? error.message : 'Не удалось связаться с CRM. Повторите позже.')
const when = (value: string | null | undefined) => (value ? formatMoscowDateTime(value) : 'без срока')

export const MaxApp = ({ initData, bridge }: Props) => {
  const api = useMemo(() => (initData === null ? null : createMaxApi(initData)), [initData])
  const [problem, setProblem] = useState<string | null>(initData === null ? 'Откройте мини-приложение из бота CRM в MAX.' : null)
  const [stack, setStack] = useState<Route[] | null>(null)
  const [canControl, setCanControl] = useState(false)
  const [due, setDue] = useState<Due>('TODAY')

  useEffect(() => {
    if (api === null) {
      return
    }
    api.start()
      .then((session) => {
        const leader = session.role === 'LEADER'
        setCanControl(leader)
        setStack(initialStack(session.startParam, leader))
      })
      .catch((error: unknown) => setProblem(startupProblem(error)))
  }, [api])

  const back = useCallback(() => setStack((current) => (current === null ? current : pop(current))), [])
  const open = useCallback((route: Route) => setStack((current) => (current === null ? [route] : push(current, route))), [])

  // Кнопка «Назад» MAX видна, пока есть куда возвращаться.
  useEffect(() => {
    const button = bridge?.BackButton
    if (button === undefined || stack === null) {
      return undefined
    }
    if (stack.length > 1) {
      button.show()
      button.onClick(back)
      return () => button.offClick(back)
    }
    button.hide()
    return undefined
  }, [bridge, stack, back])

  if (problem !== null) {
    return <main className="max-app"><p className="max-app__problem" role="alert">{problem}</p></main>
  }
  if (api === null || stack === null) {
    return <main className="max-app"><p role="status">Входим в CRM…</p></main>
  }
  const route = stack[stack.length - 1]
  return (
    <main className="max-app">
      {stack.length > 1 && (
        <button type="button" className="max-app__back" onClick={back}>← Назад</button>
      )}
      {route.screen === 'work' && (
        <WorkScreen api={api} due={due} onDue={setDue} canControl={canControl} onOpen={open} />
      )}
      {route.screen === 'card' && <CardScreen api={api} id={route.id} onOpen={open} />}
      {(route.screen === 'comment' || route.screen === 'step') && (
        <StepForm api={api} id={route.id} mode={route.screen} bridge={bridge} onDone={back} />
      )}
      {route.screen === 'control' && <ControlScreen api={api} />}
    </main>
  )
}

const WorkScreen = ({ api, due, onDue, canControl, onOpen }: {
  api: MaxApi
  due: Due
  onDue: (due: Due) => void
  canControl: boolean
  onOpen: (route: Route) => void
}) => {
  const [work, setWork] = useState<MaxAppWork | null>(null)
  const [items, setItems] = useState<MaxAppWork['items']['items']>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (page: number) => {
    try {
      const loaded = await api.work(due, page)
      setWork(loaded)
      setItems((current) => (page === 0 ? loaded.items.items : [...current, ...loaded.items.items]))
      setError(null)
    } catch (loadError) {
      setError(errorText(loadError))
    }
  }, [api, due])

  useEffect(() => {
    setItems([])
    void load(0)
  }, [load])

  return (
    <section aria-labelledby="max-work-title">
      <div className="max-app__title-row">
        <h1 id="max-work-title">Моя работа</h1>
        {canControl && <button type="button" className="max-app__link" onClick={() => onOpen({ screen: 'control' })}>Контроль</button>}
      </div>
      <div className="max-app__tabs" role="tablist" aria-label="Срок следующего шага">
        {dueTabs.map((tab) => (
          <button key={tab.due} type="button" role="tab" aria-selected={tab.due === due} onClick={() => onDue(tab.due)}>
            {tab.label}{work === null ? '' : ` · ${tab.count(work.feed)}`}
          </button>
        ))}
      </div>
      {error !== null && <p role="alert">{error}</p>}
      {work !== null && items.length === 0 && <p>Здесь пусто.</p>}
      <ul className="max-app__list">
        {items.map((item) => (
          <li key={item.id}>
            <button type="button" onClick={() => onOpen({ screen: 'card', id: item.id })}>
              <strong>{item.organizationName}</strong>
              <span>{item.title}</span>
              <span className="max-app__muted">{item.nextAction ?? 'шаг не задан'} · {when(item.nextActionAt)}</span>
            </button>
          </li>
        ))}
      </ul>
      {work !== null && items.length < work.items.total && (
        <button type="button" className="max-app__more" onClick={() => void load(Math.floor(items.length / work.items.size))}>
          Показать ещё ({work.items.total - items.length})
        </button>
      )}
    </section>
  )
}

const riskText = (card: MaxAppCard) => {
  const marks = card.interaction.marks
  const parts: string[] = []
  if (marks.status !== 'ACTIVE') {
    parts.push(marks.status === 'PAUSED' ? 'приостановлено' : 'завершено')
  }
  if (marks.waitingOn !== null) {
    parts.push(marks.waitingOn === 'UNIVERSITY' ? 'ждём вуз' : 'ждём РТК')
  }
  if (marks.riskLevel !== null) {
    parts.push(`риски: ${marks.riskCount}${marks.riskLevel === 'HIGH' ? ', есть высокий' : ''}`)
  }
  if (marks.problemCount > 0) {
    parts.push(`проблемы: ${marks.problemCount}`)
  }
  return parts
}

const CardScreen = ({ api, id, onOpen }: { api: MaxApi; id: string; onOpen: (route: Route) => void }) => {
  const [card, setCard] = useState<MaxAppCard | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.card(id).then(setCard).catch((loadError: unknown) => setError(errorText(loadError)))
  }, [api, id])

  if (error !== null) {
    return <p role="alert">{error}</p>
  }
  if (card === null) {
    return <p role="status">Загружаем карточку…</p>
  }
  const { interaction } = card
  const marks = riskText(card)
  return (
    <section aria-labelledby="max-card-title">
      <p className="max-app__muted">{card.organization.name}</p>
      <h1 id="max-card-title">{interaction.title}</h1>
      <dl className="max-app__facts">
        <dt>Этап</dt>
        <dd>{interaction.currentStageName}</dd>
        <dt>Следующий шаг</dt>
        <dd>{interaction.nextAction ?? 'не задан'} · {when(interaction.nextActionAt)}</dd>
        {marks.length > 0 && (<><dt>Отметки</dt><dd>{marks.join('; ')}</dd></>)}
        {card.organization.ownerManagerName && (<><dt>КАМ</dt><dd>{card.organization.ownerManagerName}</dd></>)}
      </dl>
      <div className="max-app__actions">
        {(interaction.nextAction !== null || interaction.nextActionAt !== null) && (
          <button type="button" onClick={() => onOpen({ screen: 'step', id })}>Шаг выполнен</button>
        )}
        <button type="button" className="max-app__secondary" onClick={() => onOpen({ screen: 'comment', id })}>Комментарий</button>
      </div>
      {card.agreements.length > 0 && (
        <>
          <h2>Договоры</h2>
          <ul className="max-app__plain">
            {card.agreements.map((agreement) => (
              <li key={agreement.id}>№ {agreement.number} — {agreement.statusTitle}{agreement.validUntil ? `, до ${agreement.validUntil.split('-').reverse().join('.')}` : ''}</li>
            ))}
          </ul>
        </>
      )}
      {card.contacts.length > 0 && (
        <>
          <h2>Контакты</h2>
          <ul className="max-app__plain">
            {card.contacts.map((contact) => (
              <li key={contact.id}>
                {contact.name}{contact.position ? `, ${contact.position}` : ''}
                {contact.phone && <> · <a href={`tel:${contact.phone.replace(/[^\d+]/g, '')}`}>{contact.phone}</a></>}
                {contact.email && <> · <a href={`mailto:${contact.email}`}>{contact.email}</a></>}
              </li>
            ))}
          </ul>
        </>
      )}
      {card.events.length > 0 && (
        <>
          <h2>Последние события</h2>
          <ul className="max-app__plain">
            {card.events.map((event) => (
              <li key={event.id}>
                <span className="max-app__muted">{formatMoscowDateTime(event.occurredAt)} · {event.actorDisplayName}</span>
                {event.comment && <p>{event.comment}</p>}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

// Черновик живёт только в памяти этой формы; пока в нём есть текст, MAX спрашивает подтверждение при закрытии.
const StepForm = ({ api, id, mode, bridge, onDone }: {
  api: MaxApi
  id: string
  mode: 'comment' | 'step'
  bridge: Bridge | undefined
  onDone: () => void
}) => {
  const [card, setCard] = useState<MaxAppCard | null>(null)
  const [text, setText] = useState('')
  const [nextAction, setNextAction] = useState('')
  const [nextActionAt, setNextActionAt] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const dirty = text !== '' || nextAction !== '' || nextActionAt !== ''

  useEffect(() => {
    api.card(id).then(setCard).catch((loadError: unknown) => setError(errorText(loadError)))
  }, [api, id])

  useEffect(() => {
    if (dirty) {
      bridge?.enableClosingConfirmation?.()
    } else {
      bridge?.disableClosingConfirmation?.()
    }
    return () => bridge?.disableClosingConfirmation?.()
  }, [bridge, dirty])

  const errors = nextStepErrors(nextAction, nextActionAt)
  const textMissing = mode === 'comment' && text.trim() === ''

  const save = async () => {
    setSubmitted(true)
    if (card === null || Object.keys(errors).length > 0 || textMissing) {
      return
    }
    const nextStep = { nextAction: nextAction.trim(), nextActionAt: moscowDateTime(nextActionAt)! }
    setBusy(true)
    try {
      if (mode === 'comment') {
        await api.comment(id, { version: card.interaction.version, text: text.trim(), nextStep }, idempotencyKey)
      } else {
        await api.completeStep(id, { version: card.interaction.version, result: text.trim() || null, nextStep }, idempotencyKey)
      }
      bridge?.disableClosingConfirmation?.()
      onDone()
    } catch (saveError) {
      setError(saveError instanceof MaxApiError && saveError.code === 'VERSION_CONFLICT'
        ? 'Карточку уже изменили. Вернитесь назад, откройте её заново и повторите; ваш текст пока остаётся здесь.'
        : errorText(saveError))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="max-app__form" noValidate onSubmit={(event) => { event.preventDefault(); void save() }}>
      <h1>{mode === 'comment' ? 'Комментарий со следующим шагом' : 'Шаг выполнен'}</h1>
      {card !== null && mode === 'step' && <p className="max-app__muted">Шаг: {card.interaction.nextAction ?? 'без текста'} · {when(card.interaction.nextActionAt)}</p>}
      <label>
        {mode === 'comment' ? 'Комментарий' : 'Результат (необязательно)'}
        <textarea value={text} maxLength={4000} rows={4} onChange={(event) => setText(event.target.value)} />
      </label>
      {submitted && textMissing && <p className="max-app__error">Напишите комментарий</p>}
      <label>
        Следующий шаг
        <input value={nextAction} maxLength={500} onChange={(event) => setNextAction(event.target.value)} />
      </label>
      {submitted && errors.nextAction && <p className="max-app__error">{errors.nextAction}</p>}
      <label>
        Срок (МСК)
        <input type="datetime-local" value={nextActionAt} onChange={(event) => setNextActionAt(event.target.value)} />
      </label>
      {submitted && errors.nextActionAt && <p className="max-app__error">{errors.nextActionAt}</p>}
      {error !== null && <p className="max-app__error" role="alert">{error}</p>}
      <button type="submit" disabled={busy || card === null}>{busy ? 'Сохраняем…' : 'Сохранить'}</button>
      <p className="max-app__muted">Этап взаимодействия не меняется. Черновик не сохраняется на сервере: закроете приложение — текст пропадёт.</p>
    </form>
  )
}

const ControlScreen = ({ api }: { api: MaxApi }) => {
  const [indicators, setIndicators] = useState<TeamIndicators | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.control().then(setIndicators).catch((loadError: unknown) => setError(errorText(loadError)))
  }, [api])

  if (error !== null) {
    return <p role="alert">{error}</p>
  }
  if (indicators === null) {
    return <p role="status">Загружаем показатели команды…</p>
  }
  return (
    <section aria-labelledby="max-control-title">
      <h1 id="max-control-title">Контроль</h1>
      <p className="max-app__muted">Только просмотр. Без ответственного: {indicators.unassignedOrganizations} вузов.</p>
      <ul className="max-app__plain">
        {indicators.managers.map((manager) => (
          <li key={manager.managerId ?? 'none'}>
            <strong>{manager.managerName ?? 'Без ответственного'}</strong>
            <span className="max-app__muted"> · вузов {manager.organizations}, просрочено {manager.overdue}, без шага {manager.withoutNextStep}, застряли {manager.stuck}, в риске {manager.atRisk}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
