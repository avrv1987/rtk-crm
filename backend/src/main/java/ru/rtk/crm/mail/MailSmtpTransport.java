package ru.rtk.crm.mail;

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import ru.rtk.crm.mail.MailRepository.MailMode;
import ru.rtk.crm.mail.MailRepository.MailSecurity;

// Отправка через SMTP-сервер, который задал администратор. Отправитель создаётся на каждый вызов из текущих настроек:
// смена сервера или пароля в админке действует со следующего письма без перезапуска.
@Component
@MailEnabled
class MailSmtpTransport implements MailTransport {
    @Override
    public MailMode mode() {
        return MailMode.SMTP;
    }

    @Override
    public void send(MimeMessage message, Connection connection) {
        sender(connection).send(message);
    }

    @Override
    public String check(Connection connection) {
        try {
            sender(connection).testConnection();
        } catch (MessagingException exception) {
            throw new MailSendException("SMTP-сервер не принял подключение: " + reason(exception), exception);
        }
        return "Подключение к " + connection.host() + ":" + connection.port() + " установлено";
    }

    // STARTTLS обязателен, если выбран: без starttls.required клиент молча продолжил бы без шифрования, а пароль ушёл
    // бы открытым текстом. Сертификат сервера проверяется по имени хоста.
    static JavaMailSenderImpl sender(Connection connection) {
        if (connection.host() == null || connection.host().isBlank() || connection.port() == null) {
            throw new MailSendException("Не заданы сервер и порт SMTP");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(connection.host());
        sender.setPort(connection.port());
        sender.setDefaultEncoding("UTF-8");
        boolean auth = connection.username() != null && !connection.username().isBlank();
        if (auth) {
            sender.setUsername(connection.username());
            sender.setPassword(connection.password());
        }
        String timeout = String.valueOf(connection.timeout().toMillis());
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", String.valueOf(auth));
        properties.put("mail.smtp.connectiontimeout", timeout);
        properties.put("mail.smtp.timeout", timeout);
        properties.put("mail.smtp.writetimeout", timeout);
        if (connection.security() == MailSecurity.STARTTLS) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if (connection.security() == MailSecurity.TLS) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        sender.setJavaMailProperties(properties);
        return sender;
    }

    static String reason(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.strip();
    }
}
