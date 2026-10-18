export type EnrolmentRoute =
  | { kind: 'streams' }
  | { kind: 'stream'; streamId: string }
  | { kind: 'learner'; learnerId: string; streamId: string | null }
  | { kind: 'batch'; batchId: string }

const idPattern = /^[0-9A-Za-z-]{1,64}$/

// Адрес вводит пользователь: всё, что не похоже на известный внутренний экран, ведёт на список потоков.
export const enrolmentRoute = (hash: string): EnrolmentRoute => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section, kind, id = ''] = path.split('/')
  if (section !== 'enrolment' || !idPattern.test(id)) {
    return { kind: 'streams' }
  }
  if (kind === 'stream') {
    return { kind: 'stream', streamId: id }
  }
  if (kind === 'batch') {
    return { kind: 'batch', batchId: id }
  }
  if (kind === 'learner') {
    const streamId = new URLSearchParams(query).get('stream')
    return { kind: 'learner', learnerId: id, streamId: streamId !== null && idPattern.test(streamId) ? streamId : null }
  }
  return { kind: 'streams' }
}

export const enrolmentHref = (route: EnrolmentRoute): string => {
  if (route.kind === 'stream') {
    return `#/enrolment/stream/${encodeURIComponent(route.streamId)}`
  }
  if (route.kind === 'batch') {
    return `#/enrolment/batch/${encodeURIComponent(route.batchId)}`
  }
  if (route.kind === 'learner') {
    const back = route.streamId === null ? '' : `?stream=${encodeURIComponent(route.streamId)}`
    return `#/enrolment/learner/${encodeURIComponent(route.learnerId)}${back}`
  }
  return '#/enrolment'
}

export const enrolmentBack = (route: EnrolmentRoute): { href: string; label: string } | null => {
  if (route.kind === 'stream' || route.kind === 'batch') {
    return { href: enrolmentHref({ kind: 'streams' }), label: '← Потоки' }
  }
  if (route.kind === 'learner') {
    return route.streamId === null
      ? { href: enrolmentHref({ kind: 'streams' }), label: '← Потоки' }
      : { href: enrolmentHref({ kind: 'stream', streamId: route.streamId }), label: '← Поток' }
  }
  return null
}
