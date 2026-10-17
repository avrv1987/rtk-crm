package ru.rtk.crm.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.AdminCrmProfileService;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.LoginJournal;

@Component
@Profile("oidc")
public class CrmProfileRegistrationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final CurrentProfileService currentProfileService;
    private final AdminCrmProfileService adminCrmProfileService;
    private final LoginJournal loginJournal;

    public CrmProfileRegistrationSuccessHandler(
            CurrentProfileService currentProfileService,
            AdminCrmProfileService adminCrmProfileService,
            LoginJournal loginJournal
    ) {
        super("/");
        setAlwaysUseDefaultTargetUrl(true);
        this.currentProfileService = currentProfileService;
        this.adminCrmProfileService = adminCrmProfileService;
        this.loginJournal = loginJournal;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof OidcUser user) {
            // Порядок после входа: профиль заводится (если нового нет), затем из токена переносится роль, затем пишется
            // журнал входа. Роль переносится после регистрации, иначе у нового профиля её не к чему применять.
            currentProfileService.registerPendingProfile(user);
            adminCrmProfileService.applyKeycloakRole(
                    user.getIdToken().getIssuer().toString(),
                    user.getSubject(),
                    CurrentProfileService.keycloakRoles(user),
                    RequestId.from(request)
            );
            loginJournal.loggedIn(user, request.getRemoteAddr(), RequestId.from(request));
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
