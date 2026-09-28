import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { errorCodes, screenshotNames, tocItems } from '../src/features/help/helpContent.ts'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '..', '..')
const helpScreenSource = readFileSync(path.join(here, '..', 'src', 'features', 'help', 'HelpScreen.tsx'), 'utf8')
const openapiSource = readFileSync(path.join(repoRoot, 'backend', 'src', 'main', 'resources', 'static', 'openapi.yaml'), 'utf8')
const screenshotsScriptSource = readFileSync(path.join(repoRoot, 'scripts', 'screenshots.mjs'), 'utf8')

const headingIds = [...helpScreenSource.matchAll(/\bid="([a-z0-9-]+)"/g)].map((match) => match[1])
const labelledByIds = [...helpScreenSource.matchAll(/aria-labelledby="([a-z0-9-]+)"/g)].map((match) => match[1])
const screenshotUsages = [...helpScreenSource.matchAll(/<Screenshot\s+name="([a-z0-9-]+)"/g)].map((match) => match[1])
const shotCalls = [...screenshotsScriptSource.matchAll(/shoot\(\w+,\s*'([a-z0-9-]+)'/g)].map((match) => match[1])
const hrefs = [...helpScreenSource.matchAll(/href=(?:"(#[^"]*)"|\{`(#[^`]*)`\})/g)].map((match) => match[1] ?? match[2])

const parseRoute = (hash: string) => {
  const [path, query = ''] = hash.replace(/^#\/?/, '').split('?')
  const [section = ''] = path.split('/')
  return { section, query }
}

test('в справке нет сырых href="#…", ломающих hash-маршрут приложения', () => {
  for (const href of hrefs) {
    assert.ok(href.startsWith('#/'), `ссылка "${href}" не начинается с "#/" и откроет вместо справки раздел по умолчанию`)
  }
})

test('прямая ссылка на любой раздел или подраздел справки разбирается роутером приложения', () => {
  for (const id of headingIds) {
    const route = parseRoute(`#/help?section=${id}`)
    assert.equal(route.section, 'help')
    assert.equal(new URLSearchParams(route.query).get('section'), id)
  }
})

test('оглавление ссылается только на существующие заголовки', () => {
  const idSet = new Set(headingIds)
  for (const item of tocItems) {
    assert.ok(idSet.has(item.id), `TOC id "${item.id}" отсутствует среди id="" в HelpScreen.tsx`)
  }
})

test('в справке нет повторяющихся id заголовков', () => {
  const counts = new Map<string, number>()
  for (const id of headingIds) {
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  const duplicates = [...counts.entries()].filter(([, count]) => count > 1).map(([id]) => id)
  assert.deepEqual(duplicates, [])
})

test('каждый aria-labelledby раздела указывает на существующий заголовок', () => {
  const idSet = new Set(headingIds)
  for (const id of labelledByIds) {
    assert.ok(idSet.has(id), `aria-labelledby="${id}" не находит заголовок с таким id`)
  }
})

test('каждый снимок в справке объявлен в манифесте helpContent.screenshotNames', () => {
  const known = new Set<string>(screenshotNames)
  for (const name of screenshotUsages) {
    assert.ok(known.has(name), `<Screenshot name="${name}"> отсутствует в screenshotNames`)
  }
})

test('скрипт съёмки снимает ровно те снимки, что перечислены в справке', () => {
  assert.deepEqual([...shotCalls].sort(), [...screenshotNames].sort())
})

test('перечень кодов ошибок совпадает с enum ApiError.code в openapi.yaml', () => {
  const schemaStart = openapiSource.indexOf('ApiError:')
  assert.ok(schemaStart >= 0, 'схема ApiError не найдена в openapi.yaml')
  const schemaBlock = openapiSource.slice(schemaStart, schemaStart + 3000)
  const enumMatch = schemaBlock.match(/enum:\n((?:\s*-\s*\w+\n)+)/)
  assert.ok(enumMatch, 'перечень enum кода ApiError не найден')
  const apiCodes = [...enumMatch[1].matchAll(/-\s*(\w+)/g)].map((match) => match[1])
  assert.ok(apiCodes.length > 0, 'enum ApiError.code пуст')
  assert.deepEqual([...errorCodes.map((item) => item.code)].sort(), [...apiCodes].sort())
})
