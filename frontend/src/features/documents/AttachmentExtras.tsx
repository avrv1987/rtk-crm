import { type FormEvent, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type Attachment,
  type Interaction,
  type Me
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  attachmentAccept,
  attachmentKindLabels,
  attachmentKinds,
  canPreview,
  commandMessage,
  deleteAttachment,
  handledAccessError,
  previewUrl,
  updateAttachmentKind,
  updateAttachmentPartnerVisible,
  type AccessHandlers,
  type AttachmentKind
} from './documentsApi'
import './documents.css'

type AttachmentExtrasProps = AccessHandlers & {
  interaction: Interaction
  attachment: Attachment
  canEdit: boolean
  profileId: string
  role: Me['role']
  onAttachment: (attachment: Attachment) => void
  onInteraction: (interaction: Interaction) => void
  onReload: () => void
}

type Panel = 'none' | 'version' | 'delete'

type ActionState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

export const AttachmentExtras = ({
  interaction,
  attachment,
  canEdit,
  profileId,
  role,
  onAttachment,
  onInteraction,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: AttachmentExtrasProps) => {
  const handlers = { onSessionExpired, onProfileUnavailable }
  const [panel, setPanel] = useState<Panel>('none')
  const [state, setState] = useState<ActionState>({ kind: 'idle' })
  const [versionFile, setVersionFile] = useState<File | null>(null)
  const [reason, setReason] = useState('')
  const commandKey = useRef<string | null>(null)
  const replacement = interaction.attachments.find((item) => item.replacesId === attachment.id && item.status !== 'REJECTED' && item.status !== 'UNVERIFIABLE')
  const previous = attachment.replacesId === null
    ? undefined
    : interaction.attachments.find((item) => item.id === attachment.replacesId)
  const canDelete = canEdit && (attachment.createdBy === profileId || role === 'LEADER')
  const busy = state.kind === 'saving'

  const open = (next: Panel) => {
    commandKey.current = null
    setState({ kind: 'idle' })
    setVersionFile(null)
    setReason('')
    setPanel(next)
  }

  const fail = (error: unknown) => {
    if (!handledAccessError(error, handlers)) {
      setState({ kind: 'failed', error })
    }
  }

  const changeKind = async (kind: AttachmentKind) => {
    setState({ kind: 'saving' })
    try {
      onAttachment(await updateAttachmentKind(attachment.id, attachment.version, kind))
      setState({ kind: 'idle' })
    } catch (error) {
      fail(error)
    }
  }

  const changePartnerVisible = async (partnerVisible: boolean) => {
    setState({ kind: 'saving' })
    try {
      onAttachment(await updateAttachmentPartnerVisible(attachment.id, attachment.version, partnerVisible))
      setState({ kind: 'idle' })
    } catch (error) {
      fail(error)
    }
  }

  const submitVersion = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (versionFile === null) {
      return
    }
    setState({ kind: 'saving' })
    try {
      const uploaded = await apiClient.uploadInteractionAttachment(
        interaction.id,
        { file: versionFile, stageId: attachment.stageId, replacesId: attachment.id },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      onAttachment(uploaded)
      open('none')
    } catch (error) {
      fail(error)
    }
  }

  const submitDeletion = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setState({ kind: 'saving' })
    try {
      const trimmed = reason.trim()
      const updated = await deleteAttachment(
        interaction.id,
        attachment.id,
        { version: interaction.version, reason: trimmed.length === 0 ? null : trimmed },
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      onInteraction(updated)
    } catch (error) {
      fail(error)
    }
  }

  const conflict = state.kind === 'failed' && state.error instanceof ApiError && state.error.status === 409

  return (
    <div className="document-extras">
      <div className="document-extras__meta">
        <label className="document-extras__kind">
          Вид документа
          {canEdit ? (
            <select
              value={attachment.kind}
              disabled={busy}
              onChange={(event) => void changeKind(event.target.value as AttachmentKind)}
            >
              {attachmentKinds.map((kind) => <option key={kind} value={kind}>{attachmentKindLabels[kind]}</option>)}
            </select>
          ) : (
            <strong>{attachmentKindLabels[attachment.kind]}</strong>
          )}
        </label>
        {canEdit ? (
          <label className="document-extras__partner">
            <input
              type="checkbox"
              checked={attachment.partnerVisible}
              disabled={busy}
              onChange={(event) => void changePartnerVisible(event.target.checked)}
            />
            Доступен вузу
            <span className="interaction-field-hint">
              {attachment.status === 'CLEAN'
                ? 'Представитель вуза увидит и скачает файл в кабинете.'
                : 'В кабинете вуза файл можно будет скачать после проверки антивирусом.'}
            </span>
          </label>
        ) : attachment.partnerVisible && <p className="document-extras__revision">Доступен вузу</p>}
        <p className="document-extras__revision">
          Версия {attachment.revision}
          {previous !== undefined && <span> · заменяет версию {previous.revision}</span>}
          {previous === undefined && attachment.replacesId !== null && <span> · прежняя версия удалена</span>}
          {replacement !== undefined && (
            <span className="document-extras__superseded"> · есть новая версия {replacement.revision}</span>
          )}
        </p>
      </div>
      <div className="document-extras__actions">
        {canPreview(attachment) && (
          <a className="document-extras__link" href={previewUrl(attachment.id)} target="_blank" rel="noopener noreferrer">
            Просмотреть
          </a>
        )}
        {canEdit && replacement === undefined && panel !== 'version' && (
          <button type="button" className="button--secondary" onClick={() => open('version')}>Загрузить новую версию</button>
        )}
        {canDelete && panel !== 'delete' && (
          <button type="button" className="button--secondary document-extras__delete" onClick={() => open('delete')}>Удалить</button>
        )}
      </div>
      {panel === 'version' && (
        <form className="document-extras__panel" onSubmit={(event) => void submitVersion(event)}>
          <label>
            <span>Файл версии {attachment.revision + 1}<span className="required-mark" aria-hidden="true"> *</span></span>
            <input
              type="file"
              required
              accept={attachmentAccept}
              onChange={(event) => {
                commandKey.current = null
                setVersionFile(event.target.files?.item(0) ?? null)
              }}
            />
            <span className="interaction-field-hint">
              Этап и вид документа сохранятся, прежняя версия останется в списке. После загрузки файл проверяется антивирусом.
            </span>
          </label>
          <div className="document-extras__buttons">
            <button type="submit" disabled={busy || versionFile === null}>{busy ? 'Загружаем…' : 'Загрузить новую версию'}</button>
            <button type="button" className="button--secondary" onClick={() => open('none')} disabled={busy}>Отмена</button>
          </div>
        </form>
      )}
      {panel === 'delete' && (
        <form className="document-extras__panel document-extras__panel--danger" onSubmit={(event) => void submitDeletion(event)}>
          <p>
            Удалить «{attachment.originalName}»? Файл станет недоступен для просмотра и скачивания,
            в истории появится запись об удалении с автором и причиной.
          </p>
          <label>
            Причина (необязательно)
            <textarea
              value={reason}
              maxLength={500}
              onChange={(event) => {
                commandKey.current = null
                setReason(event.target.value)
              }}
            />
          </label>
          <div className="document-extras__buttons">
            <button type="submit" className="button--danger" disabled={busy}>{busy ? 'Удаляем…' : 'Удалить документ'}</button>
            <button type="button" className="button--secondary" onClick={() => open('none')} disabled={busy}>Отмена</button>
          </div>
        </form>
      )}
      {state.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(state.error)}</p>
          {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
          {conflict && <button type="button" onClick={onReload}>Обновить карточку</button>}
        </div>
      )}
    </div>
  )
}
