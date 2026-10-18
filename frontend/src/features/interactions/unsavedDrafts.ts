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

export const forgetUnsavedDraft = (key: string) => {
  if (entries.delete(key)) {
    publish()
  }
}

export const markUnsavedDraft = (key: string, label: string, dirty: boolean) => {
  if (!dirty) {
    forgetUnsavedDraft(key)
  } else if (entries.get(key) !== label) {
    entries.set(key, label)
    publish()
  }
}

export const currentUnsavedLabels = () => labels

// forgetOnUnmount: текст живёт только в состоянии компонента (в хранилище не пишется). Если панель закрылась
// сразу после сохранения, эффект с dirty=false уже не успеет сработать, и метка осталась бы висеть.
export const useUnsavedDraft = (key: string, label: string, dirty: boolean, forgetOnUnmount = false) => {
  useEffect(() => markUnsavedDraft(key, label, dirty), [dirty, key, label])
  useEffect(() => forgetOnUnmount ? () => forgetUnsavedDraft(key) : undefined, [forgetOnUnmount, key])
}

export const clearUnsavedDrafts = () => {
  if (entries.size > 0) {
    entries.clear()
    publish()
  }
}

export const useUnsavedDraftLabels = () => useSyncExternalStore(subscribe, () => labels)
