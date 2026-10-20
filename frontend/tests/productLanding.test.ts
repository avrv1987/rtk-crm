import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { savedOnChange, settingValueText } from '../src/features/admin/adminSettings.ts'
import { rolesFor } from '../src/features/landing/landingModules.ts'
import { isProductDemo, landingPageFor, productDemoHref, showBackToContest } from '../src/features/landing/landingMode.ts'
import {
  productConnections,
  productOutcomes,
  productScreens,
  protectionFacts,
  reliabilityFacts,
  scenarioFor
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
  ...scenarioFor(modules).flatMap((step) => [step.role, step.text, step.screen]),
  ...productScreens.flatMap((screen) => [screen.title, screen.alt, screen.caption]),
  ...productConnections(modules).flat(),
  ...reliabilityFacts.flat(),
  ...protectionFacts(modules),
  ...rolesFor(modules).flatMap((role) => [role.title, role.benefit, ...role.duties])
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

test('продуктовая главная показывает подключаемые модули и ИИ-агентов только при включённых модулях', () => {
  const titles = (modules: typeof on) => productConnections(modules).map(([title]) => title)
  assert.deepEqual(titles(off), ['Moodle', 'Сайт ИТ Школы', 'Excel', 'Открытый API'])
  assert.deepEqual(titles(on), ['Moodle', 'Сайт ИТ Школы', 'Excel', 'MAX', 'Почта', 'ИИ-агенты (MCP)', 'Открытый API'])
  const withoutModules = productText(off).replace(source('ProductLanding.tsx'), '')
  for (const word of ['ИИ', 'координатор', 'кабинет', 'слушател', 'анкет']) {
    assert.ok(!withoutModules.includes(word), `без модулей на продуктовой главной есть «${word}»`)
  }
  assert.equal(scenarioFor(on).length, 8)
})

test('надёжность — факты замера 2.5.3, у каждого снимка есть alt и размеры', () => {
  assert.deepEqual(reliabilityFacts.map(([value]) => value), ['50', '0', '< 1 с', '12 из 12'])
  assert.ok(source('ProductLanding.tsx').includes('версия 2.5.3'))
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
