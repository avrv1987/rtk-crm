import { useEffect, useSyncExternalStore } from 'react'

// restorable: текст лежит в черновике вкладки и вернётся после входа или F5. Без него текст живёт только в форме
// (комментарий к передаче вуза, анкета слушателя с персональными данными) и пропадёт вместе с экраном.
export type UnsavedEntry = { label: string; restorable: boolean }

const entries = new Map<string, UnsavedEntry>()
const listeners = new Set<() => void>()
let snapshot: UnsavedEntry[] = []

const publish = () => {
  const byLabel = new Map<string, UnsavedEntry>()
  entries.forEach((entry) => byLabel.set(entry.label, entry))
  snapshot = [...byLabel.values()]
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

export const markUnsavedDraft = (key: string, label: string, dirty: boolean, restorable = true) => {
  const current = entries.get(key)
  if (!dirty) {
    forgetUnsavedDraft(key)
  } else if (current?.label !== label || current.restorable !== restorable) {
    entries.set(key, { label, restorable })
    publish()
  }
}

export const currentUnsavedEntries = () => snapshot

export const currentUnsavedLabels = () => snapshot.map((entry) => entry.label)

// forgetOnUnmount: текст живёт только в состоянии компонента (в хранилище не пишется). Если панель закрылась
// сразу после сохранения, эффект с dirty=false уже не успеет сработать, и метка осталась бы висеть.
// Такой текст и не восстановится — плашка истёкшего сеанса его не обещает.
export const useUnsavedDraft = (key: string, label: string, dirty: boolean, forgetOnUnmount = false) => {
  useEffect(() => markUnsavedDraft(key, label, dirty, !forgetOnUnmount), [dirty, forgetOnUnmount, key, label])
  useEffect(() => forgetOnUnmount ? () => forgetUnsavedDraft(key) : undefined, [forgetOnUnmount, key])
}

export const clearUnsavedDrafts = () => {
  if (entries.size > 0) {
    entries.clear()
    publish()
  }
}

export const useUnsavedDraftEntries = () => useSyncExternalStore(subscribe, () => snapshot)

// Н-А2: плашка истёкшего сеанса обещает ровно то, что есть: черновики вкладки вернутся, текст без черновика — нет.
export const sessionExpiredNotice = (unsaved: UnsavedEntry[]) => {
  const kept = unsaved.filter((entry) => entry.restorable).map((entry) => entry.label)
  const lost = unsaved.filter((entry) => !entry.restorable).map((entry) => entry.label)
  return [
    'Сеанс истёк. Войдите снова — вы вернётесь на тот же экран.',
    kept.length > 0 ? `Несохранённый текст вернётся в форму: ${kept.join('; ')}.` : null,
    lost.length > 0 ? `Не сохранится, введите заново: ${lost.join('; ')}.` : null
  ].filter((part) => part !== null).join(' ')
}
