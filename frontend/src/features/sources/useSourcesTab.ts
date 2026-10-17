import { useCallback, useEffect, useState } from 'react'
import type { DataSource } from '../../shared/api/client'
import {
  initialSourcesTab,
  parseSourcesHash,
  sourcesBaseHash,
  sourcesHash,
  type MappingsSource,
  type SourcesTab
} from './sourceTabs'

const onSourcesPage = () => window.location.hash.split('?')[0] === sourcesBaseHash

// Вкладку держим в адресе, но меняем его без перехода (replaceState): экран не перезагружается, «назад» не листает вкладки.
export const useSourcesTab = (sources: DataSource[] | null) => {
  const [tab, setTab] = useState<SourcesTab | null>(() => parseSourcesHash(window.location.hash).tab)
  const [mappingsSource, setMappingsSource] = useState<MappingsSource | null>(() => parseSourcesHash(window.location.hash).mappingsSource)

  useEffect(() => {
    const sync = () => {
      if (onSourcesPage()) {
        const parsed = parseSourcesHash(window.location.hash)
        setTab(parsed.tab)
        setMappingsSource(parsed.mappingsSource)
      }
    }
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])

  useEffect(() => {
    if (tab === null && sources !== null && onSourcesPage()) {
      const initial = initialSourcesTab(sources)
      setTab(initial)
      window.history.replaceState(window.history.state, '', sourcesHash(initial))
    }
  }, [sources, tab])

  const select = useCallback((next: SourcesTab, source: MappingsSource | null = null) => {
    setTab(next)
    setMappingsSource(source)
    window.history.replaceState(window.history.state, '', sourcesHash(next, source))
  }, [])

  return { tab, mappingsSource, select }
}
