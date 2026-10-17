export type ReportsHomeTab = 'catalog' | 'saved' | 'jobs'

// Вкладка главной раздела «Отчёты» задаётся параметром tab; без него или с неизвестным значением — каталог.
export const homeTabFromQuery = (query: string | undefined): ReportsHomeTab => {
  const tab = new URLSearchParams(query ?? '').get('tab')
  return tab === 'saved' || tab === 'jobs' ? tab : 'catalog'
}

export const homeTabHash = (tab: ReportsHomeTab) => (tab === 'catalog' ? '#/reports' : `#/reports?tab=${tab}`)

export const jobsLinkHash = homeTabHash('jobs')
