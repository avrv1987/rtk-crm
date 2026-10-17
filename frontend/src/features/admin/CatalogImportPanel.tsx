import { Fragment, type ChangeEvent, type FormEvent, useEffect, useId, useRef, useState } from 'react'
import {
  ApiError,
  apiClient,
  createIdempotencyKey,
  type AdminTeam,
  type CatalogImport,
  type CatalogImportJob,
  type CatalogImportMapping,
  type CatalogImportProfile,
  type CatalogImportRow,
  type CatalogImportRowTarget,
  type CatalogImportSheet
} from '../../shared/api/client'
import { HintButton } from '../../shared/ui/HintButton'
import { SupportDetails } from '../../shared/ui/SupportDetails'
import { importSteps, importStepOf, parseTransferStatuses } from './catalogImportSteps'
import './adminUi.css'
import './catalogs.css'
import './catalogImport.css'

type CatalogImportPanelProps = {
  onSessionExpired: () => void
  onProfileUnavailable: (requestId: string) => void
}

type MappingField = {
  key: string
  label: string
  required: boolean
  advanced: boolean
  aliases: string[]
}

type ErrorState = {
  action: 'inspect' | 'preview' | 'apply' | 'reload'
  error: unknown
}

type BusyAction = ErrorState['action'] | null

const field = (key: string, label: string, required: boolean, advanced: boolean, aliases: string[] = []): MappingField => (
  { key, label, required, advanced, aliases }
)

const agreementFields: MappingField[] = [
  field('organizationName', 'Название ВУЗа', true, false,
    ['ВУЗ', 'Наименование вуза', 'Название вуза', 'Университет', 'Организация', 'Наименование организации']),
  field('vendorName', 'Вендор', true, false, ['Производитель', 'Поставщик']),
  field('productName', 'ПО', true, false, ['Программное обеспечение', 'Продукт', 'ИТ-продукт', 'Наименование ПО']),
  field('contractNumber', 'Номер договора', false, false, ['Договор', '№ договора', 'Номер контракта']),
  field('licenseSigned', 'Подписание лицензии', false, false, ['Лицензия подписана', 'Подписана лицензия', 'Лицензия']),
  field('licenseExpiryYear', 'Срок действия лицензии (год)', false, false,
    ['Срок действия лицензии', 'Год окончания лицензии', 'Срок лицензии']),
  field('transferStatus', 'Статус по передаче', false, false, ['Статус по передачи', 'Статус передачи']),
  field('managerName', 'ФИО Менеджера', false, false,
    ['Менеджер', 'Ответственный менеджер', 'ФИО ответственного менеджера', 'КАМ', 'ФИО КАМ']),
  field('contactName', 'Ответственные от ВУЗа', false, false,
    ['Ответственный от ВУЗа', 'Контактное лицо', 'Контакт', 'Контакты вуза']),
  field('comment', 'Комментарий', false, false, ['Комментарии', 'Примечание']),
  field('organizationType', 'Тип организации', false, true, ['Тип']),
  field('organizationExternalKey', 'Ключ вуза', false, true),
  field('vendorExternalKey', 'Ключ вендора', false, true),
  field('productExternalKey', 'Ключ ПО', false, true),
  field('agreementExternalKey', 'Ключ договора', false, true),
  field('contactExternalKey', 'Ключ ответственного', false, true),
  field('contactPosition', 'Должность ответственного', false, true, ['Должность']),
  field('contactEmail', 'Email ответственного', false, true, ['Email', 'Электронная почта']),
  field('contactPhone', 'Телефон ответственного', false, true, ['Телефон']),
  field('interactionId', 'UUID взаимодействия', false, true)
]

const directionProgramFields: MappingField[] = [
  field('directionExternalKey', 'Ключ направления', true, false, ['directionKey']),
  field('directionName', 'Направление', true, false, ['ИТ-направление', 'Название направления']),
  field('programExternalKey', 'Ключ программы', false, false, ['programKey']),
  field('programName', 'Программа', false, false, ['ИТ-программа', 'Название программы']),
  field('programDirectionRef', 'Ключ направления программы', false, false, ['directionRef'])
]

const vendorContactFields: MappingField[] = [
  field('vendorName', 'Компания', true, false, ['Вендор', 'Производитель']),
  field('productNames', 'Продукт', false, false, ['Продукты', 'ПО', 'ИТ-продукт']),
  field('vendorContactName', 'ФИО', false, false, ['ФИО контакта', 'Контактное лицо', 'Контакт']),
  field('vendorContactPhone', 'Телефон', false, false, ['Телефон контакта']),
  field('vendorContactEmail', 'Почта', false, false, ['Email', 'E-mail', 'Электронная почта']),
  field('vendorContactChannels', 'Способ связи', false, false, ['Канал связи', 'Предпочтительный способ связи'])
]

const targetFields: Array<{ key: keyof CatalogImportRowTarget; label: string }> = [
  { key: 'organizationId', label: 'UUID вуза' },
  { key: 'managerProfileId', label: 'UUID КАМ' },
  { key: 'interactionId', label: 'UUID взаимодействия' },
  { key: 'productAgreementId', label: 'UUID договора' }
]

const extraLabels: Record<string, string> = {
  interaction: 'Взаимодействие',
  productVendor: 'Вендор ПО',
  agreementProduct: 'ПО договора',
  contactDetails: 'Данные ответственного',
  programDirection: 'Направление программы',
  productContact: 'Контакт у продуктов',
  vendorContactArchived: 'Контакт в архиве',
  vendorExternalKey: 'Ключ вендора',
  organizationId: 'UUID вуза',
  managerProfileId: 'UUID КАМ',
  productAgreementId: 'UUID договора',
  file: 'Файл',
  sheet: 'Лист',
  profile: 'Профиль',
  mapping: 'Сопоставление',
  rowTargets: 'Явные идентификаторы',
  transferStatuses: 'Словарь статусов',
  confirmedRowIds: 'Выбор строк',
  agreementArchived: 'Договор в архиве',
  unassignedTeamId: 'Команда для организаций без КАМ',
  archiveAgreementIds: 'Записи для архивирования',
  templateId: 'Шаблон процесса',
  body: 'Запрос'
}

const fieldLabels: Record<string, string> = Object.fromEntries(
  [...vendorContactFields, ...agreementFields, ...directionProgramFields].map((mappingField) => [mappingField.key, mappingField.label])
)

const indexedProductKey = /^product(\d+)ExternalKey$/

const labelOf = (key: string) => {
  const indexedProduct = key.match(indexedProductKey)
  if (indexedProduct) {
    return `Ключ ПО № ${indexedProduct[1]}`
  }
  return fieldLabels[key] ?? extraLabels[key] ?? key
}

const fieldsFor = (profile: CatalogImportProfile) => {
  switch (profile) {
    case 'AGREEMENT':
      return agreementFields
    case 'VENDOR_CONTACTS':
      return vendorContactFields
    default:
      return directionProgramFields
  }
}

const normalizedHeader = (value: string) => value
  .toLowerCase()
  .replaceAll('ё', 'е')
  .replace(/[*:]+$/u, '')
  .replace(/\s+/gu, ' ')
  .trim()

const initialColumns = (profile: CatalogImportProfile, headers: string[]) => {
  const columns: Record<string, string> = {}
  const used = new Set<string>()
  for (const mappingField of fieldsFor(profile)) {
    const names = new Set([mappingField.key, mappingField.label, ...mappingField.aliases].map(normalizedHeader))
    const header = headers.find((candidate) => !used.has(candidate) && names.has(normalizedHeader(candidate)))
    if (header !== undefined) {
      columns[mappingField.key] = header
      used.add(header)
    }
  }
  return columns
}

const eligibleRow = (row: CatalogImportRow) => (
  !row.applied && (row.status === 'CREATE' || row.status === 'UPDATE' || row.status === 'UNCHANGED')
)

const rowStatusLabel = (status: CatalogImportRow['status']) => {
  if (status === 'CREATE') {
    return 'Создать'
  }
  if (status === 'UPDATE') {
    return 'Обновить'
  }
  if (status === 'UNCHANGED') {
    return 'Без изменений'
  }
  if (status === 'CONFLICT') {
    return 'Конфликт'
  }
  return 'Ошибка'
}

const rowStatusTone: Record<CatalogImportRow['status'], string> = {
  CREATE: ' admin-chip--ok',
  UPDATE: '',
  UNCHANGED: '',
  CONFLICT: ' admin-chip--danger',
  INVALID: ' admin-chip--danger'
}

const jobActionLabel = (action: CatalogImportJob['action']) => (
  action === 'PREVIEW' ? 'Предпросмотр' : 'Применение'
)

const requestIdOf = (error: unknown) => (
  error instanceof ApiError ? error.requestId : undefined
)

const errorMessage = (state: ErrorState) => {
  if (state.error instanceof ApiError && state.error.status === 409) {
    return 'Данные изменились после предпросмотра. Файл, сопоставление, идентификаторы и выбор строк сохранены; создайте новый предпросмотр перед повторной попыткой.'
  }
  if (state.error instanceof ApiError && state.error.status === 403) {
    return 'Импорт каталогов доступен только администратору с активным профилем CRM.'
  }
  if (state.error instanceof ApiError) {
    return state.action === 'inspect'
      ? 'Не удалось проверить файл. Исправьте файл или повторите попытку.'
      : 'Операция не выполнена. Проверьте поля и сообщения протокола.'
  }
  return state.error instanceof Error ? state.error.message : 'Не удалось связаться с сервисом. Повторите попытку позже.'
}

const describeValues = (values: Record<string, string>) => {
  const pairs = Object.entries(values)
  return pairs.length === 0 ? '—' : pairs.map(([key, value]) => `${labelOf(key)}: ${value || '—'}`).join('; ')
}

const describeErrors = (errors: Record<string, string>) => Object.entries(errors)
  .map(([key, message]) => `${labelOf(key)}: ${message}`)

const cleanTargets = (targets: Record<string, CatalogImportRowTarget>) => {
  const cleaned: Record<string, CatalogImportRowTarget> = {}
  for (const [rowNumber, target] of Object.entries(targets)) {
    const value: CatalogImportRowTarget = {}
    for (const field of targetFields) {
      const candidate = target[field.key]?.trim()
      if (candidate) {
        value[field.key] = candidate
      }
    }
    if (Object.keys(value).length > 0) {
      cleaned[rowNumber] = value
    }
  }
  return cleaned
}

type ColumnSelectProps = {
  mappingField: MappingField
  headers: string[]
  value: string
  disabled: boolean
  onChange: (key: string, header: string) => void
}

const ColumnSelect = ({ mappingField, headers, value, disabled, onChange }: ColumnSelectProps) => (
  <label>
    {mappingField.label}{mappingField.required ? ' *' : ' (необязательно)'}
    <select
      value={value}
      disabled={disabled}
      required={mappingField.required}
      onChange={(event) => onChange(mappingField.key, event.target.value)}
    >
      <option value="">Не используется</option>
      {headers.map((header) => <option key={header} value={header}>{header}</option>)}
    </select>
  </label>
)

export const CatalogImportPanel = ({ onSessionExpired, onProfileUnavailable }: CatalogImportPanelProps) => {
  const [file, setFile] = useState<File | null>(null)
  const [profile, setProfile] = useState<CatalogImportProfile>('AGREEMENT')
  const [sheets, setSheets] = useState<CatalogImportSheet[]>([])
  const [sheetName, setSheetName] = useState('')
  const [columns, setColumns] = useState<Record<string, string>>({})
  const [transferStatusText, setTransferStatusText] = useState('')
  const [rowTargets, setRowTargets] = useState<Record<string, CatalogImportRowTarget>>({})
  const [targetsDirty, setTargetsDirty] = useState(false)
  const [importPlan, setImportPlan] = useState<CatalogImport | null>(null)
  const [job, setJob] = useState<CatalogImportJob | null>(null)
  const [selectedRowIds, setSelectedRowIds] = useState<string[]>([])
  const [pendingApply, setPendingApply] = useState(false)
  const [busy, setBusy] = useState<BusyAction>(null)
  const [error, setError] = useState<ErrorState | null>(null)
  const [teams, setTeams] = useState<AdminTeam[]>([])
  const [unassignedTeamId, setUnassignedTeamId] = useState('')
  const [archiveAgreementIds, setArchiveAgreementIds] = useState<string[]>([])
  const [openRows, setOpenRows] = useState<string[]>([])
  const [mappingOpen, setMappingOpen] = useState(true)
  const applyKey = useRef<string | null>(null)
  const unassignedId = useId()

  useEffect(() => {
    apiClient.listTeams()
      .then((loaded) => setTeams(loaded.filter((team) => !team.archived)))
      .catch((failure: unknown) => {
        if (failure instanceof ApiError && failure.code === 'UNAUTHENTICATED') {
          onSessionExpired()
        } else if (failure instanceof ApiError && failure.code === 'CRM_PROFILE_REQUIRED') {
          onProfileUnavailable(failure.requestId)
        } else {
          setTeams([])
        }
      })
  }, [onProfileUnavailable, onSessionExpired])

  const selectedSheet = sheets.find((sheet) => sheet.name === sheetName) ?? null
  const mappingFields = fieldsFor(profile)
  const basicFields = mappingFields.filter((mappingField) => !mappingField.advanced)
  const advancedFields = mappingFields.filter((mappingField) => mappingField.advanced)
  const mappingComplete = selectedSheet !== null && mappingFields
    .filter((field) => field.required)
    .every((field) => (columns[field.key] ?? '').length > 0)
  const applyConflict = error?.action === 'apply' && error.error instanceof ApiError && error.error.status === 409

  const clearPlan = () => {
    applyKey.current = null
    setImportPlan(null)
    setJob(null)
    setSelectedRowIds([])
    setArchiveAgreementIds([])
    setPendingApply(false)
    setTargetsDirty(false)
    setOpenRows([])
    setMappingOpen(true)
    setError(null)
  }

  const toggleOpenRow = (id: string) => {
    setOpenRows((current) => (current.includes(id) ? current.filter((item) => item !== id) : [...current, id]))
  }

  const reportError = (action: ErrorState['action'], value: unknown) => {
    if (value instanceof ApiError && value.code === 'UNAUTHENTICATED') {
      onSessionExpired()
      return
    }
    if (value instanceof ApiError && value.code === 'CRM_PROFILE_REQUIRED') {
      onProfileUnavailable(value.requestId)
      return
    }
    setError({ action, error: value })
  }

  const selectFile = (event: ChangeEvent<HTMLInputElement>) => {
    setFile(event.target.files?.[0] ?? null)
    setSheets([])
    setSheetName('')
    setColumns({})
    setTransferStatusText('')
    setRowTargets({})
    clearPlan()
  }

  const selectProfile = (nextProfile: CatalogImportProfile) => {
    setProfile(nextProfile)
    setColumns(initialColumns(nextProfile, selectedSheet?.headers ?? []))
    setTransferStatusText('')
    setRowTargets({})
    clearPlan()
  }

  const inspect = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (file === null || busy !== null) {
      if (file === null) {
        setError({ action: 'inspect', error: new Error('Выберите файл XLS или XLSX.') })
      }
      return
    }
    setBusy('inspect')
    setError(null)
    try {
      const response = await apiClient.inspectCatalogImport(file)
      const firstSheet = response.sheets[0]
      if (firstSheet === undefined) {
        throw new Error('В книге не найден лист для импорта.')
      }
      setSheets(response.sheets)
      setSheetName(firstSheet.name)
      setColumns(initialColumns(profile, firstSheet.headers))
      setTransferStatusText('')
      setRowTargets({})
      clearPlan()
    } catch (exception) {
      reportError('inspect', exception)
    } finally {
      setBusy(null)
    }
  }

  const selectSheet = (nextSheetName: string) => {
    const nextSheet = sheets.find((sheet) => sheet.name === nextSheetName)
    setSheetName(nextSheetName)
    setColumns(initialColumns(profile, nextSheet?.headers ?? []))
    setRowTargets({})
    clearPlan()
  }

  const updateColumn = (field: string, header: string) => {
    setColumns((current) => ({ ...current, [field]: header }))
    clearPlan()
  }

  const updateTarget = (rowNumber: number, field: keyof CatalogImportRowTarget, value: string) => {
    applyKey.current = null
    setRowTargets((current) => ({
      ...current,
      [rowNumber]: { ...current[rowNumber], [field]: value }
    }))
    setTargetsDirty(true)
    setError(null)
  }

  const preview = async (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault()
    if (file === null || selectedSheet === null || busy !== null) {
      return
    }
    if (!mappingComplete) {
      setError({ action: 'preview', error: new Error('Укажите столбец файла для каждого обязательного поля.') })
      return
    }
    const transferStatuses = parseTransferStatuses(transferStatusText)
    if (transferStatuses.error) {
      setError({ action: 'preview', error: new Error(transferStatuses.error) })
      return
    }
    const mappedColumns = Object.fromEntries(
      Object.entries(columns).filter(([, header]) => header.length > 0)
    )
    const mapping: CatalogImportMapping = {
      columns: mappedColumns,
      rowTargets: cleanTargets(rowTargets),
      transferStatuses: transferStatuses.statuses,
      unassignedTeamId: profile === 'AGREEMENT' && unassignedTeamId.length > 0 ? unassignedTeamId : null
    }
    setBusy('preview')
    setError(null)
    try {
      const result = await apiClient.previewCatalogImport(file, profile, selectedSheet.name, mapping)
      const [nextPlan, nextJob] = await Promise.all([
        apiClient.getCatalogImport(result.importId),
        apiClient.getCatalogImportJob(result.jobId)
      ])
      applyKey.current = null
      setImportPlan(nextPlan)
      setJob(nextJob)
      setSelectedRowIds(nextPlan.rows.filter(eligibleRow).map((row) => row.id))
      setArchiveAgreementIds([])
      setTargetsDirty(false)
      setPendingApply(false)
      setOpenRows([])
      setMappingOpen(false)
    } catch (exception) {
      reportError('preview', exception)
    } finally {
      setBusy(null)
    }
  }

  const reloadPlan = async () => {
    if (importPlan === null || busy !== null) {
      return
    }
    setBusy('reload')
    setError(null)
    try {
      const nextPlan = await apiClient.getCatalogImport(importPlan.id)
      applyKey.current = null
      setImportPlan(nextPlan)
      setSelectedRowIds((current) => current.filter((id) => nextPlan.rows.some((row) => row.id === id && eligibleRow(row))))
      setPendingApply(false)
    } catch (exception) {
      reportError('reload', exception)
    } finally {
      setBusy(null)
    }
  }

  const toggleRow = (row: CatalogImportRow) => {
    if (!eligibleRow(row)) {
      return
    }
    applyKey.current = null
    setSelectedRowIds((current) => (
      current.includes(row.id) ? current.filter((id) => id !== row.id) : [...current, row.id]
    ))
  }

  const toggleArchive = (agreementId: string) => {
    applyKey.current = null
    setArchiveAgreementIds((current) => (
      current.includes(agreementId) ? current.filter((id) => id !== agreementId) : [...current, agreementId]
    ))
  }

  const apply = async () => {
    if (importPlan === null || (selectedRowIds.length === 0 && archiveAgreementIds.length === 0) || targetsDirty || busy !== null) {
      return
    }
    const idempotencyKey = applyKey.current ?? (applyKey.current = createIdempotencyKey())
    setBusy('apply')
    setError(null)
    try {
      const result = await apiClient.applyCatalogImport(importPlan.id, {
        version: importPlan.version,
        confirmedRowIds: selectedRowIds,
        archiveAgreementIds
      }, idempotencyKey)
      const [nextPlan, nextJob] = await Promise.all([
        apiClient.getCatalogImport(result.importId),
        apiClient.getCatalogImportJob(result.jobId)
      ])
      applyKey.current = null
      setImportPlan(nextPlan)
      setJob(nextJob)
      setSelectedRowIds(nextPlan.rows.filter(eligibleRow).map((row) => row.id))
      setArchiveAgreementIds([])
      setPendingApply(false)
    } catch (exception) {
      reportError('apply', exception)
    } finally {
      setBusy(null)
    }
  }

  const stepNow = importStepOf({
    hasSheets: sheets.length > 0 && selectedSheet !== null,
    planStatus: importPlan?.status ?? null,
    confirming: pendingApply
  })
  const hasSheets = sheets.length > 0 && selectedSheet !== null
  const applyDisabled = (selectedRowIds.length === 0 && archiveAgreementIds.length === 0) || targetsDirty || busy !== null

  return (
    <section className="admin-page import-page" aria-labelledby="catalog-import-title" aria-busy={busy !== null}>
      <div className="page-title">
        <h2 id="catalog-import-title">Импорт из Excel</h2>
        <HintButton label="О разделе">
          Загрузите XLS или XLSX с десятью полями ТЗ: столбцы с привычными названиями сопоставятся сами. ФИО менеджера ищется среди активных КАМ без учёта регистра, пробелов и «ё»; если совпадений несколько, выберите нужного КАМ прямо в строке протокола. Несколько ответственных от вуза в одной ячейке разделяйте «;» или переводом строки — каждый станет отдельным контактом. Изменения сохраняются только после проверки протокола и подтверждения.
        </HintButton>
      </div>

      <ol className="admin-steps" aria-label="Шаги импорта">
        {importSteps.map(({ step, label }) => (
          <li key={step} aria-current={step === stepNow ? 'step' : undefined} data-done={step < stepNow ? 'true' : undefined}>
            {step} {label}
          </li>
        ))}
      </ol>

      <div className="import-templates">
        <a href="/catalog-import-template.xlsx" download>
          Скачать шаблон файла (10 полей ТЗ, 3 примера)
        </a>
        <HintButton label="О шаблоне">
          ФИО менеджера в примерах шаблона вымышленное — замените его на реального КАМ своей команды, иначе строка не сопоставится.
        </HintButton>
        <a href="/vendor-contacts-template.xlsx" download>
          Скачать шаблон «Вендоры, продукты и контакты» (формат файла организатора, вымышленные контакты)
        </a>
      </div>

      <form className="import-block import-file" onSubmit={(event) => void inspect(event)}>
        <label>
          Файл XLS или XLSX
          <input
            type="file"
            accept=".xls,.xlsx,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={selectFile}
          />
        </label>
        <label>
          Профиль импорта
          <select value={profile} disabled={busy !== null} onChange={(event) => selectProfile(event.target.value as CatalogImportProfile)}>
            <option value="AGREEMENT">Каталог по ТЗ (10 полей)</option>
            <option value="DIRECTION_PROGRAM">Направления и программы</option>
            <option value="VENDOR_CONTACTS">Вендоры, продукты и контакты</option>
          </select>
        </label>
        <button type="submit" className={hasSheets ? 'button--secondary' : undefined} disabled={file === null || busy !== null}>
          {busy === 'inspect' ? 'Проверяем файл…' : 'Проверить файл'}
        </button>
      </form>

      {hasSheets && selectedSheet !== null && (
        <form className="import-block import-mapping" onSubmit={(event) => void preview(event)}>
          <div className="import-block__head">
            <h3>Сопоставление столбцов</h3>
            <HintButton label="О сопоставлении">
              Столбцы сопоставлены по названиям; проверьте выбор. Несопоставленное поле не меняет данные CRM.
            </HintButton>
            <span className="page-title__spacer" />
            {importPlan !== null && (
              <button type="button" className="button--secondary" aria-expanded={mappingOpen} onClick={() => setMappingOpen(!mappingOpen)}>
                {mappingOpen ? 'Свернуть' : 'Изменить сопоставление'}
              </button>
            )}
          </div>
          {mappingOpen && (
            <>
              <label className="import-sheet">
                Лист
                <select value={sheetName} disabled={busy !== null} onChange={(event) => selectSheet(event.target.value)}>
                  {sheets.map((sheet) => <option key={sheet.name} value={sheet.name}>{sheet.name}</option>)}
                </select>
              </label>
              <div className="import-grid">
                {basicFields.map((mappingField) => (
                  <ColumnSelect
                    key={mappingField.key}
                    mappingField={mappingField}
                    headers={selectedSheet.headers}
                    value={columns[mappingField.key] ?? ''}
                    disabled={busy !== null}
                    onChange={updateColumn}
                  />
                ))}
              </div>
              {profile === 'AGREEMENT' && (
                <details className="admin-more">
                  <summary>Дополнительно: ключи, контакты и словарь статусов</summary>
                  <p className="admin-form__hint">Ключи нужны, только если в файле есть собственные идентификаторы. Без них ключ строится из названия, а записи CRM без ключа находятся по уникальному названию.</p>
                  <div className="import-grid">
                    {advancedFields.map((mappingField) => (
                      <ColumnSelect
                        key={mappingField.key}
                        mappingField={mappingField}
                        headers={selectedSheet.headers}
                        value={columns[mappingField.key] ?? ''}
                        disabled={busy !== null}
                        onChange={updateColumn}
                      />
                    ))}
                  </div>
                  <label className="import-field">
                    Словарь статусов по передаче
                    <textarea
                      value={transferStatusText}
                      disabled={busy !== null}
                      rows={3}
                      placeholder={'передан=Передано\nв ожидании=В ожидании'}
                      onChange={(event) => {
                        setTransferStatusText(event.target.value)
                        clearPlan()
                      }}
                    />
                    <span className="admin-form__hint">Необязательно. Одна строка: «значение в файле=значение в CRM». Без словаря значение принимается как есть; со словарём неизвестное значение, которого нет и среди статусов CRM, станет ошибкой строки.</span>
                  </label>
                </details>
              )}
              {profile === 'AGREEMENT' && (
                <div className="import-field">
                  <div className="import-field__head">
                    <label htmlFor={unassignedId}>Команда для новых организаций без КАМ (необязательно)</label>
                    <HintButton label="О команде для новых организаций">
                      Новый вуз без ФИО менеджера появится в выбранной команде со статусом «Требует назначения»; КАМ назначит руководитель.
                    </HintButton>
                  </div>
                  <select
                    id={unassignedId}
                    value={unassignedTeamId}
                    disabled={busy !== null}
                    onChange={(event) => {
                      setUnassignedTeamId(event.target.value)
                      clearPlan()
                    }}
                  >
                    <option value="">Не создавать такие вузы</option>
                    {teams.map((team) => <option key={team.id} value={team.id}>{team.name}</option>)}
                  </select>
                </div>
              )}
              {!mappingComplete && (
                <p className="admin-form__hint" role="status">Заполните все поля со звёздочкой, чтобы построить предпросмотр.</p>
              )}
              <div className="admin-form__actions">
                <button
                  type="submit"
                  className={importPlan === null ? undefined : 'button--secondary'}
                  disabled={!mappingComplete || busy !== null}
                >
                  {busy === 'preview' ? 'Строим предпросмотр…' : 'Создать предпросмотр'}
                </button>
              </div>
            </>
          )}
        </form>
      )}

      {error !== null && (
        <div className="interaction-command-error" role="alert">
          <p>{errorMessage(error)}</p>
          {error.error instanceof ApiError && Object.entries(error.error.fieldErrors ?? {}).length > 0 && (
            <ul>
              {describeErrors(error.error.fieldErrors ?? {}).map((message) => <li key={message}>{message}</li>)}
            </ul>
          )}
          {error.error instanceof ApiError && <SupportDetails requestId={error.error.requestId} code={error.error.code} />}
        </div>
      )}

      {importPlan !== null && (
        <section className="import-block import-preview" aria-labelledby="catalog-import-preview-title">
          <div className="import-block__head">
            <h3 id="catalog-import-preview-title">Протокол предпросмотра</h3>
            <span className="import-block__meta">Версия {importPlan.version}; статус: {importPlan.status === 'APPLIED' ? 'применён' : 'ожидает подтверждения'}.</span>
            <span className="page-title__spacer" />
            <button type="button" className="button--secondary" disabled={busy !== null} onClick={() => void reloadPlan()}>
              {busy === 'reload' ? 'Обновляем…' : 'Обновить состояние'}
            </button>
          </div>
          {job !== null && (
            <>
              <p className="import-block__job" role="status">
                {jobActionLabel(job.action)}: {job.status === 'SUCCEEDED' ? 'завершено' : 'не выполнено'}{job.result ? `; ${job.result}` : ''}.
              </p>
              <details className="support-details">
                <summary>Подробнее для поддержки</summary>
                <p className="request-id">Идентификатор задания для поддержки: {job.id}</p>
              </details>
            </>
          )}
          {targetsDirty && (
            <div className="import-warning" role="status">
              <p>Выбор КАМ или явные идентификаторы изменены. Перестройте предпросмотр до применения строк.</p>
              <button type="button" disabled={busy !== null || importPlan.status === 'APPLIED'} onClick={() => void preview()}>
                {busy === 'preview' ? 'Строим предпросмотр…' : 'Перестроить предпросмотр'}
              </button>
            </div>
          )}
          <table className="admin-table import-table" aria-label="Протокол предпросмотра">
            <thead>
              <tr>
                <th scope="col">Применить</th>
                <th scope="col">Строка</th>
                <th scope="col">Результат</th>
                <th scope="col">Сообщения</th>
                <th scope="col">Было и станет</th>
              </tr>
            </thead>
            <tbody>
              {importPlan.rows.map((row) => (
                <Fragment key={row.id}>
                  <tr>
                    <td data-label="Применить">
                      {eligibleRow(row) ? (
                        <input
                          type="checkbox"
                          aria-label={`Применить строку ${row.rowNumber}`}
                          checked={selectedRowIds.includes(row.id)}
                          disabled={busy !== null || targetsDirty || importPlan.status === 'APPLIED'}
                          onChange={() => toggleRow(row)}
                        />
                      ) : '—'}
                    </td>
                    <td data-label="Строка">{row.sheetName}, {row.rowNumber}</td>
                    <td data-label="Результат">
                      <span className={`admin-chip${rowStatusTone[row.status]}`}>{rowStatusLabel(row.status)}</span>
                    </td>
                    <td data-label="Сообщения" className="import-table__messages">
                      {Object.entries(row.fieldErrors).length === 0 ? '—' : describeErrors(row.fieldErrors).join('; ')}
                      {row.managerCandidates.length > 0 && importPlan.status === 'PREVIEWED' && (
                        <label className="import-candidate">
                          Выберите КАМ для строки {row.rowNumber}
                          <select
                            value={rowTargets[row.rowNumber]?.managerProfileId ?? ''}
                            disabled={busy !== null}
                            onChange={(event) => updateTarget(row.rowNumber, 'managerProfileId', event.target.value)}
                          >
                            <option value="">Не выбран</option>
                            {row.managerCandidates.map((candidate) => (
                              <option key={candidate.profileId} value={candidate.profileId}>
                                {candidate.displayName} — {candidate.teamName ?? 'без команды'}, вузов: {candidate.organizationCount}
                              </option>
                            ))}
                          </select>
                        </label>
                      )}
                    </td>
                    <td data-label="Подробности">
                      <button
                        type="button"
                        className="admin-table__toggle"
                        aria-expanded={openRows.includes(row.id)}
                        onClick={() => toggleOpenRow(row.id)}
                      >
                        Было и станет
                      </button>
                    </td>
                  </tr>
                  {openRows.includes(row.id) && (
                    <tr className="admin-table__detail">
                      <td colSpan={5}>
                        <dl className="import-values">
                          <dt>Было</dt>
                          <dd>{describeValues(row.oldValues)}</dd>
                          <dt>Станет</dt>
                          <dd>{describeValues(row.newValues)}</dd>
                        </dl>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>

          {profile === 'AGREEMENT' && importPlan.status === 'PREVIEWED' && (
            <details className="admin-more import-targets">
              <summary>Дополнительно: явные UUID для строк</summary>
              <p className="admin-form__hint">Нужны только для неоднозначных случаев: несколько КАМ с одинаковым ФИО, несколько взаимодействий вуза с одним ПО или привязка строки к конкретной записи CRM. Явный UUID важнее ключа и названия.</p>
              <div>
                {importPlan.rows.map((row) => (
                  <fieldset key={row.id}>
                    <legend>Строка {row.rowNumber}</legend>
                    {targetFields.map((field) => (
                      <label key={field.key}>
                        {field.label}
                        <input
                          value={rowTargets[row.rowNumber]?.[field.key] ?? ''}
                          disabled={busy !== null}
                          inputMode="text"
                          onChange={(event) => updateTarget(row.rowNumber, field.key, event.target.value)}
                        />
                      </label>
                    ))}
                  </fieldset>
                ))}
              </div>
            </details>
          )}

          {importPlan.status === 'PREVIEWED' && importPlan.missingRecords.length > 0 && (
            <div className="import-part" role="group" aria-labelledby="catalog-import-missing-title">
              <h3 id="catalog-import-missing-title">Записи вузов, которых нет в загруженном реестре</h3>
              <p className="admin-form__hint">По умолчанию записи остаются. Отметьте те, что нужно архивировать: договор скроется из действующих, история сохранится, повторная загрузка с этой записью вернёт её.</p>
              <ul>
                {importPlan.missingRecords.map((record) => (
                  <li key={record.agreementId}>
                    <label className="checkbox-field">
                      <input
                        type="checkbox"
                        checked={archiveAgreementIds.includes(record.agreementId)}
                        disabled={busy !== null || targetsDirty}
                        onChange={() => toggleArchive(record.agreementId)}
                      />
                      Архивировать: {record.organizationName} — {record.vendorName}, {record.productName}
                      {record.contractNumber === null ? '' : `, договор № ${record.contractNumber}`} (взаимодействие «{record.interactionTitle}»)
                    </label>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {importPlan.status === 'PREVIEWED' && (
            <div className="import-part import-apply" role="group" aria-labelledby="catalog-import-apply-title">
              <h3 id="catalog-import-apply-title">Применение</h3>
              <p>
                Выбрано строк: {selectedRowIds.length}. Применяются только строки без ошибок и конфликтов.
                {archiveAgreementIds.length > 0 ? ` Будет архивировано записей: ${archiveAgreementIds.length}.` : ''}
              </p>
              <button
                type="button"
                className={targetsDirty || pendingApply ? 'button--secondary' : undefined}
                disabled={applyDisabled}
                onClick={() => {
                  setPendingApply(true)
                  setError(null)
                }}
              >
                Подтвердить применение
              </button>
            </div>
          )}
        </section>
      )}

      {pendingApply && importPlan !== null && (
        <section className="import-block import-confirmation" role="alertdialog" aria-labelledby="catalog-import-confirmation-title">
          <h3 id="catalog-import-confirmation-title">Применить выбранные строки?</h3>
          <p>
            Будет сохранено строк: {selectedRowIds.length}.
            {archiveAgreementIds.length > 0 ? ` Будет архивировано записей, которых нет в реестре: ${archiveAgreementIds.length}.` : ''}
            {' '}Сервер повторно проверит версию и ключ операции.
          </p>
          <div className="admin-form__actions">
            <button type="button" className="button--secondary" disabled={busy === 'apply'} onClick={() => setPendingApply(false)}>Отмена</button>
            {!applyConflict && (
              <button type="button" disabled={busy !== null} onClick={() => void apply()}>
                {busy === 'apply' ? 'Применяем…' : 'Применить'}
              </button>
            )}
          </div>
        </section>
      )}
    </section>
  )
}
