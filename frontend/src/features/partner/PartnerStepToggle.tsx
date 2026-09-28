import { useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type Interaction } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { commandFailureMessage, handledAccessError } from '../interactions/workMarks'

type PartnerStepToggleProps = {
  interaction: Interaction
  onUpdated: (interaction: Interaction) => void
  onReload: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ToggleState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

export const PartnerStepToggle = ({
  interaction,
  onUpdated,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: PartnerStepToggleProps) => {
  const [state, setState] = useState<ToggleState>({ kind: 'idle' })
  const hasStep = (interaction.nextAction?.trim() ?? '').length > 0

  const change = async (visible: boolean) => {
    setState({ kind: 'saving' })
    try {
      onUpdated(await apiClient.updateInteractionPlan(
        interaction.id,
        { version: interaction.version, nextStepPartnerVisible: visible },
        createIdempotencyKey()
      ))
      setState({ kind: 'idle' })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }

  return (
    <div className="card-dialog__section">
      <h3>Кабинет вуза</h3>
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={interaction.nextStepPartnerVisible}
          disabled={!hasStep || state.kind === 'saving'}
          onChange={(event) => void change(event.target.checked)}
        />
        Показывать вузу
      </label>
      <p className="interaction-field-hint">
        {hasStep
          ? 'Шаг и срок появятся в кабинете представителя вуза как «ближайший шаг». При смене шага или срока отметка снимается.'
          : 'Сначала задайте следующий шаг.'}
      </p>
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandFailureMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {state.error instanceof ApiError && state.error.status === 409 && (
            <button type="button" onClick={onReload}>Обновить карточку</button>
          )}
        </div>
      )}
    </div>
  )
}
