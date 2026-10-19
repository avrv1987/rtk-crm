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

export const batchResultText: Record<'PENDING' | 'ACCEPTED' | 'FAILED', string> = {
  PENDING: 'ожидает',
  ACCEPTED: 'принята',
  FAILED: 'ошибка'
}
