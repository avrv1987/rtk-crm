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
import ru.rtk.crm.web.LogoutResponse;

@Component
@Profile("oidc")
public class KeycloakLogoutSuccessHandler implements LogoutSuccessHandler {
    private final ObjectMapper objectMapper;
    private final String endSessionUri;
    private final String clientId;
    private final String postLogoutRedirectUri;

    public KeycloakLogoutSuccessHandler(
            ObjectMapper objectMapper,
            @Value("${app.oidc.end-session-uri}") String endSessionUri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
            @Value("${app.oidc.post-logout-redirect-uri}") String postLogoutRedirectUri
    ) {
        this.objectMapper = objectMapper;
        this.endSessionUri = endSessionUri;
        this.clientId = clientId;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
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
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            logoutUrl.queryParam("id_token_hint", user.getIdToken().getTokenValue());
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new LogoutResponse(logoutUrl.encode().build().toUriString()));
    }
}
