import { useId } from 'react'
import type { Me, ReportKind } from '../../shared/api/client'
import { organizationTypeLabels } from '../organizations/OrganizationForm'
import { InfoTip, type FilterOption, type SelectedValue } from './ReportControls'
import { agreementValues, ReportAgreementFilter } from './ReportAgreementFilter'
import { filterVisibility } from './reportFilterModel'
import { ChoiceGroup, MultiSelectGroup } from './reportFilterUi'
import {
  availableDemandSorts,
  demandSorts,
  eventTypeTitles,
  eventTypes,
  managerAtOptions,
  managerWorkChangeLabels,
  managerWorkChanges,
  maxDaysOnStage,
  organizationTypeFilters,
  reportFlagLabels,
  reportFlags,
  reportWorkStatusLabels,
  reportWorkStatuses,
  type ReportSelection
} from './reportSelection'

export type FilterOptions = {
  organizations: FilterOption[]
  directions: FilterOption[]
  programs: FilterOption[]
  products: FilterOption[]
  kinds: FilterOption[]
  managers: FilterOption[]
  stages: FilterOption[]
  vendors: FilterOption[]
}

export type OptionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; options: FilterOptions }
  | { kind: 'failed'; error: unknown }

const demandSortLabels: Record<(typeof demandSorts)[number], string> = {
  DEMAND_INDEX: 'по месту (индекс востребованности)',
  APPLICATIONS: 'по заявкам',
  PAID_ORDERS: 'по оплаченным заявкам',
  PAID_STREAMS: 'по потокам с оплатами',
  PARTICIPANTS: 'по обучающимся',
  LEARNERS_COMPLETED: 'по завершившим',
  PARALLEL_RUNS: 'по параллельным потокам'
}

const periodBasisOptions: { value: ReportSelection['periodBasis']; label: string }[] = [
  { value: 'CREATED', label: 'Созданные в периоде' },
  { value: 'ACTIVITY', label: 'С событиями в периоде' },
  { value: 'ACTIVE', label: 'Активные в периоде' }
]

const stageLegends: Record<ReportKind, string> = {
  PORTFOLIO: 'Этап',
  EVENTS: 'Этап события',
  SNAPSHOT: 'Этап на дату',
  DURATION: 'Этап',
  DEMAND: 'Этап',
  AGREEMENTS: 'Этап',
  MANAGER_WORK: 'Этап на конец периода'
}

export const managerLegends: Record<ReportKind, string> = {
  PORTFOLIO: 'Ответственный',
  EVENTS: 'Ответственный на момент события',
  SNAPSHOT: 'Ответственный на дату',
  DURATION: 'Ответственный (текущий КАМ вуза)',
  DEMAND: 'Ответственный',
  AGREEMENTS: 'Ответственный за мероприятие',
  MANAGER_WORK: 'Менеджер'
}

const eventTypeOptions = (role: Me['role']): FilterOption[] => eventTypes
  .filter((type) => role === 'LEADER' || role === 'MANAGEMENT' || type !== 'ASSIGNMENT')
  .map((type) => ({ id: type, label: eventTypeTitles[type] }))

const organizationTypeOptions = organizationTypeFilters.map((type) => ({
  id: type,
  label: type === '' ? 'Все типы' : organizationTypeLabels[type]
}))

const labelOf = (options: FilterOption[], id: string) => options.find((option) => option.id === id)?.label ?? id

type FieldsProps = {
  role: Me['role']
  selection: ReportSelection
  options: FilterOptions
  update: (patch: Partial<ReportSelection>) => void
}

// Содержимое панели «Фильтры»: набор и порядок отборов прежние, лишние для вида отчёта скрыты (filterVisibility).
export const ReportFilterFields = ({ role, selection, options, update }: FieldsProps) => {
  const kind = selection.kind
  const visible = filterVisibility(kind)
  const basisId = useId()
  const daysId = useId()
  const sortId = useId()
  return (
    <>
      <ChoiceGroup
        label="Тип организации"
        options={organizationTypeOptions}
        value={selection.organizationType}
        onChange={(organizationType) => update({ organizationType })}
      />
      <MultiSelectGroup
        label="Вузы"
        options={options.organizations}
        selected={selection.organizationIds}
        onChange={(organizationIds) => update({ organizationIds })}
      />
      {visible.programs && (
        <>
          <MultiSelectGroup
            label="ИТ-направления"
            options={options.directions}
            selected={selection.directionIds}
            onChange={(directionIds) => update({ directionIds })}
            includeNone={selection.includeNoDirection}
            onIncludeNoneChange={(includeNoDirection) => update({ includeNoDirection })}
          />
          <MultiSelectGroup
            label="ИТ-программы"
            options={options.programs}
            selected={selection.programIds}
            onChange={(programIds) => update({ programIds })}
            includeNone={selection.includeNoProgram}
            onIncludeNoneChange={(includeNoProgram) => update({ includeNoProgram })}
          />
        </>
      )}
      {visible.products && (
        <MultiSelectGroup
          label="ИТ-продукты"
          options={options.products}
          selected={selection.productIds}
          onChange={(productIds) => update({ productIds })}
          includeNone={selection.includeNoProduct}
          onIncludeNoneChange={(includeNoProduct) => update({ includeNoProduct })}
        />
      )}
      {visible.kinds && options.kinds.length > 0 && (
        <MultiSelectGroup
          label="Виды взаимодействия"
          options={options.kinds}
          selected={selection.kindIds}
          onChange={(kindIds) => update({ kindIds })}
        />
      )}
      <MultiSelectGroup
        label={managerLegends[kind]}
        options={options.managers}
        selected={selection.managerIds}
        onChange={(managerIds) => update({ managerIds })}
        includeNone={selection.includeNoManager}
        onIncludeNoneChange={(includeNoManager) => update({ includeNoManager })}
      />
      {visible.stages && (
        <MultiSelectGroup
          label={stageLegends[kind]}
          options={options.stages}
          selected={selection.stages}
          onChange={(stages) => update({ stages })}
        />
      )}
      {visible.workStatuses && (
        <MultiSelectGroup
          label="Статус взаимодействия"
          options={reportWorkStatuses.map((status) => ({ id: status, label: reportWorkStatusLabels[status] }))}
          selected={selection.workStatuses}
          onChange={(ids) => update({ workStatuses: reportWorkStatuses.filter((status) => ids.includes(status)) })}
        />
      )}
      {visible.flags && (
        <MultiSelectGroup
          label="Отметки взаимодействия"
          note="Остаются взаимодействия, у которых есть все выбранные отметки одновременно."
          options={reportFlags.map((flag) => ({ id: flag, label: reportFlagLabels[flag] }))}
          selected={selection.flags}
          onChange={(ids) => update({ flags: reportFlags.filter((flag) => ids.includes(flag)) })}
        />
      )}
      {visible.eventTypes && (
        <MultiSelectGroup
          label="Вид события"
          options={eventTypeOptions(role)}
          selected={selection.eventTypes}
          onChange={(ids) => update({ eventTypes: eventTypes.filter((type) => ids.includes(type)) })}
        />
      )}
      {visible.change && (
        <ChoiceGroup
          label="Показать"
          options={managerWorkChanges.map((id) => ({ id, label: managerWorkChangeLabels[id] }))}
          value={selection.change}
          onChange={(change) => update({ change })}
        />
      )}
      {visible.change && (
        <ChoiceGroup
          label="Менеджер"
          options={managerAtOptions}
          value={selection.managerAt}
          onChange={(managerAt) => update({ managerAt })}
        />
      )}
      {visible.agreement && (
        <ReportAgreementFilter value={selection.agreement} vendors={options.vendors} onChange={(agreement) => update({ agreement })} />
      )}
      {visible.periodBasis && (
        <div className="report-field">
          <span className="report-field__label">
            <label htmlFor={basisId}>Отбор по периоду</label>
            <InfoTip label="Отбор по периоду">
              «Созданные в периоде» — по дате создания взаимодействия; «С событиями в периоде» — были любые события;
              «Активные в периоде» — созданные до конца периода и не завершённые к его началу.
            </InfoTip>
          </span>
          <select
            id={basisId}
            value={selection.periodBasis}
            onChange={(event) => update({
              periodBasis: periodBasisOptions.find((option) => option.value === event.target.value)?.value ?? 'CREATED'
            })}
          >
            {periodBasisOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </div>
      )}
      {visible.minDays && (
        <div className="report-field">
          <span className="report-field__label">
            <label htmlFor={daysId}>На этапе дольше, дней</label>
            <InfoTip label="На этапе дольше">
              Оставляет взаимодействия, которые стоят на текущем этапе не меньше указанного числа полных суток. Пустое поле — любой срок.
            </InfoTip>
          </span>
          <input
            id={daysId}
            type="number"
            inputMode="numeric"
            min={1}
            max={maxDaysOnStage}
            step={1}
            value={selection.minDaysOnStage}
            placeholder="любой срок"
            onChange={(event) => update({ minDaysOnStage: event.target.value })}
          />
        </div>
      )}
      {visible.sort && (
        <div className="report-field">
          <label htmlFor={sortId}>Сортировка</label>
          <select
            id={sortId}
            value={selection.sortBy}
            onChange={(event) => update({ sortBy: availableDemandSorts().find((sort) => sort === event.target.value) ?? 'DEMAND_INDEX' })}
          >
            {availableDemandSorts().map((sort) => <option key={sort} value={sort}>{demandSortLabels[sort]}</option>)}
          </select>
        </div>
      )}
    </>
  )
}

// Выбранные значения чипами со «×» — они же показывают, что именно отобрано, когда панель закрыта.
export const activeFilterChips = (
  selection: ReportSelection,
  options: FilterOptions,
  update: (patch: Partial<ReportSelection>) => void
): SelectedValue[] => {
  const kind = selection.kind
  const visible = filterVisibility(kind)
  return [
    ...(selection.organizationType === '' ? [] : [{
      key: 'type',
      text: `Тип: ${organizationTypeLabels[selection.organizationType]}`,
      onRemove: () => update({ organizationType: '' })
    }]),
    ...selection.organizationIds.map((id) => ({
      key: `org-${id}`,
      text: labelOf(options.organizations, id),
      onRemove: () => update({ organizationIds: selection.organizationIds.filter((item) => item !== id) })
    })),
    ...(visible.programs ? [
      ...(selection.includeNoDirection ? [{ key: 'dir-none', text: 'Направление: не указано', onRemove: () => update({ includeNoDirection: false }) }] : []),
      ...selection.directionIds.map((id) => ({
        key: `dir-${id}`,
        text: labelOf(options.directions, id),
        onRemove: () => update({ directionIds: selection.directionIds.filter((item) => item !== id) })
      })),
      ...(selection.includeNoProgram ? [{ key: 'prog-none', text: 'Программа: не указано', onRemove: () => update({ includeNoProgram: false }) }] : []),
      ...selection.programIds.map((id) => ({
        key: `prog-${id}`,
        text: labelOf(options.programs, id),
        onRemove: () => update({ programIds: selection.programIds.filter((item) => item !== id) })
      }))
    ] : []),
    ...(visible.products ? [
      ...(selection.includeNoProduct ? [{ key: 'prod-none', text: 'Продукт: не указано', onRemove: () => update({ includeNoProduct: false }) }] : []),
      ...selection.productIds.map((id) => ({
        key: `prod-${id}`,
        text: labelOf(options.products, id),
        onRemove: () => update({ productIds: selection.productIds.filter((item) => item !== id) })
      }))
    ] : []),
    ...(visible.kinds ? selection.kindIds.map((id) => ({
      key: `kind-${id}`,
      text: `Вид: ${labelOf(options.kinds, id)}`,
      onRemove: () => update({ kindIds: selection.kindIds.filter((item) => item !== id) })
    })) : []),

    ...(selection.includeNoManager ? [{ key: 'man-none', text: 'Ответственный: не указан', onRemove: () => update({ includeNoManager: false }) }] : []),
    ...selection.managerIds.map((id) => ({
      key: `man-${id}`,
      text: labelOf(options.managers, id),
      onRemove: () => update({ managerIds: selection.managerIds.filter((item) => item !== id) })
    })),
    ...(visible.stages ? selection.stages.map((stage) => ({
      key: `stage-${stage}`,
      text: `Этап: ${stage}`,
      onRemove: () => update({ stages: selection.stages.filter((item) => item !== stage) })
    })) : []),
    ...(visible.workStatuses ? selection.workStatuses.map((status) => ({
      key: `status-${status}`,
      text: reportWorkStatusLabels[status],
      onRemove: () => update({ workStatuses: selection.workStatuses.filter((item) => item !== status) })
    })) : []),
    ...(visible.flags ? selection.flags.map((flag) => ({
      key: `flag-${flag}`,
      text: reportFlagLabels[flag],
      onRemove: () => update({ flags: selection.flags.filter((item) => item !== flag) })
    })) : []),
    ...(visible.eventTypes ? selection.eventTypes.map((type) => ({
      key: `event-${type}`,
      text: eventTypeTitles[type],
      onRemove: () => update({ eventTypes: selection.eventTypes.filter((item) => item !== type) })
    })) : []),
    ...(visible.change && selection.change !== '' ? [{
      key: 'change',
      text: managerWorkChangeLabels[selection.change],
      onRemove: () => update({ change: '' })
    }] : []),
    ...(visible.change && selection.managerAt === 'CURRENT' ? [{
      key: 'manager-at',
      text: 'Менеджер: текущий',
      onRemove: () => update({ managerAt: 'PERIOD_END' })
    }] : []),
    ...(visible.periodBasis && selection.periodBasis !== 'CREATED' ? [{
      key: 'basis',
      text: periodBasisOptions.find((option) => option.value === selection.periodBasis)?.label ?? '',
      onRemove: () => update({ periodBasis: 'CREATED' })
    }] : []),
    ...(visible.minDays && selection.minDaysOnStage !== '' ? [{
      key: 'min-days',
      text: `На этапе дольше ${selection.minDaysOnStage} дн.`,
      onRemove: () => update({ minDaysOnStage: '' })
    }] : []),
    ...(visible.agreement ? agreementValues(selection.agreement, options.vendors, (agreement) => update({ agreement })) : [])
  ]
}
