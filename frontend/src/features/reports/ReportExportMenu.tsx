import { ActionMenu } from '../../shared/ui/ActionMenu'
import { terms } from '../../shared/ui/terms'

export type ExportItem = { label: string; onSelect: () => void }

// «Выгрузить ▾»: все форматы в одном меню; пока выбор некорректен или идёт заказ — неактивная кнопка.
export const ExportMenu = ({ items, disabled = false, label = terms.export }: { items: ExportItem[]; disabled?: boolean; label?: string }) => (
  disabled ? (
    <button type="button" className="button--secondary report-export" disabled>{label} ▾</button>
  ) : (
    <ActionMenu className="report-export" label={label} toggle={<>{label} ▾</>} items={items} />
  )
)
