package ru.rtk.crm.module;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ModuleGate implements WebMvcConfigurer {
    private final ModuleProperties modules;

    public ModuleGate(ModuleProperties modules) {
        this.modules = modules;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Gate(modules::enrolmentEnabled, ModuleDisabledException::enrolment))
                .addPathPatterns("/api/enrolment/**", "/api/admin/personal-data/learners/**");
        registry.addInterceptor(new Gate(modules::partnerEnabled, ModuleDisabledException::partner))
                .addPathPatterns("/api/partner/**", "/api/organizations/*/partner-access",
                        "/api/organizations/*/contacts/*/partner-access");
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
