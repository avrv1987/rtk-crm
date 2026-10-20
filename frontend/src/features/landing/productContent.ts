import type { Modules } from '../../shared/modules'

type Outcome = { title: string; text: string }

type Screen = { src: string; width: number; height: number; alt: string; caption: string; phone?: boolean }

export const productOutcomes: Outcome[] = [
  { title: 'Видно, где каждый вуз', text: 'Этапы, просрочки и ответственные по всем вузам — на одном экране.' },
  { title: 'Менеджер знает, что делать', text: 'Утром готов список дел, заявки и данные обучения приходят сами.' },
  { title: 'Процесс меняете сами', text: 'Этапы работы руководитель правит без программистов, история сохраняется.' },
  { title: 'Отчёты одной кнопкой', text: 'Отчёт за любой период сразу в Excel и PDF.' }
]

export const productSteps: string[] = [
  'Заявка вуза приходит с сайта и сразу попадает в работу.',
  'Менеджер ведёт вуз по этапам: встреча, договор, обучение.',
  'Руководитель видит, где работа застряла, и вовремя вмешивается.',
  'Отчёт по соглашению собирается сам, без таблиц и опросов.'
]

export const productScreens: Screen[] = [
  {
    src: '/help/img/work-kam.png',
    width: 758,
    height: 482,
    alt: 'Список дел менеджера: сроки и шаги по вузам',
    caption: 'Список дел менеджера на сегодня и неделю.'
  },
  {
    src: '/landing/org-path-university-a.png',
    width: 1440,
    height: 900,
    alt: 'Карточка вуза: этапы работы, срок шага, договор и обучение',
    caption: 'Вся история работы с вузом в одной карточке.'
  },
  {
    src: '/help/img/mobile-work.png',
    width: 390,
    height: 844,
    alt: 'CRM в браузере телефона: список дел и нижняя панель разделов',
    caption: 'На телефоне — в обычном браузере.',
    phone: true
  }
]

/** Замер нагрузки на сервере, версия 2.5.3, 20.10.2026 (docs/load-testing.md). */
export const reliabilityLine = 'Замер на рабочем сервере: 50 сотрудников одновременно, ни одного ответа дольше секунды. Каждый видит только данные своей роли.'

/** Подключения одной строкой; MAX — только когда модуль включён на этом сервере. */
export const connectionsLine = (modules: Modules): string => (
  modules.max ? 'Подключаются Moodle, сайт школы и MAX.' : 'Подключаются Moodle и сайт школы.'
)
