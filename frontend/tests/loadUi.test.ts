import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const source = (path: string) => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

test('ширина 320: у body и меню карточки нет жёсткого минимума шире экрана', () => {
  const tokens = source('shared/theme/tokens.css')
  assert.doesNotMatch(tokens.match(/\nbody \{[^}]*\}/)?.[0] ?? '', /min-width/)
  assert.doesNotMatch(source('features/interactions/cardUi.css'), /min-width: 320px/)
  assert.match(source('features/interactions/cardUi.css'), /min-width: min\(320px, calc\(100vw - 32px\)\)/)
})

test('кнопка камеры: правило формы вложения не перебивает скрытие на компьютере', () => {
  const css = source('features/interactions/workCard.css')
  assert.match(css, /\.interaction-attachment-upload label:not\(\.camera-input\) \{/)
  assert.doesNotMatch(css, /\.interaction-attachment-upload label \{/)
})

test('плитка без значения показывает «—», а не 0', () => {
  const tiles = source('shared/ui/StatTiles.tsx')
  assert.match(tiles, /value: number \| null/)
  assert.match(tiles, /tile\.value \?\? '—'/)
  assert.doesNotMatch(source('features/work/KamDesk.tsx'), /signals \?\? 0/)
})

test('поиск слушателя: отдельное поле на каждый вид поиска, пояснение к «Принято LMS»', () => {
  const list = source('features/enrolment/EnrolmentStreamsList.tsx')
  for (const kind of ['EMAIL', 'PHONE', 'SNILS', 'LAST_NAME']) {
    assert.match(list, new RegExp(`value: '${kind}'`))
  }
  assert.doesNotMatch(list, /Вид поиска/)
  assert.match(list, /«Принято LMS» — строка принята по протоколу LMS или отметке оператора/)
})

test('кабинет вуза: при ошибке скачивания совет обратиться к менеджеру, не обновлять страницу', () => {
  const cabinet = source('features/partner/PartnerCabinet.tsx')
  assert.match(cabinet, /обратитесь к вашему менеджеру/)
  assert.doesNotMatch(cabinet, /Обновите страницу/)
})

test('версия системы в справке берётся из сборки', () => {
  assert.match(source('features/help/HelpScreen.tsx'), /Версия системы: \{appVersion\}/)
  assert.match(readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8'), /__APP_VERSION__/)
})
