export type CatalogGroup<T> = { label: string; items: T[] }

// Длинные списки программ и продуктов в формах группируются: программы — по ИТ-направлению, продукты — по вендору.
// Записи без родителя (архивные, выбранные ранее) идут последней группой с подписью fallback.
export const catalogGroups = <T extends { groupName?: string | null }>(items: readonly T[], fallback: string): CatalogGroup<T>[] => {
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const label = item.groupName?.trim() || fallback
    groups.set(label, [...(groups.get(label) ?? []), item])
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === fallback ? 1 : b === fallback ? -1 : a.localeCompare(b, 'ru')))
    .map(([label, groupItems]) => ({ label, items: groupItems }))
}
