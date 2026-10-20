import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { MaxApiError, errorText, problemOf, type MaxApi, type MaxAppCard, type Problem } from './maxApi'
import { dueChip, dueText, quickDue, quickDues, type QuickDue } from './model'
import { moscowDateTime, nextStepErrors, type Bridge } from './startup'
import { Banner, Chips } from './ui'
import { useLoad } from './useLoad'

type SaveError = { kind: 'conflict' | 'expired' | 'other'; text: string }

/**
 * «Шаг выполнен», «Комментарий» и «Задать шаг». В комментарии действующий шаг и срок подставляются сами: сервер требует
 * шаг целиком, а КАМ обычно его не меняет. Черновик — только в памяти; пока в нём есть текст, MAX спрашивает
 * подтверждение при закрытии.
 */
export const StepForm = ({ api, bridge, id, mode, initial, back, onFatal, onDone }: {
  api: MaxApi
  bridge: Bridge | undefined
  id: string
  mode: 'comment' | 'step'
  initial: MaxAppCard | undefined
  back: ReactNode
  onFatal: (problem: Problem) => void
  onDone: (message: string) => void
}) => {
  const fieldId = useId()
  const [attempt, setAttempt] = useState(0)
  // Карточку из экрана карточки повторно не запрашиваем; «Обновить данные» после конфликта берёт свежую версию.
  const loaded = useLoad(initial !== undefined && attempt === 0 ? null : `${id}:${attempt}`, (signal) => api.card(id, signal), onFatal)
  const card = initial !== undefined && attempt === 0 ? initial : loaded.data
  const [text, setText] = useState('')
  const [nextAction, setNextAction] = useState('')
  const [quick, setQuick] = useState<QuickDue | 'other' | null>(null)
  const [date, setDate] = useState('')
  const [time, setTime] = useState('10:00')
  const [editStep, setEditStep] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<SaveError | null>(null)
  const [idempotencyKey] = useState(() => crypto.randomUUID())
  const textRef = useRef<HTMLTextAreaElement>(null)
  const stepRef = useRef<HTMLInputElement>(null)
  const dueRef = useRef<HTMLButtonElement>(null)
  const prefilled = useRef(false)
  const now = Date.now()

  const step = card?.interaction.nextAction ?? null
  const stepAt = card?.interaction.nextActionAt ?? null
  const hasStep = step !== null && stepAt !== null
  const stepOverdue = stepAt !== null && Date.parse(stepAt) < now
  const keepStep = mode === 'comment' && hasStep && !stepOverdue && !editStep

  // Просроченный шаг в комментарии: текст подставляем, срок нужен новый.
  useEffect(() => {
    if (card !== null && !prefilled.current) {
      prefilled.current = true
      if (mode === 'comment' && step !== null) {
        setNextAction(step)
      }
    }
  }, [card, mode, step])

  const dirty = text !== '' || (nextAction !== '' && nextAction !== step) || quick !== null
  useEffect(() => {
    if (dirty) {
      bridge?.enableClosingConfirmation?.()
    } else {
      bridge?.disableClosingConfirmation?.()
    }
    return () => bridge?.disableClosingConfirmation?.()
  }, [bridge, dirty])

  const quickValue = quick === null || quick === 'other' ? null : quickDue(quick, now)
  const local = quickValue !== null ? quickValue.local : quick === 'other' && date !== '' ? `${date}T${time}` : ''
  const errors = keepStep ? {} : nextStepErrors(nextAction, local)
  const textMissing = mode === 'comment' && text.trim() === ''

  const save = async () => {
    setSubmitted(true)
    if (card === null || saveError?.kind === 'expired') {
      return
    }
    if (textMissing || errors.nextAction || errors.nextActionAt) {
      // Фокус — на первое поле с ошибкой, чтобы на телефоне было видно, что не так.
      (textMissing ? textRef : errors.nextAction ? stepRef : dueRef).current?.focus()
      return
    }
    const nextStep = keepStep
      ? { nextAction: step!, nextActionAt: stepAt! }
      : { nextAction: nextAction.trim(), nextActionAt: moscowDateTime(local)! }
    setBusy(true)
    setSaveError(null)
    try {
      if (mode === 'comment') {
        await api.comment(id, { version: card.interaction.version, text: text.trim(), nextStep }, idempotencyKey)
      } else {
        await api.completeStep(id, { version: card.interaction.version, result: text.trim() || null, nextStep }, idempotencyKey)
      }
      bridge?.disableClosingConfirmation?.()
      bridge?.HapticFeedback?.notificationOccurred?.('success')
      onDone(mode === 'step' ? 'Шаг отмечен выполненным. Новый шаг записан.' : 'Комментарий записан в историю.')
    } catch (error) {
      if (error instanceof MaxApiError && error.code === 'VERSION_CONFLICT') {
        setSaveError({ kind: 'conflict', text: 'Карточку только что изменили в CRM. Обновите данные — ваш текст останется.' })
      } else if (problemOf(error, false) !== null) {
        // Сеанс пропал посреди ввода: экран входа форму не заменяет, текст остаётся на месте.
        setSaveError({ kind: 'expired', text: 'Сеанс закончился. Скопируйте текст, закройте приложение и откройте снова из бота.' })
      } else {
        setSaveError({
          kind: 'other',
          text: error instanceof MaxApiError ? error.message : 'Не сохранилось: нет связи с CRM. Текст на месте — нажмите «Сохранить» ещё раз.'
        })
      }
    } finally {
      setBusy(false)
    }
  }

  const title = mode === 'step' ? 'Шаг выполнен' : card !== null && step === null ? 'Задать шаг' : 'Комментарий'
  const error = (key: string, message: string | undefined | false) => (
    submitted && message ? <span id={`${fieldId}-${key}-error`} className="mx-error">{message}</span> : null
  )
  const due = stepAt === null ? null : dueChip(stepAt, now)
  return (
    <form className="mx-screen" noValidate aria-labelledby={`${fieldId}-title`} onSubmit={(event) => { event.preventDefault(); void save() }}>
      <div className="mx-content">
        {back}
        <h1 id={`${fieldId}-title`} className="mx-title">{title}</h1>
        {card !== null && <p className="mx-sub">{card.organization.name} · {card.interaction.title}</p>}
        {loaded.status === 'error' && card === null && (
          <Banner tone="error" action="Повторить" onAction={loaded.reload}>Не удалось загрузить карточку. {errorText(loaded.error)}</Banner>
        )}
        {saveError?.kind === 'conflict' && (
          <>
            <Banner tone="warn">{saveError.text}</Banner>
            <button type="button" className="mx-button mx-button--secondary mx-button--block mx-gap-bottom"
              onClick={() => { setSaveError(null); setAttempt((value) => value + 1) }}>Обновить данные</button>
          </>
        )}
        {saveError !== null && saveError.kind !== 'conflict' && <Banner tone="error">{saveError.text}</Banner>}
        {mode === 'step' && card !== null && (
          <div className={`mx-block mx-next mx-gap-bottom${due?.tone === 'danger' ? ' mx-next--overdue' : ''}`}>
            <span className="mx-next__label">Выполняем шаг</span>
            <span className="mx-next__text">{step ?? 'Без текста'}</span>
            {due !== null && <span className="mx-chips"><Chips chips={[due]} /></span>}
          </div>
        )}
        <label className="mx-field">
          <span className="mx-field__label">{mode === 'step' ? <>Что получилось <small>необязательно</small></> : 'Комментарий'}</span>
          <textarea ref={textRef} value={text} maxLength={4000} rows={mode === 'step' ? 3 : 4}
            placeholder={mode === 'step' ? 'Например: договорились о встрече 25.10' : 'Что произошло, о чём договорились'}
            aria-invalid={submitted && textMissing} aria-describedby={submitted && textMissing ? `${fieldId}-text-error` : undefined}
            onChange={(event) => setText(event.target.value)} />
          {error('text', textMissing && 'Напишите комментарий')}
        </label>
        {card === null && loaded.status === 'loading'
          ? <div className="mx-block" aria-busy="true"><span className="mx-sk" /><span className="mx-sk mx-sk--w60" /></div>
          : keepStep
            ? (
              <div className="mx-field">
                <span className="mx-field__label">Следующий шаг</span>
                <div className="mx-summary">
                  <span className="mx-summary__text"><b>{step}</b><span>{due?.label}</span></span>
                  <button type="button" className="mx-button mx-button--text" onClick={() => setEditStep(true)}>Изменить</button>
                </div>
                <small className="mx-hint">Шаг и срок останутся прежними.</small>
              </div>
            )
            : (
              <>
                <label className="mx-field">
                  <span className="mx-field__label">{mode === 'step' ? 'Новый следующий шаг' : 'Следующий шаг'}</span>
                  {mode === 'comment' && hasStep && stepOverdue && <span className="mx-error">Срок шага прошёл — укажите новый.</span>}
                  <input ref={stepRef} type="text" value={nextAction} maxLength={500} placeholder="Что сделать дальше"
                    aria-invalid={submitted && errors.nextAction !== undefined}
                    aria-describedby={submitted && errors.nextAction ? `${fieldId}-step-error` : undefined}
                    onChange={(event) => setNextAction(event.target.value)} />
                  {error('step', errors.nextAction)}
                </label>
                <div className="mx-field" role="group" aria-labelledby={`${fieldId}-due`}
                  aria-describedby={submitted && errors.nextActionAt ? `${fieldId}-due-error` : undefined}>
                  <span id={`${fieldId}-due`} className="mx-field__label">Срок <small>московское время</small></span>
                  <div className="mx-quick">
                    {quickDues.map(([key, label], index) => (
                      <button key={key} ref={index === 0 ? dueRef : undefined} type="button" aria-pressed={quick === key} onClick={() => setQuick(key)}>{label}</button>
                    ))}
                    <button type="button" aria-pressed={quick === 'other'} onClick={() => setQuick('other')}>Другая дата</button>
                  </div>
                  {quick === 'other' && (
                    <div className="mx-datetime">
                      <input type="date" aria-label="Дата" value={date} onChange={(event) => setDate(event.target.value)} />
                      <input type="time" aria-label="Время" value={time} onChange={(event) => setTime(event.target.value || '10:00')} />
                    </div>
                  )}
                  {local !== '' && (
                    <small className="mx-hint" role="status">
                      {dueText(local)}{quickValue?.moved ? ' — выходной, перенесли на понедельник' : ''}
                    </small>
                  )}
                  {error('due', errors.nextActionAt)}
                </div>
              </>
            )}
        <p className="mx-hint">Этап взаимодействия не меняется.</p>
      </div>
      {saveError?.kind !== 'expired' && (
        <div className="mx-bottom">
          <button type="submit" className="mx-button mx-button--primary" disabled={busy || card === null}>{busy ? 'Сохраняем…' : 'Сохранить'}</button>
        </div>
      )}
    </form>
  )
}
