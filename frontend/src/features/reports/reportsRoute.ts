export type ReportsHomeTab = 'tasks' | 'catalog' | 'saved' | 'jobs'

// Вкладка главной раздела «Отчёты» задаётся параметром tab; без него или с неизвестным значением — задачи, полный
// каталог — второй уровень.
export const homeTabFromQuery = (query: string | undefined): ReportsHomeTab => {
  const tab = new URLSearchParams(query ?? '').get('tab')
  return tab === 'catalog' || tab === 'saved' || tab === 'jobs' ? tab : 'tasks'
}

export const homeTabHash = (tab: ReportsHomeTab) => (tab === 'tasks' ? '#/reports' : `#/reports?tab=${tab}`)

export const jobsLinkHash = homeTabHash('jobs')

// «Полный отчёт» из блока «Топ-5 программ» «Сводки команд»: тот же период, что у блока.
export const demandReportHash = (from: string, to: string) => (
  `#/reports/demand?${new URLSearchParams({ source: 'overview', from, to }).toString()}`
)

export const demandLinkPeriod = (query: string): { from: string; to: string } | null => {
  const params = new URLSearchParams(query)
  const from = params.get('from')
  const to = params.get('to')
  const iso = /^\d{4}-\d{2}-\d{2}$/
  if (params.get('source') !== 'overview' || from === null || to === null || !iso.test(from) || !iso.test(to) || from > to) {
    return null
  }
  return { from, to }
}
