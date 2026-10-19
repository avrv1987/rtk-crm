package ru.rtk.crm.access;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rtk.crm.catalog.OrganizationAssignmentAccessDeniedException;

// Права на правку карточки проверяются до разбора тела запроса: иначе «Руководство» на комментарий или контакт с
// неполными данными получало 400 «Проверьте введённые данные» вместо 403 (у этих методов проверка тела — @Valid,
// и она срабатывает раньше сервиса). То же для перехода, создания взаимодействия и назначения ответственного (2.5).
@Configuration
class CardEditorGate implements WebMvcConfigurer {
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final CurrentProfileService currentProfileService;

    CardEditorGate(CurrentProfileService currentProfileService) {
        this.currentProfileService = currentProfileService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (!WRITES.contains(request.getMethod()) || authentication == null
                        || !(authentication.getPrincipal() instanceof OidcUser user)) {
                    return true;
                }
                CrmProfile profile;
                try {
                    profile = currentProfileService.requireActiveProfile(user);
                } catch (RuntimeException unavailable) {
                    // Профиль не найден или заблокирован — это ответит сам контроллер своим обычным кодом.
                    return true;
                }
                // Назначает ответственного только руководитель команды — как в OrganizationAssignmentService.
                if (request.getRequestURI().endsWith("/assignment")) {
                    if (profile.role() != UserRole.LEADER) {
                        throw new OrganizationAssignmentAccessDeniedException();
                    }
                    return true;
                }
                ContactInteractionMutationAuthorization.requireCardEditor(profile);
                return true;
            }
        }).addPathPatterns("/api/interactions/*/comments", "/api/organizations/*/contacts", "/api/organizations/*/contacts/*",
                "/api/interactions/*/transitions", "/api/interactions", "/api/organizations/*/assignment");
    }
}
