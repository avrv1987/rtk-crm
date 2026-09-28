import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { attachmentKindLabels } from '../documents/documentsApi'
import { handledAccessError, workStatusLabels } from '../interactions/workMarks'
import {
  agreementStatusLabels,
  downloadPartnerDocument,
  loadPartnerCabinet,
  organizationTypeLabels,
  type PartnerCabinet as Cabinet,
  type PartnerDocument
} from './partnerApi'
import './partner.css'

type PartnerCabinetProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type CabinetState =
  | { kind: 'loading' }
  | { kind: 'ready'; cabinet: Cabinet }
  | { kind: 'failed'; error: unknown }

type DownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading'; id: string }
  | { kind: 'failed'; id: string; error: unknown }

const fileSize = (sizeBytes: number) => (
  sizeBytes < 1024 * 1024 ? `${Math.max(1, Math.round(sizeBytes / 1024))} КиБ` : `${(sizeBytes / 1024 / 1024).toFixed(1)} МиБ`
)

const term = (from: string | null, to: string | null) => {
  if (from === null && to === null) {
    return 'срок не указан'
  }
  return [from === null ? null : `с ${formatCalendarDate(from)}`, to === null ? null : `по ${formatCalendarDate(to)}`]
    .filter((part) => part !== null)
    .join(' ')
}

export const PartnerCabinet = ({ onSessionExpired, onProfileUnavailable }: PartnerCabinetProps) => {
  const [state, setState] = useState<CabinetState>({ kind: 'loading' })
  const [download, setDownload] = useState<DownloadState>({ kind: 'idle' })

  const load = useCallback(async () => {
    setState({ kind: 'loading' })
    try {
      setState({ kind: 'ready', cabinet: await loadPartnerCabinet() })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [onSessionExpired, onProfileUnavailable])

  useEffect(() => {
    void load()
  }, [load])

  const save = async (document: PartnerDocument) => {
    setDownload({ kind: 'downloading', id: document.id })
    try {
      saveFile(await downloadPartnerDocument(document.id), document.name)
      setDownload({ kind: 'idle' })
    } catch (error) {
      if (!handledAccessError(error, onSessionExpired, onProfileUnavailable)) {
        setDownload({ kind: 'failed', id: document.id, error })
      }
    }
  }

  if (state.kind === 'loading') {
    return <p className="organizations-message" role="status">Загружаем кабинет…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="organizations-message organizations-message--error" role="alert">
        <p>{state.error instanceof ApiError && state.error.status === 403 ? state.error.message : 'Не удалось загрузить кабинет.'}</p>
        {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }

  const { organization, manager, works, documents, agreements } = state.cabinet
  return (
    <div className="partner-cabinet">
      <section className="partner-cabinet__head" aria-labelledby="partner-organization">
        <h2 id="partner-organization">{organization.name}</h2>
        <p className="partner-cabinet__chips">
          <span className="status status--planned">{organizationTypeLabels[organization.type]}</span>
        </p>
        <dl className="partner-cabinet__manager">
          <dt>Ваш менеджер ИТ Школы РТК</dt>
          <dd>{manager?.name ?? 'пока не назначен'}</dd>
        </dl>
        <p className="interaction-field-hint">
          Кабинет только для просмотра. Чтобы что-то изменить или задать вопрос, напишите своему менеджеру.
        </p>
      </section>

      <nav className="partner-cabinet__nav" aria-label="Разделы кабинета">
        <a href="#partner-works" onClick={(event) => { event.preventDefault(); document.getElementById('partner-works')?.focus() }}>
          Работы ({works.length})
        </a>
        <a href="#partner-documents" onClick={(event) => { event.preventDefault(); document.getElementById('partner-documents')?.focus() }}>
          Документы ({documents.length})
        </a>
        <a href="#partner-agreements" onClick={(event) => { event.preventDefault(); document.getElementById('partner-agreements')?.focus() }}>
          Соглашения ({agreements.length})
        </a>
      </nav>

      <section className="partner-cabinet__section" aria-labelledby="partner-works">
        <h3 id="partner-works" tabIndex={-1}>Работы с ИТ Школой</h3>
        {works.length === 0 && <p>Совместных работ пока нет.</p>}
        <ul className="partner-cabinet__list">
          {works.map((work) => (
            <li key={work.id} className="partner-card">
              <div className="partner-card__header">
                <strong>{work.title}</strong>
                <span className={`status ${work.status === 'ACTIVE' ? 'status--planned' : 'status--missing'}`}>
                  {workStatusLabels[work.status]}
                </span>
              </div>
              <dl className="partner-card__facts">
                {work.programName && <div><dt>Программа</dt><dd>{work.programName}</dd></div>}
                {work.productNames.length > 0 && <div><dt>Продукт</dt><dd>{work.productNames.join(', ')}</dd></div>}
                <div><dt>Текущий этап</dt><dd>{work.currentStageName}</dd></div>
              </dl>
              {work.passedStages.length > 0 && (
                <div className="partner-card__stages">
                  <p>Пройденные этапы</p>
                  <ol>
                    {work.passedStages.map((stage) => (
                      <li key={`${stage.name}:${stage.passedOn}`}>
                        {stage.name} — {formatCalendarDate(stage.passedOn)}
                      </li>
                    ))}
                  </ol>
                </div>
              )}
              {work.nextStep && (
                <p className="partner-card__next">
                  Ближайший шаг: <strong>{work.nextStep.action}</strong>
                  {work.nextStep.dueAt && <> · до {formatMoscowDateTime(work.nextStep.dueAt)}</>}
                </p>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="partner-cabinet__section" aria-labelledby="partner-documents">
        <h3 id="partner-documents" tabIndex={-1}>Документы</h3>
        {documents.length === 0 && <p>Менеджер пока не открыл вам документы.</p>}
        <ul className="partner-cabinet__list">
          {documents.map((document) => (
            <li key={document.id} className="partner-card">
              <div className="partner-card__header">
                <strong className="partner-card__name">{document.name}</strong>
              </div>
              <p className="partner-card__meta">
                {attachmentKindLabels[document.kind]} · {fileSize(document.sizeBytes)} · {document.workTitle} · {formatMoscowDateTime(document.createdAt)}
              </p>
              {document.downloadable ? (
                <button
                  type="button"
                  className="button--secondary"
                  disabled={download.kind === 'downloading' && download.id === document.id}
                  onClick={() => void save(document)}
                >
                  {download.kind === 'downloading' && download.id === document.id ? 'Готовим файл…' : 'Скачать'}
                </button>
              ) : (
                <p className="interaction-field-hint">Файл проверяется антивирусом, скачать его можно будет позже.</p>
              )}
              {download.kind === 'failed' && download.id === document.id && (
                <div className="interaction-command-error" role="alert">
                  <p>Не удалось скачать файл. Обновите страницу и повторите.</p>
                  {download.error instanceof ApiError && <SupportDetails requestId={download.error.requestId} code={download.error.code} />}
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="partner-cabinet__section" aria-labelledby="partner-agreements">
        <h3 id="partner-agreements" tabIndex={-1}>Соглашения</h3>
        {agreements.length === 0 && <p>Соглашений пока нет.</p>}
        <ul className="partner-cabinet__list">
          {agreements.map((agreement) => (
            <li key={agreement.id} className="partner-card">
              <div className="partner-card__header">
                <strong>Соглашение № {agreement.number}</strong>
                <span className="status status--planned">{agreementStatusLabels[agreement.status]}</span>
              </div>
              <p className="partner-card__meta">Срок: {term(agreement.concludedOn, agreement.validUntil)}</p>
              {agreement.subject.length > 0 && (
                <div className="partner-card__stages">
                  <p>Предмет</p>
                  <ul>
                    {agreement.subject.map((item, index) => <li key={`${index}:${item}`}>{item}</li>)}
                  </ul>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
