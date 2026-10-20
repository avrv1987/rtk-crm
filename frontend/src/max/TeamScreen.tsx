import { errorText, type MaxApi, type Problem } from './maxApi'
import { managerChips, moscowClock, plural, teamOrder } from './model'
import type { Bridge } from './startup'
import { Banner, Chips, OpenCrm } from './ui'
import { useLoad } from './useLoad'

/** «Команда» руководителя: только просмотр. Итоги сверху, ниже КАМ — сначала те, у кого горит; нули скрыты. */
export const TeamScreen = ({ api, bridge, refreshKey, onFatal }: {
  api: MaxApi
  bridge: Bridge | undefined
  refreshKey: number
  onFatal: (problem: Problem) => void
}) => {
  const team = useLoad(`team:${refreshKey}`, (signal) => api.control(signal), onFatal)
  if (team.status === 'loading') {
    return (
      <ul className="mx-list" aria-busy="true" aria-label="Загружаем">
        {[0, 1, 2].map((index) => <li key={index} className="mx-manager"><span className="mx-sk mx-sk--w50" /><span className="mx-sk mx-sk--w85" /></li>)}
      </ul>
    )
  }
  if (team.status === 'error') {
    return <Banner tone="error" action="Повторить" onAction={team.reload}>Не удалось обновить показатели команды. {errorText(team.error)}</Banner>
  }
  const { managers, unassignedOrganizations, stuckDays, calculatedAt } = team.data
  const sum = (pick: (manager: (typeof managers)[number]) => number) => managers.reduce((total, manager) => total + pick(manager), 0)
  const totals: [string, number, boolean][] = [
    ['Просрочено', sum((manager) => manager.overdue), true],
    ['В риске', sum((manager) => manager.atRisk), true],
    ['Застряли', sum((manager) => manager.stuck), false],
    ['Без шага', sum((manager) => manager.withoutNextStep), false],
    ['Вузов', sum((manager) => manager.organizations), false]
  ]
  return (
    <>
      <p className="mx-sub">Только просмотр · данные на {moscowClock(calculatedAt)}</p>
      {unassignedOrganizations > 0 && (
        <Banner tone="warn"><b>{plural(unassignedOrganizations, ['вуз', 'вуза', 'вузов'])} без КАМ.</b> Назначить ответственного можно в CRM.</Banner>
      )}
      <div className="mx-tiles" aria-label="Итого по команде">
        {totals.map(([label, count, hot]) => (
          <div key={label} className={`mx-tile${count === 0 ? ' mx-tile--zero' : ''}${hot && count > 0 ? ' mx-tile--hot' : ''}`}>
            <span className="mx-tile__count">{count}</span>
            <span className="mx-tile__label">{label}</span>
          </div>
        ))}
      </div>
      {managers.length === 0
        ? <p className="mx-sub">В команде пока нет КАМ.</p>
        : (
          <>
            <h2 className="mx-section">Сначала — у кого горит</h2>
            <ul className="mx-list">
              {teamOrder(managers).map((manager) => (
                <li key={manager.managerId ?? 'none'} className="mx-manager">
                  <b>{manager.managerName ?? 'Без ответственного'}</b>
                  <span className="mx-sub mx-sub--tight">{plural(manager.organizations, ['вуз', 'вуза', 'вузов'])}</span>
                  <span className="mx-chips"><Chips chips={managerChips(manager)} /></span>
                </li>
              ))}
            </ul>
          </>
        )}
      <p className="mx-hint">«Застряли» — на одном этапе дольше {plural(stuckDays, ['дня', 'дней', 'дней'])}.</p>
      <OpenCrm bridge={bridge} hash="#/control">Открыть «Контроль» в CRM ↗</OpenCrm>
    </>
  )
}
