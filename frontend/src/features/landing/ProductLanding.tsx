import { useEffect, useState } from 'react'
import { getModules } from '../../shared/modules'
import { landingLinks } from './landingLinks'
import { rolesFor } from './landingModules'
import { connectionsLine, productOutcomes, productScreens, productSteps } from './productContent'
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
              <a href="#pl-screens">Экраны</a>
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
                От первой заявки до отчёта по соглашению: у каждого дела есть ответственный, следующий шаг и срок.
              </p>
              <div className="landing-hero__actions">
                {loginButton}
                <a className="landing-hero__check" href="#/help">Справка</a>
              </div>
            </div>
            <figure className="landing-shot landing-shot--hero">
              <img src="/landing/control-matrix.png" width="1440" height="900" alt="Таблица руководителя: программы по этапам, просрочки отмечены красным" />
              <figcaption>Где застряли вузы по каждой программе.</figcaption>
            </figure>
          </div>
        </section>

        <section id="pl-outcomes" className="landing-section" aria-labelledby="pl-outcomes-title">
          <div className="landing-container">
            <h2 id="pl-outcomes-title">Что даёт система</h2>
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
            <h2 id="pl-roles-title">Каждый видит свою часть работы</h2>
            <dl className="pl-roles">
              {rolesFor(modules).map((role) => (
                <div key={role.title} className="pl-roles__row">
                  <dt>{role.title}</dt>
                  <dd>{role.benefit}</dd>
                </div>
              ))}
            </dl>
          </div>
        </section>

        <section id="pl-work" className="landing-section" aria-labelledby="pl-work-title">
          <div className="landing-container">
            <h2 id="pl-work-title">Как идёт работа</h2>
            <ol className="pl-steps">
              {productSteps.map((step) => <li key={step}>{step}</li>)}
            </ol>
          </div>
        </section>

        <section id="pl-screens" className="landing-section" aria-labelledby="pl-screens-title">
          <div className="landing-container">
            <h2 id="pl-screens-title">Как это выглядит</h2>
            <div className="landing-shots">
              {productScreens.map((screen) => (
                <figure key={screen.src} className={screen.phone ? 'landing-shot landing-shot--phone' : 'landing-shot'}>
                  <img src={screen.src} width={screen.width} height={screen.height} alt={screen.alt} loading="lazy" />
                  <figcaption>{screen.caption}</figcaption>
                </figure>
              ))}
            </div>
          </div>
        </section>

      </main>

      <footer className="landing-footer">
        <div className="landing-container landing-footer__inner">
          <div className="landing-footer__top">
            <div className="landing-footer__about">
              <p className="landing-footer__brand">CRM ИТ Школы РТК</p>
              <p>Работа с вузами, колледжами и школами по ИТ-программам и ИТ-продуктам. {connectionsLine(modules)}</p>
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
