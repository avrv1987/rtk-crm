import type { InteractionMarks } from '../../shared/api/client'
import { markBadges } from './workMarks'
import './workCard.css'

export const InteractionMarkBadges = ({ marks }: { marks: InteractionMarks }) => {
  const badges = markBadges(marks)
  if (badges.length === 0) {
    return null
  }
  return (
    <span className="work-marks">
      {badges.map((badge) => (
        <span key={badge.key} className={`status status--${badge.tone}`} title={badge.title}>{badge.label}</span>
      ))}
    </span>
  )
}
