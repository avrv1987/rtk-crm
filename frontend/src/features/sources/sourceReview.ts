import type { SourceCode } from '../../shared/api/client'

export type ReviewFilterSource = 'ALL' | SourceCode

type Reviewable = {
  source: SourceCode
  externalId: string
  organizationName?: string | null
  programName?: string | null
  contactName?: string | null
}

export const filterReviewRecords = <T extends Reviewable>(records: T[], search: string, source: ReviewFilterSource): T[] => {
  const needle = search.trim().toLowerCase()
  return records.filter((record) => (
    (source === 'ALL' || record.source === source)
    && (needle === '' || [record.organizationName, record.programName, record.contactName, record.externalId]
      .some((value) => value?.toLowerCase().includes(needle)))
  ))
}

export const reviewSourceCounts = (records: { source: SourceCode }[]): Record<ReviewFilterSource, number> => {
  const counts: Record<ReviewFilterSource, number> = { ALL: records.length, WEBSITE: 0, MOODLE: 0, CUSTOMER_CRM: 0, LABOR_MARKET: 0 }
  for (const record of records) {
    counts[record.source] += 1
  }
  return counts
}
