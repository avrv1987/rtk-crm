import type { SourceCode } from '../../shared/api/client'

export type MappingsFilter = 'ALL' | 'WEBSITE' | 'MOODLE' | 'CUSTOMER_CRM'

type Mappable = {
  source?: SourceCode
  kind: 'ORGANIZATION' | 'PROGRAM' | 'COURSE' | 'GROUP'
  label: string
  externalKey: string
  organizationName?: string | null
  programName?: string | null
}

// Источник сопоставления берём из поля source, а если его нет — по виду: курс и группа бывают только у Moodle.
export const mappingSource = (mapping: Pick<Mappable, 'source' | 'kind'>): SourceCode => (
  mapping.source ?? (mapping.kind === 'COURSE' || mapping.kind === 'GROUP' ? 'MOODLE' : 'WEBSITE')
)

const kindLabels: Record<Mappable['kind'], string> = {
  COURSE: 'Курс Moodle',
  GROUP: 'Группа Moodle',
  ORGANIZATION: 'Вуз на сайте',
  PROGRAM: 'Программа на сайте'
}

export const mappingKindLabel = (mapping: Pick<Mappable, 'source' | 'kind'>): string => (
  mappingSource(mapping) === 'CUSTOMER_CRM' ? 'Вуз в CRM заказчика' : kindLabels[mapping.kind]
)

export const filterMappings = <T extends Mappable>(mappings: T[], search: string, source: MappingsFilter): T[] => {
  const needle = search.trim().toLowerCase()
  return mappings.filter((mapping) => (
    (source === 'ALL' || mappingSource(mapping) === source)
    && (needle === '' || [mapping.label, mapping.externalKey, mapping.organizationName, mapping.programName]
      .some((value) => value?.toLowerCase().includes(needle)))
  ))
}
