export type Modules = {
  enrolment: boolean
  partner: boolean
  /** Модуль MAX; без поля (старый сервер) считается выключенным. */
  max?: boolean
  /** Модуль MCP (ИИ-агенты); без поля считается выключенным. */
  mcp?: boolean
  /** Модуль почты; без поля (старый сервер) считается выключенным. */
  mail?: boolean
}

let current: Modules = { enrolment: false, partner: false }

export const setModules = (modules: Modules): void => {
  current = modules
}

export const getModules = (): Modules => current

export const paymentColumns: readonly string[] = ['PAID_ORDERS', 'PAID_STREAMS']

export const withoutPaymentColumns = <T extends string>(columns: readonly T[], modules: Modules): T[] => (
  modules.enrolment ? [...columns] : columns.filter((column) => !paymentColumns.includes(column))
)

export const sectionAvailable = (section: string, modules: Modules): boolean => (
  section === 'enrolment' ? modules.enrolment : section === 'partner' ? modules.partner : true
)
