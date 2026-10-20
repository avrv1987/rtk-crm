import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adminSectionsFor } from '../src/features/admin/adminSections.ts'
import { categoryLabels } from '../src/features/admin/auditJournal.ts'
import { defaultPort, draftOf, invitationToken, mailPreferencesAvailable, queueDayText, rowStatusText, updateOf } from '../src/features/mail/mailAdmin.ts'

const on = { enrolment: false, partner: false, mail: true }
const off = { enrolment: false, partner: false }

test('«Уведомления на почту» — при включённом модуле, кроме представителя вуза и менеджера учебного процесса', () => {
  assert.equal(mailPreferencesAvailable({ role: 'USER', enrolmentOperator: false }, on), true)
  assert.equal(mailPreferencesAvailable({ role: 'ADMIN', enrolmentOperator: false }, on), true)
  assert.equal(mailPreferencesAvailable({ role: 'MANAGEMENT', enrolmentOperator: false }, on), true)
  assert.equal(mailPreferencesAvailable({ role: 'USER', enrolmentOperator: true }, on), false)
  // Координатор вуза отключает письмо «Школа приняла мой ответ» (решение владельца 21.10).
  assert.equal(mailPreferencesAvailable({ role: 'PARTNER', enrolmentOperator: false }, on), true)
  assert.equal(mailPreferencesAvailable({ role: 'USER', enrolmentOperator: false }, off), false)
})

test('раздел «Почта» есть только у администратора', () => {
  assert.ok(adminSectionsFor('ADMIN').some((section) => section.slug === 'mail'))
  assert.ok(!adminSectionsFor('LEADER').some((section) => section.slug === 'mail'))
  assert.equal(categoryLabels.MAIL, 'Почта')
})

test('режим «Без отправки» подписан в журнале, адрес приглашения разбирается строго', () => {
  assert.equal(rowStatusText({ status: 'SENT', transport: 'STUB' }), 'в журнале (без отправки)')
  assert.equal(rowStatusText({ status: 'SENT', transport: 'SMTP' }), 'отправлено')
  assert.equal(rowStatusText({ status: 'DROPPED', transport: null }), 'не отправлено')
  const token = 'abcdefghijklmnopqrstuvwxyz_-0123456789ABCDEFG'
  assert.equal(invitationToken(`#/invite/${token}`), token)
  assert.equal(invitationToken('#/invite/short'), null)
  assert.equal(invitationToken(`#/invite/${token}?x=1`), null)
  assert.equal(invitationToken('#/work'), null)
})

test('пустой пароль не отправляется, порт подставляется по шифрованию', () => {
  const draft = draftOf({
    mode: 'SMTP', host: 'smtp.example.ru', port: 587, security: 'STARTTLS', username: 'crm', passwordState: 'SET',
    encryptionAvailable: true, fromAddress: 'crm@example.ru', fromName: 'CRM', queue: { queued: 0, errors: 0, sent: 0, dropped: 0, cancelled: 0 }
  })
  assert.equal(updateOf(draft).password, null)
  assert.equal(updateOf({ ...draft, password: 'x' }).password, 'x')
  assert.equal(updateOf({ ...draft, port: '' }).port, null)
  assert.equal(defaultPort('TLS'), 465)
  assert.equal(defaultPort('STARTTLS'), 587)
})

test('очередь за сутки: в режиме «Без отправки» письма записаны в журнал, а не отправлены', () => {
  assert.equal(queueDayText('STUB', { sent: 0, logged: 12 }), 'записано в журнал без отправки 12')
  assert.equal(queueDayText('SMTP', { sent: 5, logged: 0 }), 'отправлено 5')
  assert.equal(queueDayText('SMTP', { sent: 5, logged: 2 }), 'отправлено 5, записано в журнал без отправки 2')
})
