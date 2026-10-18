import type { components } from '../../shared/api/openapi'

type WorkNews = components['schemas']['WorkNews']
type NewsKind = components['schemas']['WorkNewsItem']['kind']

export const newsKindLabels: Record<NewsKind, string> = {
  SITE_REQUEST: 'Заявка с сайта',
  LMS_DATA: 'Данные LMS'
}

// После отметки строка уходит к просмотренным (в конец), счётчик непросмотренных уменьшается без повторной загрузки.
export const withViewed = (news: WorkNews, eventId: string): WorkNews => {
  const target = news.items.find((item) => item.eventId === eventId)
  if (target === undefined || target.viewed) {
    return news
  }
  const rest = news.items.filter((item) => item.eventId !== eventId)
  const firstViewed = rest.findIndex((item) => item.viewed)
  const position = firstViewed < 0 ? rest.length : firstViewed
  return {
    ...news,
    unviewed: Math.max(0, news.unviewed - 1),
    items: [...rest.slice(0, position), { ...target, viewed: true }, ...rest.slice(position)]
  }
}
