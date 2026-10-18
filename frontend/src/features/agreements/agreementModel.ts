import type { components } from '../../shared/api/openapi'

type Schemas = components['schemas']
export type AgreementStatus = Schemas['AgreementStatus']
export type SupplementStatus = Schemas['AgreementSupplementStatus']
export type AgreementStageRole = Schemas['AgreementStageRole']
export type AgreementFileStatus = Schemas['AgreementFile']['status']

export const agreementStatuses: readonly AgreementStatus[] = ['DRAFT', 'REVIEW', 'CORRECTION', 'SIGNING', 'ACTIVE', 'COMPLETED', 'TERMINATED']

export const agreementStatusLabels: Record<AgreementStatus, string> = {
  DRAFT: 'Проект',
  REVIEW: 'На согласовании',
  CORRECTION: 'На корректировке',
  SIGNING: 'На подписании',
  ACTIVE: 'Действует',
  COMPLETED: 'Завершён',
  TERMINATED: 'Расторгнут'
}

export const supplementStatusLabels: Record<SupplementStatus, string> = {
  DRAFT: 'Проект',
  SIGNED: 'Подписано'
}

export const fileStatusLabels: Record<AgreementFileStatus, string> = {
  QUARANTINE: 'на проверке',
  CLEAN: 'проверен',
  REJECTED: 'отклонён проверкой',
  UNVERIFIABLE: 'не удалось проверить'
}

export const stageRoleLabels: Record<AgreementStageRole, string> = {
  EXCHANGE: 'Обмен документами',
  CORRECTION: 'Корректировка документов',
  SIGNING: 'Подписание'
}

export type RegistryFilters = {
  statuses: AgreementStatus[]
  organizationId: string
  managerId: string
  expiresBefore: string
  reviewLongerThanDays: string
}

export const emptyRegistryFilters: RegistryFilters = {
  statuses: [],
  organizationId: '',
  managerId: '',
  expiresBefore: '',
  reviewLongerThanDays: ''
}

export const registrySearch = (filters: RegistryFilters) => {
  const params = new URLSearchParams()
  for (const status of agreementStatuses.filter((item) => filters.statuses.includes(item))) {
    params.append('status', status)
  }
  const scalars: [string, string][] = [
    ['organizationId', filters.organizationId],
    ['managerId', filters.managerId],
    ['expiresBefore', filters.expiresBefore],
    ['reviewLongerThanDays', filters.reviewLongerThanDays.trim()]
  ]
  for (const [key, value] of scalars) {
    if (value !== '') {
      params.set(key, value)
    }
  }
  return params.size === 0 ? '' : `?${params.toString()}`
}

const dayMilliseconds = 24 * 60 * 60 * 1000

export const daysInStatus = (statusChangedAt: string, now: number) => {
  const time = new Date(statusChangedAt).getTime()
  return Number.isNaN(time) ? 0 : Math.max(0, Math.floor((now - time) / dayMilliseconds))
}

export const defaultSignerId = (contacts: readonly { id: string; signatory: boolean }[]) => (
  contacts.find((contact) => contact.signatory)?.id ?? ''
)

export const transitionFields = (
  toStatus: AgreementStatus,
  agreement: { concludedOn?: string | null }
) => ({
  signedOn: toStatus === 'ACTIVE' && !agreement.concludedOn,
  validUntil: toStatus === 'ACTIVE' && !agreement.concludedOn,
  sentOn: toStatus === 'REVIEW'
})

export const activeStageRole = (
  stages: readonly { stageId: string; role: AgreementStageRole }[],
  currentStageId: string
) => stages.find((stage) => stage.stageId === currentStageId)?.role ?? null

export const periodDefaults = (today: string) => ({ from: `${today.slice(0, 4)}-01-01`, to: today })

type AgreementLike = Pick<Schemas['Agreement'], 'version' | 'number' | 'concludedOn' | 'validUntil' | 'sentOn' | 'parties' | 'plannedKind' | 'plannedOn'> & {
  universitySigner: { id: string } | null
  schoolSigner: { id: string } | null
}

export const inputFromAgreement = (agreement: AgreementLike): Schemas['AgreementInput'] => ({
  version: agreement.version,
  number: agreement.number,
  concludedOn: agreement.concludedOn ?? null,
  validUntil: agreement.validUntil ?? null,
  sentOn: agreement.sentOn ?? null,
  parties: agreement.parties ?? null,
  status: null,
  fileAttachmentId: null,
  universitySignerContactId: agreement.universitySigner?.id ?? null,
  schoolSignerProfileId: agreement.schoolSigner?.id ?? null,
  interactionIds: null,
  programIds: null,
  productIds: null,
  directionIds: null,
  plannedKind: agreement.plannedKind ?? null,
  plannedOn: agreement.plannedOn ?? null
})

export type RegistryTile = 'REVIEW' | 'CORRECTION' | 'SIGNING' | 'ACTIVE' | 'EXPIRING'

export const registryTiles: { key: RegistryTile; label: string; tone?: 'warning' }[] = [
  { key: 'REVIEW', label: 'На согласовании' },
  { key: 'CORRECTION', label: 'На корректировке' },
  { key: 'SIGNING', label: 'На подписании' },
  { key: 'ACTIVE', label: 'Действует' },
  { key: 'EXPIRING', label: 'Истёк или истекает за 90 дней', tone: 'warning' }
]

// Тот же горизонт, что у напоминаний «Договоры истекают» (app.work.agreement-notice-days), чтобы числа совпадали.
const expiringDays = 90

const plusDays = (today: string, days: number) => {
  const [year, month, day] = today.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10)
}

export const registryTileCounts = (
  rows: readonly { status: AgreementStatus; validUntil?: string | null }[],
  today: string
): Record<RegistryTile, number> => {
  const limit = plusDays(today, expiringDays)
  const counts: Record<RegistryTile, number> = { REVIEW: 0, CORRECTION: 0, SIGNING: 0, ACTIVE: 0, EXPIRING: 0 }
  for (const row of rows) {
    if (row.status === 'REVIEW' || row.status === 'CORRECTION' || row.status === 'SIGNING' || row.status === 'ACTIVE') {
      counts[row.status] += 1
    }
    // Тот же отбор, что ставит плитка (registryTileFilters): «Действует» со сроком до границы, истёкшие включены.
    if (row.status === 'ACTIVE' && row.validUntil && row.validUntil <= limit) {
      counts.EXPIRING += 1
    }
  }
  return counts
}

export const registryTileFilters = (tile: RegistryTile | null, today: string): Pick<RegistryFilters, 'statuses' | 'expiresBefore'> => {
  if (tile === null) {
    return { statuses: [], expiresBefore: '' }
  }
  return tile === 'EXPIRING'
    ? { statuses: ['ACTIVE'], expiresBefore: plusDays(today, expiringDays) }
    : { statuses: [tile], expiresBefore: '' }
}

export const registryTileOf = (filters: RegistryFilters, today: string): RegistryTile | null => {
  const tile = registryTiles.find((item) => {
    const expected = registryTileFilters(item.key, today)
    return expected.expiresBefore === filters.expiresBefore
      && expected.statuses.length === filters.statuses.length
      && expected.statuses.every((status) => filters.statuses.includes(status))
  })
  return tile?.key ?? null
}

export const agreementNoticeDays = 90

export type AgreementTerm = 'expired' | 'expiring' | null

// Статус по сроку не меняется (решение владельца): «срок истёк» вычисляется по дате окончания на сегодня по МСК,
// так же как на сервере (AgreementStatus.expired). «Истекает» — только когда задан горизонт предупреждения.
export const agreementTerm = (
  agreement: { status: AgreementStatus; validUntil?: string | null },
  today: string,
  noticeDays = 0
): AgreementTerm => {
  if (agreement.status !== 'ACTIVE' || !agreement.validUntil) {
    return null
  }
  if (agreement.validUntil < today) {
    return 'expired'
  }
  return noticeDays > 0 && agreement.validUntil <= plusDays(today, noticeDays) ? 'expiring' : null
}

// Привязать можно любой договор вуза, кроме расторгнутого и уже связанного с этим взаимодействием.
export const linkCandidates = <A extends { id: string; status: AgreementStatus }>(
  agreements: readonly A[],
  linkedIds: readonly string[]
) => agreements.filter((agreement) => agreement.status !== 'TERMINATED' && !linkedIds.includes(agreement.id))

// Правка реквизитов с полным списком связей: прежние взаимодействия договора сохраняются, текущее добавляется.
export const linkInput = (
  agreement: AgreementLike & { interactions: readonly { id: string }[] },
  interactionId: string
): Schemas['AgreementInput'] => ({
  ...inputFromAgreement(agreement),
  interactionIds: [...new Set([...agreement.interactions.map((item) => item.id), interactionId])]
})
