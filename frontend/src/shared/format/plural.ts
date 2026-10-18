// Русское склонение по числу: 1 вуз, 2 вуза, 5 вузов, 11 вузов, 21 вуз.
export const pluralForm = (count: number, one: string, few: string, many: string) => {
  const lastTwo = Math.abs(count) % 100
  const last = lastTwo % 10
  if (last === 1 && lastTwo !== 11) {
    return one
  }
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? few : many
}

export const withCount = (count: number, one: string, few: string, many: string) => `${count} ${pluralForm(count, one, few, many)}`
