package ru.rtk.crm.mail;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

// При MAIL_ENABLED=false у модуля нет ни бинов, ни заданий, ни контроллеров: письма не ставятся в очередь и не
// уходят, а /api/mail/* отвечает 404 (ModuleGate).
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@ConditionalOnProperty(name = "app.modules.mail.enabled", havingValue = "true")
public @interface MailEnabled {
}
