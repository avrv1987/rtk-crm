import { useEffect, useState, type ReactNode } from 'react'
import { landingLinks } from './landingLinks'
import './landing.css'

type StageState = 'done' | 'skipped' | 'current' | 'ahead'

type PathStage = {
  name: string
  state: StageState
  optional?: boolean
}

type Feature = {
  title: string
  text: string
  icon: ReactNode
  formats?: string[]
  note?: string
}

type SubmissionLink = {
  label: string
  url: string
  address: string
  note?: string
}

const pathStages: PathStage[] = [
  { name: 'Поиск контакта', state: 'done' },
  { name: 'Уточнение актуальности', state: 'done' },
  { name: 'Встреча', state: 'done' },
  { name: 'Обмен документами', state: 'done' },
  { name: 'Корректировка документов', state: 'skipped', optional: true },
  { name: 'Подписание', state: 'current' },
  { name: 'Передача материалов, лицензии и документации', state: 'ahead' },
  { name: 'Сопровождение внедрения', state: 'ahead' },
  { name: 'Обучение преподавателей', state: 'ahead' },
  { name: 'Актуализация программы', state: 'ahead' },
  { name: 'Занятия', state: 'ahead' },
  { name: 'Обновление документации и материалов', state: 'ahead' },
  { name: 'Повышение квалификации', state: 'ahead' }
]

const stageStatuses: Record<Exclude<StageState, 'done'>, string> = {
  skipped: 'Пропущен, с комментарием',
  current: 'Текущий этап',
  ahead: 'Впереди'
}

const controlFacts: [string, string][] = [
  ['Следующий шаг', 'что сделать и к какому сроку'],
  ['Ответственный', 'менеджер по вузу'],
  ['История', 'каждое изменение с автором и временем'],
  ['Просрочки', 'счётчик и напоминания о шагах']
]

const routeSteps: [string, string][] = [
  ['Шаблон', 'Базовый шаблон повторяет путь из ТЗ. Руководитель ведёт шаблоны своей команды, администратор ведёт общие. Этапы можно переименовать, добавить, переставить, удалить и сделать необязательными.'],
  ['Карточка', 'Новое взаимодействие получает копию этапов шаблона. Правка шаблона не меняет карточки, которые уже в работе. Этапы действующей карточки можно поправить на месте.'],
  ['Переход', 'Этап меняется одним действием. К переходу можно оставить комментарий, при пропуске этапа комментарий обязателен.'],
  ['Контроль', 'У карточки есть следующий шаг, срок и ответственный. Просроченные шаги подсчитываются, о ближайших приходят напоминания. В истории видно, кто и когда что изменил.']
]

const roles = [
  {
    title: 'Менеджер по работе с вузами (КАМ)',
    benefit: 'Знает, что сделать сегодня по каждому своему вузу.',
    duties: [
      '«Моя работа»: сроки шагов, просрочки, напоминания и сигналы из LMS',
      'ведёт этапы, пишет комментарии, прикладывает документы',
      'открывает представителю вуза доступ в кабинет',
      'отчёты и статистика по своим вузам'
    ]
  },
  {
    title: 'Руководитель команды',
    benefit: 'Видит всю команду и вовремя перераспределяет работу.',
    duties: [
      'пульт команды: просрочки, работы без шага, вузы без КАМ',
      'назначает, меняет и снимает ответственных, назначает заместителя на период',
      'шаблоны маршрута команды и показатели по менеджерам'
    ]
  },
  {
    title: 'Руководство',
    benefit: 'Видит картину по всем командам.',
    duties: [
      'сводка по командам и тренд обучения',
      'отчёты и план подписаний и продлений по всем вузам',
      'только просмотр, свои сохранённые отчёты'
    ]
  },
  {
    title: 'Оператор зачисления',
    benefit: 'Ведёт слушателей открытых курсов.',
    duties: [
      'оплаченные заявки с сайта и потоки курсов',
      'анкеты слушателей, загрузка анкет из шаблона',
      'файл «Загрузка пользователей» для LMS'
    ]
  },
  {
    title: 'Администратор платформы',
    benefit: 'Отвечает за доступ и настройки.',
    duties: [
      'учётные записи сотрудников: заведение, сброс пароля, роль и команда',
      'команды, справочники, общие шаблоны, загрузка каталогов из XLS/XLSX',
      'подключение Moodle и сайта, журнал действий',
      'работы с вузами администратору не видны'
    ]
  },
  {
    title: 'Представитель вуза',
    benefit: 'Видит, как идёт совместная работа, без звонков и писем.',
    duties: [
      'входит по приглашению своего КАМ, пароль задаёт при первом входе',
      'этапы работ, ближайший шаг и документы, которые КАМ открыл вузу',
      'соглашения вуза и контакт своего менеджера, только просмотр'
    ]
  }
]

const features: Feature[] = [
  {
    title: 'Отчёты',
    text: 'Больше десяти отчётов: портфель взаимодействий, события за период, состояние на дату, длительность этапов, востребованность программ, реализация соглашений, план подписаний и продлений, динамика обучения и другие. Колонки и их порядок настраиваются, перед выгрузкой доступен предпросмотр, настройки отчёта можно сохранить.',
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinejoin="round" aria-hidden="true">
        <rect x="4" y="3" width="16" height="18" rx="2" />
        <path d="M4 9h16M4 15h16M10 9v12" />
      </svg>
    ),
    formats: ['XLSX', 'XLS', 'PDF', 'JSON'],
    note: 'Фильтры: период, вузы, тип организации (университет, колледж, школа), ИТ-направления, программы, продукты, ответственные, этапы.'
  },
  {
    title: 'Диаграммы',
    text: 'Столбцы по категориям и график по месяцам. Под каждой диаграммой есть таблица с теми же числами. Если данных нет или источник не ответил, система пишет «нет данных» и не подставляет ноль.',
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" aria-hidden="true">
        <path d="M4 5h9M4 10h14M4 15h6M4 20h11" />
      </svg>
    ),
    formats: ['PNG', 'PDF']
  },
  {
    title: 'Документы к этапам',
    text: 'Файлы до 20 МБ. Каждый файл проходит карантин, проверку формата и антивирус. Скачать можно только проверенный файл.',
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinejoin="round" aria-hidden="true">
        <path d="M6 3h8l4 4v6" />
        <path d="M14 3v4h4" />
        <path d="M6 3v18h6" />
        <path d="M17 14l4 1.5v3c0 2-1.8 3.2-4 4-2.2-.8-4-2-4-4v-3z" />
      </svg>
    ),
    formats: ['PDF', 'DOC', 'DOCX', 'XLS', 'XLSX', 'PNG', 'JPEG', 'HEIC', 'ZIP', 'GZIP', 'RAR']
  },
  {
    title: 'Данные об обучении',
    text: 'По каждому потоку из Moodle видно, сколько человек записано, учится и завершило обучение. КАМ получает сигнал, когда обучение расходится с этапом работы. Для записи преподавателей вуза формируется файл «Загрузка пользователей».',
    icon: (
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 4v16M7 20h10M5 7h14" />
        <path d="M5 7l-3 6a3 3 0 0 0 6 0z" />
        <path d="M19 7l-3 6a3 3 0 0 0 6 0z" />
      </svg>
    )
  }
]

const integrations: [string, string][] = [
  ['Moodle', 'CRM читает данные об обучении через веб-сервис Moodle только на чтение. По потокам хранятся числа, без персональных данных учащихся. На стенде подключён демонстрационный Moodle 4.5.'],
  ['Сайт ИТ Школы', 'Заявки и оплаты с сайта поступают через адаптер по описанному API. На стенде работает демонстрационный источник заявок. Адреса и токены источников администратор задаёт в CRM, токены хранятся зашифрованными.'],
  ['Каталоги из Excel', 'Вузы, направления, программы, продукты и ответственные загружаются из XLS и XLSX по шаблону из десяти полей ТЗ.']
]

const criteriaMap: [string, string, string][] = [
  ['Маршрут и его корректировка', '#lp-how', 'Как это работает'],
  ['Отчёты и фильтрация данных', '#lp-features', 'Возможности'],
  ['Объективность диаграмм', '#lp-features', 'Возможности'],
  ['Интеграция в корпоративные системы', '#lp-integrations', 'Интеграции и API'],
  ['Роли и разграничение доступа', '#lp-roles', 'Роли'],
  ['Исходный код и документация', '#lp-check-links', 'Материалы сдачи']
]

const swaggerPath = '/swagger-ui/index.html'
const openApiPath = '/openapi.yaml'

const addressOf = (url: string) => url.replace(/^https?:\/\//, '').replace(/\/$/, '')

const withAddress = (label: string, url: string, note?: string): SubmissionLink[] => (
  url === '' ? [] : [{ label, url, address: addressOf(url), note }]
)

const submissionLinks: SubmissionLink[] = [
  ...withAddress('Исходный код', landingLinks.sourceCodeUrl, 'на GitHub'),
  ...withAddress('Презентация', landingLinks.presentationUrl),
  ...withAddress('Сопроводительная документация (.pdf, .doc)', landingLinks.documentationUrl)
]

const technicalLinks: SubmissionLink[] = [
  { label: 'Swagger UI', url: swaggerPath, address: swaggerPath, note: 'открыт без входа' },
  { label: 'Описание API OpenAPI', url: openApiPath, address: openApiPath, note: 'открыто без входа' },
  ...withAddress('Демонстрационный Moodle', landingLinks.moodleUrl, 'на время показа')
]

type LoginButtonProps = {
  label: string
  pending: boolean
  onLogin: () => void
}

const LoginButton = ({ label, pending, onLogin }: LoginButtonProps) => (
  <button type="button" className="landing-login" disabled={pending} aria-busy={pending} onClick={onLogin}>
    {pending ? 'Переходим ко входу…' : label}
  </button>
)

const LinkRow = ({ link }: { link: SubmissionLink }) => (
  <li className="landing-links__row">
    <div className="landing-links__main">
      <span className="landing-links__label">{link.label}</span>
      <a className="landing-links__address" href={link.url}>{link.address}</a>
    </div>
    {link.note && <span className="landing-links__note">{link.note}</span>}
  </li>
)

type LandingPageProps = {
  onLogin: () => void
}

export const LandingPage = ({ onLogin }: LandingPageProps) => {
  const [pending, setPending] = useState(false)
  const { showJurySection, juryAccessNote, privacyPolicyUrl, operatorDetails, sourceCodeUrl } = landingLinks

  useEffect(() => {
    if (window.location.hash.startsWith('#lp-')) {
      document.getElementById(window.location.hash.slice(1))?.scrollIntoView()
    } else {
      window.scrollTo(0, 0)
    }
    const resetAfterReturn = (event: PageTransitionEvent) => {
      if (event.persisted) {
        setPending(false)
      }
    }
    window.addEventListener('pageshow', resetAfterReturn)
    return () => window.removeEventListener('pageshow', resetAfterReturn)
  }, [])

  const login = () => {
    if (pending) {
      return
    }
    setPending(true)
    onLogin()
  }

  return (
    <div className="landing">
      <a className="landing-skip" href="#lp-main">Перейти к содержанию</a>
      <header className="landing-header">
        <div className="landing-container landing-header__inner">
          <p className="landing-header__brand">CRM ИТ Школы РТК</p>
          <div className="landing-header__actions">
            <nav className="landing-header__nav" aria-label="Разделы страницы">
              <a href="#lp-how">Как это работает</a>
              <a href="#lp-roles">Роли</a>
              <a href="#lp-features">Возможности</a>
              {showJurySection && <a href="#lp-check">Для проверки</a>}
              <a href="#/help">Справка</a>
            </nav>
            <LoginButton label="Войти" pending={pending} onLogin={login} />
          </div>
        </div>
      </header>

      <main id="lp-main">
        <section className="landing-hero">
          <div className="landing-container landing-hero__grid">
            <div className="landing-hero__text">
              <p className="eyebrow">Для сотрудников ИТ Школы РТК</p>
              <h1>Каждое взаимодействие с вузом — на одной карте пути</h1>
              <p className="landing-hero__lead">
                Система ведёт работу ИТ Школы РТК с вузами, колледжами и школами по ИТ-программам и ИТ-продуктам: от поиска контакта до повышения квалификации преподавателей. На каждом этапе видно, кто отвечает, какой следующий шаг и к какому сроку.
              </p>
              <div className="landing-hero__actions">
                <LoginButton label="Войти в CRM" pending={pending} onLogin={login} />
                {showJurySection && <a className="button-link" href="#lp-check">Материалы для проверки</a>}
              </div>
              <p className="landing-hero__note">Вход по корпоративной учётной записи. Пароль вводится только на странице защищённого входа.</p>
              <p className="landing-hero__audience">
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75" aria-hidden="true">
                  <circle cx="10" cy="10" r="8" />
                  <path d="M10 9v5M10 6.2v.1" strokeLinecap="round" />
                </svg>
                <span>Работу ведут сотрудники ИТ Школы РТК. Представитель вуза, колледжа или школы получает кабинет по приглашению своего менеджера (КАМ) и видит совместные работы, открытые ему документы и соглашения.</span>
              </p>
            </div>

            <figure className="landing-path">
              <div className="landing-card landing-path__card">
                <div className="landing-path__top">
                  <p className="eyebrow">Карта пути</p>
                  <span className="landing-path__chip">Схема экрана</span>
                </div>
                <p className="landing-path__title">Взаимодействие с вузом по ИТ-программе</p>
                <ol className="landing-path__stages" aria-label="Схема карты пути: 13 этапов и контроль исполнения">
                  {pathStages.map((stage, index) => (
                    <li
                      key={stage.name}
                      className={`landing-stage landing-stage--${stage.state}`}
                      aria-current={stage.state === 'current' ? 'step' : undefined}
                    >
                      <span className="landing-stage__number" aria-hidden="true">{index + 1}</span>
                      <span className="landing-stage__body">
                        <span className="landing-stage__name">{stage.name}</span>
                        {stage.optional && <span className="landing-stage__optional">Необязательный</span>}
                        <span className="landing-stage__status">
                          {stage.state === 'done' ? <><span aria-hidden="true">✓ </span>Пройден</> : stageStatuses[stage.state]}
                        </span>
                      </span>
                    </li>
                  ))}
                </ol>
                <div className="landing-control">
                  <p className="landing-control__title">14 · Контроль исполнения</p>
                  <p className="landing-control__hint">Действует на каждом этапе:</p>
                  <dl>
                    {controlFacts.map(([term, value]) => (
                      <div key={term}>
                        <dt>{term}</dt>
                        <dd>{value}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              </div>
              <figcaption>Базовый маршрут повторяет путь из технического задания: 13 этапов и контроль исполнения. Маршрут можно изменить под свою программу.</figcaption>
            </figure>
          </div>
        </section>

        <section id="lp-how" className="landing-section">
          <div className="landing-container">
            <p className="eyebrow">Как это работает</p>
            <h2 className="landing-how__title">Маршрут задаётся один раз, а ведётся в каждой карточке</h2>
            <p className="landing-section__lead">Этапы задаёт шаблон. У каждого взаимодействия своя копия маршрута.</p>
            <ol className="landing-steps">
              {routeSteps.map(([title, text], index) => (
                <li key={title} className="landing-step">
                  <span className="landing-step__number" aria-hidden="true">{index + 1}</span>
                  {index < routeSteps.length - 1 && <span className="landing-step__line" aria-hidden="true" />}
                  <div className="landing-step__text">
                    <h3>{title}</h3>
                    <p>{text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="lp-roles" className="landing-section">
          <div className="landing-container">
            <p className="eyebrow">Роли</p>
            <h2>Каждый видит свою часть работы</h2>
            <p className="landing-section__lead">Роль и команду сотруднику назначает администратор. Доступ представителю вуза открывает его КАМ. Сервер отдаёт каждому только данные его роли.</p>
            <div className="landing-roles">
              {roles.map((role) => (
                <div key={role.title} className="landing-card landing-role">
                  <h3>{role.title}</h3>
                  <p className="landing-role__benefit">{role.benefit}</p>
                  <ul>
                    {role.duties.map((duty) => <li key={duty}>{duty}</li>)}
                  </ul>
                </div>
              ))}
            </div>
            <p className="landing-roles__first-login">
              <strong>Первый вход.</strong> Если после входа вы видите «Профиль ожидает активации администратором», доступ к вузам появится, когда администратор назначит вам роль и команду.
            </p>
          </div>
        </section>

        <section id="lp-features" className="landing-section">
          <div className="landing-container">
            <p className="eyebrow">Возможности</p>
            <h2>Отчёты, диаграммы и данные об обучении</h2>
            <div className="landing-features">
              {features.map((feature) => (
                <div key={feature.title} className="landing-card landing-feature">
                  <span className="landing-feature__icon">{feature.icon}</span>
                  <h3>{feature.title}</h3>
                  <p className="landing-feature__text">{feature.text}</p>
                  {feature.formats && (
                    <ul className="landing-chips landing-chips--mono">
                      {feature.formats.map((format) => <li key={format}>{format}</li>)}
                    </ul>
                  )}
                  {feature.note && <p className="landing-feature__note">{feature.note}</p>}
                </div>
              ))}
            </div>
          </div>
        </section>

        <section id="lp-integrations" className="landing-section">
          <div className="landing-container">
            <h2>Работает рядом с учебной платформой и сайтом</h2>
            <div className="landing-integrations">
              {integrations.map(([title, text]) => (
                <div key={title} className="landing-integration">
                  <h3>{title}</h3>
                  <p>{text}</p>
                </div>
              ))}
              <div className="landing-integration">
                <h3>Открытый API</h3>
                <p>Описание OpenAPI и Swagger UI открыты без входа. По ним подключаются другие корпоративные системы.</p>
                <div className="landing-integration__links">
                  <a href={swaggerPath}>Swagger UI <span aria-hidden="true">→</span></a>
                  <a href={openApiPath}>OpenAPI (YAML) <span aria-hidden="true">→</span></a>
                </div>
              </div>
            </div>
          </div>
        </section>

        {showJurySection && (
          <section id="lp-check" className="landing-check">
            <div className="landing-container landing-check__grid">
              <div className="landing-check__intro">
                <p className="eyebrow">Для жюри и экспертов</p>
                <h2>Материалы для проверки</h2>
                <p className="landing-check__lead">Система работает на сервере с синтетическими данными. Ссылки собраны по разделу 10 технического задания.</p>
                <h3>Где что на странице</h3>
                <dl className="landing-check__map">
                  {criteriaMap.map(([criterion, href, section]) => (
                    <div key={criterion}>
                      <dt>{criterion}</dt>
                      <dd><a href={href}>{section}</a></dd>
                    </div>
                  ))}
                </dl>
              </div>
              <div className="landing-card landing-check__card">
                <h3 id="lp-check-links">Материалы сдачи</h3>
                <ul className="landing-links">
                  <li className="landing-links__row landing-links__row--prototype">
                    <div className="landing-links__main">
                      <span className="landing-links__label">Стенд</span>
                      <a className="landing-links__address" href="/">{window.location.host}</a>
                    </div>
                    <LoginButton label="Войти в систему" pending={pending} onLogin={login} />
                  </li>
                  {submissionLinks.map((link) => <LinkRow key={link.label} link={link} />)}
                </ul>
                {juryAccessNote && <p className="landing-check__note">Учётные записи для проверки выдаются отдельно: {juryAccessNote}.</p>}
                <h3 className="landing-check__technical">Техническое</h3>
                <ul className="landing-links">
                  {technicalLinks.map((link) => <LinkRow key={link.label} link={link} />)}
                </ul>
                <ul className="landing-chips">
                  {['Java 21', 'Spring Boot', 'React', 'PostgreSQL', 'Keycloak (OIDC)', 'ClamAV', 'Docker'].map((item) => <li key={item}>{item}</li>)}
                </ul>
                <p className="landing-check__note">Поставляется в Docker-контейнерах, ставится на Linux: Ubuntu 22.04+, CentOS Stream 9 или аналог.</p>
              </div>
            </div>
          </section>
        )}

        <section className="landing-final">
          <div className="landing-container">
            <div className="landing-card landing-final__card">
              <div className="landing-final__text">
                <h2>Работаете в ИТ Школе РТК?</h2>
                <p>После входа откроется «Моя работа» с вашими взаимодействиями. Если профиль ещё не активирован, обратитесь к администратору платформы.</p>
              </div>
              <div className="landing-final__action">
                <LoginButton label="Войти в CRM" pending={pending} onLogin={login} />
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="landing-footer">
        <div className="landing-container landing-footer__inner">
          <div className="landing-footer__top">
            <div className="landing-footer__about">
              <p className="landing-footer__brand">CRM ИТ Школы РТК</p>
              <p>Демонстрационный стенд, данные синтетические</p>
            </div>
            <nav className="landing-footer__links" aria-label="Ссылки в подвале">
              <a href={swaggerPath}>Swagger UI</a>
              {sourceCodeUrl && <a href={sourceCodeUrl}>Исходный код на GitHub</a>}
              {privacyPolicyUrl && <a href={privacyPolicyUrl}>Политика обработки персональных данных</a>}
            </nav>
          </div>
          {operatorDetails && <p className="landing-footer__operator">{operatorDetails}</p>}
        </div>
      </footer>
    </div>
  )
}
