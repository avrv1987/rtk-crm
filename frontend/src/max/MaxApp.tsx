import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { CardScreen } from './CardScreen'
import { createMaxApi, problemOf, type MaxAppCard, type Problem } from './maxApi'
import { initialSection, initialStack, ownBackButton, pop, push, type Bridge, type Route, type Section } from './startup'
import { StepForm } from './StepForm'
import { TeamScreen } from './TeamScreen'
import { FullScreen, OpenCrm } from './ui'
import { WorkScreen } from './WorkScreen'

type Props = {
  initData: string | null
  bridge: Bridge | undefined
}

type Session =
  | { status: 'starting' }
  | { status: 'ready'; leader: boolean }
  | { status: 'problem'; problem: Problem }

const noInitData: Problem = {
  title: 'Откройте из бота',
  text: 'Откройте мини-приложение кнопкой «Открыть» в сообщении бота CRM в MAX.',
  action: 'close'
}

export const MaxApp = ({ initData, bridge }: Props) => {
  const api = useMemo(() => (initData === null ? null : createMaxApi(initData)), [initData])
  const [session, setSession] = useState<Session>(api === null ? { status: 'problem', problem: noInitData } : { status: 'starting' })
  const [stack, setStack] = useState<Route[]>([{ screen: 'work' }])
  const [section, setSection] = useState<Section>('work')
  const [refreshKey, setRefreshKey] = useState(0)
  const [toast, setToast] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  // Карточка, из которой открыли форму: форма не запрашивает её второй раз.
  const formCards = useRef(new Map<string, MaxAppCard>())
  // Прокрутка каждого уровня стека: «Назад» возвращает на то же место списка.
  const scrolls = useRef<number[]>([])
  const restore = useRef<number | null>(null)

  useEffect(() => {
    if (api === null) {
      return undefined
    }
    let active = true
    setSession({ status: 'starting' })
    api.start()
      .then((started) => {
        if (!active) return
        const leader = started.role === 'LEADER'
        setStack(initialStack(started.startParam))
        setSection(initialSection(started.startParam, leader))
        setSession({ status: 'ready', leader })
      })
      .catch((error: unknown) => {
        if (active) setSession({ status: 'problem', problem: problemOf(error, true)! })
      })
    return () => { active = false }
  }, [api, attempt])

  // ready() есть в скрипте моста, но не описан в документации MAX: вызываем, только если он есть.
  useEffect(() => {
    if (session.status !== 'starting') {
      bridge?.ready?.()
    }
  }, [bridge, session.status])

  const onFatal = useCallback((problem: Problem) => setSession({ status: 'problem', problem }), [])

  const back = useCallback(() => {
    setStack((current) => {
      if (current.length > 1) {
        restore.current = scrolls.current[current.length - 2] ?? 0
      }
      return pop(current)
    })
  }, [])

  const open = useCallback((route: Route) => {
    setStack((current) => {
      scrolls.current[current.length - 1] = window.scrollY
      restore.current = 0
      return push(current, route)
    })
  }, [])

  useLayoutEffect(() => {
    if (restore.current !== null) {
      window.scrollTo(0, restore.current)
      restore.current = null
    }
  }, [stack])

  // Системная «Назад» MAX — пока есть куда возвращаться.
  useEffect(() => {
    const button = bridge?.BackButton
    if (button === undefined || session.status !== 'ready') {
      return undefined
    }
    if (stack.length > 1) {
      button.show()
      button.onClick(back)
      return () => button.offClick(back)
    }
    button.hide()
    return undefined
  }, [bridge, stack.length, back, session.status])

  useEffect(() => {
    if (toast === null) return undefined
    const timer = window.setTimeout(() => setToast(null), 2500)
    return () => window.clearTimeout(timer)
  }, [toast])

  if (session.status === 'problem') {
    const { problem } = session
    return (
      <main className="mx-app">
        <FullScreen icon={problem.action === 'retry' ? '⚠' : problem.action === 'crm' ? '🔗' : '⏱'} title={problem.title} text={problem.text}>
          {problem.action === 'retry' && api !== null && (
            <button type="button" className="mx-button mx-button--primary mx-button--block" onClick={() => setAttempt((value) => value + 1)}>Повторить</button>
          )}
          {problem.action === 'crm' && <OpenCrm bridge={bridge} variant="primary">Открыть CRM</OpenCrm>}
          {problem.action === 'close' && bridge?.close !== undefined && (
            <button type="button" className="mx-button mx-button--primary mx-button--block" onClick={() => bridge.close!()}>Закрыть</button>
          )}
        </FullScreen>
      </main>
    )
  }
  if (session.status === 'starting' || api === null) {
    return <main className="mx-app" aria-busy="true"><p className="mx-starting" role="status">Входим в CRM…</p></main>
  }

  const backButton = ownBackButton(bridge, stack.length)
    ? <button type="button" className="mx-button mx-button--text mx-back" onClick={back}>‹ Назад</button>
    : null
  const openForm = (screen: 'step' | 'comment', id: string, card?: MaxAppCard) => {
    if (card === undefined) formCards.current.delete(id)
    else formCards.current.set(id, card)
    open({ screen, id })
  }
  const done = (message: string) => {
    back()
    setRefreshKey((value) => value + 1)
    setToast(message)
  }

  // Все экраны стека остаются смонтированными, невидимые — hidden: «Назад» не грузит список заново.
  return (
    <main className="mx-app">
      {stack.map((route, index) => {
        const top = index === stack.length - 1
        const key = `${index}:${route.screen}:${'id' in route ? route.id : ''}`
        return (
          <div key={key} hidden={!top} className="mx-layer">
            {route.screen === 'work' && (
              <>
                {session.leader && (
                  <div className="mx-content mx-content--head">
                    <div className="mx-segments" role="group" aria-label="Раздел">
                      <button type="button" aria-pressed={section === 'work'} onClick={() => setSection('work')}>Моя работа</button>
                      <button type="button" aria-pressed={section === 'team'} onClick={() => setSection('team')}>Команда</button>
                    </div>
                  </div>
                )}
                <div hidden={section !== 'work'}>
                  <WorkScreen api={api} bridge={bridge} showTitle={!session.leader} refreshKey={refreshKey} onFatal={onFatal}
                    onOpenCard={(id) => open({ screen: 'card', id })} onForm={(screen, id) => openForm(screen, id)} />
                </div>
                {session.leader && section === 'team' && (
                  <section className="mx-screen" aria-label="Команда">
                    <div className="mx-content">
                      <TeamScreen api={api} bridge={bridge} refreshKey={refreshKey} onFatal={onFatal} />
                    </div>
                  </section>
                )}
              </>
            )}
            {route.screen === 'card' && (
              <CardScreen api={api} bridge={bridge} id={route.id} refreshKey={refreshKey} back={backButton} onFatal={onFatal} onForm={openForm} />
            )}
            {(route.screen === 'step' || route.screen === 'comment') && (
              <StepForm api={api} bridge={bridge} id={route.id} mode={route.screen} initial={formCards.current.get(route.id)}
                back={backButton} onFatal={onFatal} onDone={done} />
            )}
          </div>
        )
      })}
      {toast !== null && <div className="mx-toast" role="status">✓ {toast}</div>}
    </main>
  )
}
