package ru.rtk.crm.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import ru.rtk.crm.access.LoginJournal;
import ru.rtk.crm.web.LogoutResponse;

@Component
@Profile("oidc")
public class KeycloakLogoutSuccessHandler implements LogoutSuccessHandler {
    private final ObjectMapper objectMapper;
    private final String endSessionUri;
    private final String clientId;
    private final String postLogoutRedirectUri;
    private final LoginJournal loginJournal;

    public KeycloakLogoutSuccessHandler(
            ObjectMapper objectMapper,
            LoginJournal loginJournal,
            @Value("${app.oidc.end-session-uri}") String endSessionUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
            @Value("${app.oidc.post-logout-redirect-uri}") String postLogoutRedirectUri
    ) {
        this.objectMapper = objectMapper;
        this.endSessionUri = endSessionUri;
        this.clientId = clientId;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
        this.loginJournal = loginJournal;
    }

    @Override
    public void onLogoutSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        UriComponentsBuilder logoutUrl = UriComponentsBuilder.fromUriString(endSessionUri)
                .queryParam("client_id", clientId)
                .queryParam("post_logout_redirect_uri", postLogoutRedirectUri);
        // Сессия CRM уже закрыта, но сессия в Keycloak остаётся, пока браузер не перейдёт на его end_session. Адрес
        // с id_token_hint отдаётся интерфейсу JSON-ом, а не редиректом: выход — POST под защитой CSRF, а переход
        // на end_session выполняет сам интерфейс после ответа.
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            logoutUrl.queryParam("id_token_hint", user.getIdToken().getTokenValue());
            loginJournal.loggedOut(user, request.getRemoteAddr(), RequestId.from(request));
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new LogoutResponse(logoutUrl.encode().build().toUriString()));
    }
}
