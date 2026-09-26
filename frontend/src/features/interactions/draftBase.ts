export type BasedDraft<T extends object> = {
  base: T
  values: T
}

const same = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right)

const keysOf = <T extends object>(value: T) => Object.keys(value) as (keyof T)[]

export const editedKeys = <T extends object>(draft: BasedDraft<T> | null): (keyof T)[] => (
  draft === null ? [] : keysOf(draft.values).filter((key) => !same(draft.values[key], draft.base[key]))
)

export const conflictKeys = <T extends object>(draft: BasedDraft<T> | null, current: T): (keyof T)[] => (
  draft === null
    ? []
    : editedKeys(draft).filter((key) => !same(current[key], draft.base[key]) && !same(current[key], draft.values[key]))
)

export const mergedValues = <T extends object>(draft: BasedDraft<T> | null, current: T): T => {
  if (draft === null) {
    return current
  }
  const merged = { ...current }
  editedKeys(draft).forEach((key) => {
    merged[key] = draft.values[key]
  })
  return merged
}

export const changedDraft = <T extends object>(draft: BasedDraft<T> | null, current: T, change: Partial<T>): BasedDraft<T> => ({
  base: draft?.base ?? current,
  values: { ...(draft?.values ?? current), ...change }
})

export const keepDraftValues = <T extends object>(draft: BasedDraft<T>, current: T): BasedDraft<T> => ({
  base: current,
  values: mergedValues(draft, current)
})

export const takeCurrentValues = <T extends object>(draft: BasedDraft<T>, current: T): BasedDraft<T> => {
  const values = mergedValues(draft, current)
  conflictKeys(draft, current).forEach((key) => {
    values[key] = current[key]
  })
  return { base: current, values }
}

export const parseBasedDraft = <T extends object>(
  value: unknown,
  parseValues: (value: unknown) => T | null
): BasedDraft<T> | null => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const base = parseValues(record.base)
  const values = parseValues(record.values)
  return base === null || values === null ? null : { base, values }
}
