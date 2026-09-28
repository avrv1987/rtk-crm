import { useCallback, useEffect, useId, useState, type FormEvent } from 'react'
import { apiClient, type Organization } from '../../shared/api/client'
import {
  agreementsApi,
  formatInstant,
  formatPeriod,
  saveBlob,
  type ActivityKind,
  type AgreementConfirmation,
  type ConfirmationQuery
} from './agreementsApi'
import { CommandError, useAccessErrorHandler } from './agreementUi'
import { todayInMoscow } from '../../shared/format/datetime'
import { PeriodPicker } from '../reports/ReportControls'
import './agreements.css'

type AgreementConfirmationsPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Options = {
  organizations: Organization[]
  kinds: ActivityKind[]
}

type ResultState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'ready'; query: ConfirmationQuery; items: AgreementConfirmation[] }
  | { kind: 'failed'; error: unknown }

const currentYear = todayInMoscow().slice(0, 4)

const loadOrganizations = async () => {
  const items: Organization[] = []
  for (let page = 0; ; page += 1) {
    const result = await apiClient.listOrganizations({ page, size: 100, sort: 'name,asc' })
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= result.total) {
      return items
    }
  }
}

const groupByKind = (items: AgreementConfirmation[]) => {
  const groups = new Map<string, { name: string; items: AgreementConfirmation[] }>()
  for (const item of items) {
    const group = groups.get(item.kindId) ?? { name: item.kindName, items: [] }
    group.items.push(item)
    groups.set(item.kindId, group)
  }
  return [...groups.entries()]
}

export const AgreementConfirmationsPanel = ({ onSessionExpired, onProfileUnavailable }: AgreementConfirmationsPanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [options, setOptions] = useState<Options | null>(null)
  const [optionsError, setOptionsError] = useState<unknown>(null)
  const [organizationId, setOrganizationId] = useState('')
  const [kindId, setKindId] = useState('')
  const [from, setFrom] = useState(`${currentYear}-01-01`)
  const [to, setTo] = useState(`${currentYear}-12-31`)
  const [result, setResult] = useState<ResultState>({ kind: 'idle' })
  const [archiving, setArchiving] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  const organizationFieldId = useId()
  const kindFieldId = useId()

  const loadOptions = useCallback(async () => {
    setOptionsError(null)
    try {
      const [organizations, kinds] = await Promise.all([loadOrganizations(), agreementsApi.listKinds(true)])
      setOptions({ organizations, kinds })
    } catch (error) {
      if (!handleAccessError(error)) {
        setOptionsError(error)
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void loadOptions()
  }, [loadOptions])

  const query: ConfirmationQuery = {
    organizationId: organizationId || undefined,
    kindId: kindId || undefined,
    from: from || undefined,
    to: to || undefined
  }
  const periodInvalid = from !== '' && to !== '' && from > to

  const show = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (periodInvalid) {
      return
    }
    const requested = { ...query }
    setResult({ kind: 'loading' })
    setActionError(null)
    try {
      setResult({ kind: 'ready', query: requested, items: await agreementsApi.confirmations(requested) })
    } catch (error) {
      if (!handleAccessError(error)) {
        setResult({ kind: 'failed', error })
      }
    }
  }

  const download = async (requested: ConfirmationQuery) => {
    setArchiving(true)
    setActionError(null)
    try {
      const organization = options?.organizations.find((item) => item.id === requested.organizationId)
      const scope = organization ? organization.name.replace(/[\\/:*?"<>|]/g, '_') : 'все_вузы'
      saveBlob(await agreementsApi.archive(requested), `Подтверждения_${scope}_${requested.from ?? 'начало'}_${requested.to ?? 'сегодня'}.zip`)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    } finally {
      setArchiving(false)
    }
  }

  const downloadDocument = async (item: AgreementConfirmation) => {
    setActionError(null)
    try {
      saveBlob(await apiClient.downloadAttachment(item.attachmentId), item.originalName)
    } catch (error) {
      if (!handleAccessError(error)) {
        setActionError(error)
      }
    }
  }

  const outdated = result.kind === 'ready' && JSON.stringify(result.query) !== JSON.stringify(query)

  return (
    <div className="agreement-confirmations">
      {optionsError !== null && (
        <CommandError error={optionsError} fallback="Не удалось загрузить вузы и виды мероприятий." onRetry={() => void loadOptions()} />
      )}
      <form className="report-builder__panel agreement-confirmations__form" onSubmit={(event) => void show(event)}>
        <div className="report-builder__main">
          <div className="report-field">
            <label htmlFor={organizationFieldId}>Вуз</label>
            <select id={organizationFieldId} value={organizationId} onChange={(event) => setOrganizationId(event.target.value)} disabled={options === null}>
              <option value="">Все доступные</option>
              {options?.organizations.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </div>
          <div className="report-field">
            <label htmlFor={kindFieldId}>Вид мероприятия</label>
            <select id={kindFieldId} value={kindId} onChange={(event) => setKindId(event.target.value)} disabled={options === null}>
              <option value="">Все виды</option>
              {options?.kinds.map((item) => <option key={item.id} value={item.id}>{item.name}{item.archived ? ' (в архиве)' : ''}</option>)}
            </select>
          </div>
          <PeriodPicker
            from={from}
            to={to}
            hint="Период мероприятия: фактические сроки, иначе плановые, иначе срок соглашения. Даты включительно; пустая дата снимает ограничение."
            onChange={(nextFrom, nextTo) => {
              setFrom(nextFrom)
              setTo(nextTo)
            }}
          />
        </div>
        <div className="report-actions reports__actions">
          <button type="submit" disabled={periodInvalid || result.kind === 'loading'}>
            {result.kind === 'loading' ? 'Отбираем…' : 'Показать подтверждения'}
          </button>
        </div>
      </form>
      {periodInvalid && <p className="reports__problem" role="alert">Дата начала периода позже даты окончания.</p>}
      {result.kind === 'idle' && <p className="report-empty">Выберите вуз, вид мероприятия и период и нажмите «Показать подтверждения».</p>}
      {result.kind === 'loading' && <p className="report-empty report-loading" role="status">Отбираем подтверждения…</p>}
      {result.kind === 'failed' && <CommandError error={result.error} fallback="Не удалось отобрать подтверждения." />}
      {actionError !== null && <CommandError error={actionError} fallback="Файл не скачан." />}
      {result.kind === 'ready' && (
        <>
          {outdated && <p className="reports__outdated" role="status">Отбор изменён. Нажмите «Показать подтверждения», чтобы обновить список.</p>}
          <div className="agreement-confirmations__summary">
            <p role="status">Документов: {result.items.length}</p>
            <button type="button" disabled={archiving || result.items.length === 0} onClick={() => void download(result.query)}>
              {archiving ? 'Готовим архив…' : 'Скачать архивом'}
            </button>
          </div>
          {result.items.length === 0 && <p className="report-empty">По выбранному отбору подтверждений нет.</p>}
          {groupByKind(result.items).map(([id, group]) => (
            <section key={id} className="agreement-confirmations__group" aria-label={group.name}>
              <h3>{group.name} <span className="agreement-confirmations__count">({group.items.length})</span></h3>
              <ul>
                {group.items.map((item) => (
                  <li key={`${item.activityId}:${item.attachmentId}`} className="agreement-confirmations__item">
                    <button type="button" className="agreements-link" onClick={() => void downloadDocument(item)}>{item.originalName}</button>
                    <span>{item.organizationName}, соглашение № {item.agreementNumber}</span>
                    <span>{item.activityTitle}; {formatPeriod(item.activityStart, item.activityEnd)}</span>
                    <span className="agreement-activity__document-meta">Загружен {formatInstant(item.createdAt)} в работу «{item.interactionTitle}»</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      )}
    </div>
  )
}
