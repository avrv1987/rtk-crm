package ru.rtk.crm.mail;

import java.time.Duration;

import jakarta.mail.internet.MimeMessage;
import ru.rtk.crm.mail.MailRepository.MailMode;
import ru.rtk.crm.mail.MailRepository.MailSecurity;

// Способ доставки готового MIME-письма. Письмо собирается одинаково для обоих режимов (MailOutbox), поэтому заглушка
// проходит весь путь, кроме сетевой отправки, и в журнале лежит ровно то письмо, которое ушло бы по SMTP.
interface MailTransport {
    MailMode mode();

    // Бросает org.springframework.mail.MailException; отправщик повторяет письмо по расписанию.
    void send(MimeMessage message, Connection connection);

    // Текст результата для администратора; ошибка — исключение с причиной.
    String check(Connection connection);

    record Connection(String host, Integer port, MailSecurity security, String username, String password, Duration timeout) {
        // Пароль не попадает в toString: запись могут вывести в лог при ошибке.
        @Override
        public String toString() {
            return "Connection[host=" + host + ", port=" + port + ", security=" + security + ", username=" + username + "]";
        }
    }
}
