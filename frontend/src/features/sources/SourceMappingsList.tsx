import { Fragment, useCallback, useEffect, useState } from 'react'
import {
  apiClient,
  type RunKind,
  type SourceMapping,
  type SourceMappingOptions,
  type SourceMappingUpdate
} from '../../shared/api/client'
import { ActionMenu, type ActionMenuItem } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { accessHandled, errorText, fieldErrors, formatDateTime, requestIdOf, runPeriod, shiftDate } from './sourceFormat'
import { filterMappings, mappingKindLabel, type MappingsFilter } from './sourceMappings'
import type { MappingsSource } from './sourceTabs'
import './sourcePage.css'

type SourceMappingsListProps = {
  options: SourceMappingOptions
  reloadToken: number
  initialSource: MappingsSource | null
  onChanged: () => void
  onNotice: (message: string) => void
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
  | { kind: 'failed'; id: string; error: unknown }

type Confirm = { kind: 'remove' | 'snapshot'; mapping: SourceMapping } | null

const runKindLabels: Record<RunKind, string> = {
  STUDENTS: 'занятия студентов',
  TEACHERS: 'обучение преподавателей'
}

const filters: { value: MappingsFilter; label: string }[] = [
  { value: 'ALL', label: 'Все' },
  { value: 'WEBSITE', label: 'Сайт' },
  { value: 'MOODLE', label: 'Moodle' },
  { value: 'CUSTOMER_CRM', label: 'CRM заказчика' }
]

const pageSize = 50

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
  initialSource,
  onChanged,
  onNotice,
  onSessionExpired,
  onProfileUnavailable
}: SourceMappingsListProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [editing, setEditing] = useState<{ id: string; draft: Draft } | null>(null)
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' })
  const [confirm, setConfirm] = useState<Confirm>(null)
  const [search, setSearch] = useState('')
  const [source, setSource] = useState<MappingsFilter>(initialSource === 'website' ? 'WEBSITE' : initialSource === 'moodle' ? 'MOODLE' : 'ALL')
  const [expanded, setExpanded] = useState<string | null>(null)
  const [shown, setShown] = useState(pageSize)

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
      setCommand({ kind: 'idle' })
      onNotice(message)
      setEditing(null)
      setExpanded(null)
      await load()
      onChanged()
    } catch (error) {
      if (!handled(error)) {
        setCommand({ kind: 'failed', id: mapping.id, error })
        setExpanded(mapping.id)
      }
      await load()
    }
  }

  const save = (mapping: SourceMapping, draft: Draft) => run(mapping, async () => {
    if (draft.mode === 'run') {
      await apiClient.addSourceMappingRun(mapping.id, payloadOf(mapping, draft))
      return `Для «${mapping.label}» добавлен поток ${runPeriod(draft.runStartsOn, shiftDate(draft.runLastDay, 1))}; данные участия пересчитаются при следующей синхронизации Moodle.`
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
      return `Данные участия «${mapping.label}» удалены из карточек и отчётов.`
    })
  }

  const startEditing = (mapping: SourceMapping, mode: EditMode) => {
    setEditing({ id: mapping.id, draft: draftOf(mapping, mode) })
    setExpanded(mapping.id)
  }

  const menuOf = (mapping: SourceMapping): ActionMenuItem[] => [
    { label: 'Изменить', onSelect: () => startEditing(mapping, 'edit') },
    ...(isLearning(mapping) ? [{ label: 'Добавить поток', onSelect: () => startEditing(mapping, 'run') }] : []),
    ...(isLearning(mapping) && mapping.observedAt
      ? [{ label: 'Удалить данные участия', danger: true, separated: true, onSelect: () => setConfirm({ kind: 'snapshot', mapping }) }]
      : []),
    { label: 'Снять', danger: true, onSelect: () => setConfirm({ kind: 'remove', mapping }) }
  ]

  const busy = command.kind === 'running'
  const mappings = state.kind === 'ready' ? filterMappings(state.mappings, search, source) : []

  const renderDetails = (mapping: SourceMapping) => {
    const draft = editing?.id === mapping.id ? editing.draft : null
    const learning = isLearning(mapping)
    const setDraft = (patch: Partial<Draft>) => setEditing({ id: mapping.id, draft: { ...draft!, ...patch } })
    return (
      <div className="src-section">
        <p className="src-muted">
          {learning && (mapping.observedAt
            ? `Данные участия: ${mapping.participants ?? 0}, посчитаны ${formatDateTime(mapping.observedAt)}. `
            : 'Данные участия ещё не посчитаны. ')}
          Внешний ключ: {mapping.externalKey}. Изменил(а): {mapping.updatedByName ?? 'нет данных'}, {formatDateTime(mapping.updatedAt)}.
        </p>
        {draft !== null && (
          <>
            <p>
              {draft.mode === 'run'
                ? 'Новый поток того же курса или группы: укажите вуз, программу и даты, не пересекающиеся с другими потоками.'
                : 'Изменение сопоставления.'}
            </p>
            <div className="src-record-form">
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
                  <label className="src-record-form__wide">
                    Вид потока
                    <select value={draft.runKind} onChange={(event) => setDraft({ runKind: event.target.value as RunKind })}>
                      <option value="STUDENTS">Занятия студентов — входит в «Обучающихся»</option>
                      <option value="TEACHERS">Обучение преподавателей — не входит в «Обучающихся» и «Востребованность»</option>
                    </select>
                  </label>
                </>
              )}
            </div>
            <div className="src-actions">
              <button type="button" disabled={busy} onClick={() => void save(mapping, draft)}>
                {busy ? 'Сохраняем…' : draft.mode === 'run' ? 'Добавить поток' : 'Сохранить'}
              </button>
              <button type="button" className="button--secondary" disabled={busy} onClick={() => setEditing(null)}>Отмена</button>
            </div>
          </>
        )}
        {command.kind === 'failed' && command.id === mapping.id && (
          <div role="alert">
            <p className="src-error">{errorText(command.error, 'Сопоставление не изменено.')}</p>
            {fieldErrors(command.error).map((text) => <p key={text} className="src-error">{text}</p>)}
            <SupportDetails requestId={requestIdOf(command.error)} />
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="src-section">
      <div className="src-toolbar">
        <input
          type="search"
          aria-label="Поиск по сопоставлениям"
          placeholder="Курс, группа, вуз или программа"
          value={search}
          onChange={(event) => {
            setSearch(event.target.value)
            setShown(pageSize)
          }}
        />
        <Segmented label="Источник" value={source} onChange={setSource} options={filters} />
        <HintButton label="Что такое сопоставления">
          Курс или группа Moodle → вуз, программа, даты и вид потока; вуз и программа сайта → вуз и программа CRM.
          Правка и снятие пересчитывают снимки и заявки и попадают в историю карточек.
        </HintButton>
      </div>
      {state.kind === 'loading' && <p role="status">Загружаем сопоставления…</p>}
      {state.kind === 'failed' && (
        <div role="alert">
          <p>Не удалось загрузить сопоставления.</p>
          <SupportDetails requestId={state.requestId} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}
      {state.kind === 'ready' && mappings.length === 0 && <EmptyState text="Сохранённых сопоставлений нет." />}
      {mappings.length > 0 && (
        <>
          <table className="src-table" aria-label="Сохранённые сопоставления">
            <thead>
              <tr>
                <th scope="col">Что сопоставлено</th>
                <th scope="col">Вуз и программа</th>
                <th scope="col">Поток</th>
                <th scope="col"><span className="sr-only">Действия</span></th>
              </tr>
            </thead>
            <tbody>
              {mappings.slice(0, shown).map((mapping) => {
                const learning = isLearning(mapping)
                const open = expanded === mapping.id
                return (
                  <Fragment key={mapping.id}>
                    <tr>
                      <td className="src-cell__main">
                        <button type="button" className="src-row-toggle" aria-expanded={open} onClick={() => setExpanded(open ? null : mapping.id)}>
                          {mappingKindLabel(mapping)}: {mapping.label}
                        </button>
                        {mapping.closed && <span className="src-badge">поток завершён</span>}
                        {mapping.outdated && <span className="src-badge src-badge--danger">нет в Moodle при последней синхронизации</span>}
                        {learning && (
                          <span className="src-cell__sub">
                            {mapping.observedAt ? `Данные участия: ${mapping.participants ?? 0}` : 'Данные участия ещё не посчитаны'}
                          </span>
                        )}
                      </td>
                      <td data-label="Вуз и программа">
                        {mapping.organizationName ?? (mapping.kind === 'PROGRAM' ? 'любой вуз' : 'вуз не указан')}
                        {mapping.programName && <span className="src-cell__sub">{mapping.programName}</span>}
                      </td>
                      <td data-label="Поток">
                        {learning ? runPeriod(mapping.runStartsOn, mapping.runEndsOn) : '—'}
                        {learning && <span className="src-cell__sub">{runKindLabels[mapping.runKind]}</span>}
                      </td>
                      <td className="src-cell__actions">
                        <ActionMenu label={`Действия: ${mapping.label}`} items={busy ? [] : menuOf(mapping)} />
                      </td>
                    </tr>
                    {open && (
                      <tr className="src-form-row">
                        <td colSpan={4}>{renderDetails(mapping)}</td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
          {mappings.length > shown && (
            <button type="button" className="button--secondary src-more" onClick={() => setShown(shown + pageSize)}>
              Показать ещё
            </button>
          )}
        </>
      )}
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.kind === 'snapshot' ? 'Удалить данные участия?' : 'Снять сопоставление?'}
        description={confirm?.kind === 'snapshot'
          ? `Числа «${confirm.mapping.label}» исчезнут из карточки и отчётов. Если поток ещё идёт, а курс или группа есть в Moodle, данные участия появятся снова при следующей синхронизации.`
          : `«${confirm?.mapping.label ?? ''}» вернётся в «Записи для разбора»; данные участия этого потока будут удалены.`}
        confirmLabel={confirm?.kind === 'snapshot' ? 'Удалить данные участия' : 'Снять'}
        onConfirm={confirmAction}
        onCancel={() => setConfirm(null)}
      />
    </div>
  )
}
