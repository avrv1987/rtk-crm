import type { InteractionMarks } from '../../shared/api/client'
import { markBadges } from './workMarks'
import '../issues/issues.css'
import './workCard.css'

type InteractionMarkBadgesProps = {
  marks: InteractionMarks
  issuesHref?: string
  onOpenIssues?: () => void
}

export const InteractionMarkBadges = ({ marks, issuesHref, onOpenIssues }: InteractionMarkBadgesProps) => {
  const badges = markBadges(marks)
  if (badges.length === 0) {
    return null
  }
  return (
    <span className="work-marks">
      {badges.map((badge) => {
        const className = `status status--${badge.tone}`
        if (badge.issues && onOpenIssues !== undefined) {
          return (
            <button key={badge.key} type="button" className={`${className} work-marks__issue`} title={badge.title} onClick={onOpenIssues}>
              {badge.label}
            </button>
          )
        }
        if (badge.issues && issuesHref !== undefined) {
          return <a key={badge.key} className={`${className} work-marks__issue`} title={badge.title} href={issuesHref}>{badge.label}</a>
        }
        return <span key={badge.key} className={className} title={badge.title}>{badge.label}</span>
      })}
    </span>
  )
}
