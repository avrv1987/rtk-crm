import { useCallback, useEffect, useState } from 'react'
import { changedDraft, editedKeys, mergedValues, parseBasedDraft, type BasedDraft } from './draftBase.ts'
import { activeProfileId, loadFormDraft, saveFormDraft } from './drafts.ts'
import { forgetUnsavedDraft, useUnsavedDraft } from './unsavedDrafts.ts'

export const useFormDraft = <T extends object>(
  profileId: string,
  key: string,
  parse: (value: unknown) => T | null
) => {
  const [draft, setDraft] = useState<T | null>(() => parse(loadFormDraft(profileId, key)))
  const updateDraft = useCallback((value: T | null) => {
    setDraft(value)
    saveFormDraft(profileId, key, value)
  }, [key, profileId])
  return [draft, updateDraft] as const
}

export const textOf = (value: unknown) => typeof value === 'string' ? value : ''

export const recordOf = (value: unknown): Record<string, unknown> | null => (
  typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
)

export type DraftValue = string | boolean | string[]
export type DraftValues = Record<string, DraftValue>

const sameKind = (sample: DraftValue, value: unknown) => (
  Array.isArray(sample)
    ? Array.isArray(value) && value.every((item) => typeof item === 'string')
    : typeof value === typeof sample
)

// Прочитанное из хранилища не доверяется: берутся только поля этой формы и только того же типа, остальное — из записи.
export const valuesLike = <T extends DraftValues>(shape: T, raw: unknown): T | null => {
  const record = recordOf(raw)
  if (record === null) {
    return null
  }
  const values = { ...shape }
  for (const key of Object.keys(shape) as (keyof T & string)[]) {
    if (sameKind(shape[key], record[key])) {
      values[key] = record[key] as T[typeof key]
    }
  }
  return values
}

// Черновик хранит исходные значения записи (base) и введённые (values). Поле, которое пользователь не трогал, берётся
// из свежей записи: чужое изменение, пришедшее через «Обновить карточку», не затирается старым снимком формы.
export const nextRecordDraft = <T extends DraftValues>(draft: BasedDraft<T> | null, current: T, patch: Partial<T>) => {
  const next = changedDraft(draft, current, patch)
  return editedKeys(next).length === 0 ? null : next
}

export const parseRecordDraft = <T extends DraftValues>(stored: unknown, current: T) => (
  parseBasedDraft(stored, (value) => valuesLike(current, value))
)

// Н-А2: общий черновик формы записи. Ключ — форма и запись («agreement:<id>», «activity:new:<договор>»), профиль берётся
// из вкладки. Текст лежит в sessionStorage этой вкладки, переживает F5 и повторный вход после истёкшего сеанса,
// при выходе из CRM удаляется вместе с остальными черновиками профиля. «Отмена» и сохранение черновик стирают;
// «Закрыть» окно его оставляет, и плашка «Есть несохранённый черновик» напоминает о нём.
export const useRecordDraft = <T extends DraftValues>(key: string, label: string, current: T) => {
  const [profileId] = useState(activeProfileId)
  const storageKey = `record:${key}`
  const unsavedKey = `form:${storageKey}`
  const [draft, setDraft] = useState<BasedDraft<T> | null>(() => (
    profileId === null ? null : parseRecordDraft(loadFormDraft(profileId, storageKey), current)
  ))
  const edited = draft !== null && editedKeys(draft).length > 0
  useUnsavedDraft(unsavedKey, label, edited)
  useEffect(() => {
    if (profileId !== null) {
      saveFormDraft(profileId, storageKey, edited && draft !== null ? { label, base: draft.base, values: draft.values } : null)
    }
  }, [draft, edited, label, profileId, storageKey])

  const set = (patch: Partial<T>) => setDraft((previous) => nextRecordDraft(previous, current, patch))
  // Сразу, а не эффектом: после сохранения окно закрывается в том же обновлении, и эффект уже не сработал бы.
  const clear = () => {
    setDraft(null)
    if (profileId !== null) {
      saveFormDraft(profileId, storageKey, null)
    }
    forgetUnsavedDraft(unsavedKey)
  }
  return { values: mergedValues(draft, current), edited, set, clear }
}

// Черновик, который больше не к чему применить (перечень уже отправлен вузу), стирается без открытия формы.
export const discardRecordDraft = (key: string) => {
  const profileId = activeProfileId()
  if (profileId !== null) {
    saveFormDraft(profileId, `record:${key}`, null)
  }
  forgetUnsavedDraft(`form:record:${key}`)
}
