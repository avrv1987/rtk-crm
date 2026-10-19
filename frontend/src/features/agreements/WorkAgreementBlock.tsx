import { useCallback, useEffect, useState } from 'react'
import { CardDialog } from '../interactions/cardUi'
import { todayInMoscow } from '../../shared/format/datetime'
import { agreementsApi, formatDate, type AgreementOptions, type AgreementSummary, type WorkAgreements } from './agreementsApi'
import { activeStageRole, agreementStatusLabels, agreementTerm, linkCandidates, linkInput } from './agreementModel'
import { AgreementForm } from './AgreementForm'
import { AgreementTransitions } from './AgreementTransitions'
import { AgreementTermMark, CommandError, useAccessErrorHandler, useCommandKey } from './agreementUi'
import { signingRuleHint } from '../interactions/workCardModel'
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
  const [linking, setLinking] = useState(false)
  const [organizationAgreements, setOrganizationAgreements] = useState<Load<AgreementSummary[]> | null>(null)
  const [linkId, setLinkId] = useState('')
  const [linkSaving, setLinkSaving] = useState(false)
  const [linkError, setLinkError] = useState<unknown>(null)
  const linkKey = useCommandKey()

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

  const openLink = useCallback(async () => {
    setCreating(false)
    setLinking(true)
    setLinkError(null)
    setOrganizationAgreements({ kind: 'loading' })
    try {
      setOrganizationAgreements({ kind: 'ready', value: await agreementsApi.list(organizationId) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setOrganizationAgreements({ kind: 'failed', error })
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
  const candidates = organizationAgreements?.kind === 'ready'
    ? linkCandidates(organizationAgreements.value, work.agreements.map((agreement) => agreement.id))
    : []
  const selectedLink = candidates.some((agreement) => agreement.id === linkId) ? linkId : candidates[0]?.id ?? ''
  const today = todayInMoscow()

  const link = async () => {
    setLinkSaving(true)
    setLinkError(null)
    try {
      const payload = linkInput(await agreementsApi.get(selectedLink), interactionId)
      await agreementsApi.update(selectedLink, payload, linkKey.keyFor({ agreementId: selectedLink, payload }))
      linkKey.settle()
      setLinking(false)
      changed()
    } catch (error) {
      linkKey.settle(error)
      if (!handleAccessError(error)) {
        setLinkError(error)
      }
    } finally {
      setLinkSaving(false)
    }
  }

  return (
    <section className="work-agreement" aria-labelledby="work-agreement-title">
      <h5 id="work-agreement-title">Договор с вузом</h5>
      {work.signingRuleEnabled && hasSigningStage && signingRuleHint(role, work) !== null && (
        <p className="work-agreement__rule">{signingRuleHint(role, work)}</p>
      )}
      {work.agreements.length === 0 ? (
        <div className="work-agreement__empty">
          <p>{role === null ? 'Договор с вузом не привязан.' : 'Создайте договор с вузом и привяжите его к этому взаимодействию.'}</p>
          {canEdit && <button type="button" onClick={() => void openCreate()}>Создать договор</button>}
          {canEdit && <button type="button" className="button--secondary" onClick={() => void openLink()}>Привязать договор вуза</button>}
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
                <AgreementTermMark agreement={agreement} />
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
      {canEdit && work.agreements.length > 0 && (
        <p>
          <button type="button" className="button--secondary" onClick={() => void openLink()}>Привязать ещё договор вуза</button>
        </p>
      )}
      <CardDialog open={linking} title="Привязать договор вуза" onClose={() => setLinking(false)}>
        {organizationAgreements?.kind === 'loading' && <p role="status">Загружаем договоры вуза…</p>}
        {organizationAgreements?.kind === 'failed' && (
          <CommandError error={organizationAgreements.error} fallback="Не удалось загрузить договоры вуза." onRetry={() => void openLink()} />
        )}
        {organizationAgreements?.kind === 'ready' && candidates.length === 0 && (
          <p>Других договоров у вуза нет. Создайте новый договор.</p>
        )}
        {organizationAgreements?.kind === 'ready' && candidates.length > 0 && (
          <form className="agreement-form" onSubmit={(event) => { event.preventDefault(); void link() }} aria-label="Привязать договор вуза">
            <label>
              <span>Договор вуза</span>
              <select value={selectedLink} onChange={(event) => setLinkId(event.target.value)}>
                {candidates.map((agreement) => (
                  <option key={agreement.id} value={agreement.id}>
                    {[
                      `№ ${agreement.number}`,
                      agreementStatusLabels[agreement.status].toLowerCase(),
                      agreementTerm(agreement, today) === 'expired' ? 'срок истёк' : '',
                      agreement.validUntil ? `до ${formatDate(agreement.validUntil)}` : ''
                    ].filter((part) => part !== '').join(' · ')}
                  </option>
                ))}
              </select>
            </label>
            <p className="agreement-form__hint">Договор свяжется с этим взаимодействием, прежние связи договора сохранятся.</p>
            {linkError !== null && <CommandError error={linkError} fallback="Договор не привязан." />}
            <div className="agreement-form__actions">
              <button type="submit" disabled={linkSaving}>{linkSaving ? 'Привязываем…' : 'Привязать'}</button>
              <button type="button" className="button--secondary" onClick={() => setLinking(false)} disabled={linkSaving}>Отмена</button>
            </div>
          </form>
        )}
      </CardDialog>
      <CardDialog open={creating} title="Новый договор с вузом" onClose={() => setCreating(false)}>
        <p className="agreement-form__hint">
          Договор с вузом уже заключён?{' '}
          <button type="button" className="button--secondary" onClick={() => void openLink()}>Привязать договор вуза</button>
        </p>
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
