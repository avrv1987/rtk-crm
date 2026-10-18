package ru.rtk.crm.mail;

import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;
import ru.rtk.crm.mail.MailRepository.MailMode;

// Заглушка: наружу ничего не уходит, письмо остаётся в журнале отправки со статусом «отправлено (заглушка)».
@Component
@MailEnabled
class MailStubTransport implements MailTransport {
    @Override
    public MailMode mode() {
        return MailMode.STUB;
    }

    @Override
    public void send(MimeMessage message, Connection connection) {
    }

    @Override
    public String check(Connection connection) {
        return "Режим заглушки: письма собираются полностью и сохраняются в журнал, наружу ничего не уходит";
    }
}
