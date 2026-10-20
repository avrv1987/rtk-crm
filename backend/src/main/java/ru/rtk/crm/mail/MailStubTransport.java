package ru.rtk.crm.mail;

import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;
import ru.rtk.crm.mail.MailRepository.MailMode;

// Режим «Без отправки» (STUB): наружу ничего не уходит, письмо остаётся в журнале отправки со статусом «в журнале (без отправки)».
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
        return "Режим «Без отправки»: письма собираются полностью и сохраняются в журнал, наружу ничего не уходит";
    }
}
