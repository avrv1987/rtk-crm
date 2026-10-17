import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, type Me, type Organization, type ReportManagerOption } from '../../shared/api/client'
import { todayInMoscow } from '../../shared/format/datetime'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { HintButton } from '../../shared/ui/HintButton'
import { StatTiles } from '../../shared/ui/StatTiles'
import { MultiChip } from '../reports/ReportControls'
import { daysLabel } from '../work/workShared'
import { agreementsApi, formatDate, saveBlob, type AgreementRegistryRow } from './agreementsApi'
import {
  agreementStatusLabels,
  agreementStatuses,
  emptyRegistryFilters,
  registryTileCounts,
  registryTileFilters,
  registryTileOf,
  registryTiles,
  type AgreementStatus,
  type RegistryFilters
} from './agreementModel'
import { CommandError, useAccessErrorHandler } from './agreementUi'
import '../reports/reports.css'
import './agreements.css'

type AgreementRegistryProps = {
  role: Me['role']
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type Options = {
  organizations: Organization[]
  managers: ReportManagerOption[]
}

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; rows: AgreementRegistryRow[]; filters: RegistryFilters }
  | { kind: 'failed'; error: unknown }

const loadOrganizations = async () => {
  const items: Organization[] = []
  for (let page = 0; ; page += 1) {
    const result = await apiClient.listOrganizations({ page, size: 100, sort: 'name,asc', status: 'ALL' })
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= result.total) {
      return items
    }
  }
}

const rowHref = (row: AgreementRegistryRow) => `#/organizations/${row.organizationId}?agreement=${row.id}`

export const AgreementRegistry = ({ role, onSessionExpired, onProfileUnavailable }: AgreementRegistryProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [filters, setFilters] = useState<RegistryFilters>(emptyRegistryFilters)
  const [options, setOptions] = useState<Options | null>(null)
  const [state, setState] = useState<State>({ kind: 'loading' })
  const [downloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<unknown>(null)
  const [allRows, setAllRows] = useState<AgreementRegistryRow[] | null>(null)
  const version = useRef(0)
  const withManagers = role !== 'USER'
  const today = todayInMoscow()

  useEffect(() => {
    let active = true
    void Promise.all([loadOrganizations(), withManagers ? apiClient.listReportManagers() : Promise.resolve([])])
      .then(([organizations, managers]) => {
        if (active) {
          setOptions({ organizations, managers })
        }
      })
      .catch((error: unknown) => {
        if (active && !handleAccessError(error)) {
          setOptions({ organizations: [], managers: [] })
        }
      })
    return () => {
      active = false
    }
  }, [handleAccessError, withManagers])

  const load = useCallback(async (requested: RegistryFilters) => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const rows = await agreementsApi.registry(requested)
      if (current === version.current) {
        setState({ kind: 'ready', rows, filters: requested })
        if (JSON.stringify(requested) === JSON.stringify(emptyRegistryFilters)) {
          setAllRows(rows)
        }
      }
    } catch (error) {
      if (current === version.current && !handleAccessError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(filters), 250)
    return () => window.clearTimeout(timer)
  }, [filters, load])

  const update = (patch: Partial<RegistryFilters>) => setFilters((current) => ({ ...current, ...patch }))

  const download = async () => {
    if (state.kind !== 'ready') {
      return
    }
    setDownloading(true)
    setDownloadError(null)
    try {
      saveBlob(await agreementsApi.registryFile(state.filters), `Реестр_договоров_${todayInMoscow()}.xlsx`)
    } catch (error) {
      if (!handleAccessError(error)) {
        setDownloadError(error)
      }
    } finally {
      setDownloading(false)
    }
  }

  const filtered = JSON.stringify(filters) !== JSON.stringify(emptyRegistryFilters)
  const tileCounts = allRows === null ? null : registryTileCounts(allRows, today)

  return (
    <section className="agreement-registry" aria-labelledby="agreement-registry-title" aria-busy={state.kind === 'loading'}>
      <div className="agreement-registry__head">
        <h2 id="agreement-registry-title" className="visually-hidden">Реестр договоров с вузами</h2>
        <HintButton label="Что в реестре">
          {role === 'USER' ? 'Договоры ваших вузов.' : role === 'LEADER' ? 'Договоры вузов вашей команды.' : 'Договоры всех вузов, только просмотр.'}
          {' '}Сначала — договоры, которые дольше всего в текущем статусе. Строка открывает договор в карточке вуза.
        </HintButton>
        {state.kind === 'ready' && <span className="agreement-registry__count" aria-live="polite">Договоров: {state.rows.length}</span>}
        <ActionMenu
          label="Ещё действия с реестром"
          items={state.kind === 'ready' && state.rows.length > 0 && !downloading
            ? [{ label: 'Выгрузить XLSX', onSelect: () => void download() }]
            : []}
        />
      </div>
      {tileCounts !== null && (
        <StatTiles
          label="Договоры по статусам"
          tiles={registryTiles.map((tile) => ({ ...tile, value: tileCounts[tile.key] }))}
          selected={registryTileOf(filters, today)}
          onSelect={(tile) => update(registryTileFilters(tile, today))}
        />
      )}
      <form className="agreement-registry__filters" onSubmit={(event) => event.preventDefault()}>
        <MultiChip
          label="Статус"
          options={agreementStatuses.map((status) => ({ id: status, label: agreementStatusLabels[status] }))}
          selected={filters.statuses}
          onChange={(ids) => update({ statuses: ids as AgreementStatus[] })}
        />
        <label>
          Вуз
          <select value={filters.organizationId} onChange={(event) => update({ organizationId: event.target.value })}>
            <option value="">Все вузы</option>
            {options?.organizations.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        {withManagers && (
          <label>
            КАМ
            <select value={filters.managerId} onChange={(event) => update({ managerId: event.target.value })}>
              <option value="">Все КАМ</option>
              {options?.managers.map((item) => (
                <option key={item.id} value={item.id}>{item.active ? item.displayName : `${item.displayName} (неактивен)`}</option>
              ))}
            </select>
          </label>
        )}
        <label>
          Истекает до
          <input type="date" value={filters.expiresBefore} onChange={(event) => update({ expiresBefore: event.target.value })} />
        </label>
        <label>
          На согласовании дольше, дней
          <input
            type="number"
            min={1}
            max={3650}
            inputMode="numeric"
            value={filters.reviewLongerThanDays}
            onChange={(event) => update({ reviewLongerThanDays: event.target.value })}
          />
        </label>
        {filtered && <button type="button" className="button--secondary" onClick={() => setFilters(emptyRegistryFilters)}>Сбросить фильтры</button>}
      </form>
      {state.kind === 'loading' && <p role="status">Загружаем реестр договоров…</p>}
      {state.kind === 'failed' && (
        <CommandError error={state.error} fallback="Не удалось загрузить реестр договоров." onRetry={() => void load(filters)} />
      )}
      {state.kind === 'ready' && (
        <>
          {downloading && <p role="status">Готовим XLSX…</p>}
          {downloadError !== null && <CommandError error={downloadError} fallback="Не удалось выгрузить реестр." />}
          {state.rows.length === 0 ? <p className="report-empty">{filtered ? 'По этим фильтрам договоров нет.' : 'Договоров пока нет.'}</p> : (
            <table className="agreement-registry__table">
              <thead>
                <tr>
                  <th scope="col">Номер</th>
                  <th scope="col">Вуз</th>
                  <th scope="col">Статус</th>
                  <th scope="col">В статусе</th>
                  <th scope="col">Подписан</th>
                  <th scope="col">Действует до</th>
                  <th scope="col">Предмет</th>
                  <th scope="col">КАМ</th>
                </tr>
              </thead>
              <tbody>
                {state.rows.map((row) => (
                  <tr key={row.id} onClick={(event) => { if (!(event.target instanceof HTMLAnchorElement)) { window.location.hash = rowHref(row) } }}>
                    <td data-label="Номер" className="agreement-registry__nowrap"><a href={rowHref(row)}>№ {row.number}</a></td>
                    <td data-label="Вуз">{row.organizationName}</td>
                    <td data-label="Статус" className="agreement-registry__nowrap">
                      <span className={`agreements__status agreements__status--${row.status.toLowerCase()}`}>{agreementStatusLabels[row.status]}</span>
                    </td>
                    <td data-label="В статусе" className="agreement-registry__nowrap">{daysLabel(row.daysInStatus)}</td>
                    <td data-label="Подписан" className="agreement-registry__nowrap">{row.concludedOn ? formatDate(row.concludedOn) : '—'}</td>
                    <td data-label="Действует до" className="agreement-registry__nowrap">{row.validUntil ? formatDate(row.validUntil) : '—'}</td>
                    <td data-label="Предмет">{row.subject ?? '—'}</td>
                    <td data-label="КАМ" className="agreement-registry__nowrap">{row.managerName ?? 'Без ответственного КАМ'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
