package ru.rtk.crm.module;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rtk.crm.security.ApiErrorWriter;

@Configuration
public class ModuleGate implements WebMvcConfigurer {
    private final ModuleProperties modules;
    private final ApiErrorWriter apiErrorWriter;

    public ModuleGate(ModuleProperties modules, ApiErrorWriter apiErrorWriter) {
        this.modules = modules;
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Gate(modules::enrolmentEnabled, ModuleDisabledException::enrolment))
                .addPathPatterns("/api/enrolment/**", "/api/admin/personal-data/learners/**");
        registry.addInterceptor(new Gate(modules::partnerEnabled, ModuleDisabledException::partner))
                .addPathPatterns("/api/partner/**", "/api/organizations/*/partner-access",
                        "/api/organizations/*/contacts/*/partner-access", "/api/interactions/*/partner-requests",
                        "/api/partner-requests/**");
    }

    // Модуль MAX закрывается фильтром до Spring Security, а не перехватчиком MVC: у выключенного модуля нет ни
    // контроллеров, ни своей цепочки безопасности, и без фильтра вебхук и мини-приложение отвечали бы 401 вместо 404.
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> maxModuleGate() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (modules.maxEnabled()) {
                    chain.doFilter(request, response);
                } else {
                    apiErrorWriter.write(request, response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                            "Канал MAX недоступен: модуль выключен", "MAX module disabled");
                }
            }
        });
        registration.addUrlPatterns("/api/max/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    // Модуль почты закрывается так же: открытие приглашения доступно без входа, и без фильтра выключенный модуль
    // отвечал бы на него не 404.
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mailModuleGate() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (modules.mailEnabled()) {
                    chain.doFilter(request, response);
                } else {
                    apiErrorWriter.write(request, response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                            "Почта недоступна: модуль выключен", "Mail module disabled");
                }
            }
        });
        registration.addUrlPatterns("/api/mail/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    private record Gate(BooleanSupplier enabled, Supplier<ModuleDisabledException> exception) implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!enabled.getAsBoolean()) {
                throw exception.get();
            }
            return true;
        }
    }
}
