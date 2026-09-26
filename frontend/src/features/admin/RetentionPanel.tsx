import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, type RetentionPolicy, type RetentionRun } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './security.css'

type PolicyState =
  | { kind: 'loading' }
  | { kind: 'ready'; policy: RetentionPolicy }
  | { kind: 'failed'; requestId?: string }

type RunState =
  | { kind: 'idle' }
  | { kind: 'confirming' }
  | { kind: 'running' }
  | { kind: 'done'; run: RetentionRun }
  | { kind: 'failed'; error: unknown }

const number = (value: number) => value.toLocaleString('ru-RU')

const runError = (error: unknown) => (
  error instanceof ApiError && error.code === 'RETENTION_RUNNING'
    ? 'Сроки хранения уже применяются. Дождитесь завершения и обновите страницу.'
    : 'Не удалось применить сроки хранения. Повторите попытку позже.'
)

export const RetentionPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<PolicyState>({ kind: 'loading' })
  const [runState, setRunState] = useState<RunState>({ kind: 'idle' })

  const handleError = useCallback((error: unknown) => (
    handledSessionError(error, { onSessionExpired, onProfileUnavailable })
  ), [onProfileUnavailable, onSessionExpired])

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', policy: await apiClient.getRetentionPolicy() })
    } catch (error) {
      if (!handleError(error)) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [handleError])

  useEffect(() => {
    void load()
  }, [load])

  const run = async () => {
    setRunState({ kind: 'running' })
    try {
      const result = await apiClient.runRetention()
      setRunState({ kind: 'done', run: result })
      void load()
    } catch (error) {
      if (!handleError(error)) {
        setRunState({ kind: 'failed', error })
      }
    }
  }

  return (
    <section className="security-panel" aria-labelledby="retention-title" aria-busy={state.kind === 'loading'}>
      <div className="security-panel__header">
        <div>
          <p className="eyebrow">Безопасность</p>
          <h2 id="retention-title">Сроки хранения</h2>
        </div>
      </div>
      <p className="security-panel__intro">
        Сроки заданы конфигурацией сервера по умолчанию до решения оператора персональных данных. Задача выполняется
        каждую ночь; результат каждого запуска записывается в журнал.
      </p>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем сроки хранения…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить сроки хранения.</p>
          {state.requestId && <p className="request-id">Request ID: {state.requestId}</p>}
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          <dl className="security-fields security-fields--grid">
            <div>
              <dt>Файлы заказанных отчётов</dt>
              <dd>удаляются через {state.policy.reportFilesDays} дн.</dd>
            </div>
            <div>
              <dt>Контакты вузов без работы</dt>
              <dd>обезличиваются через {state.policy.inactiveContactsDays} дн. без изменений работ и контактов</dd>
            </div>
            <div>
              <dt>Профили уволенных сотрудников</dt>
              <dd>обезличиваются через {state.policy.dismissedProfilesDays} дн. после блокировки</dd>
            </div>
            <div>
              <dt>Журнал администратора и безопасности</dt>
              <dd>записи удаляются через {state.policy.auditEventsDays} дн.</dd>
            </div>
            <div>
              <dt>Анкеты слушателей</dt>
              <dd>обезличиваются через {state.policy.learnerProfilesTerm} после окончания последнего потока слушателя</dd>
            </div>
            <div>
              <dt>Анкет в CRM</dt>
              <dd>
                {number(state.policy.learners.profiles)} из {number(state.policy.learners.profilesLimit)} — условие
                уровня защищённости УЗ-4
              </dd>
            </div>
            <div>
              <dt>Потоки без даты окончания</dt>
              <dd>
                {state.policy.learners.streamsWithoutEndDate === 0
                  ? 'нет'
                  : `${number(state.policy.learners.streamsWithoutEndDate)}: срок хранения анкет не определён`}
              </dd>
            </div>
            <div>
              <dt>Анкеты со старой версией ключа</dt>
              <dd>
                {state.policy.learners.moduleEnabled
                  ? number(state.policy.learners.profilesWithOldKey)
                  : 'модуль «Слушатели» выключен'}
              </dd>
            </div>
            <div>
              <dt>Последний запуск</dt>
              <dd>
                {state.policy.lastRun === null
                  ? 'ещё не выполнялся'
                  : `${formatDateTime(state.policy.lastRun.occurredAt)}, ${state.policy.lastRun.actorDisplayName}: ${state.policy.lastRun.details ?? ''}`}
              </dd>
            </div>
          </dl>
          {state.policy.learners.nearLimit && (
            <p className="security-warning" role="status">
              Анкет слушателей уже {number(state.policy.learners.profiles)}. При {number(state.policy.learners.profilesLimit)} и
              более субъектов в CRM уровень защищённости повышается с УЗ-4 до УЗ-3: согласуйте с оператором
              персональных данных срок хранения анкет или меры УЗ-3.
            </p>
          )}
          {state.policy.learners.streamsWithoutEndDate > 0 && (
            <p className="security-warning" role="status">
              Анкеты слушателей потоков без даты окончания автоматически не обезличиваются, пока оператор зачисления
              не укажет дату окончания потока.
            </p>
          )}
          <div className="security-actions">
            <button
              type="button"
              className="button--secondary"
              disabled={runState.kind === 'running'}
              onClick={() => setRunState({ kind: 'confirming' })}
            >
              {runState.kind === 'running' ? 'Применяем…' : 'Применить сроки сейчас'}
            </button>
          </div>
        </>
      )}
      {runState.kind === 'done' && (
        <p className="security-success" role="status">
          Удалено файлов отчётов: {runState.run.reportFilesDeleted}, обезличено контактов: {runState.run.contactsAnonymized},
          профилей: {runState.run.profilesAnonymized}, анкет слушателей: {runState.run.learnersAnonymized}, удалено записей
          журнала: {runState.run.auditEventsDeleted}.
        </p>
      )}
      {runState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{runError(runState.error)}</p>
          {runState.error instanceof ApiError && <p className="request-id">Request ID: {runState.error.requestId}</p>}
        </div>
      )}
      <ConfirmDialog
        open={runState.kind === 'confirming'}
        title="Применить сроки хранения сейчас?"
        description="Файлы отчётов старше срока будут удалены, контакты, профили и анкеты слушателей с истёкшим сроком — обезличены без возможности восстановления."
        confirmLabel="Применить"
        onConfirm={() => void run()}
        onCancel={() => setRunState({ kind: 'idle' })}
      />
    </section>
  )
}
