import './workCard.css'

export type DraftConflict = {
  key: string
  label: string
  current: string
  draft: string
}

type DraftConflictNoticeProps = {
  conflicts: DraftConflict[]
  onKeepDraft: () => void
  onTakeCurrent: () => void
}

export const DraftConflictNotice = ({ conflicts, onKeepDraft, onTakeCurrent }: DraftConflictNoticeProps) => (
  conflicts.length === 0 ? null : (
    <div className="interaction-catalog-message interaction-catalog-message--error draft-conflict" role="alert">
      <p>Пока черновик ждал сохранения, эти поля уже изменили в карточке. Выберите, что оставить, и затем сохраните.</p>
      <ul>
        {conflicts.map((conflict) => (
          <li key={conflict.key}>
            {conflict.label}: сейчас в карточке «{conflict.current}», в вашем черновике «{conflict.draft}»
          </li>
        ))}
      </ul>
      <div className="interaction-plan-form__actions">
        <button type="button" onClick={onKeepDraft}>Оставить мои значения</button>
        <button type="button" className="button--secondary" onClick={onTakeCurrent}>Взять значения из карточки</button>
      </div>
    </div>
  )
)
