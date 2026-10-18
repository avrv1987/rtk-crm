package ru.rtk.crm.max;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpServletResponse;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import ru.rtk.crm.security.ApiErrorWriter;
import ru.rtk.crm.security.JsonAccessDeniedHandler;
import ru.rtk.crm.security.RequestIdFilter;

@Configuration
@MaxEnabled
public class MaxModuleConfiguration {
    public static final String SCHEMA_HISTORY_TABLE = "flyway_schema_history_max";

    // Свой экземпляр Flyway со своей таблицей истории: основной Flyway видит только db/migration, поэтому при
    // выключенном модуле схема рабочей базы не меняется, а номера основных миграций модуль не занимает.
    // baselineOnMigrate нужен потому, что схема уже не пуста (в ней основные таблицы), а история модуля ещё нет.
    @Bean
    MaxSchema maxSchema(DataSource dataSource, ObjectProvider<FlywayMigrationInitializer> mainMigration) {
        // Сначала основная схема: таблицы модуля ссылаются на crm_user_profiles.
        mainMigration.ifAvailable(initializer -> { });
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/max")
                .table(SCHEMA_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        return new MaxSchema();
    }

    // Отдельная цепочка для мини-приложения: без cookie-сессии и CSRF, вход только по Bearer-токену сеанса MAX.
    // Порядок 1 — раньше основной цепочки CRM, которая этот путь не видит и не меняется.
    @Bean
    @Order(1)
    SecurityFilterChain maxAppSecurityFilterChain(
            HttpSecurity http,
            MaxSessionService sessions,
            RequestIdFilter requestIdFilter,
            ApiErrorWriter apiErrorWriter,
            JsonAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http.securityMatcher("/api/max/app/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Контекст безопасности не читается из HTTP-сессии: cookie основной CRM здесь ничего не открывает.
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(requestIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(new MaxSessionFilter(sessions), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/max/app/session").permitAll()
                        // Доступ только с принципалом сеанса MAX, а не с любой аутентификацией.
                        .anyRequest().access((authentication, context) -> new AuthorizationDecision(
                                authentication.get().getPrincipal() instanceof MaxSessionFilter.MaxPrincipal)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> apiErrorWriter.write(
                                request, response, HttpServletResponse.SC_UNAUTHORIZED, "MAX_SESSION_EXPIRED",
                                "Сеанс мини-приложения закончился: откройте приложение заново", exception.getClass().getSimpleName()))
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    public static final class MaxSchema {
    }
}
