package ru.rtk.crm.max;

// Вид исходящего сообщения. Какой настройкой пользователя он отключается — MaxLinkRepository.Link.allows; ответы
// бота на действия самого пользователя (REPLY) не отключаются и данных CRM не содержат.
public enum MaxMessageKind {
    REPLY,
    DIGEST,
    ASSIGNMENT,
    AGREEMENT_CORRECTION,
    AGREEMENT_EXPIRY,
    SITE_REQUEST
}
