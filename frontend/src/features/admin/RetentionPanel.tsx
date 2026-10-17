import { getModules } from '../../shared/modules'
import { useCallback, useEffect, useState } from 'react'
import { ApiError, apiClient, type RetentionPolicy, type RetentionRun } from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { formatDateTime, handledSessionError, requestIdOf, type SessionHandlers } from './adminShared'
import './adminUi.css'
import './adminSecurity.css'
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

const policyRows = (policy: RetentionPolicy, enrolment: boolean): [string, string][] => [
  ['Файлы заказанных отчётов', `удаляются через ${policy.reportFilesDays} дн.`],
  ['Контакты вузов без взаимодействий', `обезличиваются через ${policy.inactiveContactsDays} дн. без изменений взаимодействий и контактов`],
  ['Профили уволенных сотрудников', `обезличиваются через ${policy.dismissedProfilesDays} дн. после блокировки`],
  ['Журнал', `записи удаляются через ${policy.auditEventsDays} дн.`],
  ...(enrolment
    ? [
        ['Анкеты слушателей', `обезличиваются через ${policy.learnerProfilesTerm} после окончания последнего потока слушателя`],
        [
          'Анкет в CRM',
          `${number(policy.learners.profiles)} из ${number(policy.learners.profilesLimit)} — условие уровня защищённости УЗ-4`
        ],
        [
          'Потоки без даты окончания',
          policy.learners.streamsWithoutEndDate === 0
            ? 'нет'
            : `${number(policy.learners.streamsWithoutEndDate)}: срок хранения анкет не определён`
        ],
        [
          'Анкеты со старой версией ключа',
          policy.learners.moduleEnabled ? number(policy.learners.profilesWithOldKey) : 'модуль «Слушатели» выключен'
        ]
      ] as [string, string][]
    : []),
  [
    'Последний запуск',
    policy.lastRun === null
      ? 'ещё не выполнялся'
      : `${formatDateTime(policy.lastRun.occurredAt)}, ${policy.lastRun.actorDisplayName}: ${policy.lastRun.details ?? ''}`
  ]
]

export const RetentionPanel = ({ onSessionExpired, onProfileUnavailable }: SessionHandlers) => {
  const [state, setState] = useState<PolicyState>({ kind: 'loading' })
  const [runState, setRunState] = useState<RunState>({ kind: 'idle' })
  const enrolment = getModules().enrolment

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
    <section className="admin-page" aria-labelledby="retention-title" aria-busy={state.kind === 'loading'}>
      <div className="page-title">
        <h2 id="retention-title">Сроки хранения</h2>
        <HintButton label="О разделе">
          Сроки заданы конфигурацией сервера по умолчанию до решения оператора персональных данных. Задача выполняется
          каждую ночь; результат каждого запуска записывается в журнал.
        </HintButton>
        <span className="page-title__spacer" />
        {state.kind === 'ready' && (
          <button
            type="button"
            className="button--secondary"
            disabled={runState.kind === 'running'}
            onClick={() => setRunState({ kind: 'confirming' })}
          >
            {runState.kind === 'running' ? 'Применяем…' : 'Применить сроки сейчас'}
          </button>
        )}
      </div>
      {state.kind === 'loading' && <p className="organizations-message" role="status">Загружаем сроки хранения…</p>}
      {state.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>Не удалось загрузить сроки хранения.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" className="button--secondary" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          <div className="admin-table-wrap">
            <table className="admin-table retention-table" aria-label="Сроки хранения">
              <thead>
                <tr>
                  <th scope="col">Что</th>
                  <th scope="col">Срок</th>
                </tr>
              </thead>
              <tbody>
                {policyRows(state.policy, enrolment).map(([label, value]) => (
                  <tr key={label}>
                    <td className="admin-table__cell-main"><span className="admin-table__main">{label}</span></td>
                    <td data-label="Срок">{value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {enrolment && state.policy.learners.nearLimit && (
            <p className="security-warning" role="status">
              Анкет слушателей уже {number(state.policy.learners.profiles)}. При {number(state.policy.learners.profilesLimit)} и
              более субъектов в CRM уровень защищённости повышается с УЗ-4 до УЗ-3: согласуйте с оператором
              персональных данных срок хранения анкет или меры УЗ-3.
            </p>
          )}
          {enrolment && state.policy.learners.streamsWithoutEndDate > 0 && (
            <p className="security-warning" role="status">
              Анкеты слушателей потоков без даты окончания автоматически не обезличиваются, пока оператор зачисления
              не укажет дату окончания потока.
            </p>
          )}
        </>
      )}
      {runState.kind === 'done' && (
        <p className="security-success" role="status">
          Удалено файлов отчётов: {runState.run.reportFilesDeleted}, обезличено контактов: {runState.run.contactsAnonymized},
          профилей: {runState.run.profilesAnonymized}, {enrolment && `анкет слушателей: ${runState.run.learnersAnonymized}, `}удалено записей
          журнала: {runState.run.auditEventsDeleted}.
        </p>
      )}
      {runState.kind === 'failed' && (
        <div className="organizations-message organizations-message--error" role="alert">
          <p>{runError(runState.error)}</p>
          <SupportDetails requestId={runState.error instanceof ApiError ? runState.error.requestId : undefined} />
        </div>
      )}
      <ConfirmDialog
        open={runState.kind === 'confirming'}
        title="Применить сроки хранения сейчас?"
        description={enrolment
          ? 'Файлы отчётов старше срока будут удалены, контакты, профили и анкеты слушателей с истёкшим сроком — обезличены без возможности восстановления.'
          : 'Файлы отчётов старше срока будут удалены, контакты и профили с истёкшим сроком — обезличены без возможности восстановления.'}
        confirmLabel="Применить"
        onConfirm={() => void run()}
        onCancel={() => setRunState({ kind: 'idle' })}
      />
    </section>
  )
}
