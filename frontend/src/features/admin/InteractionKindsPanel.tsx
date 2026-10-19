import { Fragment, useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { apiClient, type WorkflowTemplate } from '../../shared/api/client'
import { ActionMenu } from '../../shared/ui/ActionMenu'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog'
import { EmptyState } from '../../shared/ui/EmptyState'
import { HintButton } from '../../shared/ui/HintButton'
import { Toast } from '../../shared/ui/Toast'
import './adminUi.css'
import './catalogs.css'
import { CommandError, useAccessErrorHandler, useCommandKey } from '../agreements/agreementUi'
import { CardDialog } from '../interactions/cardUi'
import {
  interactionKindsApi,
  organizationTypeLabels,
  overlappingRules,
  ruleConditions,
  sameConditions,
  type InteractionKind,
  type InteractionKindRule,
  type KindOrganizationType
} from '../interactions/interactionKinds'

type PanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
  /** Счётчик нажатий главной кнопки страницы «Справочники»: каждое новое значение открывает окно добавления. */
  addRequest?: number
  onNotify?: (message: string) => void
}

type Option = { id: string; name: string }

type KindDraft = {
  id: string | null
  version: number
  name: string
  description: string
  active: boolean
  templateId: string
  sortOrder: string
  defaultKind: boolean
  wasDefault: boolean
}
type RuleDraft = {
  id: string | null
  version: number
  kindId: string
  organizationType: KindOrganizationType | ''
  programId: string
  productId: string
  priority: string
}

const emptyKind: KindDraft = {
  id: null, version: 0, name: '', description: '', active: true, templateId: '', sortOrder: '', defaultKind: false, wasDefault: false
}
const emptyRule: RuleDraft = { id: null, version: 0, kindId: '', organizationType: '', programId: '', productId: '', priority: '100' }

// Добавление по главной кнопке страницы: новое значение счётчика открывает окно один раз.
const useAddRequest = (addRequest: number | undefined, open: () => void) => {
  const seen = useRef(addRequest)
  useEffect(() => {
    if (addRequest !== undefined && addRequest !== seen.current) {
      seen.current = addRequest
      open()
    }
  }, [addRequest, open])
}

export const InteractionKindsPanel = ({ onSessionExpired, onProfileUnavailable, addRequest, onNotify }: PanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [kinds, setKinds] = useState<InteractionKind[] | null>(null)
  const [templates, setTemplates] = useState<WorkflowTemplate[]>([])
  const [loadError, setLoadError] = useState<unknown>(null)
  const [draft, setDraft] = useState<KindDraft | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [ownMessage, setOwnMessage] = useState<string | null>(null)
  const commandKey = useCommandKey()
  const notify = onNotify ?? setOwnMessage

  const load = useCallback(async () => {
    try {
      const [loaded, page] = await Promise.all([interactionKindsApi.managed(), apiClient.listWorkflowTemplates({ page: 0, size: 100 })])
      setKinds(loaded)
      setTemplates(page.items.filter((template) => template.teamId === null))
      setLoadError(null)
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setLoadError(caught)
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const openAdd = useCallback(() => {
    setError(null)
    setDraft(emptyKind)
  }, [])
  useAddRequest(addRequest, openAdd)

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft === null) {
      return
    }
    const payload = {
      ...(draft.id === null ? {} : { version: draft.version }),
      name: draft.name.trim(),
      description: draft.description.trim() === '' ? null : draft.description.trim(),
      active: draft.active,
      templateId: draft.templateId === '' ? null : draft.templateId,
      sortOrder: draft.sortOrder.trim() === '' ? null : Number(draft.sortOrder),
      defaultKind: draft.defaultKind && !draft.wasDefault ? true : null
    }
    setSaving(true)
    setError(null)
    try {
      const key = commandKey.keyFor({ id: draft.id, ...payload })
      if (draft.id === null) {
        await interactionKindsApi.create(payload, key)
      } else {
        await interactionKindsApi.update(draft.id, payload, key)
      }
      commandKey.settle()
      setDraft(null)
      notify(`Вид «${payload.name}» сохранён.`)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Fragment>
      <div className="admin-toolbar" aria-label="Виды взаимодействия">
        <HintButton label="О видах взаимодействия">
          Вид определяет маршрут новой карточки: шаблон команды для вида, иначе шаблон вида, иначе шаблон по умолчанию. Смена вида или
          шаблона идущие карточки не меняет; перевести их на другую версию маршрута можно в «Шаблонах этапов». Недействующий вид остаётся у
          существующих карточек, но не предлагается для новых. Вид по умолчанию — один, его получает новая карточка, если КАМ не выбрал
          вид и ни одно правило не подошло; порядок в списке на это не влияет.
        </HintButton>
        {addRequest === undefined && <button type="button" onClick={openAdd}>Добавить вид взаимодействия</button>}
      </div>
      {kinds === null && loadError === null && <p role="status">Загружаем виды взаимодействия…</p>}
      {loadError !== null && <CommandError error={loadError} fallback="Не удалось загрузить виды взаимодействия." onRetry={() => void load()} />}
      {kinds !== null && kinds.length === 0 && <EmptyState text="Видов взаимодействия нет." />}
      {kinds !== null && kinds.length > 0 && (
        <table className="admin-table" aria-label="Виды взаимодействия">
          <thead>
            <tr>
              <th scope="col">Порядок</th>
              <th scope="col">Название</th>
              <th scope="col">Шаблон маршрута</th>
              <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
            </tr>
          </thead>
          <tbody>
            {kinds.map((kind) => (
              <tr key={kind.id}>
                <td>{kind.sortOrder}</td>
                <td className="admin-table__cell-main">
                  <span className="admin-table__main">{kind.name}</span>
                  {!kind.active && <> <span className="admin-chip admin-chip--warn">Не действует</span></>}
                  {kind.defaultKind && <> <span className="admin-chip">По умолчанию</span></>}
                  {kind.description && <span className="admin-table__sub">{kind.description}</span>}
                </td>
                <td>{kind.templateName ?? 'Шаблон по умолчанию'}</td>
                <td className="admin-table__actions">
                  <ActionMenu
                    label={`Действия: «${kind.name}»`}
                    items={[{
                      label: 'Изменить',
                      onSelect: () => {
                        setError(null)
                        setDraft({
                          id: kind.id,
                          version: kind.version,
                          name: kind.name,
                          description: kind.description ?? '',
                          active: kind.active,
                          templateId: kind.templateId ?? '',
                          sortOrder: String(kind.sortOrder),
                          defaultKind: kind.defaultKind,
                          wasDefault: kind.defaultKind
                        })
                      }
                    }]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <CardDialog open={draft !== null} title={draft?.id ? `Вид «${draft.name}»` : 'Добавить вид взаимодействия'} onClose={() => setDraft(null)}>
        {draft !== null && (
          <form className="admin-form" onSubmit={(event) => void save(event)}>
            <label>
              Название
              <input value={draft.name} required maxLength={200} disabled={saving} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
            </label>
            <label>
              Описание
              <textarea value={draft.description} maxLength={1000} disabled={saving} onChange={(event) => setDraft({ ...draft, description: event.target.value })} />
            </label>
            <label>
              Шаблон маршрута по умолчанию
              <select value={draft.templateId} disabled={saving} onChange={(event) => setDraft({ ...draft, templateId: event.target.value })}>
                <option value="">Общий шаблон по умолчанию</option>
                {templates.map((template) => <option key={template.id} value={template.id}>{template.name}</option>)}
              </select>
            </label>
            <label>
              Порядок в списках
              <input type="number" min={0} max={10000} value={draft.sortOrder} placeholder="В конец списка" disabled={saving}
                onChange={(event) => setDraft({ ...draft, sortOrder: event.target.value })} />
            </label>
            <label className="interaction-stage-editor__checkbox">
              <input type="checkbox" checked={draft.active} disabled={saving || draft.wasDefault}
                onChange={(event) => setDraft({ ...draft, active: event.target.checked })} />
              Действует (предлагается для новых карточек)
            </label>
            <label className="interaction-stage-editor__checkbox">
              <input type="checkbox" checked={draft.defaultKind} disabled={saving || draft.wasDefault || !draft.active}
                onChange={(event) => setDraft({ ...draft, defaultKind: event.target.checked })} />
              Вид по умолчанию для новых карточек
            </label>
            {draft.wasDefault && (
              <p className="admin-form__hint">Это вид по умолчанию: чтобы снять отметку или отключить вид, назначьте видом по умолчанию другой вид.</p>
            )}
            {draft.defaultKind && !draft.wasDefault && (
              <p className="notice" role="note">Все новые карточки без выбранного вида и без подходящего правила получат этот вид вместо нынешнего.</p>
            )}
            {error !== null && <CommandError error={error} fallback="Вид взаимодействия не сохранён." />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={saving} onClick={() => setDraft(null)}>Отмена</button>
              <button type="submit" disabled={saving || draft.name.trim() === ''}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
            </div>
          </form>
        )}
      </CardDialog>
      {onNotify === undefined && <Toast message={ownMessage} onClose={() => setOwnMessage(null)} />}
    </Fragment>
  )
}

export const InteractionKindRulesPanel = ({ onSessionExpired, onProfileUnavailable, addRequest, onNotify }: PanelProps) => {
  const handleAccessError = useAccessErrorHandler(onSessionExpired, onProfileUnavailable)
  const [rules, setRules] = useState<InteractionKindRule[] | null>(null)
  const [kinds, setKinds] = useState<InteractionKind[]>([])
  const [programs, setPrograms] = useState<Option[]>([])
  const [products, setProducts] = useState<Option[]>([])
  const [loadError, setLoadError] = useState<unknown>(null)
  const [draft, setDraft] = useState<RuleDraft | null>(null)
  const [deleting, setDeleting] = useState<InteractionKindRule | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [ownMessage, setOwnMessage] = useState<string | null>(null)
  const commandKey = useCommandKey()
  const notify = onNotify ?? setOwnMessage

  const load = useCallback(async () => {
    try {
      const [loadedRules, loadedKinds, programPage, productPage] = await Promise.all([
        interactionKindsApi.rules(),
        interactionKindsApi.managed(),
        // Форма открыта только администратору, а /api/programs и /api/products отдают ему пустые списки: справочники
        // бизнес-ролей привязаны к команде. Берём действующие записи из админского справочника.
        apiClient.listAdminCatalogEntries('programs', { page: 0, size: 100, state: 'ACTIVE' }),
        apiClient.listAdminCatalogEntries('products', { page: 0, size: 100, state: 'ACTIVE' })
      ])
      setRules(loadedRules)
      setKinds(loadedKinds)
      setPrograms(programPage.items)
      setProducts(productPage.items)
      setLoadError(null)
    } catch (caught) {
      if (!handleAccessError(caught)) {
        setLoadError(caught)
      }
    }
  }, [handleAccessError])

  useEffect(() => {
    void load()
  }, [load])

  const openAdd = useCallback(() => {
    setError(null)
    setDraft(emptyRule)
  }, [])
  useAddRequest(addRequest, openAdd)

  const run = async (action: () => Promise<unknown>, message: string) => {
    setSaving(true)
    setError(null)
    try {
      await action()
      commandKey.settle()
      setDraft(null)
      setDeleting(null)
      notify(message)
      await load()
    } catch (caught) {
      commandKey.settle(caught)
      if (!handleAccessError(caught)) {
        setError(caught)
      }
    } finally {
      setSaving(false)
    }
  }

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draft === null) {
      return
    }
    const payload = {
      ...(draft.id === null ? {} : { version: draft.version }),
      kindId: draft.kindId,
      organizationType: draft.organizationType === '' ? null : draft.organizationType,
      programId: draft.programId === '' ? null : draft.programId,
      productId: draft.productId === '' ? null : draft.productId,
      priority: Number(draft.priority)
    }
    const key = commandKey.keyFor({ id: draft.id, ...payload })
    const id = draft.id
    void run(() => (id === null ? interactionKindsApi.createRule(payload, key) : interactionKindsApi.updateRule(id, payload, key)),
      'Правило сохранено.')
  }

  const remove = (rule: InteractionKindRule) => {
    const key = commandKey.keyFor({ delete: rule.id, version: rule.version })
    void run(() => interactionKindsApi.deleteRule(rule.id, rule.version, key), 'Правило удалено.')
  }

  const noCondition = draft !== null && draft.organizationType === '' && draft.programId === '' && draft.productId === ''

  return (
    <Fragment>
      <div className="admin-toolbar" aria-label="Правила выбора вида">
        <HintButton label="О правилах выбора вида">
          Правило подставляет вид новой карточке, если КАМ не выбрал вид сам, а также при импорте из Excel и для заявок и данных из
          источников. Все заполненные условия должны совпасть. Побеждает меньший номер приоритета, при равенстве — правило с большим
          числом условий, затем порядок вида в справочнике. Если ни одно правило не подошло, берётся вид по умолчанию. Правило с теми же
          условиями и приоритетом, что у другого, не сохраняется: одно из них никогда бы не сработало.
        </HintButton>
        {addRequest === undefined && <button type="button" onClick={openAdd}>Добавить правило</button>}
      </div>
      {rules === null && loadError === null && <p role="status">Загружаем правила…</p>}
      {loadError !== null && <CommandError error={loadError} fallback="Не удалось загрузить правила." onRetry={() => void load()} />}
      {error !== null && draft === null && <CommandError error={error} fallback="Правило не изменено." onRetry={() => void load()} retryLabel="Обновить список" />}
      {rules !== null && rules.length === 0 && (
        <EmptyState text="Правил нет: новые карточки получают выбранный КАМ вид или вид по умолчанию." />
      )}
      {rules !== null && rules.length > 0 && (
        <table className="admin-table" aria-label="Правила выбора вида взаимодействия">
          <thead>
            <tr>
              <th scope="col">Приоритет</th>
              <th scope="col">Условия</th>
              <th scope="col">Вид</th>
              <th scope="col" className="admin-table__actions"><span className="catalogs-sr">Действия</span></th>
            </tr>
          </thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={rule.id}>
                <td>{rule.priority}</td>
                <td>{ruleConditions(rule)}</td>
                <td>
                  {rule.kindName}
                  {kinds.some((kind) => kind.id === rule.kindId && !kind.active) && (
                    <> <span className="admin-chip admin-chip--warn">Вид отключён — правило не действует</span></>
                  )}
                </td>
                <td className="admin-table__actions">
                  <ActionMenu
                    label={`Действия: правило «${rule.kindName}»`}
                    items={[
                      {
                        label: 'Изменить',
                        onSelect: () => {
                          setError(null)
                          setDraft({
                            id: rule.id,
                            version: rule.version,
                            kindId: rule.kindId,
                            organizationType: rule.organizationType ?? '',
                            programId: rule.programId ?? '',
                            productId: rule.productId ?? '',
                            priority: String(rule.priority)
                          })
                        }
                      },
                      { label: 'Удалить', danger: true, onSelect: () => setDeleting(rule) }
                    ]}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <CardDialog open={draft !== null} title={draft?.id ? 'Изменить правило' : 'Добавить правило'} onClose={() => setDraft(null)}>
        {draft !== null && (
          <form className="admin-form" onSubmit={save}>
            <label>
              Тип организации
              <select value={draft.organizationType} disabled={saving}
                onChange={(event) => setDraft({ ...draft, organizationType: event.target.value as RuleDraft['organizationType'] })}>
                <option value="">Любой</option>
                {(Object.keys(organizationTypeLabels) as KindOrganizationType[]).map((type) => (
                  <option key={type} value={type}>{organizationTypeLabels[type]}</option>
                ))}
              </select>
            </label>
            <label>
              ИТ-программа
              <select value={draft.programId} disabled={saving} onChange={(event) => setDraft({ ...draft, programId: event.target.value })}>
                <option value="">Любая</option>
                {programs.map((program) => <option key={program.id} value={program.id}>{program.name}</option>)}
              </select>
            </label>
            <label>
              ИТ-продукт
              <select value={draft.productId} disabled={saving} onChange={(event) => setDraft({ ...draft, productId: event.target.value })}>
                <option value="">Любой</option>
                {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
              </select>
            </label>
            <label>
              Вид взаимодействия
              <select value={draft.kindId} required disabled={saving} onChange={(event) => setDraft({ ...draft, kindId: event.target.value })}>
                <option value="">Выберите вид</option>
                {kinds.map((kind) => <option key={kind.id} value={kind.id}>{kind.name}{kind.active ? '' : ' (не действует)'}</option>)}
              </select>
            </label>
            <label>
              Приоритет (1 — самый высокий)
              <input type="number" min={1} max={1000} required value={draft.priority} disabled={saving}
                onChange={(event) => setDraft({ ...draft, priority: event.target.value })} />
            </label>
            {noCondition && <p className="notice" role="note">Укажите хотя бы одно условие.</p>}
            {rules !== null && !noCondition && overlappingRules({
              id: draft.id,
              organizationType: draft.organizationType === '' ? null : draft.organizationType,
              programId: draft.programId === '' ? null : draft.programId,
              productId: draft.productId === '' ? null : draft.productId,
              priority: Number(draft.priority)
            }, rules).map((rule) => sameConditions(rule, {
              organizationType: draft.organizationType === '' ? null : draft.organizationType,
              programId: draft.programId === '' ? null : draft.programId,
              productId: draft.productId === '' ? null : draft.productId,
              priority: Number(draft.priority)
            })
              ? (
                <p key={rule.id} className="notice" role="note">
                  Правило с теми же условиями уже есть: «{ruleConditions(rule)} → {rule.kindName}». Измените условия или правьте
                  существующее правило.
                </p>
              )
              : (
                <p key={rule.id} className="notice" role="note">
                  Пересекается с правилом «{ruleConditions(rule)} → {rule.kindName}» того же приоритета: подойдут оба — выиграет правило с
                  большим числом условий, при равенстве — вид выше в справочнике.
                </p>
              ))}
            {error !== null && <CommandError error={error} fallback="Правило не сохранено." />}
            <div className="admin-form__actions">
              <button type="button" className="button--secondary" disabled={saving} onClick={() => setDraft(null)}>Отмена</button>
              <button type="submit" disabled={saving || draft.kindId === '' || noCondition}>{saving ? 'Сохраняем…' : 'Сохранить'}</button>
            </div>
          </form>
        )}
      </CardDialog>
      <ConfirmDialog
        open={deleting !== null}
        title="Удалить правило?"
        description={deleting === null ? '' : `${ruleConditions(deleting)} → «${deleting.kindName}». Карточки, созданные по правилу, не меняются.`}
        confirmLabel="Удалить"
        onConfirm={() => deleting !== null && remove(deleting)}
        onCancel={() => setDeleting(null)}
      />
      {onNotify === undefined && <Toast message={ownMessage} onClose={() => setOwnMessage(null)} />}
    </Fragment>
  )
}
