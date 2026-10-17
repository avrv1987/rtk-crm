import { useState } from 'react'
import { ActionMenu } from './ActionMenu'
import { ConfirmButton } from './ConfirmButton'
import { EmptyState } from './EmptyState'
import { FilterButton, FilterPanel } from './FilterPanel'
import { HintButton } from './HintButton'
import { Segmented } from './Segmented'
import { StatTiles } from './StatTiles'
import { Toast } from './Toast'
import { terms } from './terms'

type Period = 'ALL' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR'
type Tile = 'OVERDUE' | 'THIS_WEEK' | 'NO_NEXT_STEP'

export const SharedUiExamples = () => {
  const [period, setPeriod] = useState<Period>('ALL')
  const [tile, setTile] = useState<Tile | null>(null)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  return (
    <section aria-labelledby="shared-ui-examples">
      <div className="page-title">
        <h2 id="shared-ui-examples">Контроль</h2>
        <HintButton label="Что на этом экране">
          Кто, с каким вузом и на каком этапе. Плитки отбирают строки таблицы, второе нажатие снимает отбор.
        </HintButton>
        <Segmented
          label="Период"
          value={period}
          onChange={setPeriod}
          options={[
            { value: 'ALL', label: 'Без ограничения' },
            { value: 'WEEK', label: 'Неделя' },
            { value: 'MONTH', label: 'Месяц' },
            { value: 'QUARTER', label: 'Квартал' },
            { value: 'YEAR', label: 'Год' }
          ]}
        />
        <FilterButton count={2} onClick={() => setFiltersOpen(true)} />
        <ActionMenu
          label={terms.moreActions}
          items={[
            { label: `${terms.export} XLSX`, onSelect: () => setMessage('Файл заказан. Он появится в разделе «Отчёты» → «Выгрузки».') },
            { label: 'Все форматы — в отчёте', href: '#/reports/manager-work' },
            { label: 'Свернуть всех', onSelect: () => undefined, separated: true }
          ]}
        />
      </div>
      <StatTiles
        label="Отбор по показателям"
        selected={tile}
        onSelect={setTile}
        tiles={[
          { key: 'OVERDUE', label: 'Просрочено', value: 3, tone: 'danger' },
          { key: 'THIS_WEEK', label: 'Срок на этой неделе', value: 5, tone: 'warning' },
          { key: 'NO_NEXT_STEP', label: terms.noStepOrDue, value: 0 }
        ]}
      />
      <EmptyState text="Взаимодействий нет. Начните новое взаимодействие." action={<button type="button">{terms.newInteraction}</button>} />
      <ConfirmButton
        label="Удалить этап"
        title="Удалить этап?"
        description="Этап исчезнет из маршрута этой карточки. Шаблон и другие карточки не изменятся."
        onConfirm={() => setMessage('Этап удалён.')}
      />
      <FilterPanel open={filtersOpen} onClose={() => setFiltersOpen(false)} onReset={() => setTile(null)}>
        <label>
          Этап
          <select defaultValue="">
            <option value="">Все этапы</option>
          </select>
        </label>
      </FilterPanel>
      <Toast message={message} onClose={() => setMessage(null)} />
    </section>
  )
}
