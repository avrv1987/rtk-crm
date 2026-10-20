import { useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type WorkControl } from '../../shared/api/client'
import { withCount } from '../../shared/format/plural'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { missingStepIds } from './controlModel'

type MissingStepActionProps = {
  control: WorkControl
  onDone: (text: string, persistent: boolean) => void
}

export const MissingStepAction = ({ control, onDone }: MissingStepActionProps) => {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const ids = missingStepIds(control)
  if (ids.length === 0) {
    return null
  }

  const assign = async () => {
    setConfirming(false)
    setBusy(true)
    try {
      const result = await apiClient.assignMissingSteps(ids, createIdempotencyKey())
      onDone(`Шаг «${result.nextAction}» задан: ${withCount(result.assigned, 'карточка', 'карточки', 'карточек')}`
        + (result.skipped > 0 ? `; пропущено: ${result.skipped} (шаг уже есть или карточка не вашей команды)` : ''), false)
    } catch (error) {
      onDone(error instanceof ApiError && error.message !== '' ? error.message : 'Не удалось задать шаг. Повторите попытку.', true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button type="button" className="button--secondary" disabled={busy} onClick={() => setConfirming(true)}>
        {busy ? 'Задаём шаг…' : `Задать шаг карточкам без шага (${ids.length})`}
      </button>
      <ConfirmDialog
        open={confirming}
        title="Задать шаг карточкам без шага?"
        description={`${withCount(ids.length, 'карточка', 'карточки', 'карточек')} по текущему отбору получат шаг по умолчанию из настройки импорта. Уже введённый текст шага сохранится. Действие попадёт в журнал и в историю каждой карточки.`}
        confirmLabel="Задать шаг"
        onConfirm={() => void assign()}
        onCancel={() => setConfirming(false)}
      />
    </>
  )
}
