import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../shared/api/client'
import { withCount } from '../../shared/format/plural'
import { Segmented } from '../../shared/ui/Segmented'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { learningText } from '../control/controlModel'
import {
  agreementLine,
  dueLine,
  organizationPathApi,
  pathGroups,
  pathSegments,
  pathSortOptions,
  pathSummary,
  stageLine,
  type OrganizationPath as PathData,
  type PathSort,
  type PathWork
} from './pathModel'
import './organizationPath.css'

type PathState =
  | { kind: 'loading' }
  | { kind: 'ready'; path: PathData }
  | { kind: 'failed'; error: unknown }

const statusLabels: Record<PathWork['status'], string | null> = { ACTIVE: null, PAUSED: 'Приостановлено', COMPLETED: 'Завершено' }

const WorkRow = ({ work, organizationId }: { work: PathWork; organizationId: string }) => {
  const href = (id: string) => `#/organizations/${organizationId}/${id}`
  const due = dueLine(work)
  const catalog = [work.kindName, work.programName ? `Программа: ${work.programName}` : 'Программа не указана',
    work.products.length === 0 ? 'Продукты не указаны' : `Продукты: ${work.products.join(', ')}`]
    .filter((value): value is string => Boolean(value))
  const status = statusLabels[work.status]
  return (
    <li className={`org-path__work org-path__work--${due.tone}`}>
      <a className="org-path__open" href={href(work.interactionId)}>
        <span className="org-path__title">
          {work.title}
          {status !== null && <span className="status status--missing">{status}</span>}
        </span>
        <span className="org-path__meta">{catalog.join(' · ')}</span>
        <span className="org-path__bar" role="img" aria-label={stageLine(work)}>
          {pathSegments(work).map((segment, index) => (
            <span
              key={`${index}:${segment.name}`}
              className={`org-path__segment org-path__segment--${segment.state}${segment.optional ? ' org-path__segment--optional' : ''}`}
              title={`${index + 1}. ${segment.name}${segment.optional ? ' (необязательный)' : ''}`}
            />
          ))}
        </span>
        <span className="org-path__stage">{stageLine(work)}</span>
      </a>
      <p className={`org-path__due org-path__due--${due.tone}`}>{due.text}</p>
      <p className="org-path__line">
        {agreementLine(work)}
        {work.problems + work.risks > 0 && ` · проблем ${work.problems}, рисков ${work.risks}`}
      </p>
      {work.learning && <p className="org-path__line">{`Обучение: ${learningText(work.learning).toLowerCase()}`}</p>}
      {(work.previous || work.next) && (
        <p className="org-path__line org-path__cycles">
          {work.previous && <>Предыдущий цикл: <a href={href(work.previous.interactionId)}>{work.previous.title}</a></>}
          {work.previous && work.next && ' · '}
          {work.next && <>Следующий цикл: <a href={href(work.next.interactionId)}>{work.next.title}</a></>}
        </p>
      )}
    </li>
  )
}

export const OrganizationPath = ({ organizationId, onSessionError }: {
  organizationId: string
  onSessionError: (error: unknown) => boolean
}) => {
  const [state, setState] = useState<PathState>({ kind: 'loading' })
  const [sort, setSort] = useState<PathSort>('PROGRAM')
  const version = useRef(0)

  const load = useCallback(async () => {
    const current = ++version.current
    setState({ kind: 'loading' })
    try {
      const path = await organizationPathApi.get(organizationId)
      if (current === version.current) {
        setState({ kind: 'ready', path })
      }
    } catch (error) {
      if (current === version.current && !onSessionError(error)) {
        setState({ kind: 'failed', error })
      }
    }
  }, [onSessionError, organizationId])

  useEffect(() => {
    void load()
    return () => {
      version.current += 1
    }
  }, [load])

  if (state.kind === 'loading') {
    return <p className="organizations-message" role="status">Собираем путь вуза…</p>
  }
  if (state.kind === 'failed') {
    return (
      <div className="organizations-message organizations-message--error" role="alert">
        <p>Не удалось собрать путь вуза.</p>
        {state.error instanceof ApiError && <SupportDetails requestId={state.error.requestId} code={state.error.code} />}
        <button type="button" onClick={() => void load()}>Повторить</button>
      </div>
    )
  }
  const { works, total } = state.path
  if (works.length === 0) {
    return <p className="organizations-message">У вуза пока нет взаимодействий.</p>
  }
  const summary = pathSummary(works)
  return (
    <section className="org-path" aria-label="Путь вуза">
      <div className="org-path__head">
        <p className="org-path__summary">
          {`${withCount(works.length, 'взаимодействие', 'взаимодействия', 'взаимодействий')}: идут ${summary.active}, завершено ${summary.completed}`
            + ` · программ ${summary.programs} · просрочено шагов ${summary.overdue}`
            + (total > works.length ? ` · показаны последние ${works.length} из ${total}` : '')}
        </p>
        <Segmented label="Порядок взаимодействий" options={pathSortOptions} value={sort} onChange={setSort} />
      </div>
      {pathGroups(works, sort).map((group) => (
        <div key={group.key} className="org-path__group">
          {group.label !== null && <h4>{`${group.label} · ${group.works.length}`}</h4>}
          <ul className="org-path__list">
            {group.works.map((work) => <WorkRow key={work.interactionId} work={work} organizationId={organizationId} />)}
          </ul>
        </div>
      ))}
    </section>
  )
}
