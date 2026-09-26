import { useEffect, useSyncExternalStore } from 'react'

const entries = new Map<string, string>()
const listeners = new Set<() => void>()
let labels: string[] = []

const publish = () => {
  labels = [...new Set(entries.values())]
  listeners.forEach((listener) => listener())
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export const useUnsavedDraft = (key: string, label: string, dirty: boolean) => {
  useEffect(() => {
    if (dirty && entries.get(key) !== label) {
      entries.set(key, label)
      publish()
    }
    if (!dirty && entries.delete(key)) {
      publish()
    }
  }, [dirty, key, label])
}

export const clearUnsavedDrafts = () => {
  if (entries.size > 0) {
    entries.clear()
    publish()
  }
}

export const useUnsavedDraftLabels = () => useSyncExternalStore(subscribe, () => labels)
