import { useState } from 'react'
import { ApiError, apiClient, type MailInvitationRedeemed } from '../../shared/api/client'
import { SessionScreen } from '../session/SessionScreen'
import './mail.css'

// Страница ссылки из письма-приглашения. Пароль запрашивается кнопкой, а не при открытии: почтовые сканеры ссылок
// открывают страницу сами, и приглашение не должно сгорать до того, как его откроет человек.
export const InvitationScreen = ({ token }: { token: string }) => {
  const [state, setState] = useState<
    { kind: 'idle' } | { kind: 'busy' } | { kind: 'done'; result: MailInvitationRedeemed } | { kind: 'failed'; message: string }
  >({ kind: 'idle' })
  const [copied, setCopied] = useState(false)

  const redeem = async () => {
    setState({ kind: 'busy' })
    try {
      setState({ kind: 'done', result: await apiClient.redeemMailInvitation(token) })
    } catch (failure) {
      setState({ kind: 'failed', message: failure instanceof ApiError ? failure.message : 'Не удалось открыть ссылку. Повторите позже.' })
    }
  }

  return (
    <SessionScreen title="Вход в CRM ИТ Школы РТК">
      <div className="mail-invite">
        {(state.kind === 'idle' || state.kind === 'busy') && (
          <>
            <p>Нажмите кнопку, чтобы получить временный пароль. Он показывается один раз — сразу сохраните его или войдите.</p>
            <button type="button" disabled={state.kind === 'busy'} onClick={() => void redeem()}>Показать пароль</button>
          </>
        )}
        {state.kind === 'done' && (
          <>
            <dl>
              <dt>Логин</dt>
              <dd><code>{state.result.login}</code></dd>
              <dt>Временный пароль</dt>
              <dd><code>{state.result.temporaryPassword}</code></dd>
            </dl>
            <p>При первом входе система попросит задать свой пароль. Эта ссылка больше не работает.</p>
            <div className="mail-admin__actions">
              <button type="button" className="button--secondary" onClick={() => void navigator.clipboard.writeText(state.result.temporaryPassword).then(() => setCopied(true), () => setCopied(false))}>
                Скопировать пароль
              </button>
              {copied && <span role="status">Скопировано</span>}
              <button
                type="button"
                onClick={() => {
                  // Адрес приглашения не должен стать адресом возврата после входа: ссылка уже не работает.
                  window.history.replaceState(null, '', '#/')
                  apiClient.login()
                }}
              >
                Войти в CRM
              </button>
            </div>
          </>
        )}
        {state.kind === 'failed' && (
          <>
            <p role="alert">{state.message}</p>
            <p><a href="#/">Перейти ко входу</a></p>
          </>
        )}
      </div>
    </SessionScreen>
  )
}
