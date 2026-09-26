import { useCallback, useState } from 'react'
import { loadFormDraft, saveFormDraft } from './drafts'

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
