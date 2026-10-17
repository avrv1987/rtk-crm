import { useRef, type KeyboardEvent } from 'react'
import { sourceTabs, type SourcesTab } from './sourceTabs'
import './sourcePage.css'

export type SourceTabLabel = { label: string; flag?: string; flagWarning?: boolean }

type SourceTabsProps = {
  active: SourcesTab
  labels: Record<SourcesTab, SourceTabLabel>
  onSelect: (tab: SourcesTab) => void
}

export const tabId = (tab: SourcesTab) => `sources-tab-${tab}`
export const tabPanelId = 'sources-tab-panel'

export const SourceTabBar = ({ active, labels, onSelect }: SourceTabsProps) => {
  const listRef = useRef<HTMLDivElement>(null)

  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys = sourceTabs.map((tab) => tab.key)
    const index = keys.indexOf(active)
    const target: Record<string, number> = {
      ArrowRight: (index + 1) % keys.length,
      ArrowLeft: (index - 1 + keys.length) % keys.length,
      Home: 0,
      End: keys.length - 1
    }
    if (event.key in target) {
      event.preventDefault()
      const next = keys[target[event.key]]
      onSelect(next)
      listRef.current?.querySelector<HTMLElement>(`#${tabId(next)}`)?.focus()
    }
  }

  return (
    <div ref={listRef} className="src-tabs" role="tablist" aria-label="Источники данных" onKeyDown={move}>
      {sourceTabs.map((tab) => {
        const item = labels[tab.key]
        return (
          <button
            key={tab.key}
            id={tabId(tab.key)}
            type="button"
            role="tab"
            className="src-tabs__tab"
            aria-selected={tab.key === active}
            aria-controls={tabPanelId}
            tabIndex={tab.key === active ? 0 : -1}
            onClick={() => onSelect(tab.key)}
          >
            {item.label}
            {item.flag && (
              <span className={`src-tabs__flag${item.flagWarning ? ' src-tabs__flag--warning' : ''}`} title={item.flag}>
                <span aria-hidden="true"> !</span>
                <span className="sr-only">, {item.flag}</span>
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
