import { useEffect } from 'react'
import { useUnsavedDraftLabels } from './unsavedDrafts'
import './workCard.css'

const warnBeforeUnload = (event: BeforeUnloadEvent) => {
  event.preventDefault()
  event.returnValue = ''
}

export const UnsavedDraftNotice = () => {
  const labels = useUnsavedDraftLabels()
  const hasDrafts = labels.length > 0

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
      <strong>Есть несохранённый черновик: {labels.join('; ')}.</strong>
      <span>Текст хранится только в этой вкладке и вернётся в форму после перехода в другой раздел, обрыва связи или повторного входа; сам он не отправляется. При выходе из CRM черновики удаляются.</span>
    </div>
  )
}
