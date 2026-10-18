import type { Interaction, Organization } from '../../shared/api/client'
import { agreementStatusLabels, daysInStatus } from '../agreements/agreementModel'
import { formatDate, type AgreementSummary } from '../agreements/agreementsApi'
import { AgreementTermMark } from '../agreements/agreementUi'
import { formatMoscowDateTime } from './moscowTime'
import { daysLabel } from './RouteDisclosure'
import type { AgreementFact } from './workCardModel'

type WorkFactsProps = {
  interaction: Interaction
  organization?: Organization
  stageNumber: number
  daysOnStage: number | null
  schedule: { className: string; label: string }
  agreement: AgreementFact<AgreementSummary> | 'loading' | 'failed'
  canEdit: boolean
  onCreateAgreement: () => void
}

const productsLabel = (count: number) => {
  const lastTwo = count % 100
  const last = count % 10
  if (last === 1 && lastTwo !== 11) {
    return `${count} продукт`
  }
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? `${count} продукта` : `${count} продуктов`
}

export const WorkFacts = ({ interaction, organization, stageNumber, daysOnStage, schedule, agreement, canEdit, onCreateAgreement }: WorkFactsProps) => {
  const products = interaction.productAgreements.map((item) => item.productName)
  const deputy = organization?.deputyManagerName
  const hasStep = (interaction.nextAction ?? '').trim() !== ''
  return (
    <dl className="work-facts">
      <div className="work-facts__item">
        <dt>Этап</dt>
        <dd>
          <span><strong>{stageNumber} из {interaction.stages.length}</strong> · {interaction.currentStageName}</span>
          {daysOnStage !== null && <span className="work-facts__sub">{daysLabel(daysOnStage)} на этапе</span>}
        </dd>
      </div>
      <div className={`work-facts__item work-facts__step ${schedule.className}`}>
        <dt>Следующий шаг</dt>
        <dd>
          {hasStep ? interaction.nextAction : 'не задан'}
          {hasStep && <span className="work-facts__sub">{schedule.label}</span>}
        </dd>
      </div>
      <div className="work-facts__item">
        <dt>Ответственный</dt>
        <dd>
          {organization === undefined ? 'КАМ вуза' : organization.ownerManagerName ?? 'не назначен'}
          {deputy && <span className="work-facts__sub">Замещает {deputy}{organization?.deputyEndsOn ? ` до ${formatDate(organization.deputyEndsOn)}` : ''}</span>}
          {organization?.teamName && <span className="work-facts__sub">{organization.teamName}</span>}
        </dd>
      </div>
      <div className="work-facts__item">
        <dt>Договор с вузом</dt>
        <dd>
          {agreement === 'loading' && 'Загружаем…'}
          {agreement === 'failed' && 'Не удалось загрузить'}
          {typeof agreement === 'object' && agreement.kind === 'none' && 'Нет'}
          {typeof agreement === 'object' && agreement.kind === 'create' && (
            <>
              Нет
              {canEdit && <button type="button" className="button--secondary work-facts__action" onClick={onCreateAgreement}>Создать договор</button>}
            </>
          )}
          {typeof agreement === 'object' && agreement.kind === 'agreement' && (
            <>
              <span>
                <a href={`#/organizations/${agreement.agreement.organizationId}?agreement=${agreement.agreement.id}`}>№ {agreement.agreement.number}</a>
                {' · '}{agreementStatusLabels[agreement.agreement.status]}
                {' '}<AgreementTermMark agreement={agreement.agreement} />
              </span>
              <span className="work-facts__sub">{daysLabel(daysInStatus(agreement.agreement.statusChangedAt, Date.now()))} в статусе</span>
            </>
          )}
        </dd>
      </div>
      <div className="work-facts__item">
        <dt>Программа</dt>
        <dd>
          <span>
            {interaction.program === null ? 'не указана' : interaction.program.name}
            {interaction.program?.archived && <span className="interaction-archived">Архивирована</span>}
          </span>
          <span className="work-facts__sub" title={products.join(', ')}>
            {products.length === 0 ? 'продукты не указаны' : `${productsLabel(products.length)}: ${products.join(', ')}`}
            {' · контакт '}{interaction.lastContactAt === null ? 'не указан' : formatMoscowDateTime(interaction.lastContactAt)}
          </span>
        </dd>
      </div>
    </dl>
  )
}
