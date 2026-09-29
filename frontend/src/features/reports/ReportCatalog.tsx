import type { Me, ReportKind } from '../../shared/api/client'

export type ReportView =
  | 'portfolio'
  | 'events'
  | 'snapshot'
  | 'duration'
  | 'demand'
  | 'agreements'
  | 'signing-plan'
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
  AGREEMENTS: 'agreements'
}

export const viewKind = (view: ReportView): ReportKind | null => (
  (Object.keys(kindViews) as ReportKind[]).find((kind) => kindViews[kind] === view) ?? null
)

type CatalogCard = {
  view: ReportView
  title: string
  purpose: string
  data: string
}

export const reportCatalog = (role: Me['role']): CatalogCard[] => [
  {
    view: 'portfolio',
    title: 'Портфель взаимодействий',
    purpose: 'Все работы с вузами сейчас: на каком этапе, кто ведёт, какой следующий шаг и где есть риск.',
    data: 'Работы, этапы, ответственные, отметки, договоры и лицензии'
  },
  {
    view: 'events',
    title: 'События за период',
    purpose: role === 'USER'
      ? 'Что происходило в работах: переходы, комментарии, изменения этапов и планов.'
      : 'Что происходило в работах: переходы, комментарии, изменения планов, назначения и смены КАМ.',
    data: 'История работ с датой, автором и ответственным на момент события'
  },
  {
    view: 'snapshot',
    title: 'Состояние портфеля на дату',
    purpose: 'Каким был портфель на выбранный день — для сравнения с началом месяца или квартала.',
    data: 'Этап и ответственный, восстановленные по истории'
  },
  {
    view: 'duration',
    title: 'Длительность этапов и цикла',
    purpose: 'Сколько дней работы проводят на каждом этапе и во всём цикле — чтобы найти, где они застревают.',
    data: 'Средние и максимальные сроки по командам, программам и этапам'
  },
  {
    view: 'demand',
    title: 'Востребованность программ',
    purpose: 'Какие ИТ-программы нужны вузам: заявки, оплаты и обучение по каждой программе.',
    data: 'Заявки и оплаты с сайта, потоки и обучающиеся из Moodle'
  },
  {
    view: 'agreements',
    title: 'Реализация соглашений',
    purpose: 'План и факт мероприятий по соглашениям с вузами за период.',
    data: 'Мероприятия, объёмы, сроки, связанные работы и подтверждения'
  },
  {
    view: 'signing-plan',
    title: 'План подписаний и продлений',
    purpose: 'Какие соглашения вузов будут подписаны и продлены в квартале или году: по вузам, КАМ и командам, с отметкой «просрочено».',
    data: 'Плановые даты и сроки действия соглашений'
  },
  {
    view: 'statistics',
    title: 'Статистика и диаграммы',
    purpose: 'Числа по этапам, вузам, программам или месяцам — столбцами или графиком, с выгрузкой PNG и PDF.',
    data: 'Те же данные и фильтры, что в таблицах отчётов'
  },
  {
    view: 'learning-dynamics',
    title: 'Динамика обучения',
    purpose: 'Обучающиеся и завершившие по месяцам — линии по вузам или ИТ-программам, за последний год или свой период.',
    data: 'История наблюдений Moodle по потокам'
  },
  ...(role === 'USER' ? [] : [{
    view: 'kam-review' as const,
    title: 'Разбор КАМ',
    purpose: 'События, открытые шаги и риски одного КАМ за период на одной странице — для регулярного разбора.',
    data: 'События, активные работы и их отметки'
  }]),
  {
    view: 'confirmations',
    title: 'Подтверждения по соглашениям',
    purpose: 'Документы, подтверждающие мероприятия соглашений: найти и скачать одним архивом.',
    data: 'Проверенные файлы по вузу, виду мероприятия и периоду'
  }
]

export const catalogTitle = (role: Me['role'], view: ReportView) => (
  reportCatalog(role).find((card) => card.view === view)?.title ?? ''
)

export const ReportCatalog = ({ role }: { role: Me['role'] }) => (
  <section className="report-catalog" aria-labelledby="report-catalog-title">
    <h2 id="report-catalog-title" className="reports__section-title">Выберите отчёт</h2>
    <ul className="report-catalog__grid">
      {reportCatalog(role).map((card) => (
        <li key={card.view}>
          <a className="report-card" href={`#/reports/${card.view}`}>
            <span className="report-card__title">{card.title}</span>
            <span className="report-card__purpose">{card.purpose}</span>
            <span className="report-card__data">{card.data}</span>
          </a>
        </li>
      ))}
    </ul>
  </section>
)
