export type ReportsHomeTab = 'tasks' | 'catalog' | 'saved' | 'jobs'

// Вкладка главной раздела «Отчёты» задаётся параметром tab; без него или с неизвестным значением — задачи, полный
// каталог — второй уровень.
export const homeTabFromQuery = (query: string | undefined): ReportsHomeTab => {
  const tab = new URLSearchParams(query ?? '').get('tab')
  return tab === 'catalog' || tab === 'saved' || tab === 'jobs' ? tab : 'tasks'
}

export const homeTabHash = (tab: ReportsHomeTab) => (tab === 'tasks' ? '#/reports' : `#/reports?tab=${tab}`)

export const jobsLinkHash = homeTabHash('jobs')
