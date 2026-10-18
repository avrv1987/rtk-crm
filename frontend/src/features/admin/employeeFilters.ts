import type { CrmProfile, CrmProfileListParams, CrmProfileUpdate, LoginLockoutStatus } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime.ts'

export type EmployeeStatus = NonNullable<CrmProfileListParams['status']>

export type EmployeeFilter = {
  status: '' | EmployeeStatus
  role: '' | CrmProfile['role']
  teamId: string
}

export const noEmployeeFilter: EmployeeFilter = { status: '', role: '', teamId: '' }

export type EmployeeSegment = 'ALL' | 'PENDING' | 'BLOCKED'

/** Сегмент над таблицей; для «Активные» и «Требуют синхронизации» сегмент не подсвечивается (их выбирают в «Фильтрах»). */
export const segmentOf = (filter: EmployeeFilter): EmployeeSegment | null => {
  if (filter.status === '') {
    return 'ALL'
  }
  return filter.status === 'PENDING' || filter.status === 'BLOCKED' ? filter.status : null
}

export const filterWithSegment = (filter: EmployeeFilter, segment: EmployeeSegment): EmployeeFilter => (
  { ...filter, status: segment === 'ALL' ? '' : segment }
)

const statuses: EmployeeStatus[] = ['ACTIVE', 'PENDING', 'BLOCKED', 'SYNC_REQUIRED']

/** Состояние из адреса: ?status=PENDING; старое ?pending=true означает то же самое. */
export const statusFromQuery = (query: string): '' | EmployeeStatus => {
  const params = new URLSearchParams(query)
  const status = params.get('status')
  if (status !== null && (statuses as string[]).includes(status)) {
    return status as EmployeeStatus
  }
  return params.get('pending') === 'true' ? 'PENDING' : ''
}

export const employeeFilterParams = (filter: EmployeeFilter, search: string) => ({
  status: filter.status || undefined,
  role: filter.role || undefined,
  teamId: filter.teamId || undefined,
  q: search || undefined
})

export const isEmployeeFiltered = (filter: EmployeeFilter, search: string) => (
  filter.status !== '' || filter.role !== '' || filter.teamId !== '' || search !== ''
)

/** Число на кнопке «Фильтры»: то, что не видно на сегментах. */
export const panelFilterCount = (filter: EmployeeFilter) => (
  (filter.status === 'ACTIVE' || filter.status === 'SYNC_REQUIRED' ? 1 : 0)
  + (filter.role === '' ? 0 : 1)
  + (filter.teamId === '' ? 0 : 1)
)

export type EmployeeMenuKey = 'edit' | 'journal' | 'reset' | 'logout' | 'otp' | 'email' | 'sync'

type MenuProfile = Pick<CrmProfile, 'id' | 'role' | 'pendingActivation' | 'accountSyncRequired' | 'roleReconcileRequired'>

/** Пункты «⋯» строки в порядке показа; условия те же, что были у кнопок и меню учётной записи. */
export const employeeMenuKeys = (profile: MenuProfile, currentProfileId: string): EmployeeMenuKey[] => {
  const own = profile.id === currentProfileId
  const keys: EmployeeMenuKey[] = []
  if (!profile.pendingActivation) {
    keys.push('edit')
  }
  keys.push('journal')
  if (!own && profile.role !== 'ADMIN') {
    keys.push('reset')
  }
  if (!own) {
    keys.push('logout')
  }
  if (!own && profile.role !== 'ADMIN') {
    keys.push('otp')
  }
  if (profile.role !== 'PARTNER') {
    keys.push('email')
  }
  if (profile.accountSyncRequired === true || profile.roleReconcileRequired === true) {
    keys.push('sync')
  }
  return keys
}

export const syncActionLabel = (profile: Pick<CrmProfile, 'active' | 'accountSyncRequired'>) => {
  if (profile.accountSyncRequired !== true) {
    return 'Сверить роль с Keycloak'
  }
  return profile.active ? 'Включить в Keycloak' : 'Отключить в Keycloak'
}

export const roleSourceLabel = (profile: Pick<CrmProfile, 'roleSyncedAt' | 'pendingActivation'>) => {
  if (profile.roleSyncedAt) {
    return 'из Keycloak'
  }
  return profile.pendingActivation ? null : 'в Keycloak не перенесена'
}

// Те же условия, что на сервере: вузы остаются за сотрудником, только если он действующий КАМ или руководитель
// и команда не меняется.
export const unassignsOrganizations = (profile: Pick<CrmProfile, 'role' | 'active'>, update: CrmProfileUpdate) => {
  const nextRole = update.role ?? profile.role
  const nextActive = update.active ?? profile.active
  return (profile.role === 'USER' || profile.role === 'LEADER')
    && (update.teamId !== undefined || (nextRole !== 'USER' && nextRole !== 'LEADER') || !nextActive)
}

export const transferSummary = (organizations: number, recipient: string | null) => {
  if (organizations === 0) {
    return 'За сотрудником нет вузов.'
  }
  return recipient === null
    ? `Вузы сотрудника (${organizations}) останутся без КАМ: руководитель команды увидит их в колокольчике, в истории вуза будет причина.`
    : `Вузы сотрудника (${organizations}) перейдут к КАМ «${recipient}»; в истории каждого вуза будет причина.`
}

export const lockoutText = (status: Pick<LoginLockoutStatus, 'available' | 'failures' | 'locked' | 'lastFailureAt'>) => {
  if (!status.available) {
    return 'Связь с Keycloak не настроена: неудачные входы не видны.'
  }
  if (status.locked) {
    return `Вход временно заблокирован Keycloak после неудачных попыток (подряд: ${status.failures}). С верным паролем сотрудник тоже не войдёт, пока блокировка не истечёт или её не снимут.`
  }
  if (status.failures > 0) {
    const last = status.lastFailureAt === null ? '' : `, последняя — ${formatMoscowDateTime(status.lastFailureAt)}`
    return `Неудачных попыток входа подряд: ${status.failures}${last}. Ещё несколько ошибок — и Keycloak временно заблокирует вход.`
  }
  return 'Неудачных попыток входа нет, вход не заблокирован.'
}
