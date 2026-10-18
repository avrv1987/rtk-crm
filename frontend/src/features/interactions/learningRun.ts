export type RunPhase = 'upcoming' | 'running' | 'finished' | 'unknown'

type RunNumbers = {
  participants: number
  completed?: number | null
  notCompleted?: number | null
  runStartsOn?: string | null
  runEndsOn?: string | null
}

// «16 из 20 с известным статусом; данные по 20 из 100»: доля завершивших — только среди участий с известным статусом,
// а вторая часть показывает, на какую долю всех участий она опирается. Так же подписывает сервер строки «Динамики обучения».
export const completionBasis = (
  completed: number | null | undefined,
  known: number | null | undefined,
  participants: number | null | undefined
): string => {
  if (participants === null || participants === undefined) {
    return 'нет данных: у потоков нет наблюдений'
  }
  if (completed === null || completed === undefined || known === null || known === undefined) {
    return 'нет данных: завершение в Moodle не отслеживается'
  }
  return `${completed} из ${known} с известным статусом; данные по ${known} из ${participants}`
}

export const runPhase = (run: RunNumbers, today: string): RunPhase => {
  if (!run.runStartsOn || !run.runEndsOn) {
    return 'unknown'
  }
  if (today < run.runStartsOn) {
    return 'upcoming'
  }
  return today < run.runEndsOn ? 'running' : 'finished'
}

export const notFinishedRow = (run: RunNumbers, today: string): { label: string; value: number | null } | null => {
  const phase = runPhase(run, today)
  if (phase === 'upcoming') {
    return null
  }
  if (phase === 'running') {
    return { label: 'Учатся сейчас', value: run.completed === null || run.completed === undefined ? null : run.participants - run.completed }
  }
  return { label: 'Не завершили', value: run.notCompleted ?? null }
}
