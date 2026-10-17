import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = (...parts: string[]) => readFileSync(path.join(here, '..', 'src', ...parts), 'utf8')

const selection = source('features', 'reports', 'reportSelection.ts')
const help = source('features', 'help', 'HelpScreen.tsx')
const catalogs = source('features', 'admin', 'AdminCatalogsPanel.tsx')
const sources = source('features', 'admin', 'SourcesPanel.tsx')
const api = source('shared', 'api', 'client.ts')

test('отчёт «Востребованность программ» предлагает колонки спроса рынка труда с понятными названиями', () => {
  assert.match(selection, /DEMAND: \[[^\]]*'MARKET_VACANCIES', 'MARKET_TREND'\s*\]/)
  assert.match(selection, /MARKET_VACANCIES: 'Спрос рынка: вакансии'/)
  assert.match(selection, /MARKET_TREND: 'Тренд спроса, %'/)
})

test('флажок «Учитывать спрос рынка труда» есть только у ИТ-направлений в справочнике', () => {
  assert.match(catalogs, /kind\.kind === 'directions' && !entry\.archived/)
  assert.match(catalogs, /Учитывать спрос рынка труда/)
})

test('блок «Рынок труда» подключён к экрану источников и к клиенту API', () => {
  assert.match(sources, /<LaborMarketBlock/)
  for (const method of ['getLaborMarketSettings', 'updateLaborMarketSettings', 'checkLaborMarketConnection', 'listLaborMarketMappings', 'updateLaborMarketMapping']) {
    assert.ok(api.includes(`async ${method}(`), method)
  }
})

test('справка объясняет признак, источник и значения «не применяется» и «нет данных»', () => {
  for (const phrase of ['Учитывать спрос рынка труда', 'Блок «Рынок труда»', 'не применяется', 'нет данных', 'Тренд спроса, %']) {
    assert.ok(help.includes(phrase), phrase)
  }
})
