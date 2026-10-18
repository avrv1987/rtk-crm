// Ссылка tel: или mailto: открывает звонок и письмо одним касанием; если адрес не распознан, остаётся обычный текст.
export const ContactLink = ({ href, text }: { href: string | null; text: string }) => (
  href === null ? <>{text}</> : <a href={href}>{text}</a>
)
