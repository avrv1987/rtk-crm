export type ImportStep = 1 | 2 | 3 | 4

export const importSteps: Array<{ step: ImportStep; label: string }> = [
  { step: 1, label: 'Файл' },
  { step: 2, label: 'Столбцы' },
  { step: 3, label: 'Проверка' },
  { step: 4, label: 'Применение' }
]

type ImportProgress = {
  hasSheets: boolean
  planStatus: 'PREVIEWED' | 'APPLIED' | null
  confirming: boolean
}

/** Текущий шаг по состоянию экрана: файл проверен, построен предпросмотр, идёт подтверждение или уже применено. */
export const importStepOf = ({ hasSheets, planStatus, confirming }: ImportProgress): ImportStep => {
  if (planStatus === 'APPLIED' || (planStatus !== null && confirming)) {
    return 4
  }
  if (planStatus !== null) {
    return 3
  }
  return hasSheets ? 2 : 1
}

/** Словарь статусов из текста: по строке «значение в файле=значение в CRM». */
export const parseTransferStatuses = (value: string) => {
  const statuses: Record<string, string> = {}
  const lines = value.split(/\r?\n/)
  for (const [index, line] of lines.entries()) {
    const trimmed = line.trim()
    if (!trimmed) {
      continue
    }
    const separator = trimmed.indexOf('=')
    if (separator < 1 || separator === trimmed.length - 1) {
      return { statuses, error: `Строка ${index + 1}: используйте формат «значение в файле=значение в CRM».` }
    }
    const source = trimmed.slice(0, separator).trim()
    const target = trimmed.slice(separator + 1).trim()
    if (!source || !target || statuses[source] !== undefined) {
      return { statuses, error: `Строка ${index + 1}: значение статуса должно быть непустым и уникальным.` }
    }
    statuses[source] = target
  }
  return { statuses }
}
