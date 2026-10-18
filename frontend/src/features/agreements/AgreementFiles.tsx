import { useState, type FormEvent, type ReactNode } from 'react'
import { agreementsApi, formatInstant, saveBlob, type AgreementFile } from './agreementsApi'
import { fileStatusLabels, signedFileName } from './agreementModel'
import { CommandError, useCommandKey } from './agreementUi'

type AgreementFilesProps = {
  title: string
  agreementId: string
  supplementId?: string
  currentFile: AgreementFile | null
  files: AgreementFile[]
  canEdit: boolean
  onUploaded: () => void
  handleAccessError: (error: unknown) => boolean
  children?: ReactNode
}

const fileMeta = (file: AgreementFile) => [
  `версия ${file.versionNumber}`,
  formatInstant(file.createdAt),
  file.createdByName,
  file.interactionTitle ? `из взаимодействия «${file.interactionTitle}»` : null,
  file.status === 'CLEAN' ? null : fileStatusLabels[file.status]
].filter((part) => part !== null && part !== '').join(' · ')

export const AgreementFiles = ({
  title,
  agreementId,
  supplementId,
  currentFile,
  files,
  canEdit,
  onUploaded,
  handleAccessError,
  children
}: AgreementFilesProps) => {
  const [uploading, setUploading] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const commandKey = useCommandKey()

  const download = async (item: AgreementFile) => {
    setError(null)
    try {
      saveBlob(await agreementsApi.downloadFile(item.id), item.signed ? signedFileName(item.originalName) : item.originalName)
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (file === null) {
      return
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor({ name: file.name, size: file.size, modified: file.lastModified, comment, supplementId })
      await agreementsApi.uploadFile(agreementId, { file, comment, supplementId }, key)
      commandKey.settle()
      setUploading(false)
      setFile(null)
      setComment('')
      onUploaded()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const fileLink = (item: AgreementFile) => (
    <>
      {item.downloadable ? (
        <button type="button" className="agreements-link" onClick={() => void download(item)}>{item.originalName}</button>
      ) : <span>{item.originalName}</span>}
      {item.signed && <> <span className="status status--planned">подписанная</span></>}
    </>
  )
  // Подписанная версия может быть не последней: скан часто загружают после перехода в «Действует».
  const signed = files.find((item) => item.signed && item.id !== currentFile?.id)

  return (
    <section className="agreement-files" aria-label={title}>
      <h6>{title}</h6>
      {currentFile === null ? (
        <p className="agreement-activity__missing">Файл не загружен.</p>
      ) : (
        <p className="agreement-files__current">
          {fileLink(currentFile)}
          <span className="agreement-activity__document-meta">{fileMeta(currentFile)}</span>
          {currentFile.comment && <span className="agreement-files__comment">{currentFile.comment}</span>}
        </p>
      )}
      {signed && (
        <p className="agreement-files__current">
          <span>Подписанная версия:</span> {fileLink(signed)}
          <span className="agreement-activity__document-meta">{fileMeta(signed)}</span>
        </p>
      )}
      {canEdit && !uploading && (
        <div className="agreement-detail__actions">
          <button type="button" className="button--secondary" onClick={() => setUploading(true)}>Загрузить новую версию</button>
          {children}
        </div>
      )}
      {uploading && (
        <form className="agreement-form" onSubmit={(event) => void submit(event)} aria-label={`Новая версия: ${title.toLowerCase()}`}>
          <div className="agreement-form__grid">
            <label className="agreement-form__wide">
              <span>Файл<span className="required-mark" aria-hidden="true"> *</span></span>
              <input type="file" required onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
              <span className="agreement-form__hint">PDF, DOC, DOCX, XLS, XLSX, изображения или архив до 20 МБ.</span>
            </label>
            <label className="agreement-form__wide">
              Комментарий к версии
              <input value={comment} maxLength={500} placeholder="Например, редакция вуза или после корректировки" onChange={(event) => setComment(event.target.value)} />
            </label>
          </div>
          <div className="agreement-form__actions">
            <button type="submit" disabled={saving || file === null}>{saving ? 'Загружаем…' : 'Загрузить версию'}</button>
            <button type="button" className="button--secondary" disabled={saving} onClick={() => { setUploading(false); setError(null) }}>Отмена</button>
          </div>
        </form>
      )}
      {error !== null && <CommandError error={error} fallback="Файл не принят." />}
      {files.length > 1 && (
        <details className="agreement-files__history">
          <summary>История версий ({files.length})</summary>
          <ol>
            {files.map((item) => (
              <li key={item.id}>
                {fileLink(item)}
                <span className="agreement-activity__document-meta">{fileMeta(item)}</span>
                {item.comment && <span className="agreement-files__comment">{item.comment}</span>}
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  )
}
