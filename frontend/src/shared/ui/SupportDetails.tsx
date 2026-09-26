type SupportDetailsProps = {
  requestId?: string
  code?: string
}

export const SupportDetails = ({ requestId, code }: SupportDetailsProps) => {
  if (!requestId && !code) {
    return null
  }
  return (
    <details className="support-details">
      <summary>Подробнее для поддержки</summary>
      {code && <p>Код ошибки: {code}</p>}
      {requestId && <p className="request-id">Request ID: {requestId}</p>}
    </details>
  )
}
