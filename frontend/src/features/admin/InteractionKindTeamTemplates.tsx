import { useCallback, useEffect, useState } from 'react'
import { apiClient, type WorkflowTemplate } from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { CommandError, useAccessErrorHandler, useCommandKey } from '../agreements/agreementUi'
import { interactionKindsApi, templateStagesLine, type InteractionKind } from '../interactions/interactionKinds'
import './adminUi.css'

type Props = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

/** Руководитель задаёт для своей команды шаблон маршрута по каждому виду взаимодействия. */
export const InteractionKindTeamTemplates = ({ onSessionExpired, onProfileUnavailable }: Props) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [kinds, setKinds] = useState<InteractionKind[] | null>(null)
  const [templates, setTemplates] = useState<WorkflowTemplate[]>([])
  const [error, setError] = useState<unknown>(null)
  const [savingId, setSavingId] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const commandKey = useCommandKey()

  const load = useCallback(async () => {
    try {
      const [loaded, page] = await Promise.all([interactionKindsApi.managed(), apiClient.listAvailableWorkflowTemplates({ page: 0, size: 100 })])
      setKinds(loaded)
      setTemplates(page.items)
      setError(null)
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const change = async (kind: InteractionKind, templateId: string) => {
    const payload = { kind: kind.id, templateId: templateId === '' ? null : templateId }
    setSavingId(kind.id)
    setError(null)
    try {
      await interactionKindsApi.setTeamTemplate(kind.id, payload.templateId, commandKey.keyFor(payload))
      commandKey.settle()
      setMessage(payload.templateId === null
        ? `«${kind.name}»: команда работает по шаблону вида.`
        : `«${kind.name}»: шаблон команды сохранён.`)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSavingId(null)
    }
  }

  return (
    <section aria-labelledby="kind-team-templates-title" className="admin-section-block">
      <div className="admin-toolbar">
        <h3 id="kind-team-templates-title">Шаблоны команды для видов взаимодействия</h3>
        <HintButton label="О шаблонах для видов">
          Новая карточка вида получает шаблон команды, если он выбран здесь, иначе шаблон вида из справочника администратора, иначе шаблон по
          умолчанию. Идущие карточки не меняются.
        </HintButton>
      </div>
      {kinds === null && error === null && <p role="status">Загружаем виды взаимодействия…</p>}
      {error !== null && <CommandError error={error} fallback="Шаблон команды не сохранён." onRetry={() => void load()} retryLabel="Обновить" />}
      {message !== null && <p role="status">{message}</p>}
      {kinds !== null && (
        <table className="admin-table" aria-label="Шаблоны команды для видов взаимодействия">
          <thead>
            <tr>
              <th scope="col">Вид</th>
              <th scope="col">Шаблон вида</th>
              <th scope="col">Шаблон команды</th>
            </tr>
          </thead>
          <tbody>
            {kinds.filter((kind) => kind.active || kind.teamTemplateId !== null).map((kind) => (
              <tr key={kind.id}>
                <td className="admin-table__cell-main">{kind.name}</td>
                <td>{kind.templateName ?? 'Шаблон по умолчанию'}</td>
                <td>
                  <select
                    aria-label={`Шаблон команды: ${kind.name}`}
                    value={kind.teamTemplateId ?? ''}
                    disabled={savingId !== null}
                    onChange={(event) => void change(kind, event.target.value)}
                  >
                    <option value="">Как у вида</option>
                    {templates.map((template) => <option key={template.id} value={template.id}>{template.name}</option>)}
                  </select>
                  {templateStagesLine(templates.find((template) => template.id === (kind.teamTemplateId ?? kind.templateId))) && (
                    <span className="admin-table__sub">
                      {templateStagesLine(templates.find((template) => template.id === (kind.teamTemplateId ?? kind.templateId)))}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
