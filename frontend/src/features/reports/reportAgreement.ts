import type { ReportColumn } from '../../shared/api/client'
import { transferKinds, type ProductTransferKind, type ReportAgreementFilters } from '../documents/documentsApi'

export type AgreementSelection = {
  vendorIds: string[]
  licenseSigned: '' | 'true' | 'false'
  licenseExpiresBy: string
  notTransferred: ProductTransferKind[]
}

export const agreementColumns: readonly ReportColumn[] = [
  'VENDORS', 'CONTRACT_NUMBER', 'LICENSE_SIGNED', 'LICENSE_EXPIRY_YEAR', 'TRANSFER_STATUS', 'MATERIALS_TRANSFERRED_ON'
]

export const agreementColumnTitles = {
  VENDORS: 'Вендор',
  CONTRACT_NUMBER: 'Номер договора',
  LICENSE_SIGNED: 'Подписание лицензии',
  LICENSE_EXPIRY_YEAR: 'Срок лицензии',
  TRANSFER_STATUS: 'Статус передачи',
  MATERIALS_TRANSFERRED_ON: 'Передача материалов'
} satisfies Partial<Record<ReportColumn, string>>

export const defaultAgreementSelection = (): AgreementSelection => ({
  vendorIds: [],
  licenseSigned: '',
  licenseExpiresBy: '',
  notTransferred: []
})

export const normalizeAgreementSelection = (value: unknown): AgreementSelection => {
  if (typeof value !== 'object' || value === null) {
    return defaultAgreementSelection()
  }
  const record = value as Record<string, unknown>
  return {
    vendorIds: Array.isArray(record.vendorIds) ? record.vendorIds.filter((item): item is string => typeof item === 'string') : [],
    licenseSigned: record.licenseSigned === 'true' || record.licenseSigned === 'false' ? record.licenseSigned : '',
    licenseExpiresBy: typeof record.licenseExpiresBy === 'string' && /^\d{0,4}$/.test(record.licenseExpiresBy) ? record.licenseExpiresBy : '',
    notTransferred: Array.isArray(record.notTransferred)
      ? transferKinds.filter((kind) => (record.notTransferred as unknown[]).includes(kind))
      : []
  }
}

export const agreementProblem = (selection: AgreementSelection): string | null => {
  if (selection.licenseExpiresBy === '') {
    return null
  }
  const year = Number(selection.licenseExpiresBy)
  return Number.isInteger(year) && year >= 2000 && year <= 2100
    ? null
    : 'Год в фильтре «Лицензия истекает до» должен быть от 2000 до 2100.'
}

export const toAgreementFilters = (selection: AgreementSelection): ReportAgreementFilters => ({
  vendorIds: selection.vendorIds,
  licenseSigned: selection.licenseSigned === '' ? null : selection.licenseSigned === 'true',
  licenseExpiresBy: selection.licenseExpiresBy === '' ? null : Number(selection.licenseExpiresBy),
  notTransferred: selection.notTransferred
})
