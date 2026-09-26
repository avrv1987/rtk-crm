import { useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type RunKind,
  type SourceMapping,
  type SourceMappingOptions,
  type SourceMappingUpdate
} from '../../shared/api/client'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf, runPeriod, shiftDate } from './sourceFormat'
import './sources.css'

type SourceMappingsListProps = {
  options: SourceMappingOptions
  reloadToken: number
  onChanged: () => void
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; mappings: SourceMapping[] }
  | { kind: 'failed'; requestId: string | undefined }

type EditMode = 'edit' | 'run'

type Draft = {
  mode: EditMode
  organizationId: string
  programId: string
  runStartsOn: string
  runLastDay: string
  runKind: RunKind
}

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; id: string }
  | { kind: 'done'; message: string }
  | { kind: 'failed'; id: string; error: unknown }

type Confirm = { kind: 'remove' | 'snapshot'; mapping: SourceMapping } | null

const kindLabels: Record<SourceMapping['kind'], string> = {
  COURSE: 'Курс Moodle',
  GROUP: 'Группа Moodle',
  ORGANIZATION: 'Вуз на сайте',
  PROGRAM: 'Программа на сайте'
}

const runKindLabels: Record<RunKind, string> = {
  STUDENTS: 'занятия студентов',
  TEACHERS: 'обучение преподавателей'
}

const isLearning = (mapping: SourceMapping) => mapping.kind === 'COURSE' || mapping.kind === 'GROUP'

const draftOf = (mapping: SourceMapping, mode: EditMode): Draft => ({
  mode,
  organizationId: mapping.organizationId ?? '',
  programId: mapping.programId ?? '',
  runStartsOn: mode === 'edit' ? mapping.runStartsOn ?? '' : '',
  runLastDay: mode === 'edit' && mapping.runEndsOn ? shiftDate(mapping.runEndsOn, -1) : '',
  runKind: mapping.runKind
})

const payloadOf = (mapping: SourceMapping, draft: Draft): SourceMappingUpdate => ({
  version: draft.mode === 'edit' ? mapping.version : null,
  organizationId: draft.organizationId === '' ? null : draft.organizationId,
  programId: draft.programId === '' ? null : draft.programId,
  runStartsOn: isLearning(mapping) && draft.runStartsOn !== '' ? draft.runStartsOn : null,
  runEndsOn: isLearning(mapping) && draft.runLastDay !== '' ? shiftDate(draft.runLastDay, 1) : null,
  runKind: isLearning(mapping) ? draft.runKind : null
})

export const SourceMappingsList = ({
  options,
  reloadToken,
  onChanged,
  onSessionExpired,
  onProfileUnavailable
}: SourceMappingsListProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [editing, setEditing] = useState<{ id: string; draft: Draft } | null>(null)
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [confirm, setConfirm] = useState<Confirm>(null)
  const [filter, setFilter] = useState('')

  const handled = useCallback(
    (error: unknown) => accessHandled(error, onSessionExpired, onProfileUnavailable),
    [onProfileUnavailable, onSessionExpired]
  )

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', mappings: await apiClient.listSourceMappings() })
    } catch (error) {
      if (!handled(error)) {
        setState({ kind: 'failed', requestId: requestIdOf(error) })
      }
    }
  }, [handled])

  useEffect(() => {
    void load()
  }, [load, reloadToken])

  const run = async (mapping: SourceMapping, action: () => Promise<string>) => {
    setCommand({ kind: 'running', id: mapping.id })
    try {
      const message = await action()
      setCommand({ kind: 'done', message })
      setEditing(null)
      await load()
      onChanged()
    } catch (error) {
      if (!handled(error)) {
        setCommand({ kind: 'failed', id: mapping.id, error })
      }
      await load()
    }
  }

  const save = (mapping: SourceMapping, draft: Draft) => run(mapping, async () => {
    if (draft.mode === 'run') {
      await apiClient.addSourceMappingRun(mapping.id, payloadOf(mapping, draft))
      return `Для «${mapping.label}» добавлен поток ${runPeriod(draft.runStartsOn, shiftDate(draft.runLastDay, 1))}; снимок пересчитается при следующей синхронизации Moodle.`
    }
    await apiClient.updateSourceMapping(mapping.id, payloadOf(mapping, draft))
    return `Сопоставление «${mapping.label}» изменено${isLearning(mapping) ? '; снимки пересчитаны по новому сопоставлению' : '; заявки пересопоставлены'}.`
  })

  const confirmAction = () => {
    if (confirm === null) {
      return
    }
    const { kind, mapping } = confirm
    setConfirm(null)
    void run(mapping, async () => {
      if (kind === 'remove') {
        await apiClient.removeSourceMapping(mapping.id, mapping.version)
        return `Сопоставление «${mapping.label}» снято; запись вернулась в разбор.`
      }
      await apiClient.deleteSourceMappingSnapshot(mapping.id)
      return `Снимок «${mapping.label}» удалён из карточек и отчётов.`
    })
  }

  const busy = command.kind === 'running'
  const needle = filter.trim().toLowerCase()
  const mappings = state.kind === 'ready'
    ? state.mappings.filter((mapping) => needle === '' || [mapping.label, mapping.externalKey, mapping.organizationName, mapping.programName]
      .some((value) => value?.toLowerCase().includes(needle)))
    : []

  return (
    <section className="data-sources__records" aria-labelledby="source-mappings-title">
      <h3 id="source-mappings-title">Сохранённые сопоставления</h3>
      <p className="data-sources__hint">
        Курс или группа Moodle → вуз, программа, даты и вид потока; вуз и программа сайта → организация и программа CRM.
        Правка и снятие пересчитывают снимки и заявки и попадают в историю карточек.
      </p>
      <label className="source-panel__row">
        Поиск
        <input type="search" value={filter} onChange={(event) => setFilter(event.target.value)} placeholder="Курс, группа, вуз или программа" />
      </label>
      {state.kind === 'loading' && <p role="status">Загружаем сопоставления…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить сопоставления.</p>
          <SupportDetails requestId={state.requestId} />
        </div>
      )}
      {command.kind === 'done' && <p role="status">{command.message}</p>}
      {state.kind === 'ready' && mappings.length === 0 && <p>Сохранённых сопоставлений нет.</p>}
      {mappings.length > 0 && (
        <ul className="source-list">
          {mappings.map((mapping) => {
            const draft = editing?.id === mapping.id ? editing.draft : null
            const learning = isLearning(mapping)
            const setDraft = (patch: Partial<Draft>) => setEditing({ id: mapping.id, draft: { ...draft!, ...patch } })
            return (
              <li key={mapping.id}>
                <strong>
                  {kindLabels[mapping.kind]}: {mapping.label}
                  {mapping.closed && <span className="source-badge"> поток завершён</span>}
                  {mapping.outdated && <span className="source-badge source-badge--danger"> нет в Moodle при последней синхронизации</span>}
                </strong>
                <span>
                  → {mapping.organizationName ?? (mapping.kind === 'PROGRAM' ? 'любой вуз' : 'вуз не указан')}
                  {mapping.programName ? `, ${mapping.programName}` : ''}
                  {learning ? `; поток ${runPeriod(mapping.runStartsOn, mapping.runEndsOn)}; ${runKindLabels[mapping.runKind]}` : ''}
                </span>
                {learning && (
                  <span>
                    {mapping.observedAt
                      ? `Снимок: участий — ${mapping.participants ?? 0}, наблюдение ${formatDateTime(mapping.observedAt)}`
                      : 'Снимка ещё нет'}
                  </span>
                )}
                <span className="data-sources__hint">
                  Внешний ключ: {mapping.externalKey}. Изменил(а): {mapping.updatedByName ?? 'нет данных'}, {formatDateTime(mapping.updatedAt)}
                </span>
                {draft === null ? (
                  <div className="source-panel__row">
                    <button type="button" className="button--secondary" disabled={busy} onClick={() => setEditing({ id: mapping.id, draft: draftOf(mapping, 'edit') })}>
                      Изменить
                    </button>
                    {learning && (
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => setEditing({ id: mapping.id, draft: draftOf(mapping, 'run') })}>
                        Добавить поток
                      </button>
                    )}
                    {learning && mapping.observedAt && (
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => setConfirm({ kind: 'snapshot', mapping })}>
                        Удалить снимок
                      </button>
                    )}
                    <button type="button" className="button--danger" disabled={busy} onClick={() => setConfirm({ kind: 'remove', mapping })}>
                      Снять
                    </button>
                  </div>
                ) : (
                  <div className="source-form">
                    <p className="source-form__wide">
                      {draft.mode === 'run'
                        ? 'Новый поток того же курса или группы: укажите вуз, программу и даты, не пересекающиеся с другими потоками.'
                        : 'Изменение сопоставления.'}
                    </p>
                    {mapping.kind !== 'PROGRAM' && (
                      <label>
                        Вуз CRM
                        <select value={draft.organizationId} onChange={(event) => setDraft({ organizationId: event.target.value })}>
                          <option value="">Выберите вуз</option>
                          {options.organizations.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
                        </select>
                      </label>
                    )}
                    {mapping.kind !== 'ORGANIZATION' && (
                      <label>
                        Программа CRM
                        <select value={draft.programId} onChange={(event) => setDraft({ programId: event.target.value })}>
                          <option value="">Выберите программу</option>
                          {options.programs.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}
                        </select>
                      </label>
                    )}
                    {learning && (
                      <>
                        <label>
                          Начало потока
                          <input type="date" value={draft.runStartsOn} onChange={(event) => setDraft({ runStartsOn: event.target.value })} />
                        </label>
                        <label>
                          Последний день потока
                          <input
                            type="date"
                            min={draft.runStartsOn === '' ? undefined : draft.runStartsOn}
                            value={draft.runLastDay}
                            onChange={(event) => setDraft({ runLastDay: event.target.value })}
                          />
                        </label>
                        <label className="source-form__wide">
                          Вид потока
                          <select value={draft.runKind} onChange={(event) => setDraft({ runKind: event.target.value as RunKind })}>
                            <option value="STUDENTS">Занятия студентов — входит в «Обучающихся»</option>
                            <option value="TEACHERS">Обучение преподавателей — не входит в «Обучающихся» и «Востребованность»</option>
                          </select>
                        </label>
                      </>
                    )}
                    <div className="source-form__actions">
                      <button type="button" disabled={busy} onClick={() => void save(mapping, draft)}>
                        {busy ? 'Сохраняем…' : draft.mode === 'run' ? 'Добавить поток' : 'Сохранить'}
                      </button>
                      <button type="button" className="button--secondary" disabled={busy} onClick={() => setEditing(null)}>Отмена</button>
                    </div>
                  </div>
                )}
                {command.kind === 'failed' && command.id === mapping.id && (
                  <div role="alert">
                    <p className="source-error">{errorText(command.error, 'Сопоставление не изменено.')}</p>
                    {fieldErrors(command.error).map((text) => <p key={text}>{text}</p>)}
                    <SupportDetails requestId={requestIdOf(command.error)} />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.kind === 'snapshot' ? 'Удалить снимок?' : 'Снять сопоставление?'}
        description={confirm?.kind === 'snapshot'
          ? `Числа «${confirm.mapping.label}» исчезнут из карточки и отчётов. Если поток ещё идёт, а курс или группа есть в Moodle, снимок появится снова при следующей синхронизации.`
          : `«${confirm?.mapping.label ?? ''}» вернётся в «Записи для разбора»; снимок этого потока будет удалён.`}
        confirmLabel={confirm?.kind === 'snapshot' ? 'Удалить снимок' : 'Снять'}
        onConfirm={confirmAction}
        onCancel={() => setConfirm(null)}
      />
    </section>
  )
}
