import { useCallback, useEffect, useState } from 'react'
import type { ActivityList } from '../agreements/reportingApi'
import { pluralForm } from '../../shared/format/plural'
import { ApiError } from '../../shared/api/client'
import { formatCalendarDate, formatMoscowDateTime, todayInMoscow } from '../../shared/format/datetime'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { saveFile } from '../admin/saveFile'
import { daysInStatus, partnerApprovalLine } from '../agreements/agreementModel'
import { AgreementTermMark } from '../agreements/agreementUi'
import { attachmentKindLabel } from '../documents/documentsApi'
import { handledAccessError, workStatusLabels } from '../interactions/workMarks'
import {
  agreementStatusLabels,
  downloadPartnerDocument,
  loadPartnerCabinet,
  organizationTypeLabels,
  type PartnerCabinet as Cabinet,
  type PartnerDocument
} from './partnerApi'
import { emailHref, phoneHref } from '../../shared/format/links'
import { ContactLink } from '../../shared/ui/ContactLink'
import { PartnerActivityLists } from './PartnerActivityLists'
import { PartnerRequestCard } from './PartnerRequestCard'
import { partnerRequestGroups, partnerRequiredCount, partnerReviewLists } from './partnerRequestModel'
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

const statusSince = (changedAt: string) => {
  const days = daysInStatus(changedAt, Date.now())
  return `В этом статусе с ${formatCalendarDate(todayInMoscow(new Date(changedAt)))} (${days} дн.)`
}

export const PartnerCabinet = ({ onSessionExpired, onProfileUnavailable }: PartnerCabinetProps) => {
  const [state, setState] = useState<CabinetState>({ kind: 'loading' })
  const [download, setDownload] = useState<DownloadState>({ kind: 'idle' })
  const [lists, setLists] = useState<ActivityList[] | null>(null)

  const load = useCallback(async (quiet = false) => {
    if (!quiet) {
      setState({ kind: 'loading' })
    }
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

  const { organization, manager, works, documents, agreements, requests } = state.cabinet
  const groups = partnerRequestGroups(requests)
  const reviewLists = partnerReviewLists(lists)
  const focusLists = () => document.getElementById('partner-activity-lists')?.focus()
  const requestCards = (items: typeof requests) => (
    <ul className="partner-cabinet__list">
      {items.map((request) => (
        <PartnerRequestCard
          key={`${request.id}:${request.version}`}
          request={request}
          onChanged={() => void load(true)}
          onSessionExpired={onSessionExpired}
          onProfileUnavailable={onProfileUnavailable}
        />
      ))}
    </ul>
  )
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
          {manager?.phone && <dd><ContactLink href={phoneHref(manager.phone)} text={manager.phone} /></dd>}
          {manager?.email && <dd><ContactLink href={emailHref(manager.email)} text={manager.email} /></dd>}
        </dl>
        <p className="interaction-field-hint">
          Здесь — что школа ждёт от вуза и ваши ответы. Чтобы изменить сведения о взаимодействиях, договорах или контактах,
          напишите своему менеджеру.
        </p>
      </section>

      <nav className="partner-cabinet__nav" aria-label="Разделы кабинета">
        <a href="#partner-required" onClick={(event) => { event.preventDefault(); document.getElementById('partner-required')?.focus() }}>
          Требуется от вуза ({partnerRequiredCount(groups.required.length, lists)})
        </a>
        <a href="#partner-works" onClick={(event) => { event.preventDefault(); document.getElementById('partner-works')?.focus() }}>
          Взаимодействия ({works.length})
        </a>
        <a href="#partner-documents" onClick={(event) => { event.preventDefault(); document.getElementById('partner-documents')?.focus() }}>
          Документы ({documents.length})
        </a>
        <a href="#partner-agreements" onClick={(event) => { event.preventDefault(); document.getElementById('partner-agreements')?.focus() }}>
          Договоры ({agreements.length})
        </a>
        <a href="#partner-activity-lists" onClick={(event) => { event.preventDefault(); document.getElementById('partner-activity-lists')?.focus() }}>
          Перечни мероприятий{lists === null ? '' : ` (${lists.length})`}
        </a>
      </nav>

      <section className="partner-cabinet__section" aria-labelledby="partner-required">
        <h3 id="partner-required" tabIndex={-1}>Требуется от вуза</h3>
        {reviewLists.length > 0 && (
          <p className="notice">
            {`${pluralForm(reviewLists.length, 'Перечень', 'Перечня', 'Перечней')} мероприятий ждёт вашего решения: `}
            {reviewLists.map((list) => `«${list.summary.title}»`).join(', ')}.{' '}
            <a href="#partner-activity-lists" onClick={(event) => { event.preventDefault(); focusLists() }}>Перейти к перечням</a>
          </p>
        )}
        {groups.required.length > 0 && requestCards(groups.required)}
        {groups.required.length === 0 && reviewLists.length === 0 && <p>Сейчас школа ничего не ждёт от вуза.</p>}
      </section>

      {groups.sent.length > 0 && (
        <section className="partner-cabinet__section" aria-labelledby="partner-sent">
          <h3 id="partner-sent" tabIndex={-1}>Отправлено нами, ожидает проверки</h3>
          <p className="interaction-field-hint">Ответ проверяет ваш менеджер. Если он вернёт ответ на доработку, запрос снова появится выше с причиной.</p>
          {requestCards(groups.sent)}
        </section>
      )}

      {groups.closed.length > 0 && (
        <section className="partner-cabinet__section" aria-labelledby="partner-closed">
          <h3 id="partner-closed" tabIndex={-1}>Закрытые запросы</h3>
          {requestCards(groups.closed)}
        </section>
      )}

      <section className="partner-cabinet__section" aria-labelledby="partner-works">
        <h3 id="partner-works" tabIndex={-1}>Взаимодействия с ИТ Школой</h3>
        {works.length === 0 && <p>Совместных взаимодействий пока нет.</p>}
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
                  <p>Этапы до текущего (по отметкам в CRM)</p>
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
                {attachmentKindLabel(document)} · версия {document.revision}{document.replaced ? ' (есть новая версия)' : ''} · {fileSize(document.sizeBytes)} · {document.workTitle} · {formatMoscowDateTime(document.createdAt)}
              </p>
              {document.receivedAt !== null && (
                <p className="partner-card__meta">Получение этой версии подтверждено {formatMoscowDateTime(document.receivedAt)}</p>
              )}
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
                  <p>{download.error instanceof ApiError && download.error.code === 'FILE_UNAVAILABLE'
                    ? download.error.message
                    : 'Не удалось скачать файл. Нажмите «Скачать» ещё раз; если не получится, обратитесь к вашему менеджеру в школе.'}</p>
                  {download.error instanceof ApiError && <SupportDetails requestId={download.error.requestId} code={download.error.code} />}
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="partner-cabinet__section" aria-labelledby="partner-agreements">
        <h3 id="partner-agreements" tabIndex={-1}>Договоры</h3>
        {agreements.length === 0 && <p>Договоров пока нет.</p>}
        <ul className="partner-cabinet__list">
          {agreements.map((agreement) => (
            <li key={agreement.id} className="partner-card">
              <div className="partner-card__header">
                <strong>Договор № {agreement.number}</strong>
                <span>
                  <span className="status status--planned">{agreementStatusLabels[agreement.status]}</span>
                  {' '}<AgreementTermMark agreement={agreement} />
                </span>
              </div>
              {agreement.statusChangedAt && <p className="partner-card__meta">{statusSince(agreement.statusChangedAt)}</p>}
              {partnerApprovalLine(agreement.status, agreement.approvalRound) && (
                <p className="partner-card__meta">{partnerApprovalLine(agreement.status, agreement.approvalRound)}</p>
              )}
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

      <PartnerActivityLists onSessionExpired={onSessionExpired} onProfileUnavailable={onProfileUnavailable} onLoaded={setLists} />
    </div>
  )
}
