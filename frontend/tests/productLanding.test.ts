import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { savedOnChange, settingValueText } from '../src/features/admin/adminSettings.ts'
import { rolesFor } from '../src/features/landing/landingModules.ts'
import { isProductDemo, landingPageFor, productDemoHref, showBackToContest } from '../src/features/landing/landingMode.ts'
import {
  connectionsLine,
  productOutcomes,
  productScreens,
  productSteps
} from '../src/features/landing/productContent.ts'
import { getLandingMode, setLandingMode } from '../src/shared/modules.ts'

const here = path.dirname(fileURLToPath(import.meta.url))
const landingDir = path.join(here, '..', 'src', 'features', 'landing')
const source = (name: string) => readFileSync(path.join(landingDir, name), 'utf8')
const off = { enrolment: false, partner: false }
const on = { enrolment: true, partner: true, max: true, mcp: true, mail: true }

const productText = (modules: typeof on) => [
  source('ProductLanding.tsx'),
  ...productOutcomes.flatMap((item) => [item.title, item.text]),
  ...productSteps,
  ...productScreens.flatMap((screen) => [screen.alt, screen.caption]),
  connectionsLine(modules),
  ...rolesFor(modules).flatMap((role) => [role.title, role.benefit])
].join(' ')

test('главная выбирается по режиму из /api/config, демонстрационный адрес открывает продуктовую при любом режиме', () => {
  assert.equal(landingPageFor('CONTEST', ''), 'contest')
  assert.equal(landingPageFor(undefined, ''), 'contest')
  assert.equal(landingPageFor('PRODUCT', ''), 'product')
  assert.equal(landingPageFor('CONTEST', '?landing=product'), 'product')
  assert.equal(landingPageFor('PRODUCT', '?landing=product'), 'product')
  assert.equal(landingPageFor('CONTEST', '?landing=contest'), 'contest')
  assert.equal(isProductDemo(new URL(productDemoHref, 'http://crm.test').search), true)
})

test('ссылка обратно на конкурсную — только в демонстрации при конкурсном режиме', () => {
  assert.equal(showBackToContest('CONTEST', '?landing=product'), true)
  assert.equal(showBackToContest('PRODUCT', '?landing=product'), false)
  assert.equal(showBackToContest('CONTEST', ''), false)
})

test('режим главной по умолчанию — конкурсная, старый сервер без поля тоже даёт конкурсную', () => {
  assert.equal(getLandingMode(), 'CONTEST')
  setLandingMode('PRODUCT')
  assert.equal(getLandingMode(), 'PRODUCT')
  setLandingMode(undefined)
  assert.equal(getLandingMode(), 'CONTEST')
})

test('на конкурсной главной есть демонстрационная ссылка на продуктовую', () => {
  const contest = source('LandingPage.tsx')
  assert.ok(contest.includes('href={productDemoHref}'))
  assert.ok(contest.includes('Продуктовая версия главной (демонстрация)'))
  assert.equal(productDemoHref, '/?landing=product')
})

test('на продуктовой главной нет блоков для жюри, сдачи и истории разработки', () => {
  const text = productText(on).toLowerCase()
  for (const word of ['жюри', 'для проверки', 'материалы сдачи', 'хакатон', 'техническ', ' тз', 'showjurysection', 'criteriamap', 'swagger', 'исходный код']) {
    assert.ok(!text.includes(word), `на продуктовой главной осталось «${word}»`)
  }
})

test('продуктовая главная показывает модульные роли и MAX только при включённых модулях', () => {
  const titles = (modules: typeof on) => rolesFor(modules).map((role) => role.title)
  assert.ok(!titles(off).includes('Координатор вуза') && !titles(off).includes('Менеджер учебного процесса'))
  assert.ok(titles(on).includes('Координатор вуза') && titles(on).includes('Менеджер учебного процесса'))
  assert.ok(!connectionsLine(off).includes('MAX') && connectionsLine(on).includes('MAX'))
  const withoutModules = productText(off).replace(source('ProductLanding.tsx'), '')
  for (const word of ['ИИ', 'координатор', 'кабинет', 'слушател', 'анкет', 'MAX']) {
    assert.ok(!withoutModules.includes(word), `без модулей на продуктовой главной есть «${word}»`)
  }
})

const words = (text: string) => text.split(/\s+/).filter(Boolean).length

test('продуктовая главная короткая: не больше 7 секций, без перечня интеграций и внутренних терминов', () => {
  const page = source('ProductLanding.tsx')
  assert.ok((page.match(/<section/g) ?? []).length <= 7)
  assert.ok((page.match(/<nav className="landing-header__nav"[\s\S]*?<\/nav>/)?.[0].match(/<a /g) ?? []).length <= 3)
  assert.ok(!page.includes('pl-connections') && !page.includes('landing-integration'), 'вернулся блок перечня интеграций')
  assert.ok(productOutcomes.length >= 3 && productOutcomes.length <= 4)
  for (const outcome of productOutcomes) {
    assert.ok(words(outcome.text) <= 12, outcome.text)
  }
  assert.ok(productSteps.length <= 4 && productSteps.every((step) => words(step) <= 12))
  assert.ok(productScreens.length >= 2 && productScreens.length <= 3)
  for (const role of rolesFor(on)) {
    assert.ok(words(role.benefit) <= 16, role.benefit)
  }
  const text = productText(on).replace(page, '') + page.replace(/className="[^"]*"/g, '')
  for (const term of ['p95', 'LOAD', 'XLSX', 'OpenAPI', 'MCP', '«Контроль»', '«Моя работа»', '«Путь вуза»', 'Экран:']) {
    assert.ok(!text.includes(term), `на продуктовой главной есть «${term}»`)
  }
})

test('без блока надёжности, у каждого снимка есть alt и размеры', () => {
  assert.ok(!source('ProductLanding.tsx').includes('pl-reliability'))
  assert.ok(!source('ProductLanding.tsx').includes('<dl className="landing-facts"'))
  for (const screen of productScreens) {
    assert.ok(screen.alt.length > 20 && screen.width > 0 && screen.height > 0, screen.src)
  }
})

test('настройка «Главная страница» показывается словами и сохраняется сразу', () => {
  assert.equal(settingValueText('LANDING', 0), 'конкурсная')
  assert.equal(settingValueText('LANDING', 1), 'продуктовая')
  assert.equal(savedOnChange('LANDING'), true)
  assert.equal(savedOnChange('DAYS'), false)
})
