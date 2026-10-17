import type { AdminOrganization } from '../../shared/api/client'

export type OrganizationListStatus = 'CURRENT' | 'PENDING' | 'ARCHIVED' | 'ALL'

export type OrganizationMenuKey = 'edit' | 'APPROVE' | 'REJECT' | 'ARCHIVE' | 'RESTORE' | 'transfer'

/** Пункты «⋯» организации; администратору доступны все права, условия — по состоянию и типу. */
export const organizationMenuActions = (organization: Pick<AdminOrganization, 'status' | 'type'>): OrganizationMenuKey[] => {
  const keys: OrganizationMenuKey[] = []
  if (organization.status !== 'ARCHIVED' && organization.type !== 'OPEN_ENROLLMENT') {
    keys.push('edit')
  }
  if (organization.status === 'PENDING') {
    keys.push('APPROVE', 'REJECT')
  }
  if (organization.status === 'ARCHIVED') {
    keys.push('RESTORE')
  } else {
    keys.push('ARCHIVE')
  }
  keys.push('transfer')
  return keys
}
