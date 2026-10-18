import type { Me, ReportKind } from '../../shared/api/client'
import { getModules } from '../../shared/modules'
import { reportTasks, taskHref } from './reportTasks'

export type ReportView =
  | 'manager-work'
  | 'portfolio'
  | 'events'
  | 'snapshot'
  | 'duration'
  | 'demand'
  | 'agreements'
  | 'signing-plan'
  | 'agreements-period'
  | 'statistics'
  | 'learning-dynamics'
  | 'kam-review'
  | 'confirmations'

export const kindViews: Record<ReportKind, ReportView> = {
  PORTFOLIO: 'portfolio',
  EVENTS: 'events',
  SNAPSHOT: 'snapshot',
  DURATION: 'duration',
  DEMAND: 'demand',
  AGREEMENTS: 'agreements',
  MANAGER_WORK: 'manager-work'
}

export const viewKind = (view: ReportView): ReportKind | null => (
  (Object.keys(kindViews) as ReportKind[]).find((kind) => kindViews[kind] === view) ?? null
)

type CatalogGroup = 'Контроль' | 'Договоры' | 'Обучение' | 'Служебные'

const catalogGroups: readonly CatalogGroup[] = ['Контроль', 'Договоры', 'Обучение', 'Служебные']

type CatalogCard = {
  group: CatalogGroup
  view: ReportView
  title: string
  purpose: string
  data: string
}

export const reportCatalog = (role: Me['role']): CatalogCard[] => [
  {
    group: 'Контроль',
    view: 'manager-work',
    title: 'Работа менеджеров за период',
    purpose: 'Какой менеджер с каким вузом работал, на каком этапе взаимодействие было в начале и в конце периода, сколько было переходов, комментариев и просрочек.',
    data: 'Менеджеры, вузы, продукты, этапы на начало и конец периода, шаги и сроки'
  },
  {
    group: 'Контроль',
    view: 'portfolio',
    title: 'Портфель взаимодействий',
    purpose: 'Все взаимодействия с действующими вузами сейчас: на каком этапе, кто ведёт, какой следующий шаг и где есть риск.',
    data: 'Взаимодействия, этапы, ответственные, отметки, договоры и лицензии'
  },
  {
    group: 'Контроль',
    view: 'events',
    title: 'События за период',
    purpose: role === 'USER'
      ? 'Что происходило во взаимодействиях: переходы, комментарии, изменения этапов и планов.'
      : 'Что происходило во взаимодействиях: переходы, комментарии, изменения планов, назначения и смены КАМ.',
    data: 'История взаимодействий с датой, автором и ответственным на момент события'
  },
  {
    group: 'Контроль',
    view: 'snapshot',
    title: 'Состояние портфеля на дату',
    purpose: 'Каким был портфель на выбранный день — для сравнения с началом месяца или квартала.',
    data: 'Этап и ответственный, восстановленные по истории'
  },
  {
    group: 'Контроль',
    view: 'duration',
    title: 'Длительность этапов и время до входа в последний этап',
    purpose: 'Сколько дней взаимодействия проводят на каждом этапе и сколько проходит от создания до входа в последний этап — чтобы найти, где они застревают. Вход в последний этап — не подтверждённое завершение.',
    data: 'Средние и максимальные сроки по командам, программам и этапам'
  },
  {
    group: 'Обучение',
    view: 'demand',
    title: 'Востребованность программ',
    purpose: getModules().enrolment
      ? 'Какие ИТ-программы нужны вузам: заявки, оплаты и обучение по каждой программе; для отмеченных направлений рядом показан спрос рынка труда.'
      : 'Какие ИТ-программы нужны вузам: заявки и обучение по каждой программе; для отмеченных направлений рядом показан спрос рынка труда.',
    data: getModules().enrolment
      ? 'Заявки и оплаты с сайта, потоки и обучающиеся из Moodle, вакансии рынка труда'
      : 'Заявки с сайта, потоки и обучающиеся из Moodle, вакансии рынка труда'
  },
  {
    group: 'Договоры',
    view: 'agreements',
    title: 'Реализация договоров',
    purpose: 'План и факт мероприятий по договорам с вузами за период.',
    data: 'Мероприятия, объёмы, сроки, связанные взаимодействия и подтверждения'
  },
  {
    group: 'Договоры',
    view: 'signing-plan',
    title: 'План подписаний и продлений договоров',
    purpose: 'Какие договоры вузов будут подписаны и продлены в квартале или году: по вузам, КАМ и командам, с отметкой «просрочено».',
    data: 'Плановые даты и сроки действия договоров'
  },
  {
    group: 'Договоры',
    view: 'agreements-period',
    title: 'Договоры за период',
    purpose: 'Сколько договоров подписано и расторгнуто, какие переходы статусов были, сколько в среднем длится согласование и что застряло на согласовании.',
    data: 'История статусов договоров, даты отправки и подписания'
  },
  {
    group: 'Служебные',
    view: 'statistics',
    title: 'Статистика и диаграммы',
    purpose: 'Числа по этапам, вузам, программам или месяцам — столбцами или графиком, с выгрузкой PNG и PDF.',
    data: 'Те же данные и фильтры, что в таблицах отчётов'
  },
  {
    group: 'Обучение',
    view: 'learning-dynamics',
    title: 'Динамика обучения',
    purpose: 'Обучающиеся и завершившие по месяцам — линии по вузам или ИТ-программам, за последний год или свой период.',
    data: 'История наблюдений Moodle по потокам'
  },
  ...(role === 'USER' ? [] : [{
    group: 'Контроль' as const,
    view: 'kam-review' as const,
    title: 'Разбор КАМ',
    purpose: 'События, открытые шаги и риски одного КАМ за период на одной странице — для регулярного разбора.',
    data: 'События, активные взаимодействия и их отметки'
  }]),
  {
    group: 'Договоры',
    view: 'confirmations',
    title: 'Подтверждения по договорам',
    purpose: 'Документы, подтверждающие мероприятия договоров: найти и скачать одним архивом.',
    data: 'Проверенные файлы по вузу, виду мероприятия и периоду'
  }
]

export const catalogTitle = (role: Me['role'], view: ReportView) => (
  reportCatalog(role).find((card) => card.view === view)?.title ?? ''
)

export type ReportMeta = { title: string; group: string; data: string }

export const reportMeta = (role: Me['role'], view: ReportView): ReportMeta => {
  const card = reportCatalog(role).find((item) => item.view === view)
  return { title: card?.title ?? '', group: card?.group ?? 'Служебные', data: card?.data ?? '' }
}

// Первый уровень раздела: четыре задачи, каждая ведёт в преднастроенный отчёт и называет его методику. Связанные
// отчёты показываются, только если они есть в каталоге роли, поэтому права не расширяются.
export const ReportTasks = ({ role }: { role: Me['role'] }) => {
  const titles = new Map(reportCatalog(role).map((card) => [card.view, card.title]))
  return (
    <section className="report-tasks" aria-label="Задачи">
      <ul className="report-tasks__list">
        {reportTasks.filter((task) => titles.has(task.view)).map((task) => (
          <li key={task.id} className="report-task">
            <h3 className="report-task__title">{task.title}</h3>
            <p className="report-task__question">{task.question}</p>
            <p className="report-task__method">{task.method}</p>
            <a className="button-link report-task__open" href={taskHref(task)}>{task.linkLabel}</a>
            {task.related.some((view) => titles.has(view)) && (
              <p className="report-task__related">
                Ещё:{' '}
                {task.related.filter((view) => titles.has(view)).map((view, index) => (
                  <span key={view}>{index > 0 && ', '}<a href={`#/reports/${view}`}>{titles.get(view)}</a></span>
                ))}
              </p>
            )}
          </li>
        ))}
      </ul>
      <p className="report-tasks__all">Остальные отчёты, конструктор и диаграммы — на вкладке <a href="#/reports?tab=catalog">«Все отчёты»</a>.</p>
    </section>
  )
}

// В каталоге у отчёта одна строка «для чего»; строка «какие данные» ушла в «?» на странице отчёта.
export const ReportCatalog = ({ role }: { role: Me['role'] }) => (
  <section className="report-catalog" aria-label="Все отчёты">
    {catalogGroups.map((group) => (
      <section key={group} className="report-catalog__group" aria-labelledby={`report-group-${group}`}>
        <h3 id={`report-group-${group}`} className="report-catalog__group-title">{group}</h3>
        <ul className="report-catalog__list">
          {reportCatalog(role).filter((card) => card.group === group).map((card) => (
            <li key={card.view}>
              <a className="report-card" href={`#/reports/${card.view}`}>
                <span className="report-card__title">{card.title}</span>
                <span className="report-card__purpose">{card.purpose}</span>
              </a>
            </li>
          ))}
        </ul>
      </section>
    ))}
  </section>
)
