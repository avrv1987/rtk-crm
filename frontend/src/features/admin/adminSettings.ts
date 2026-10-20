import type {
  ModuleStatus,
  PersonalDataRequest,
  PersonalDataRequestKind,
  PersonalDataRequestStatus,
  RouteVersionsRow,
  SystemSetting
} from '../../shared/api/client'

export const settingGroups: { group: SystemSetting['group']; title: string; hint: string }[] = [
  {
    group: 'RETENTION',
    title: 'Сроки хранения',
    hint: 'Через сколько данные удаляются или обезличиваются. Ночная задача и кнопка «Применить сроки сейчас» берут значения отсюда.'
  },
  {
    group: 'SIGNALS',
    title: 'Пороги сигналов',
    hint: 'Пороги «Контроля», сигналов LMS и отчётов. Норматив этапа задаётся в шаблоне этапов и важнее общего порога.'
  },
  {
    group: 'PRIVACY',
    title: 'Персональные данные',
    hint: 'Срок ответа на обращение субъекта и учёт согласий. Нужен ли учёт согласий и для кого — решает оператор ПДн.'
  },
  {
    group: 'SITE',
    title: 'Главная страница',
    hint: 'Что видит посетитель до входа: конкурсную или продуктовую главную. Продуктовую можно заранее посмотреть по демонстрационной ссылке с конкурсной главной.'
  }
]

const units: Record<SystemSetting['unit'], string> = {
  DAYS: 'дн.',
  HOURS: 'ч',
  MONTHS: 'мес.',
  PERCENT: '%',
  FLAG: '',
  LANDING: ''
}

export const unitLabel = (unit: SystemSetting['unit']) => units[unit]

export const landingOptions: [number, string][] = [[0, 'Конкурсная'], [1, 'Продуктовая']]

export const settingValueText = (unit: SystemSetting['unit'], value: number) => (
  unit === 'FLAG'
    ? (value === 0 ? 'выключено' : 'включено')
    : unit === 'LANDING' ? (value === 0 ? 'конкурсная' : 'продуктовая') : `${value} ${units[unit]}`
)

/** Флаг и выбор главной сохраняются сразу, без кнопки «Сохранить». */
export const savedOnChange = (unit: SystemSetting['unit']) => unit === 'FLAG' || unit === 'LANDING'

/** Черновик поля: пусто или вне пределов — сохранить нельзя, текст подсказывает пределы. */
export const settingDraftProblem = (setting: Pick<SystemSetting, 'min' | 'max' | 'unit'>, draft: string): string | null => {
  const value = Number(draft)
  if (draft.trim() === '' || !Number.isInteger(value) || value < setting.min || value > setting.max) {
    return `Целое число от ${setting.min} до ${setting.max} ${units[setting.unit]}`.trim()
  }
  return null
}

export const requestKindLabels: Record<PersonalDataRequestKind, string> = {
  INFORMATION: 'Сведения об обработке',
  RECTIFICATION: 'Уточнение',
  DELETION: 'Удаление',
  CONSENT_WITHDRAWAL: 'Отзыв согласия'
}

export const requestStatusLabels: Record<PersonalDataRequestStatus, string> = {
  NEW: 'Новое',
  IN_PROGRESS: 'В работе',
  DONE: 'Исполнено',
  REJECTED: 'Отказано'
}

export const closedStatus = (status: PersonalDataRequestStatus) => status === 'DONE' || status === 'REJECTED'

export const dueText = (request: Pick<PersonalDataRequest, 'dueState' | 'dueOn'>, formatDate: (value: string) => string) => (
  request.dueState === 'OVERDUE'
    ? `Срок истёк ${formatDate(request.dueOn)}`
    : request.dueState === 'SOON' ? `Срок ${formatDate(request.dueOn)}` : `до ${formatDate(request.dueOn)}`
)

/** Строки сводки, где у команды есть работы на прежних версиях маршрута: это напоминание руководителю. */
export const outdatedRoutes = (rows: RouteVersionsRow[]) => rows.filter((row) => row.outdatedWorks > 0)

export const versionsText = (row: RouteVersionsRow) => row.versions
  .map((item) => `v${item.version}${item.version === row.latestVersion ? ' (последняя)' : ''}: ${item.works}`)
  .join(', ')

export const moduleHealth = (module: ModuleStatus): { tone: 'off' | 'ok' | 'warning' | 'error'; text: string } => {
  if (!module.enabled) {
    return { tone: 'off', text: 'Выключен' }
  }
  if (module.errors !== null && module.errors > 0) {
    return { tone: 'error', text: `Ошибок за 7 дней: ${module.errors}` }
  }
  if (module.lastSuccessAt === null) {
    return { tone: 'warning', text: 'Включён, успешных операций ещё не было' }
  }
  return { tone: 'ok', text: 'Работает' }
}
