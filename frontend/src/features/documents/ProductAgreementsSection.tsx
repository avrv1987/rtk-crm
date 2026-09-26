import { type FormEvent, useRef, useState } from 'react'
import {
  ApiError,
  createIdempotencyKey,
  type Attachment,
  type Interaction,
  type ProductAgreement
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import {
  canPreview,
  commandMessage,
  handledAccessError,
  previewUrl,
  saveAttachment,
  transferKindLabels,
  transferKinds,
  updateProductAgreement,
  type AccessHandlers,
  type ProductTransfer,
  type ProductTransferKind
} from './documentsApi'
import './documents.css'

type ProductAgreementsSectionProps = AccessHandlers & {
  interaction: Interaction
  canEdit: boolean
  onInteraction: (interaction: Interaction) => void
  onReload: () => void
}

type Mode = 'contract' | 'transfers'

type Editing = { agreementId: string; mode: Mode } | null

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type ContractDraft = {
  contractNumber: string
  licenseSigned: '' | 'true' | 'false'
  licenseExpiryYear: string
  scanAttachmentId: string
}

type TransferDraft = Record<ProductTransferKind, { status: '' | ProductTransfer['status']; transferredOn: string; attachmentId: string }>

const minYear = 2000
const maxYear = 2100

const todayIso = () => {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Moscow' }).formatToParts(new Date())
  const value = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

const formatDate = (value: string) => new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short' })
  .format(new Date(`${value}T00:00:00`))

const licenseLabel = (value: boolean | null) => {
  if (value === null) {
    return 'Не указано'
  }
  return value ? 'Подписана' : 'Не подписана'
}

const contractDraft = (agreement: ProductAgreement): ContractDraft => ({
  contractNumber: agreement.contractNumber ?? '',
  licenseSigned: agreement.licenseSigned === null ? '' : agreement.licenseSigned ? 'true' : 'false',
  licenseExpiryYear: agreement.licenseExpiryYear?.toString() ?? '',
  scanAttachmentId: agreement.scanAttachmentId ?? ''
})

const transferDraft = (agreement: ProductAgreement): TransferDraft => {
  const draft = {} as TransferDraft
  transferKinds.forEach((kind) => {
    const mark = agreement.transfers.find((transfer) => transfer.kind === kind)
    draft[kind] = {
      status: mark?.status ?? '',
      transferredOn: mark?.transferredOn ?? '',
      attachmentId: mark?.attachmentId ?? ''
    }
  })
  return draft
}

const yearProblem = (value: string) => {
  if (value.trim() === '') {
    return undefined
  }
  const year = Number(value)
  return Number.isInteger(year) && year >= minYear && year <= maxYear
    ? undefined
    : `Год от ${minYear} до ${maxYear}`
}

const FileReference = ({ attachment, onError }: { attachment: Attachment | undefined; onError: (error: unknown) => void }) => {
  if (attachment === undefined) {
    return <span className="product-contract__muted">файл удалён</span>
  }
  return (
    <span className="product-contract__file">
      «{attachment.originalName}»
      {canPreview(attachment) && (
        <a href={previewUrl(attachment.id)} target="_blank" rel="noopener noreferrer">просмотреть</a>
      )}
      {attachment.status === 'CLEAN' && (
        <button type="button" className="product-contract__inline" onClick={() => void saveAttachment(attachment).catch(onError)}>
          скачать
        </button>
      )}
    </span>
  )
}

export const ProductAgreementsSection = ({
  interaction,
  canEdit,
  onInteraction,
  onReload,
  onSessionExpired,
  onProfileUnavailable
}: ProductAgreementsSectionProps) => {
  const handlers = { onSessionExpired, onProfileUnavailable }
  const [editing, setEditing] = useState<Editing>(null)
  const [contract, setContract] = useState<ContractDraft | null>(null)
  const [transfers, setTransfers] = useState<TransferDraft | null>(null)
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' })
  const [fileError, setFileError] = useState<unknown>(null)
  const commandKey = useRef<string | null>(null)
  const cleanFiles = interaction.attachments.filter((attachment) => attachment.status === 'CLEAN')
  const attachmentById = new Map(interaction.attachments.map((attachment) => [attachment.id, attachment]))
  const today = todayIso()

  const fail = (error: unknown) => {
    if (!handledAccessError(error, handlers)) {
      setFileError(error)
    }
  }

  const start = (agreement: ProductAgreement, mode: Mode) => {
    commandKey.current = null
    setSaveState({ kind: 'idle' })
    setContract(contractDraft(agreement))
    setTransfers(transferDraft(agreement))
    setEditing({ agreementId: agreement.id, mode })
  }

  const close = () => {
    commandKey.current = null
    setEditing(null)
    setSaveState({ kind: 'idle' })
  }

  const changeContract = (patch: Partial<ContractDraft>) => {
    commandKey.current = null
    setContract((current) => current === null ? current : { ...current, ...patch })
  }

  const changeTransfer = (kind: ProductTransferKind, patch: Partial<TransferDraft[ProductTransferKind]>) => {
    commandKey.current = null
    setTransfers((current) => {
      if (current === null) {
        return current
      }
      const next = { ...current[kind], ...patch }
      if (patch.status === 'TRANSFERRED' && next.transferredOn === '') {
        next.transferredOn = today
      }
      if (patch.status !== undefined && patch.status !== 'TRANSFERRED') {
        next.transferredOn = ''
        next.attachmentId = ''
      }
      return { ...current, [kind]: next }
    })
  }

  const submit = async (event: FormEvent<HTMLFormElement>, agreement: ProductAgreement, mode: Mode) => {
    event.preventDefault()
    if (contract === null || transfers === null) {
      return
    }
    const payload = mode === 'contract'
      ? {
        version: interaction.version,
        contract: {
          contractNumber: contract.contractNumber.trim() === '' ? null : contract.contractNumber,
          licenseSigned: contract.licenseSigned === '' ? null : contract.licenseSigned === 'true',
          licenseExpiryYear: contract.licenseExpiryYear.trim() === '' ? null : Number(contract.licenseExpiryYear),
          scanAttachmentId: contract.scanAttachmentId === '' ? null : contract.scanAttachmentId
        }
      }
      : {
        version: interaction.version,
        transfers: transferKinds
          .filter((kind) => transfers[kind].status !== '')
          .map((kind): ProductTransfer => ({
            kind,
            status: transfers[kind].status as ProductTransfer['status'],
            transferredOn: transfers[kind].status === 'TRANSFERRED' ? transfers[kind].transferredOn : null,
            attachmentId: transfers[kind].status === 'TRANSFERRED' && transfers[kind].attachmentId !== ''
              ? transfers[kind].attachmentId
              : null
          }))
      }
    setSaveState({ kind: 'saving' })
    try {
      const updated = await updateProductAgreement(
        interaction.id,
        agreement.id,
        payload,
        commandKey.current ?? (commandKey.current = createIdempotencyKey())
      )
      onInteraction(updated)
      close()
    } catch (error) {
      if (!handledAccessError(error, handlers)) {
        setSaveState({ kind: 'failed', error })
      }
    }
  }

  const fileOptions = (
    <>
      <option value="">Без файла</option>
      {cleanFiles.map((attachment) => (
        <option key={attachment.id} value={attachment.id}>{attachment.originalName} (версия {attachment.revision})</option>
      ))}
    </>
  )

  const renderContractForm = (agreement: ProductAgreement) => {
    if (contract === null) {
      return null
    }
    const expiryProblem = yearProblem(contract.licenseExpiryYear)
    return (
      <form className="product-contract__form" onSubmit={(event) => void submit(event, agreement, 'contract')}>
        <h6>Договор и лицензия: {agreement.productName}</h6>
        <div className="product-contract__grid">
          <label>
            Номер договора
            <input
              value={contract.contractNumber}
              maxLength={200}
              onChange={(event) => changeContract({ contractNumber: event.target.value })}
            />
          </label>
          <label>
            Подписание лицензии
            <select
              value={contract.licenseSigned}
              onChange={(event) => changeContract({ licenseSigned: event.target.value as ContractDraft['licenseSigned'] })}
            >
              <option value="">Не указано</option>
              <option value="true">Подписана</option>
              <option value="false">Не подписана</option>
            </select>
          </label>
          <label>
            Срок лицензии (год окончания)
            <input
              type="number"
              inputMode="numeric"
              min={minYear}
              max={maxYear}
              value={contract.licenseExpiryYear}
              aria-invalid={expiryProblem === undefined ? undefined : true}
              onChange={(event) => changeContract({ licenseExpiryYear: event.target.value })}
            />
            {expiryProblem !== undefined && <span className="product-contract__problem">{expiryProblem}</span>}
          </label>
          <label>
            Скан договора или лицензии
            <select value={contract.scanAttachmentId} onChange={(event) => changeContract({ scanAttachmentId: event.target.value })}>
              {fileOptions}
            </select>
          </label>
        </div>
        <p className="interaction-field-hint">
          Скан сначала загрузите в блоке «Документы» и дождитесь статуса «Проверен: доступен». Пустое поле очищает значение;
          каждое изменение попадает в историю с прежним и новым значением.
        </p>
        {renderFormActions(expiryProblem !== undefined)}
      </form>
    )
  }

  const renderTransfersForm = (agreement: ProductAgreement) => {
    if (transfers === null) {
      return null
    }
    const missingDate = transferKinds.some((kind) => transfers[kind].status === 'TRANSFERRED' && transfers[kind].transferredOn === '')
    return (
      <form className="product-contract__form" onSubmit={(event) => void submit(event, agreement, 'transfers')}>
        <h6>Отметки передачи: {agreement.productName}</h6>
        {transferKinds.map((kind) => (
          <fieldset key={kind} className="product-contract__transfer">
            <legend>{transferKindLabels[kind]}</legend>
            <label>
              Статус
              <select
                value={transfers[kind].status}
                onChange={(event) => changeTransfer(kind, { status: event.target.value as TransferDraft[ProductTransferKind]['status'] })}
              >
                <option value="">Не отмечено</option>
                <option value="TRANSFERRED">Передано</option>
                <option value="NOT_TRANSFERRED">Не передано</option>
              </select>
            </label>
            {transfers[kind].status === 'TRANSFERRED' && (
              <>
                <label>
                  Дата передачи
                  <input
                    type="date"
                    required
                    max={today}
                    value={transfers[kind].transferredOn}
                    onChange={(event) => changeTransfer(kind, { transferredOn: event.target.value })}
                  />
                </label>
                <label>
                  Подтверждающий файл
                  <select
                    value={transfers[kind].attachmentId}
                    onChange={(event) => changeTransfer(kind, { attachmentId: event.target.value })}
                  >
                    {fileOptions}
                  </select>
                </label>
              </>
            )}
          </fieldset>
        ))}
        <p className="interaction-field-hint">
          Статус передачи продукта считается по отметкам: все три переданы — «Передано», часть — «Передано частично»,
          ни одной — «Не передано». Снимите все отметки, чтобы очистить статус.
        </p>
        {renderFormActions(missingDate)}
      </form>
    )
  }

  const renderFormActions = (invalid: boolean) => (
    <>
      <div className="product-contract__buttons">
        <button type="submit" disabled={invalid || saveState.kind === 'saving'}>
          {saveState.kind === 'saving' ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <button type="button" className="button--secondary" onClick={close} disabled={saveState.kind === 'saving'}>Отмена</button>
      </div>
      {saveState.kind === 'failed' && (
        <div className="interaction-command-error" role="alert">
          <p>{commandMessage(saveState.error)}</p>
          {saveState.error instanceof ApiError && <SupportDetails requestId={saveState.error.requestId} code={saveState.error.code} />}
          {saveState.error instanceof ApiError && saveState.error.status === 409 && (
            <button type="button" onClick={() => { close(); onReload() }}>Обновить карточку</button>
          )}
        </div>
      )}
    </>
  )

  const transferText = (agreement: ProductAgreement, kind: ProductTransferKind) => {
    const mark = agreement.transfers.find((transfer) => transfer.kind === kind)
    if (mark === undefined) {
      return <span className="product-contract__muted">не отмечено</span>
    }
    if (mark.status === 'NOT_TRANSFERRED') {
      return <span className="product-contract__waiting">не передано</span>
    }
    return (
      <span>
        передано {mark.transferredOn === null || mark.transferredOn === undefined ? '' : formatDate(mark.transferredOn)}
        {mark.attachmentId !== null && mark.attachmentId !== undefined && (
          <> · <FileReference attachment={attachmentById.get(mark.attachmentId)} onError={fail} /></>
        )}
      </span>
    )
  }

  return (
    <section className="interaction-product-agreements" aria-labelledby="interaction-product-agreements-title">
      <h6 id="interaction-product-agreements-title">Продукты и соглашения</h6>
      {interaction.productAgreements.length === 0 && <p>Продукты не указаны.</p>}
      {interaction.productAgreements.length > 0 && (
        <ul>
          {interaction.productAgreements.map((agreement) => (
            <li key={agreement.id}>
              <div className="interaction-product-agreement__header">
                <strong>{agreement.productName}</strong>
                <span className="product-contract__vendor">{agreement.vendorName}</span>
                {agreement.productArchived && <span className="interaction-archived">Архивирован</span>}
                {agreement.archived && <span className="interaction-archived">Договор в архиве: нет в реестре</span>}
              </div>
              <dl>
                <div>
                  <dt>Номер договора</dt>
                  <dd>{agreement.contractNumber ?? 'Не указан'}</dd>
                </div>
                <div>
                  <dt>Подписание лицензии</dt>
                  <dd>{licenseLabel(agreement.licenseSigned)}</dd>
                </div>
                <div>
                  <dt>Срок лицензии</dt>
                  <dd>{agreement.licenseExpiryYear ?? 'Не указан'}</dd>
                </div>
                <div>
                  <dt>Статус передачи</dt>
                  <dd>{agreement.transferStatus ?? 'Не указан'}</dd>
                </div>
                <div className="product-contract__wide">
                  <dt>Скан</dt>
                  <dd>
                    {agreement.scanAttachmentId === null
                      ? 'Нет'
                      : <FileReference attachment={attachmentById.get(agreement.scanAttachmentId)} onError={fail} />}
                  </dd>
                </div>
              </dl>
              <ul className="product-contract__transfers" aria-label={`Передача по продукту ${agreement.productName}`}>
                {transferKinds.map((kind) => (
                  <li key={kind}>
                    <strong>{transferKindLabels[kind]}:</strong> {transferText(agreement, kind)}
                  </li>
                ))}
              </ul>
              {canEdit && editing?.agreementId !== agreement.id && (
                <div className="product-contract__buttons">
                  <button type="button" className="button--secondary" onClick={() => start(agreement, 'contract')}>
                    Договор и лицензия
                  </button>
                  <button type="button" className="button--secondary" onClick={() => start(agreement, 'transfers')}>
                    Отметки передачи
                  </button>
                </div>
              )}
              {editing?.agreementId === agreement.id && editing.mode === 'contract' && renderContractForm(agreement)}
              {editing?.agreementId === agreement.id && editing.mode === 'transfers' && renderTransfersForm(agreement)}
            </li>
          ))}
        </ul>
      )}
      {fileError !== null && (
        <div className="interaction-command-error" role="alert">
          <p>Файл недоступен для скачивания. Обновите карточку и проверьте статус документа.</p>
          {fileError instanceof ApiError && <SupportDetails requestId={fileError.requestId} code={fileError.code} />}
          <button type="button" onClick={() => setFileError(null)}>Скрыть</button>
        </div>
      )}
    </section>
  )
}
