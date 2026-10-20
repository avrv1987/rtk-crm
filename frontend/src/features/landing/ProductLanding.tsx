import { useEffect, useState } from 'react'
import { getModules } from '../../shared/modules'
import { landingLinks } from './landingLinks'
import { rolesFor, rolesLead } from './landingModules'
import {
  productConnections,
  productOutcomes,
  productScreens,
  protectionFacts,
  reliabilityFacts,
  scenarioFor
} from './productContent'
import './landing.css'

const instructionsUrl = landingLinks.sourceCodeUrl === ''
  ? ''
  : `${landingLinks.sourceCodeUrl}/blob/main/docs/public/Инструкции_пользователей.md`

type ProductLandingProps = {
  onLogin: () => void
  /** Сотрудник уже вошёл и смотрит демонстрацию: вместо «Войти» — «Открыть CRM». */
  signedIn?: boolean
  /** Демонстрация при конкурсной главной: ссылка обратно. */
  backToContest?: boolean
  notice?: string | null
}

export const ProductLanding = ({ onLogin, signedIn = false, backToContest = false, notice = null }: ProductLandingProps) => {
  const [pending, setPending] = useState(false)
  const modules = getModules()
  const { privacyPolicyUrl, operatorDetails } = landingLinks

  useEffect(() => {
    if (window.location.hash.startsWith('#pl-')) {
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

  const loginButton = (
    <button type="button" className="landing-login" disabled={pending} aria-busy={pending} onClick={login}>
      {pending ? 'Открываем…' : signedIn ? 'Открыть CRM' : 'Войти'}
    </button>
  )

  return (
    <div className="landing landing--product">
      <a className="landing-skip" href="#pl-main">Перейти к содержанию</a>
      {backToContest && (
        <p className="landing-demo-bar">
          Демонстрация продуктовой главной. <a href="/">Вернуться на текущую главную</a>
        </p>
      )}
      {notice !== null && (
        <div className="landing-session-notice" role="alert">
          <p>{notice}</p>
          <button type="button" onClick={login} disabled={pending}>Войти снова</button>
        </div>
      )}
      <header className="landing-header">
        <div className="landing-container landing-header__inner">
          <p className="landing-header__brand">CRM ИТ Школы РТК</p>
          <div className="landing-header__actions">
            <nav className="landing-header__nav" aria-label="Разделы страницы">
              <a href="#pl-roles">Роли</a>
              <a href="#pl-day">Как идёт работа</a>
              <a href="#pl-screens">Экраны</a>
              <a href="#pl-reliability">Надёжность</a>
              <a href="#/help">Справка</a>
            </nav>
            {loginButton}
          </div>
        </div>
      </header>

      <main id="pl-main">
        <section className="landing-hero">
          <div className="landing-container landing-hero__grid">
            <div className="landing-hero__text">
              <p className="eyebrow">Для сотрудников ИТ Школы РТК</p>
              <h1>Вся работа школы с вузами — в одной системе</h1>
              <p className="landing-hero__lead">
                CRM ведёт путь с каждым вузом, колледжем и школой по ИТ-программам и ИТ-продуктам: от первого контакта до
                повышения квалификации преподавателей и отчёта по соглашению. У каждой работы есть ответственный,
                следующий шаг и срок, а руководитель видит всю картину без таблиц и опросов.
              </p>
              <div className="landing-hero__actions">
                {loginButton}
                <a className="landing-hero__check" href="#/help">Справка</a>
              </div>
              <p className="landing-hero__note">Вход по корпоративной учётной записи. Пароль вводится только на странице защищённого входа.</p>
            </div>
            <figure className="landing-shot landing-shot--hero">
              <img src="/landing/control-matrix.png" width="1440" height="900" alt="«Контроль» руководителя: матрица «программа × этап», красным отмечены просрочки" />
              <figcaption>«Контроль» → «Этапы»: где застряли вузы по каждой программе.</figcaption>
            </figure>
          </div>
        </section>

        <section id="pl-outcomes" className="landing-section" aria-labelledby="pl-outcomes-title">
          <div className="landing-container">
            <p className="eyebrow">Что даёт система</p>
            <h2 id="pl-outcomes-title">Шесть вещей, ради которых она сделана</h2>
            <ul className="landing-outcomes">
              {productOutcomes.map((outcome) => (
                <li key={outcome.title} className="landing-card landing-outcome">
                  <h3>{outcome.title}</h3>
                  <p>{outcome.text}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section id="pl-roles" className="landing-section" aria-labelledby="pl-roles-title">
          <div className="landing-container">
            <p className="eyebrow">Роли</p>
            <h2 id="pl-roles-title">Каждый видит свою часть работы</h2>
            <p className="landing-section__lead">{rolesLead(modules)}</p>
            <div className="landing-roles">
              {rolesFor(modules).map((role) => (
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

        <section id="pl-day" className="landing-section" aria-labelledby="pl-day-title">
          <div className="landing-container">
            <p className="eyebrow">Как идёт работа</p>
            <h2 id="pl-day-title">Один вуз — от заявки до отчёта</h2>
            <p className="landing-section__lead">Так проходит путь вуза в системе. На каждом шаге понятно, кто действует и на каком экране.</p>
            <ol className="landing-steps">
              {scenarioFor(modules).map((step, index, steps) => (
                <li key={step.text} className="landing-step">
                  <span className="landing-step__number" aria-hidden="true">{index + 1}</span>
                  {index < steps.length - 1 && <span className="landing-step__line" aria-hidden="true" />}
                  <div className="landing-step__text">
                    <h3>{step.role}</h3>
                    <p>{step.text}</p>
                    <p className="landing-step__screen">Экран: {step.screen}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="pl-screens" className="landing-section" aria-labelledby="pl-screens-title">
          <div className="landing-container">
            <p className="eyebrow">Экраны</p>
            <h2 id="pl-screens-title">Как это выглядит</h2>
            <p className="landing-section__lead">Снимки сделаны на демонстрационных данных.</p>
            <div className="landing-shots">
              {productScreens.map((screen) => (
                <figure key={screen.src} className={screen.phone ? 'landing-shot landing-shot--phone' : 'landing-shot'}>
                  <img src={screen.src} width={screen.width} height={screen.height} alt={screen.alt} loading="lazy" />
                  <figcaption><strong>{screen.title}.</strong> {screen.caption}</figcaption>
                </figure>
              ))}
            </div>
          </div>
        </section>

        <section id="pl-connections" className="landing-section" aria-labelledby="pl-connections-title">
          <div className="landing-container">
            <p className="eyebrow">Связь с другими системами</p>
            <h2 id="pl-connections-title">Данные приходят сами</h2>
            <div className="landing-integrations">
              {productConnections(modules).map(([title, text]) => (
                <div key={title} className="landing-integration">
                  <h3>{title}</h3>
                  <p>{text}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section id="pl-reliability" className="landing-section" aria-labelledby="pl-reliability-title">
          <div className="landing-container">
            <p className="eyebrow">Надёжность и защита</p>
            <h2 id="pl-reliability-title">Выдерживает работу всей команды</h2>
            <p className="landing-section__lead">Замер нагрузки на рабочем сервере, версия 2.5.3:</p>
            <dl className="landing-facts">
              {reliabilityFacts.map(([value, text]) => (
                <div key={text} className="landing-card landing-fact">
                  <dt>{value}</dt>
                  <dd>{text}</dd>
                </div>
              ))}
            </dl>
            <ul className="landing-protection">
              {protectionFacts(modules).map((fact) => <li key={fact}>{fact}</li>)}
            </ul>
          </div>
        </section>

        <section id="pl-help" className="landing-section" aria-labelledby="pl-help-title">
          <div className="landing-container landing-help">
            <div>
              <h2 id="pl-help-title">Начать работу</h2>
              <p className="landing-section__lead">
                Войдите по корпоративной учётной записи. Как работать в своей роли — в справке внутри CRM и в инструкциях пользователей.
              </p>
            </div>
            <div className="landing-help__actions">
              {loginButton}
              <a className="landing-hero__check" href="#/help">Справка</a>
              {instructionsUrl && <a className="landing-hero__check" href={instructionsUrl}>Инструкции пользователей</a>}
            </div>
          </div>
        </section>
      </main>

      <footer className="landing-footer">
        <div className="landing-container landing-footer__inner">
          <div className="landing-footer__top">
            <div className="landing-footer__about">
              <p className="landing-footer__brand">CRM ИТ Школы РТК</p>
              <p>Работа с вузами, колледжами и школами по ИТ-программам и ИТ-продуктам</p>
            </div>
            <nav className="landing-footer__links" aria-label="Ссылки в подвале">
              <a href="#/help">Справка</a>
              {instructionsUrl && <a href={instructionsUrl}>Инструкции пользователей</a>}
              {privacyPolicyUrl && <a href={privacyPolicyUrl}>Политика обработки персональных данных</a>}
            </nav>
          </div>
          {operatorDetails && <p className="landing-footer__operator">{operatorDetails}</p>}
        </div>
      </footer>
    </div>
  )
}
