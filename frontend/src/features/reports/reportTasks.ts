import type { ReportColumn } from '../../shared/api/client'
import type { ReportView } from './ReportCatalog'
import type { PeriodPreset, PeriodPresetId } from './reportPeriods'
import { matchingPreset } from './reportPeriods.ts'
import type { ReportSelection } from './reportSelection'

export type ReportTaskId = 'attention' | 'done' | 'learning' | 'agreements'

export type ReportTask = {
  id: ReportTaskId
  title: string
  question: string
  method: string
  view: ReportView
  linkLabel: string
  related: ReportView[]
  // Пресет таблицы отчёта; у отчётов с собственным экраном («Динамика обучения») его нет.
  preset?: { kind: ReportSelection['kind']; period: PeriodPresetId | null; columns?: ReportColumn[] }
}

const managerWorkColumns: ReportColumn[] = [
  'MANAGER', 'ORGANIZATION', 'PRODUCTS', 'PROGRAM', 'STAGE_AT_START', 'STAGE_AT_END', 'TRANSITIONS', 'PERIOD_COMMENTS',
  'NEXT_ACTION', 'NEXT_ACTION_AT', 'OVERDUE_DAYS', 'LMS_LEARNING', 'LMS_CHECK'
]

export const reportTasks: readonly ReportTask[] = [
  {
    id: 'attention',
    title: 'Что требует внимания',
    question: 'Где просрочен следующий шаг, нет шага или срока, есть проблемы и риски.',
    method: 'Отчёт «Работа менеджеров» без периода: строка — взаимодействие, которое ведётся сейчас. Состояние срока и дни'
      + ' просрочки — по сроку следующего шага на момент формирования, проблемы и риски — открытые отметки, менеджер — текущий'
      + ' ответственный за вуз.',
    view: 'manager-work',
    linkLabel: 'Открыть отчёт',
    related: ['portfolio', 'kam-review'],
    preset: {
      kind: 'MANAGER_WORK',
      period: null,
      columns: ['MANAGER', 'ORGANIZATION', 'INTERACTION', 'STAGE_AT_END', 'DUE_STATE', 'NEXT_ACTION', 'NEXT_ACTION_AT',
        'OVERDUE_DAYS', 'DAYS_ON_STAGE', 'PROBLEM', 'RISK', 'LMS_CHECK']
    }
  },
  {
    id: 'done',
    title: 'Что сделано за период',
    question: 'Кто с какими вузами работал в прошлом месяце и как сдвинулись взаимодействия.',
    method: 'Отчёт «Работа менеджеров» за прошлый месяц: этап на начало и конец периода — по истории переходов, переходы'
      + ' вперёд и назад считаются вместе, комментарии — только людей. Менеджер — ведущий вуз на конец периода. Переход'
      + ' этапа — это действие, а не результат.',
    view: 'manager-work',
    linkLabel: 'Открыть отчёт',
    related: ['events', 'duration', 'kam-review'],
    preset: { kind: 'MANAGER_WORK', period: 'PREVIOUS_MONTH', columns: managerWorkColumns }
  },
  {
    id: 'learning',
    title: 'Что происходит с обучением',
    question: 'Сколько учится и завершает по месяцам — по вузам или ИТ-программам.',
    method: 'Отчёт «Динамика обучения» по наблюдениям Moodle. Числа — участия в потоках, а не уникальные люди; месяц без'
      + ' наблюдения — «нет данных», а не ноль.',
    view: 'learning-dynamics',
    linkLabel: 'Открыть отчёт',
    related: ['demand']
  },
  {
    id: 'agreements',
    title: 'Исполнение договоров',
    question: 'Что по договорам с вузами сделано по плану и что отстаёт в этом году.',
    method: 'Отчёт «Реализация договоров» за этот год: строка — мероприятие плана. Мероприятие входит, если его фактические,'
      + ' иначе плановые сроки, иначе срок договора пересекаются с периодом. План и факт — в единице мероприятия, разные'
      + ' единицы не складываются.',
    view: 'agreements',
    linkLabel: 'Открыть отчёт',
    related: ['signing-plan', 'agreements-period', 'confirmations'],
    preset: { kind: 'AGREEMENTS', period: 'YEAR' }
  }
]

export const taskHref = (task: ReportTask) => `#/reports/${task.view}?task=${task.id}`

const presetPhrases: Record<PeriodPresetId, string> = {
  WEEK: 'за эту неделю',
  MONTH: 'за этот месяц',
  QUARTER: 'за этот квартал',
  YEAR: 'за этот год',
  PREVIOUS_MONTH: 'за прошлый месяц'
}

const dottedDate = (value: string) => value.split('-').reverse().join('.')

export const periodPhrase = (from: string, to: string, today: string) => {
  if (from === '' && to === '') {
    return 'без периода'
  }
  const preset = matchingPreset(from, to, today)
  if (preset !== null) {
    return presetPhrases[preset]
  }
  return `за период${from === '' ? '' : ` с ${dottedDate(from)}`}${to === '' ? '' : ` по ${dottedDate(to)}`}`
}

// Описание задачи над отчётом написано для периода пресета; если сотрудник сменил период, слова о периоде
// меняются вместе с ним, иначе над отчётом за этот месяц оставалось бы «за прошлый месяц».
export const taskMethod = (task: ReportTask, from: string, to: string, today: string) => {
  const preset = task.preset?.period
  return preset === undefined || preset === null ? task.method : task.method.replace(presetPhrases[preset], periodPhrase(from, to, today))
}

// Задача приходит параметром task и действует, только если адрес ведёт в её отчёт: чужой параметр не меняет выбор.
export const reportTaskFor = (view: ReportView | null, query: string): ReportTask | null => {
  const id = new URLSearchParams(query).get('task')
  return reportTasks.find((task) => task.id === id && task.view === view) ?? null
}

// Пресет задаёт вид, период и колонки; отборы (вузы, менеджеры и т. п.) сбрасываются, чтобы методика на карточке
// совпадала с тем, что посчитано.
export const taskSelection = (selection: ReportSelection, task: ReportTask, presets: PeriodPreset[]): ReportSelection => {
  if (task.preset === undefined) {
    return selection
  }
  const period = task.preset.period === null ? undefined : presets.find((item) => item.id === task.preset?.period)
  return {
    ...selection,
    kind: task.preset.kind,
    from: period?.from ?? '',
    to: period?.to ?? '',
    organizationIds: [],
    organizationType: '',
    directionIds: [],
    includeNoDirection: false,
    programIds: [],
    includeNoProgram: false,
    productIds: [],
    includeNoProduct: false,
    managerIds: [],
    includeNoManager: false,
    stages: [],
    workStatuses: [],
    flags: [],
    agreement: { vendorIds: [], licenseSigned: '', licenseExpiresBy: '', notTransferred: [] },
    minDaysOnStage: '',
    change: '',
    columns: task.preset.columns === undefined ? selection.columns : { ...selection.columns, [task.preset.kind]: task.preset.columns }
  }
}
