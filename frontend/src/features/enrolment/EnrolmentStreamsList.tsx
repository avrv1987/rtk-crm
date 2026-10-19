import { type FormEvent, Fragment, useCallback, useEffect, useState } from 'react'
import { apiClient, type EnrolmentStreams, type LearnerSearch, type LearnerSummary } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { EnrolmentQueuePanel } from './EnrolmentQueuePanel'
import { PaidOrdersUpload } from './PaidOrdersUpload'
import { type AccessErrorReporter, formatIsoDate, requestIdOf, responseErrorMessage } from './enrolmentShared'

type StreamRow = EnrolmentStreams['streams'][number]

const stageSteps = (stream: StreamRow): { label: string; value: number }[] => [
  { label: 'Оплачено', value: stream.paid },
  { label: 'Анкета заполнена', value: stream.profilesComplete },
  { label: 'Выгружено в LMS', value: stream.exported },
  { label: 'Принято LMS', value: stream.transferred }
]

const stageTotalsOf = (streams: StreamRow[]) => streams.reduce((totals, stream) => {
  const steps = stageSteps(stream)
  return totals.map((total, index) => total + steps[index].value)
}, [0, 0, 0, 0])

const StageBar = ({ stream }: { stream: StreamRow }) => {
  const steps = stageSteps(stream)
  const base = stream.paid
  const title = steps.map((step) => `${step.label}: ${step.value} из ${base}`).join('; ')
  return (
    <div className="enrolment__stage-bar" title={title} aria-label={title}>
      {steps.map((step) => (
        <span key={step.label} className="enrolment__stage-bar-segment">
          <span
            className="enrolment__stage-bar-fill"
            style={{ width: `${base === 0 ? 0 : Math.min(100, Math.round((step.value / base) * 100))}%` }}
          />
        </span>
      ))}
    </div>
  )
}

type EnrolmentStreamsListProps = {
  onOpenStream: (streamId: string) => void
  onOpenLearner: (learnerId: string) => void
  onAccessError: AccessErrorReporter
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready'; data: EnrolmentStreams }
  | { kind: 'failed'; error: unknown }

type SearchState =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | { kind: 'done'; results: LearnerSummary[] }
  | { kind: 'failed'; error: unknown }

const searchFields: Array<{ value: LearnerSearch['kind']; label: string; example: string; type: 'email' | 'tel' | 'text'; inputMode: 'email' | 'tel' | 'numeric' | 'text' }> = [
  { value: 'EMAIL', label: 'Email', example: 'name@example.ru', type: 'email', inputMode: 'email' },
  { value: 'PHONE', label: 'Телефон', example: '+7 900 000-00-00', type: 'tel', inputMode: 'tel' },
  { value: 'SNILS', label: 'СНИЛС', example: '11 цифр, без пробелов', type: 'text', inputMode: 'numeric' },
  { value: 'LAST_NAME', label: 'Фамилия', example: 'Иванов', type: 'text', inputMode: 'text' }
]

const streamsOf = (enrolments: LearnerSummary['enrolments']) => (
  enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`).join('; ')
)

export const EnrolmentStreamsList = ({ onOpenStream, onOpenLearner, onAccessError }: EnrolmentStreamsListProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [searchValues, setSearchValues] = useState<Record<LearnerSearch['kind'], string>>({ EMAIL: '', PHONE: '', SNILS: '', LAST_NAME: '' })
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })
  const [expanded, setExpanded] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setState({ kind: 'ready', data: await apiClient.listEnrolmentStreams() })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setState({ kind: 'failed', error })
    }
  }, [onAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const runSearch = async (event: FormEvent<HTMLFormElement>, kind: LearnerSearch['kind']) => {
    event.preventDefault()
    const value = searchValues[kind].trim()
    if (value === '' || search.kind === 'searching') {
      return
    }
    setSearch({ kind: 'searching' })
    try {
      const results = await apiClient.searchLearners({ kind, value })
      setSearch({ kind: 'done', results })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setSearch({ kind: 'failed', error })
    }
  }

  return (
    <section className="enrolment" aria-label="Потоки зачисления">

      {state.kind === 'loading' && <p role="status">Загружаем потоки…</p>}

      {state.kind === 'failed' && (
        <div className="notice notice--error" role="alert">
          <p>{responseErrorMessage(state.error)}</p>
          <SupportDetails requestId={requestIdOf(state.error)} />
          <button type="button" onClick={() => void load()}>Повторить</button>
        </div>
      )}

      {state.kind === 'ready' && (
        <>
          <dl className="enrolment__counters">
            <div>
              <dt>Анкет в CRM</dt>
              <dd>{state.data.counters.profiles} из {state.data.counters.profilesLimit}</dd>
            </div>
            <div>
              <dt>Потоков без даты окончания</dt>
              <dd>{state.data.counters.streamsWithoutEndDate}</dd>
            </div>
          </dl>
          {state.data.counters.nearLimit && (
            <p className="notice" role="status">Приближается порог 100 000 анкет.</p>
          )}
          {state.data.counters.streamsWithoutEndDate > 0 && (
            <p className="notice" role="status">
              Срок хранения анкет не определён: укажите дату окончания потока.
            </p>
          )}

          <EnrolmentQueuePanel onAccessError={onAccessError} />

          <PaidOrdersUpload onUploaded={() => void load()} onAccessError={onAccessError} />

          <section className="enrolment__section" aria-labelledby="enrolment-streams-title">
            <h3 id="enrolment-streams-title">Потоки</h3>
            {state.data.streams.length === 0 ? (
              <p>Потоков пока нет: загрузите файл оплат.</p>
            ) : (
              <>
                <p className="enrolment__hint">
                  «Выгружено в LMS» — файл для LMS сформирован. «Принято LMS» — строка принята по протоколу LMS или отметке менеджера учебного процесса; отметка «передано» без результата приёмом не считается.
                </p>
                <dl className="enrolment__funnel" aria-label="Итог по стадиям зачисления, все потоки">
                  {['Оплачено', 'Анкета заполнена', 'Выгружено в LMS', 'Принято LMS'].map((label, index) => (
                    <div key={label}>
                      <dt>{label}</dt>
                      <dd>{stageTotalsOf(state.data.streams)[index]}</dd>
                    </div>
                  ))}
                </dl>
                <div className="catalog-import__table-scroll">
                  <table aria-label="Потоки зачисления">
                    <thead>
                      <tr>
                        <th scope="col">Курс, поток</th>
                        <th scope="col">Программа</th>
                        <th scope="col">Анкеты</th>
                        <th scope="col">Стадия</th>
                        <th scope="col">Окончание</th>
                        <th scope="col">Действие</th>
                      </tr>
                    </thead>
                    <tbody>
                      {state.data.streams.map((stream) => (
                        <Fragment key={stream.id}>
                          <tr>
                            <td>
                              <button
                                type="button"
                                className="enrolment__expand"
                                aria-expanded={expanded === stream.id}
                                aria-label={`Подробнее: ${stream.courseName}, поток ${stream.streamNo}`}
                                onClick={() => setExpanded((current) => (current === stream.id ? null : stream.id))}
                              >
                                {expanded === stream.id ? '▾' : '▸'}
                              </button>
                              {stream.courseName}, поток {stream.streamNo}
                            </td>
                            <td>{stream.programName ?? 'не сопоставлена'}</td>
                            <td>{stream.profilesComplete} из {stream.paid}</td>
                            <td><StageBar stream={stream} /></td>
                            <td>{stream.endsOn === null ? 'не указана' : formatIsoDate(stream.endsOn)}</td>
                            <td>
                              <button type="button" className="button--secondary" onClick={() => onOpenStream(stream.id)}>
                                Открыть
                              </button>
                            </td>
                          </tr>
                          {expanded === stream.id && (
                            <tr className="enrolment__details-row">
                              <td colSpan={6}>
                                <dl className="enrolment__details">
                                  <div><dt>Оплачено</dt><dd>{stream.paid}</dd></div>
                                  <div><dt>Выгружено</dt><dd>{stream.exported}</dd></div>
                                  <div><dt>Принято LMS</dt><dd>{stream.transferred}</dd></div>
                                  <div><dt>Ожидает</dt><dd>{stream.pending}</dd></div>
                                  <div><dt>Хранить до</dt><dd>{stream.keepUntil === null ? 'срок не определён' : formatIsoDate(stream.keepUntil)}</dd></div>
                                </dl>
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </section>

          <section className="enrolment__section" aria-labelledby="enrolment-search-title">
            <h3 id="enrolment-search-title">Поиск слушателя</h3>
            {searchFields.map((field) => (
              <form key={field.value} className="enrolment__form" onSubmit={(event) => void runSearch(event, field.value)}>
                <label>
                  {field.label}
                  <input
                    type={field.type}
                    value={searchValues[field.value]}
                    maxLength={254}
                    inputMode={field.inputMode}
                    placeholder={field.example}
                    autoComplete="off"
                    onChange={(event) => setSearchValues({ ...searchValues, [field.value]: event.target.value })}
                  />
                </label>
                <button type="submit" disabled={searchValues[field.value].trim() === '' || search.kind === 'searching'}>
                  {search.kind === 'searching' ? 'Ищем…' : 'Найти'}
                </button>
              </form>
            ))}
            <p className="enrolment__hint">Поиск точный: введите значение полностью.</p>

            {search.kind === 'failed' && (
              <div className="notice notice--error" role="alert">
                <p>{responseErrorMessage(search.error)}</p>
                <SupportDetails requestId={requestIdOf(search.error)} />
              </div>
            )}

            {search.kind === 'done' && search.results.length === 0 && <p>Никого не нашли.</p>}

            {search.kind === 'done' && search.results.length > 0 && (
              <ul className="enrolment__search-results">
                {search.results.map((learner) => (
                  <li key={learner.id} className="enrolment__search-result">
                    <span>
                      {learner.status === 'ANONYMIZED'
                        ? 'Анкета обезличена'
                        : [learner.lastName, learner.firstName, learner.middleName].filter((part) => part !== null).join(' ')}
                      {' — '}{learner.phone ?? '—'}, {learner.email ?? '—'}
                      {' — '}{learner.complete ? 'анкета заполнена' : `заполнено ${learner.filledFields} из ${learner.requiredFields}`}
                      {learner.enrolments.length > 0 && ` — ${streamsOf(learner.enrolments)}`}
                    </span>
                    <button type="button" className="button--secondary" onClick={() => onOpenLearner(learner.id)}>
                      Открыть анкету
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </section>
  )
}
