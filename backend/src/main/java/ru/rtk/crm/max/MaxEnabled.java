package ru.rtk.crm.max;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

// Каждый бин модуля помечен этим условием: при MAX_ENABLED=false модуль не создаёт ни бинов, ни своих таблиц, ни
// планировщиков, и CRM работает так же, как без него.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@ConditionalOnProperty(name = "app.modules.max.enabled", havingValue = "true")
public @interface MaxEnabled {
}
