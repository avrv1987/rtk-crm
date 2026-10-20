import { useEffect } from 'react'
import { useUnsavedDraftEntries } from './unsavedDrafts'
import './workCard.css'

const warnBeforeUnload = (event: BeforeUnloadEvent) => {
  event.preventDefault()
  event.returnValue = ''
}

export const UnsavedDraftNotice = () => {
  const entries = useUnsavedDraftEntries()
  const kept = entries.filter((entry) => entry.restorable).map((entry) => entry.label)
  const unstored = entries.filter((entry) => !entry.restorable).map((entry) => entry.label)
  const hasDrafts = entries.length > 0

  useEffect(() => {
    if (!hasDrafts) {
      return
    }
    window.addEventListener('beforeunload', warnBeforeUnload)
    return () => window.removeEventListener('beforeunload', warnBeforeUnload)
  }, [hasDrafts])

  if (!hasDrafts) {
    return null
  }
  return (
    <div className="unsaved-drafts" role="status">
      {kept.length > 0 && (
        <>
          <strong>Есть несохранённый черновик: {kept.join('; ')}.</strong>
          <span>Текст хранится только в этой вкладке и вернётся в форму после перехода в другой раздел, обрыва связи, F5 или повторного входа; сам он не отправляется. При выходе из CRM черновики удаляются.</span>
        </>
      )}
      {unstored.length > 0 && (
        <>
          <strong>Не сохранено: {unstored.join('; ')}.</strong>
          <span>Этот текст не хранится в черновике: сохраните его, прежде чем уходить с экрана.</span>
        </>
      )}
    </div>
  )
}
