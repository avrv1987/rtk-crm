export const catalogTabSlugs = ['directions', 'programs', 'vendors', 'products', 'activity-kinds', 'journal'] as const

export type CatalogsTabSlug = typeof catalogTabSlugs[number]

export const catalogTabLabels: Record<CatalogsTabSlug, string> = {
  directions: 'ИТ-направления',
  programs: 'ИТ-программы',
  vendors: 'Вендоры',
  products: 'ИТ-продукты',
  'activity-kinds': 'Виды мероприятий',
  journal: 'Журнал изменений'
}

/** Подпись главной кнопки страницы; у журнала кнопки нет. */
export const catalogAddLabels: Partial<Record<CatalogsTabSlug, string>> = {
  directions: 'Добавить направление',
  programs: 'Добавить программу',
  vendors: 'Добавить вендора',
  products: 'Добавить продукт',
  'activity-kinds': 'Добавить вид мероприятия'
}

/** Разбор параметра `tab` из строки запроса адреса (с «?» или без); неизвестное значение — «ИТ-направления». */
export const catalogTabFrom = (query: string): CatalogsTabSlug => {
  const value = new URLSearchParams(query.startsWith('?') ? query.slice(1) : query).get('tab')
  return catalogTabSlugs.find((slug) => slug === value) ?? 'directions'
}
