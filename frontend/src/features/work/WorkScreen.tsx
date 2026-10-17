import { useCallback, useEffect, useState } from 'react'
import { apiClient, type LmsSignal, type Me } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { KamDesk } from './KamDesk'
import { LmsSignalList } from './LmsSignals'
import { WorkList } from './WorkList'
import { browserStorage, type KamView, readKamView, saveKamView } from './workView'
import { type AccessHandlers, handledAccessError, requestIdOf } from './workShared'
import './workControl.css'

type WorkScreenProps = {
  role: Me['role']
  initialQuery: string
  refreshSignal: number
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

const viewOptions: { value: KamView; label: string }[] = [
  { value: 'deadlines', label: 'Сроки и шаги' },
  { value: 'signals', label: 'Сигналы LMS' }
]

type SignalsState = { kind: 'loading' } | { kind: 'ready'; signals: LmsSignal[] } | { kind: 'failed'; requestId?: string }

const KamSignals = ({ refreshKey, onSessionExpired, onProfileUnavailable }: AccessHandlers & { refreshKey: number }) => {
  const [state, setState] = useState<SignalsState>({ kind: 'loading' })

  const load = useCallback(() => {
    setState({ kind: 'loading' })
    apiClient.listLmsSignals()
      .then((result) => setState({ kind: 'ready', signals: result.items }))
      .catch((error: unknown) => {
        if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
          setState({ kind: 'failed', requestId: requestIdOf(error) })
        }
      })
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(load, [load, refreshKey])

  return (
    <section className="desk-feed" aria-labelledby="desk-signals-title">
      <h2 id="desk-signals-title" className="desk-feed__heading">
        Сигналы LMS{state.kind === 'ready' && state.signals.length > 0 && <span className="desk-feed__count">{state.signals.length}</span>}
      </h2>
      {state.kind === 'loading' && <p className="work-control__message" role="status">Проверяем сигналы LMS…</p>}
      {state.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>Сигналы LMS сейчас недоступны.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={load}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && state.signals.length === 0 && <p className="work-control__message">Сигналов по данным LMS нет.</p>}
      {state.kind === 'ready' && state.signals.length > 0 && <LmsSignalList signals={state.signals} limit={20} />}
      <p className="work-control__caption">Скрыть сигнал, записать его как риск или перейти к этапу можно в карточке взаимодействия.</p>
    </section>
  )
}

export const WorkScreen = ({ role, initialQuery, refreshSignal, onSessionExpired, onProfileUnavailable }: WorkScreenProps) => {
  const [view, setView] = useState<KamView>(() => readKamView(browserStorage()))
  const [pulse, setPulse] = useState(0)
  const [total, setTotal] = useState<number | null>(null)
  const [licenseActive, setLicenseActive] = useState(false)

  const changeView = (next: KamView) => {
    setView(next)
    saveKamView(browserStorage(), next)
  }

  return (
    <section className="work" aria-label="Моя работа">
      <div className="work-toolbar">
        <HintButton label="Что на этом экране">
          Сроки ваших шагов: просроченные, сегодня, на этой неделе и позже. Плитка отбирает задачи, второе нажатие снимает
          отбор. «Шаг выполнен» записывает результат в историю и задаёт новый шаг. Сигналы LMS — во втором виде, выбор
          запоминается. Ниже — все ваши взаимодействия с поиском и фильтрами.
        </HintButton>
        <Segmented label="Что показать" options={viewOptions} value={view} onChange={changeView} />
        <button
          type="button"
          className="button--secondary work-toolbar__all"
          onClick={() => {
            const heading = document.getElementById('work-results-title')
            heading?.scrollIntoView({ block: 'start' })
            heading?.focus()
          }}
        >
          {total === null ? 'Все мои взаимодействия' : `Все мои взаимодействия (${total})`}
        </button>
      </div>

      {view === 'deadlines' ? (
        <KamDesk
          refreshKey={refreshSignal}
          licenseActive={licenseActive}
          onChanged={() => setPulse((value) => value + 1)}
          onShowSignals={() => changeView('signals')}
          onLicenses={(year) => {
            window.location.hash = year === null ? '#/work' : `#/work?license=${year}&status=ALL`
          }}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      ) : (
        <KamSignals refreshKey={refreshSignal} onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} />
      )}

      <WorkList
        role={role}
        path="work"
        initialQuery={initialQuery}
        refreshSignal={refreshSignal + pulse}
        title="Все мои взаимодействия"
        onTotal={setTotal}
        onFilters={(filters) => setLicenseActive(filters.licenseExpiresBy !== '')}
        onSessionExpired={onSessionExpired}
        onProfileUnavailable={onProfileUnavailable}
      />
    </section>
  )
}
