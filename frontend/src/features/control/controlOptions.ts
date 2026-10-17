import { apiClient, type Organization } from '../../shared/api/client'
import { organizationTypeLabels } from '../organizations/OrganizationForm'
import type { FilterOption } from '../reports/ReportControls'

export type ControlOptions = {
  organizations: FilterOption[]
  directions: FilterOption[]
  programs: FilterOption[]
  products: FilterOption[]
  managers: FilterOption[]
  stages: FilterOption[]
  organizationList: Organization[]
}

const loadAllPages = async <T,>(loadPage: (page: number) => Promise<{ items: T[]; total: number }>) => {
  const items: T[] = []
  for (let page = 0; ; page += 1) {
    const result = await loadPage(page)
    items.push(...result.items)
    if (result.items.length === 0 || items.length >= result.total) {
      return items
    }
  }
}

const catalogOptions = (items: { id: string; name: string; archived: boolean }[]): FilterOption[] => (
  items.map((item) => ({ id: item.id, label: item.archived ? `${item.name} — в архиве` : item.name }))
)

export const loadControlOptions = async (): Promise<ControlOptions> => {
  const [organizations, directions, programs, products, managers, stages] = await Promise.all([
    loadAllPages((page) => apiClient.listOrganizations({ page, size: 100, sort: 'name,asc', status: 'ALL' })),
    loadAllPages((page) => apiClient.listDirections({ page, size: 100, state: 'ALL' })),
    loadAllPages((page) => apiClient.listPrograms({ page, size: 100, state: 'ALL' })),
    loadAllPages((page) => apiClient.listProducts({ page, size: 100, state: 'ALL' })),
    apiClient.listReportManagers(),
    apiClient.listReportStages()
  ])
  return {
    organizations: organizations.map((organization) => ({
      id: organization.id,
      label: `${organization.name}${organization.type === 'UNIVERSITY'
        ? ''
        : ` (${organizationTypeLabels[organization.type].toLowerCase()})`}${organization.status === 'ARCHIVED' ? ' — в архиве' : ''}`
    })),
    directions: catalogOptions(directions),
    programs: catalogOptions(programs),
    products: catalogOptions(products),
    managers: managers.map((manager) => ({
      id: manager.id,
      label: manager.active ? manager.displayName : `${manager.displayName} (неактивен)`
    })),
    stages: stages.map((stage) => ({ id: stage, label: stage })),
    organizationList: organizations
  }
}
