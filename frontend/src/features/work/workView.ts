import type { DeadlineGroup } from './deadlines'

export type KamView = 'deadlines' | 'signals'

export type KamTile = 'OVERDUE' | 'THIS_WEEK' | 'NO_NEXT_STEP' | 'LICENSES' | 'LMS' | 'AGREEMENTS' | 'ISSUES' | 'RESUME'

export type FeedGroup = DeadlineGroup | 'noStep'

const viewKey = 'rtk-crm.work.kam-view'

export const readKamView = (storage: Pick<Storage, 'getItem'> | null): KamView => {
  try {
    return storage?.getItem(viewKey) === 'signals' ? 'signals' : 'deadlines'
  } catch {
    return 'deadlines'
  }
}

export const saveKamView = (storage: Pick<Storage, 'setItem'> | null, view: KamView): boolean => {
  try {
    storage?.setItem(viewKey, view)
    return storage !== null
  } catch {
    return false
  }
}

export const browserStorage = (): Storage | null => {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

const tileGroups: Record<'OVERDUE' | 'THIS_WEEK' | 'NO_NEXT_STEP', FeedGroup[]> = {
  OVERDUE: ['overdue'],
  THIS_WEEK: ['today', 'week'],
  NO_NEXT_STEP: ['noStep']
}

export const feedGroupsFor = (tile: KamTile | null): FeedGroup[] => {
  if (tile === 'AGREEMENTS' || tile === 'ISSUES' || tile === 'RESUME') {
    return []
  }
  return tile === 'OVERDUE' || tile === 'THIS_WEEK' || tile === 'NO_NEXT_STEP'
    ? tileGroups[tile]
    : ['overdue', 'today', 'week', 'later', 'noStep']
}

export const feedGroupLinks: Record<FeedGroup, string> = {
  overdue: '#/work?due=OVERDUE',
  today: '#/work?due=TODAY',
  week: '#/work?due=THIS_WEEK',
  later: '#/work?due=LATER',
  noStep: '#/work?due=NO_NEXT_STEP'
}
