import type { ReactNode } from 'react'

export const EmptyState = ({ text, action }: { text: string; action?: ReactNode }) => (
  <div className="empty-state">
    <p>{text}</p>
    {action}
  </div>
)
