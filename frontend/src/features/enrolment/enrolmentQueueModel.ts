export type QueueKind = 'MISSING_DATA' | 'AMBIGUOUS_MATCH' | 'READY' | 'NOT_SENT' | 'AWAITING_RESULT' | 'LMS_ERROR'
  | 'MOVED_AFTER_TRANSFER' | 'CHANGED_AFTER_TRANSFER'

export type QueueAction = 'learner' | 'stream' | 'batch' | 'reconcile'

// У каждой очереди — понятное название, следующее действие и куда оно ведёт: в анкету, в поток, в партию или
// к отметке сверки прямо в очереди.
export const queueKinds: Record<QueueKind, { title: string; next: string; action: QueueAction }> = {
  MOVED_AFTER_TRANSFER: {
    title: 'Перенос после передачи — нужна сверка LMS',
    next: 'Уберите слушателя с прежнего курса в LMS, проверьте новый и отметьте сверку',
    action: 'reconcile'
  },
  LMS_ERROR: {
    title: 'Ошибка LMS',
    next: 'Исправьте анкету по тексту ошибки и выгрузите поток повторно; если курс Moodle не сопоставлен — обратитесь к администратору',
    action: 'learner'
  },
  CHANGED_AFTER_TRANSFER: {
    title: 'Анкета изменена после передачи',
    next: 'Внесите изменения в LMS или выгрузите анкету повторно, затем отметьте сверку',
    action: 'reconcile'
  },
  MISSING_DATA: { title: 'Не хватает обязательных сведений', next: 'Дополните анкету', action: 'learner' },
  AMBIGUOUS_MATCH: {
    title: 'Неоднозначное совпадение',
    next: 'Проверьте, один ли это человек; дубль объедините по СНИЛС в анкете',
    action: 'learner'
  },
  AWAITING_RESULT: {
    title: 'Ожидается результат LMS',
    next: 'Загрузите протокол приёма LMS или отметьте строки партии',
    action: 'batch'
  },
  NOT_SENT: {
    title: 'Файл сформирован, не передан',
    next: 'Передайте файл в LMS и отметьте партию переданной',
    action: 'batch'
  },
  READY: { title: 'Готово к передаче', next: 'Выгрузите поток для LMS', action: 'stream' }
}

export const queueOrder = Object.keys(queueKinds) as QueueKind[]

// «Нет курса Moodle» — не ошибка анкеты: курс программы сопоставляет администратор, менеджер учебного процесса этого не может.
export const courseNotMapped = (detail: string | null) => detail !== null && detail.startsWith('Нет курса Moodle')

export const lmsStatusText: Record<'PENDING' | 'EXPORTED' | 'SENT' | 'FAILED' | 'TRANSFERRED', string> = {
  PENDING: 'Ожидает выгрузки',
  EXPORTED: 'Файл сформирован',
  SENT: 'Передано, ждём результат LMS',
  FAILED: 'Ошибка LMS',
  TRANSFERRED: 'Принято LMS'
}

// Решение владельца 21.10: прежние отметки «передано» стали «принято LMS» с источником LEGACY. Пока сверка с Moodle их
// не подтвердила, строка честно говорит, откуда «принято».
export const legacyUnverified = (item: { result: string; resultSource: string | null }) => (
  item.result === 'ACCEPTED' && item.resultSource === 'LEGACY'
)

export const legacyNote = 'из прежнего учёта, не сверено с LMS'

// Кнопка Moodle в партии: передать не принятые строки или, если остались только строки прежнего учёта, сверить их.
export const moodleBatchAction = (
  batch: { moodleTransferAvailable: boolean; accepted: number; rows: number; items: { result: string; resultSource: string | null }[] }
): 'transfer' | 'verify' | null => {
  if (!batch.moodleTransferAvailable) {
    return null
  }
  if (batch.accepted < batch.rows) {
    return 'transfer'
  }
  return batch.items.some(legacyUnverified) ? 'verify' : null
}

// Уточнение курса Moodle у потока: пустой курс — «по программе», группа только вместе с курсом.
export type MoodleCourseInput = { courseId: number | null; groupId: number | null }

export const parseMoodleCourse = (course: string, group: string): MoodleCourseInput | string => {
  const number = (value: string) => (/^\d{1,15}$/.test(value.trim()) && Number(value.trim()) > 0 ? Number(value.trim()) : null)
  if (course.trim() === '') {
    return group.trim() === '' ? { courseId: null, groupId: null } : 'Группа задаётся вместе с курсом'
  }
  const courseId = number(course)
  if (courseId === null) {
    return 'Номер курса Moodle — целое число больше нуля'
  }
  if (group.trim() === '') {
    return { courseId, groupId: null }
  }
  const groupId = number(group)
  return groupId === null ? 'Номер группы Moodle — целое число больше нуля' : { courseId, groupId }
}

export const moodleCourseText = (stream: { moodleCourseId: number | null; moodleGroupId: number | null }) => (
  stream.moodleCourseId === null
    ? 'по сопоставлениям программы потока'
    : `курс ${stream.moodleCourseId}${stream.moodleGroupId === null ? ', группа по сопоставлениям или без группы' : `, группа ${stream.moodleGroupId}`}`
)

export const batchResultText: Record<'PENDING' | 'ACCEPTED' | 'FAILED', string> = {
  PENDING: 'ожидает',
  ACCEPTED: 'принята',
  FAILED: 'ошибка'
}

export type EnrolmentQueueQuery = { streamId?: string; kind?: QueueKind; offset?: number }

// Отбор очереди по потоку и группе и смещение для «Показать ещё».
export const enrolmentQueueSearch = (query: EnrolmentQueueQuery) => {
  const params = new URLSearchParams()
  if (query.streamId) {
    params.set('streamId', query.streamId)
  }
  if (query.kind) {
    params.set('kind', query.kind)
  }
  if (query.offset) {
    params.set('offset', String(query.offset))
  }
  const search = params.toString()
  return search === '' ? '' : `?${search}`
}
