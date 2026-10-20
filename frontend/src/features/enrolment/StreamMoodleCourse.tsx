import { useState } from 'react'
import { ApiError, apiClient, createIdempotencyKey, type EnrolmentStream } from '../../shared/api/client'
import { moodleCourseText, parseMoodleCourse } from './enrolmentQueueModel'
import { type AccessErrorReporter, responseErrorMessage } from './enrolmentShared'

type StreamMoodleCourseProps = {
  stream: EnrolmentStream
  onSaved: (stream: EnrolmentStream) => void
  onConflict: () => void
  onAccessError: AccessErrorReporter
}

// Курс Moodle потока: по умолчанию — из сопоставлений программы; явное уточнение нужно, когда у программы несколько
// курсов или групп. Передача и перенос в Moodle берут уточнение, если оно задано.
export const StreamMoodleCourse = ({ stream, onSaved, onConflict, onAccessError }: StreamMoodleCourseProps) => {
  const [course, setCourse] = useState(stream.moodleCourseId === null ? '' : String(stream.moodleCourseId))
  const [group, setGroup] = useState(stream.moodleGroupId === null ? '' : String(stream.moodleGroupId))
  const [key, setKey] = useState(createIdempotencyKey)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const save = async (courseText: string, groupText: string) => {
    const parsed = parseMoodleCourse(courseText, groupText)
    if (typeof parsed === 'string') {
      setProblem(parsed)
      return
    }
    setBusy(true)
    setProblem(null)
    try {
      const saved = await apiClient.updateEnrolmentStreamMoodleCourse(stream.id, { version: stream.version, ...parsed }, key)
      setKey(createIdempotencyKey())
      onSaved(saved)
    } catch (error) {
      if (onAccessError(error)) {
        return
      }
      if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') {
        setKey(createIdempotencyKey())
        onConflict()
        return
      }
      setProblem(responseErrorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="enrolment__end-date" aria-label="Курс Moodle потока" onSubmit={(event) => {
      event.preventDefault()
      void save(course, group)
    }}>
      <label>
        Курс Moodle (номер)
        <input inputMode="numeric" value={course} disabled={busy} placeholder="по программе"
          onChange={(event) => { setCourse(event.target.value); setKey(createIdempotencyKey()) }} />
      </label>
      <label>
        Группа Moodle (номер)
        <input inputMode="numeric" value={group} disabled={busy}
          onChange={(event) => { setGroup(event.target.value); setKey(createIdempotencyKey()) }} />
      </label>
      <button type="submit" disabled={busy}>Сохранить курс</button>
      {stream.moodleCourseId !== null && (
        <button type="button" className="button--secondary" disabled={busy} onClick={() => {
          setCourse('')
          setGroup('')
          void save('', '')
        }}>По программе</button>
      )}
      <p>Курс для передачи и переноса: {moodleCourseText(stream)}</p>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}
