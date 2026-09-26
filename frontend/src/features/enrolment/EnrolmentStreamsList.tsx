import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { apiClient, type EnrolmentStreams, type LearnerSearch, type LearnerSummary } from '../../shared/api/client'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { PaidOrdersUpload } from './PaidOrdersUpload'
import { type AccessErrorReporter, formatIsoDate, requestIdOf, responseErrorMessage } from './enrolmentShared'

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

const searchKinds: Array<{ value: LearnerSearch['kind']; label: string }> = [
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Телефон' },
  { value: 'SNILS', label: 'СНИЛС' },
  { value: 'LAST_NAME', label: 'Фамилия' }
]

const streamsOf = (enrolments: LearnerSummary['enrolments']) => (
  enrolments.map((enrolment) => `${enrolment.courseName}, поток ${enrolment.streamNo}`).join('; ')
)

export const EnrolmentStreamsList = ({ onOpenStream, onOpenLearner, onAccessError }: EnrolmentStreamsListProps) => {
  const [state, setState] = useState<ListState>({ kind: 'loading' })
  const [searchKind, setSearchKind] = useState<LearnerSearch['kind']>('EMAIL')
  const [searchValue, setSearchValue] = useState('')
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })

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

  const runSearch = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (searchValue.trim() === '' || search.kind === 'searching') {
      return
    }
    setSearch({ kind: 'searching' })
    try {
      const results = await apiClient.searchLearners({ kind: searchKind, value: searchValue.trim() })
      setSearch({ kind: 'done', results })
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      setSearch({ kind: 'failed', error })
    }
  }

  return (
    <section className="enrolment" aria-labelledby="enrolment-title">
      <div>
        <p className="eyebrow">Оператор зачисления</p>
        <h2 id="enrolment-title">Зачисление</h2>
      </div>

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

          <PaidOrdersUpload onUploaded={() => void load()} onAccessError={onAccessError} />

          <section className="enrolment__section" aria-labelledby="enrolment-streams-title">
            <h3 id="enrolment-streams-title">Потоки</h3>
            {state.data.streams.length === 0 ? (
              <p>Потоков пока нет: загрузите файл оплат.</p>
            ) : (
              <div className="catalog-import__table-scroll">
                <table aria-label="Потоки зачисления">
                  <thead>
                    <tr>
                      <th scope="col">Курс</th>
                      <th scope="col">Поток</th>
                      <th scope="col">Программа</th>
                      <th scope="col">Оплачено</th>
                      <th scope="col">Анкеты</th>
                      <th scope="col">Выгружено</th>
                      <th scope="col">Передано</th>
                      <th scope="col">Ожидает</th>
                      <th scope="col">Окончание</th>
                      <th scope="col">Хранить до</th>
                      <th scope="col">Действие</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.data.streams.map((stream) => (
                      <tr key={stream.id}>
                        <td>{stream.courseName}</td>
                        <td>{stream.streamNo}</td>
                        <td>{stream.programName ?? 'не сопоставлена'}</td>
                        <td>{stream.paid}</td>
                        <td>{stream.profilesComplete} из {stream.paid}</td>
                        <td>{stream.exported}</td>
                        <td>{stream.transferred}</td>
                        <td>{stream.pending}</td>
                        <td>{stream.endsOn === null ? 'не указана' : formatIsoDate(stream.endsOn)}</td>
                        <td>{stream.keepUntil === null ? 'срок не определён' : formatIsoDate(stream.keepUntil)}</td>
                        <td>
                          <button type="button" className="button--secondary" onClick={() => onOpenStream(stream.id)}>
                            Открыть
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="enrolment__section" aria-labelledby="enrolment-search-title">
            <h3 id="enrolment-search-title">Поиск слушателя</h3>
            <form className="enrolment__form" onSubmit={(event) => void runSearch(event)}>
              <label>
                Вид поиска
                <select value={searchKind} onChange={(event) => setSearchKind(event.target.value as LearnerSearch['kind'])}>
                  {searchKinds.map((kind) => <option key={kind.value} value={kind.value}>{kind.label}</option>)}
                </select>
              </label>
              <label>
                Значение
                <input
                  value={searchValue}
                  maxLength={254}
                  inputMode="text"
                  autoComplete="off"
                  onChange={(event) => setSearchValue(event.target.value)}
                />
              </label>
              <button type="submit" disabled={searchValue.trim() === '' || search.kind === 'searching'}>
                {search.kind === 'searching' ? 'Ищем…' : 'Найти'}
              </button>
            </form>
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
