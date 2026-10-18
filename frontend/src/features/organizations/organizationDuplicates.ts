export type DuplicateQuery = { name: string; inn: string }

const innPattern = /^(\d{10}|\d{12})$/

export const normalizedInn = (value: string) => value.replace(/\s/g, '')

// Сервер сравнивает ИНН только целиком (10 или 12 цифр), поэтому неполный ввод не запрашивается.
// Название и ИНН, которые при правке не менялись, тоже не проверяются: предупреждать о них нечего.
export const duplicateQuery = (
  values: { name: string; inn: string },
  initial: { name?: string; inn?: string } = {}
): DuplicateQuery | null => {
  const name = values.name.trim()
  const inn = normalizedInn(values.inn)
  const query = {
    name: name.length >= 2 && name !== (initial.name ?? '').trim() ? name : '',
    inn: innPattern.test(inn) && inn !== normalizedInn(initial.inn ?? '') ? inn : ''
  }
  return query.name === '' && query.inn === '' ? null : query
}
