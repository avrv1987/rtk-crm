import { useCallback, useEffect, useState } from 'react'
import { CardDialog } from '../interactions/cardUi'
import { agreementsApi, formatDate, type AgreementOptions, type WorkAgreements } from './agreementsApi'
import { activeStageRole, agreementStatusLabels } from './agreementModel'
import { AgreementForm } from './AgreementForm'
import { AgreementTransitions } from './AgreementTransitions'
import { CommandError, useAccessErrorHandler } from './agreementUi'
import './agreements.css'

type WorkAgreementBlockProps = {
  interactionId: string
  organizationId: string
  currentStageId: string
  revision: number
  canEdit: boolean
  startCreate?: boolean
  onCreateStarted?: () => void
  onChanged?: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'failed'; error: unknown }

export const WorkAgreementBlock = ({
  interactionId,
  organizationId,
  currentStageId,
  revision,
  canEdit,
  startCreate = false,
  onCreateStarted,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: WorkAgreementBlockProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [state, setState] = useState<Load<WorkAgreements>>({ kind: 'loading' })
  const [creating, setCreating] = useState(false)
  const [options, setOptions] = useState<Load<AgreementOptions> | null>(null)

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', value: await agreementsApi.workAgreements(interactionId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, interactionId])

  useEffect(() => {
    void load()
  }, [load, revision])

  const changed = () => {
    void load()
    onChanged?.()
  }

  const openCreate = useCallback(async () => {
    setCreating(true)
    setOptions({ kind: 'loading' })
    try {
      setOptions({ kind: 'ready', value: await agreementsApi.options(organizationId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setOptions({ kind: 'failed', error })
      }
    }
  }, [handleAccessError, organizationId])

  useEffect(() => {
    if (startCreate && canEdit) {
      onCreateStarted?.()
      void openCreate()
    }
  }, [canEdit, onCreateStarted, openCreate, startCreate])

  if (state.kind === 'loading') {
    return null
  }
  if (state.kind === 'failed') {
    return (
      <section className="work-agreement" aria-label="Договор с вузом">
        <CommandError error={state.error} fallback="Не удалось загрузить договор с вузом." onRetry={() => void load()} />
      </section>
    )
  }
  const work = state.value
  const role = activeStageRole(work.stages, currentStageId)
  const hasSigningStage = work.stages.some((stage) => stage.role === 'SIGNING')

  return (
    <section className="work-agreement" aria-labelledby="work-agreement-title">
      <h5 id="work-agreement-title">Договор с вузом</h5>
      {work.signingRuleEnabled && hasSigningStage && (
        <p className="work-agreement__rule">
          {role === 'SIGNING'
            ? 'Этап завершится, когда договор будет подписан.'
            : 'Этап «Подписание» завершится только с подписанным договором.'}
        </p>
      )}
      {work.agreements.length === 0 ? (
        <div className="work-agreement__empty">
          <p>{role === null ? 'Договор с вузом не привязан.' : 'Создайте договор с вузом и привяжите его к этому взаимодействию.'}</p>
          {canEdit && <button type="button" onClick={() => void openCreate()}>Создать договор</button>}
        </div>
      ) : (
        <ul className="work-agreement__list">
          {work.agreements.map((agreement) => (
            <li key={agreement.id}>
              <span className="work-agreement__head">
                <a href={`#/organizations/${work.organizationId}?agreement=${agreement.id}`}>
                  № {agreement.number}{agreement.concludedOn ? ` от ${formatDate(agreement.concludedOn)}` : ''}
                </a>
                <span className={`agreements__status agreements__status--${agreement.status.toLowerCase()}`}>
                  {agreementStatusLabels[agreement.status]}
                </span>
              </span>
              {canEdit && (
                <AgreementTransitions
                  agreement={agreement}
                  interactionId={interactionId}
                  onDone={changed}
                  handleAccessError={handleAccessError}
                />
              )}
            </li>
          ))}
        </ul>
      )}
      <CardDialog open={creating} title="Новый договор с вузом" onClose={() => setCreating(false)}>
        {options?.kind === 'loading' && <p role="status">Загружаем справочники…</p>}
        {options?.kind === 'failed' && (
          <CommandError error={options.error} fallback="Не удалось загрузить справочники." onRetry={() => void openCreate()} />
        )}
        {options?.kind === 'ready' && (
          <AgreementForm
            organizationId={organizationId}
            options={options.value}
            defaultInteractionIds={[interactionId]}
            onSaved={() => { setCreating(false); changed() }}
            onCancel={() => setCreating(false)}
            handleAccessError={handleAccessError}
          />
        )}
      </CardDialog>
    </section>
  )
}
