type PaginationProps = {
  label: string
  page: number
  size: number
  total: number
  disabled?: boolean
  onChange: (page: number) => void
}

export const Pagination = ({ label, page, size, total, disabled = false, onChange }: PaginationProps) => {
  const pages = Math.ceil(total / size)
  if (pages <= 1) {
    return null
  }
  return (
    <nav className="pagination" aria-label={label}>
      <button type="button" className="button--secondary" disabled={disabled || page === 0} onClick={() => onChange(page - 1)}>
        Предыдущая
      </button>
      <p>Страница {page + 1} из {pages}</p>
      <button
        type="button"
        className="button--secondary"
        disabled={disabled || page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        Следующая
      </button>
    </nav>
  )
}
