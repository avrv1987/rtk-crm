import type { ReactNode } from 'react'
import type { MaxAppWork } from './maxApi'
import { dueChip, markChips, type Chip } from './model'
import type { Bridge } from './startup'

export type WorkItem = MaxAppWork['items']['items'][number]

export const Chips = ({ chips }: { chips: Chip[] }) => (
  <>{chips.map((chip) => <span key={chip.label} className={`mx-chip mx-chip--${chip.tone}`}>{chip.label}</span>)}</>
)

/** Строка «Моей работы»: слева — переход в карточку, справа — «Шаг выполнен» или «Задать шаг». */
export const WorkRow = ({ item, now, onOpen, onStep, onSetStep }: {
  item: WorkItem
  now: number
  onOpen: () => void
  onStep: () => void
  onSetStep: () => void
}) => (
  <li className="mx-row">
    <button type="button" className="mx-row__main" onClick={onOpen}>
      <span className="mx-row__step">{item.nextAction ?? 'Следующий шаг не задан'}</span>
      <span className="mx-row__org">{item.organizationName} · {item.title}</span>
      <span className="mx-chips"><Chips chips={[dueChip(item.nextActionAt, now), ...markChips(item.marks, 2)]} /></span>
    </button>
    {item.marks.status !== 'COMPLETED' && (item.nextAction !== null
      ? <button type="button" className="mx-row__action" aria-label={`Шаг выполнен: ${item.nextAction}`} title="Шаг выполнен" onClick={onStep}>✓</button>
      : <button type="button" className="mx-row__action mx-row__action--add" aria-label={`Задать шаг: ${item.organizationName}`} title="Задать шаг" onClick={onSetStep}>＋</button>)}
  </li>
)

/** Плашка: ошибка с «Повторить», предупреждение или успех. */
export const Banner = ({ tone, children, action, onAction }: {
  tone: 'error' | 'warn' | 'ok'
  children: ReactNode
  action?: string
  onAction?: () => void
}) => (
  <div className={`mx-banner mx-banner--${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
    <span className="mx-banner__text">{children}</span>
    {action !== undefined && <button type="button" className="mx-banner__button" onClick={onAction}>{action}</button>}
  </div>
)

export const Empty = ({ title, text, children }: { title: string; text: string; children?: ReactNode }) => (
  <div className="mx-empty">
    <span className="mx-empty__icon" aria-hidden="true">✓</span>
    <strong>{title}</strong>
    <p>{text}</p>
    {children}
  </div>
)

export const SkeletonRows = ({ count = 3 }: { count?: number }) => (
  <ul className="mx-list" aria-busy="true" aria-label="Загружаем">
    {Array.from({ length: count }, (_, index) => (
      <li key={index} className="mx-row mx-row--skeleton">
        <span className="mx-sk mx-sk--w85" />
        <span className="mx-sk mx-sk--w60" />
        <span className="mx-sk mx-sk--w35" />
      </li>
    ))}
  </ul>
)

/** Адрес CRM: мини-приложение отдаётся с того же домена, что и CRM. */
export const crmUrl = (hash = '') => `${window.location.origin}/${hash}`

/**
 * «Открыть CRM ↗»: через openLink моста — во внешнем браузере, где действует вход CRM. Обычная ссылка внутри WebView
 * открылась бы в том же окне и закрыла мини-приложение; без моста — обычная ссылка в новой вкладке.
 */
export const OpenCrm = ({ bridge, hash, children, variant = 'text' }: {
  bridge: Bridge | undefined
  hash?: string
  children: ReactNode
  variant?: 'text' | 'secondary' | 'primary'
}) => {
  const url = crmUrl(hash)
  const className = `mx-button mx-button--${variant}${variant === 'text' ? ' mx-button--block' : ''}`
  if (bridge?.openLink !== undefined) {
    return <button type="button" className={className} onClick={() => bridge.openLink!(url)}>{children}</button>
  }
  return <a className={className} href={url} target="_blank" rel="noopener noreferrer">{children}</a>
}

/** Экран на всю высоту: вход не удался, сеанс закончился. */
export const FullScreen = ({ icon, title, text, children }: { icon: string; title: string; text: string; children?: ReactNode }) => (
  <section className="mx-full" aria-labelledby="mx-full-title">
    <span className="mx-full__icon" aria-hidden="true">{icon}</span>
    <h1 id="mx-full-title">{title}</h1>
    <p>{text}</p>
    {children}
  </section>
)

const icons = {
  phone: 'M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z',
  mail: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm0 2.2V17h16V7.2l-8 5.3z'
}

export const Icon = ({ name }: { name: keyof typeof icons }) => (
  <svg className="mx-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fillRule="evenodd" d={icons[name]} /></svg>
)
