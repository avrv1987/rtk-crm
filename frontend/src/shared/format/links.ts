// Телефон вводится как угодно («+7 (900) 123-45-67 доб. 12»), а ссылка tel: понимает только цифры и ведущий «+».
// Короче пяти цифр — не телефон (например, «12»), ссылку не строим.
export const phoneHref = (value: string | null | undefined): string | null => {
  const digits = (value ?? '').split(/доб|ext|вн/i)[0].replace(/[^\d+]/g, '').replace(/(?!^)\+/g, '')
  return digits.replace(/\D/g, '').length >= 5 ? `tel:${digits}` : null
}

export const emailHref = (value: string | null | undefined): string | null => {
  const email = (value ?? '').trim()
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? `mailto:${email}` : null
}
