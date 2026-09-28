import { useId } from 'react'
import { transferKindLabels, transferKinds, type ProductTransferKind } from '../documents/documentsApi'
import { Chip, type FilterOption, type SelectedValue } from './ReportControls'
import { defaultAgreementSelection, type AgreementSelection } from './reportAgreement'

type ReportAgreementFilterProps = {
  value: AgreementSelection
  vendors: FilterOption[]
  onChange: (value: AgreementSelection) => void
}

const activeCount = (value: AgreementSelection) => (
  value.vendorIds.length + value.notTransferred.length + (value.licenseSigned === '' ? 0 : 1) + (value.licenseExpiresBy === '' ? 0 : 1)
)

export const agreementValues = (
  value: AgreementSelection,
  vendors: FilterOption[],
  onChange: (value: AgreementSelection) => void
): SelectedValue[] => [
  ...(value.licenseSigned === '' ? [] : [{
    key: 'license-signed',
    text: value.licenseSigned === 'true' ? 'Лицензия подписана' : 'Лицензия не подписана или не указано',
    onRemove: () => onChange({ ...value, licenseSigned: '' })
  }]),
  ...(value.licenseExpiresBy === '' ? [] : [{
    key: 'license-expires',
    text: `Лицензия истекает до ${value.licenseExpiresBy} г.`,
    onRemove: () => onChange({ ...value, licenseExpiresBy: '' })
  }]),
  ...value.notTransferred.map((kind) => ({
    key: `transfer-${kind}`,
    text: `Не передано: ${transferKindLabels[kind].toLocaleLowerCase('ru-RU')}`,
    onRemove: () => onChange({ ...value, notTransferred: value.notTransferred.filter((item) => item !== kind) })
  })),
  ...value.vendorIds.map((id) => ({
    key: `vendor-${id}`,
    text: `Вендор: ${vendors.find((vendor) => vendor.id === id)?.label ?? 'недоступен'}`,
    onRemove: () => onChange({ ...value, vendorIds: value.vendorIds.filter((item) => item !== id) })
  }))
]

export const ReportAgreementFilter = ({ value, vendors, onChange }: ReportAgreementFilterProps) => {
  const signedId = useId()
  const expiresId = useId()
  const count = activeCount(value)

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
    <Chip
      label="Договор и лицензия"
      value={count === 0 ? 'все' : `условий ${count}`}
      active={count > 0}
      onClear={() => onChange(defaultAgreementSelection())}
    >
      {() => (
        <div className="report-agreement">
          <p className="report-popover__note">Все условия относятся к одному и тому же продукту взаимодействия.</p>
          <div className="report-agreement__field">
            <label htmlFor={signedId}>Подписание лицензии</label>
            <select
              id={signedId}
              value={value.licenseSigned}
              onChange={(event) => onChange({ ...value, licenseSigned: event.target.value as AgreementSelection['licenseSigned'] })}
            >
              <option value="">Все</option>
              <option value="true">Лицензия подписана</option>
              <option value="false">Не подписана или не указано</option>
            </select>
          </div>
          <div className="report-agreement__field">
            <label htmlFor={expiresId}>Лицензия истекает до (год, включительно)</label>
            <input
              id={expiresId}
              type="number"
              inputMode="numeric"
              min={2000}
              max={2100}
              placeholder="Например, 2026"
              value={value.licenseExpiresBy}
              onChange={(event) => onChange({ ...value, licenseExpiresBy: event.target.value.slice(0, 4) })}
            />
          </div>
          <fieldset className="report-agreement__group">
            <legend>Не передано</legend>
            {transferKinds.map((kind) => (
              <label key={kind} className="report-chip__option">
                <input
                  type="checkbox"
                  checked={value.notTransferred.includes(kind)}
                  onChange={(event) => toggleTransfer(kind, event.target.checked)}
                />
                <span>{transferKindLabels[kind]}</span>
              </label>
            ))}
          </fieldset>
          <fieldset className="report-agreement__group">
            <legend>Вендор</legend>
            {vendors.length === 0 && <p className="report-popover__note">Нет доступных значений.</p>}
            <ul className="report-chip__options">
              {vendors.map((vendor) => (
                <li key={vendor.id}>
                  <label className="report-chip__option">
                    <input
                      type="checkbox"
                      checked={value.vendorIds.includes(vendor.id)}
                      onChange={(event) => toggleVendor(vendor.id, event.target.checked)}
                    />
                    <span>{vendor.label}</span>
                  </label>
                </li>
              ))}
            </ul>
          </fieldset>
        </div>
      )}
    </Chip>
  )
}
