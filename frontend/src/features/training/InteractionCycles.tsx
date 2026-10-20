import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  apiClient,
  createIdempotencyKey,
  type Interaction,
  type InteractionCycle,
  type WorkflowTemplate
} from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDate, requestIdOf, todayIso } from '../sources/sourceFormat'
import {
  cycleRouteHint,
  interactionKindsApi,
  type InteractionKind,
  type InteractionKindResolution
} from '../interactions/interactionKinds'
import '../sources/sources.css'

type InteractionCyclesProps = {
  interaction: Interaction
  canEdit: boolean
  onOpen: (id: Interaction['id']) => void
  onStarted: (id: Interaction['id']) => void
  /** «Начать новый цикл…» из меню карточки: форма открывается сразу, а не второй кнопкой на вкладке. */
  formRequested?: boolean
  onFormRequestHandled?: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type CycleState =
  | { kind: 'loading' }
  | { kind: 'ready'; cycle: InteractionCycle }
  | { kind: 'failed'; requestId: string | undefined }

type StartState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'failed'; error: unknown }

type Draft = {
  title: string
  startsOn: string
  templateId: string
  kindId: string
}

const CycleAnchor = ({ interaction, link, onOpen }: {
  interaction: Interaction
  link: NonNullable<InteractionCycle['previous']>
  onOpen: (id: Interaction['id']) => void
}) => (
  <a
    href={`#/organizations/${interaction.organizationId}/${link.interactionId}`}
    onClick={(event) => {
      event.preventDefault()
      onOpen(link.interactionId)
    }}
  >
    {link.title}
  </a>
)

export const InteractionCycles = ({
  interaction,
  canEdit,
  onOpen,
  onStarted,
  formRequested = false,
  onFormRequestHandled,
  onSessionExpired,
  onProfileUnavailable
}: InteractionCyclesProps) => {
  const [state, setState] = useState<CycleState>({ kind: 'loading' })
  const [templates, setTemplates] = useState<WorkflowTemplate[]>([])
  const [kinds, setKinds] = useState<InteractionKind[]>([])
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<Draft>({ title: '', startsOn: todayIso(), templateId: '', kindId: '' })
  const [start, setStart] = useState<StartState>({ kind: 'idle' })
  const [resolution, setResolution] = useState<InteractionKindResolution | null>(null)
  const startKey = useRef<string | null>(null)

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  useEffect(() => {
    let active = true
    apiClient.getInteractionCycle(interaction.id)
      .then((cycle) => {
        if (active) {
          setState({ kind: 'ready', cycle })
        }
      })
      .catch((error: unknown) => {
        if (active && !handled(error)) {
          setState({ kind: 'failed', requestId: requestIdOf(error) })
        }
      })
    return () => {
      active = false
    }
  }, [handled, interaction.id, interaction.version])

  // Маршрут нового цикла выбирается через вид: по умолчанию — вид прошлого цикла, шаблон — шаблон этого вида.
  const openForm = async () => {
    setDraft({ title: `${interaction.title} — новый цикл`, startsOn: todayIso(), templateId: '', kindId: '' })
    setStart({ kind: 'idle' })
    startKey.current = null
    setOpen(true)
    try {
      const [page, loadedKinds] = await Promise.all([
        apiClient.listAvailableWorkflowTemplates({ page: 0, size: 100 }),
        interactionKindsApi.available()
      ])
      setTemplates(page.items)
      setKinds(loadedKinds)
      if (interaction.kind && loadedKinds.some((kind) => kind.id === interaction.kind?.id)) {
        setDraft((current) => ({ ...current, kindId: interaction.kind?.id ?? '' }))
      }
    } catch (error) {
      if (!handled(error)) {
        setStart({ kind: 'failed', error })
      }
    }
  }

  useEffect(() => {
    if (!formRequested || state.kind !== 'ready') {
      return
    }
    onFormRequestHandled?.()
    if (canEdit && !state.cycle.next) {
      void openForm()
    }
    // openForm зависит от карточки, а запрос формы одноразовый: реагируем только на запрос и загрузку цикла.
  }, [formRequested, state.kind])

  // Тот же расчёт, что выполнит сервер: форма показывает, какой вид и маршрут получит новый цикл.
  useEffect(() => {
    if (!open) {
      return
    }
    let active = true
    interactionKindsApi.resolve({
      organizationId: interaction.organizationId,
      kindId: draft.kindId || undefined,
      programId: interaction.programId ?? undefined,
      productIds: interaction.productIds
    }).then((loaded) => active && setResolution(loaded)).catch(() => active && setResolution(null))
    return () => {
      active = false
    }
  }, [open, draft.kindId, interaction.organizationId, interaction.programId, interaction.productIds])

  const update = (patch: Partial<Draft>) => {
    startKey.current = null
    setDraft((current) => ({ ...current, ...patch }))
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setStart({ kind: 'saving' })
    try {
      const created = await apiClient.startInteractionCycle(interaction.id, {
        title: draft.title,
        startsOn: draft.startsOn === '' ? null : draft.startsOn,
        templateId: draft.templateId === '' ? null : draft.templateId,
        kindId: draft.kindId === '' ? null : draft.kindId
      }, startKey.current ?? (startKey.current = createIdempotencyKey()))
      startKey.current = null
      setOpen(false)
      setStart({ kind: 'idle' })
      onStarted(created.id)
    } catch (error) {
      if (!handled(error)) {
        setStart({ kind: 'failed', error })
      }
    }
  }

  if (state.kind === 'loading') {
    return null
  }
  if (state.kind === 'failed') {
    return (
      <section className="interaction-learning" aria-labelledby="interaction-cycle-title">
        <h6 id="interaction-cycle-title">Циклы взаимодействия</h6>
        <div role="alert">
          <p>Не удалось загрузить связь с другими циклами.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      </section>
    )
  }
  const { cycle } = state

  return (
    <section className="interaction-learning" aria-labelledby="interaction-cycle-title">
      <h6 id="interaction-cycle-title">Циклы взаимодействия</h6>
      {cycle.startsOn && <p>Этот цикл начат {formatDate(cycle.startsOn)}.</p>}
      {cycle.previous && (
        <p>
          Предыдущий цикл:{' '}
          <CycleAnchor interaction={interaction} link={cycle.previous} onOpen={onOpen} />
        </p>
      )}
      {cycle.next && (
        <p>
          Следующий цикл:{' '}
          <CycleAnchor interaction={interaction} link={cycle.next} onOpen={onOpen} />
          {cycle.next.startsOn ? ` (с ${formatDate(cycle.next.startsOn)})` : ''}
        </p>
      )}
      {!cycle.previous && !cycle.next && (
        <p>Повторный цикл (например, новый поток или повышение квалификации) начинается отдельным взаимодействием со ссылкой на это.</p>
      )}
      {canEdit && !cycle.next && !open && (
        <div className="source-panel__row">
          <button type="button" className="button--secondary" onClick={() => void openForm()}>Начать новый цикл</button>
        </div>
      )}
      {canEdit && open && (
        <form className="source-form" onSubmit={(event) => void submit(event)}>
          <p className="source-form__wide">
            Новое взаимодействие получит тот же вуз, программу, продукты и контакты и ссылку на этот цикл. Данные LMS нового цикла
            считаются по потокам, которые начинаются с даты начала цикла.
          </p>
          <label className="source-form__wide">
            <span>Название нового взаимодействия<span className="required-mark" aria-hidden="true"> *</span></span>
            <input required maxLength={300} value={draft.title} onChange={(event) => update({ title: event.target.value })} />
          </label>
          <label>
            <span>Начало цикла<span className="required-mark" aria-hidden="true"> *</span></span>
            <input type="date" required value={draft.startsOn} onChange={(event) => update({ startsOn: event.target.value })} />
          </label>
          <label>
            Вид взаимодействия
            <select value={draft.kindId} onChange={(event) => update({ kindId: event.target.value })}>
              <option value="">Подобрать по правилам</option>
              {kinds.map((kind) => <option key={kind.id} value={kind.id}>{kind.name}</option>)}
            </select>
            {kinds.find((kind) => kind.id === (draft.kindId || resolution?.kindId))?.description && (
              <small>{kinds.find((kind) => kind.id === (draft.kindId || resolution?.kindId))?.description}</small>
            )}
          </label>
          <label>
            Шаблон этапов
            <select value={draft.templateId} onChange={(event) => update({ templateId: event.target.value })}>
              <option value="">
                {kinds.find((kind) => kind.id === draft.kindId)?.teamTemplateName
                  ?? kinds.find((kind) => kind.id === draft.kindId)?.templateName
                  ?? 'По виду взаимодействия'}
              </option>

              {templates.map((template) => (
                <option key={template.id} value={template.id}>{template.name}</option>
              ))}
            </select>
          </label>
          {cycleRouteHint(resolution, draft.templateId === '' ? null : templates.find((template) => template.id === draft.templateId)?.name ?? null) && (
            <p className="source-form__wide" role="note">
              {cycleRouteHint(resolution, draft.templateId === '' ? null : templates.find((template) => template.id === draft.templateId)?.name ?? null)}
            </p>
          )}
          <div className="source-form__actions">
            <button type="submit" disabled={start.kind === 'saving'}>
              {start.kind === 'saving' ? 'Создаём…' : 'Создать новый цикл'}
            </button>
            <button type="button" className="button--secondary" onClick={() => setOpen(false)} disabled={start.kind === 'saving'}>
              Отмена
            </button>
          </div>
          {start.kind === 'failed' && (
            <div className="source-form__wide" role="alert">
              <p className="source-error">{errorText(start.error, 'Новый цикл не создан.')}</p>
              {fieldErrors(start.error).map((text) => <p key={text}>{text}</p>)}
              <SupportDetails requestId={requestIdOf(start.error)} />
            </div>
          )}
        </form>
      )}
    </section>
  )
}
