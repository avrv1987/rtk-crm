export type RunPhase = 'upcoming' | 'running' | 'finished' | 'unknown'

type RunNumbers = {
  participants: number
  completed?: number | null
  notCompleted?: number | null
  runStartsOn?: string | null
  runEndsOn?: string | null
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
