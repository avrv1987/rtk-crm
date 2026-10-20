export type LandingMode = 'CONTEST' | 'PRODUCT'

/** Демонстрационный адрес продуктовой главной: работает при любой настройке. Параметр, а не хеш, — якоря страницы его не сбивают. */
export const productDemoHref = '/?landing=product'

export const isProductDemo = (search: string): boolean => new URLSearchParams(search).get('landing') === 'product'

export const landingPageFor = (mode: LandingMode | undefined, search: string): 'contest' | 'product' => (
  mode === 'PRODUCT' || isProductDemo(search) ? 'product' : 'contest'
)

/** Ссылка обратно на конкурсную нужна только в демонстрации: при «Продуктовой» конкурсная публично не показывается. */
export const showBackToContest = (mode: LandingMode | undefined, search: string): boolean => (
  mode !== 'PRODUCT' && isProductDemo(search)
)
