import { useCallback, useEffect, useState } from 'react'
import {
  handledAccessError,
  listReportVendors,
  transferKindLabels,
  transferKinds,
  type AccessHandlers,
  type CatalogReference,
  type ProductTransferKind
} from '../documents/documentsApi'
import type { AgreementSelection } from './reportAgreement'
import '../documents/documents.css'

type ReportAgreementFilterProps = AccessHandlers & {
  value: AgreementSelection
  onChange: (value: AgreementSelection) => void
}

type VendorsState =
  | { kind: 'loading' }
  | { kind: 'ready'; vendors: CatalogReference[] }
  | { kind: 'failed' }

export const ReportAgreementFilter = ({ value, onChange, onSessionExpired, onProfileUnavailable }: ReportAgreementFilterProps) => {
  const [vendorsState, setVendorsState] = useState<VendorsState>({ kind: 'loading' })

  const loadVendors = useCallback(async () => {
    setVendorsState({ kind: 'loading' })
    try {
      setVendorsState({ kind: 'ready', vendors: await listReportVendors() })
    } catch (error) {
      if (!handledAccessError(error, { onSessionExpired, onProfileUnavailable })) {
        setVendorsState({ kind: 'failed' })
      }
    }
  }, [onProfileUnavailable, onSessionExpired])

  useEffect(() => {
    void loadVendors()
  }, [loadVendors])

  const toggleVendor = (id: string, checked: boolean) => {
    onChange({ ...value, vendorIds: checked ? [...value.vendorIds, id] : value.vendorIds.filter((item) => item !== id) })
  }

  const toggleTransfer = (kind: ProductTransferKind, checked: boolean) => {
    onChange({
      ...value,
      notTransferred: checked
        ? transferKinds.filter((item) => item === kind || value.notTransferred.includes(item))
        : value.notTransferred.filter((item) => item !== kind)
    })
  }

  return (
    <fieldset className="report-filter report-agreement-filter">
      <legend>Договор, лицензия и передача</legend>
      <p className="report-filter__summary">Условия относятся к одному и тому же продукту взаимодействия.</p>
      <div className="report-agreement-filter__fields">
        <label>
          Подписание лицензии
          <select
            value={value.licenseSigned}
            onChange={(event) => onChange({ ...value, licenseSigned: event.target.value as AgreementSelection['licenseSigned'] })}
          >
            <option value="">Все</option>
            <option value="true">Лицензия подписана</option>
            <option value="false">Не подписана или не указано</option>
          </select>
        </label>
        <label>
          Лицензия истекает до (год, включительно)
          <input
            type="number"
            inputMode="numeric"
            min={2000}
            max={2100}
            placeholder="Например, 2026"
            value={value.licenseExpiresBy}
            onChange={(event) => onChange({ ...value, licenseExpiresBy: event.target.value.slice(0, 4) })}
          />
        </label>
      </div>
      <div>
        <p className="report-filter__summary">Не передано:</p>
        <ul className="report-filter__options">
          {transferKinds.map((kind) => (
            <li key={kind}>
              <label>
                <input
                  type="checkbox"
                  checked={value.notTransferred.includes(kind)}
                  onChange={(event) => toggleTransfer(kind, event.target.checked)}
                />
                <span>{transferKindLabels[kind]}</span>
              </label>
            </li>
          ))}
        </ul>
      </div>
      <div>
        <p className="report-filter__summary">
          Вендор{value.vendorIds.length > 0 ? ` (выбрано: ${value.vendorIds.length})` : ': все'}
        </p>
        {vendorsState.kind === 'loading' && <p role="status">Загружаем вендоров…</p>}
        {vendorsState.kind === 'failed' && (
          <p role="alert">
            Не удалось загрузить вендоров. <button type="button" className="report-filter__clear" onClick={() => void loadVendors()}>Повторить</button>
          </p>
        )}
        {vendorsState.kind === 'ready' && vendorsState.vendors.length === 0 && <p className="report-filter__empty">Нет доступных значений.</p>}
        {vendorsState.kind === 'ready' && vendorsState.vendors.length > 0 && (
          <ul className="report-filter__options">
            {vendorsState.vendors.map((vendor) => (
              <li key={vendor.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={value.vendorIds.includes(vendor.id)}
                    onChange={(event) => toggleVendor(vendor.id, event.target.checked)}
                  />
                  <span>{vendor.archived ? `${vendor.name} (архивирован)` : vendor.name}</span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </div>
    </fieldset>
  )
}
