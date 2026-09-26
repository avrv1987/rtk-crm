package ru.rtk.crm.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = Type.SERVLET)
public class ApiSecurityConfiguration {
    private static final AntPathRequestMatcher API_MATCHER = new AntPathRequestMatcher("/api/**");

    @Bean
    RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Configuration
    @Profile("oidc")
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class OidcConfiguration {
        @Bean
        JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(
                @Value("${app.oidc.issuer-uri}") String issuerUri
        ) {
            OidcIdTokenDecoderFactory decoderFactory = new OidcIdTokenDecoderFactory();
            decoderFactory.setJwtValidatorFactory(clientRegistration -> new DelegatingOAuth2TokenValidator<>(
                    new OidcIdTokenValidator(clientRegistration),
                    JwtValidators.createDefaultWithIssuer(issuerUri)
            ));
            return decoderFactory;
        }

        @Bean
        SecurityFilterChain oidcSecurityFilterChain(
                HttpSecurity http,
                JsonAuthenticationEntryPoint authenticationEntryPoint,
                JsonAccessDeniedHandler accessDeniedHandler,
                RequestIdFilter requestIdFilter,
                KeycloakLogoutSuccessHandler logoutSuccessHandler,
                CrmProfileRegistrationSuccessHandler loginSuccessHandler
        ) throws Exception {
            configureApiSecurity(http, authenticationEntryPoint, accessDeniedHandler, requestIdFilter);
            http.oauth2Login(oauth2 -> oauth2
                            .loginPage("/api/auth/login")
                            .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/auth/authorization"))
                            .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/auth/callback/*"))
                            .successHandler(loginSuccessHandler))
                    .logout(logout -> logout
                            .logoutUrl("/api/auth/logout")
                            .invalidateHttpSession(true)
                            .clearAuthentication(true)
                            .deleteCookies("SESSION", "XSRF-TOKEN")
                            .logoutSuccessHandler(logoutSuccessHandler));
            return http.build();
        }
    }

    @Configuration
    @Profile("!oidc")
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class LocalConfiguration {
        @Bean
        SecurityFilterChain localSecurityFilterChain(
                HttpSecurity http,
                JsonAuthenticationEntryPoint authenticationEntryPoint,
                JsonAccessDeniedHandler accessDeniedHandler,
                RequestIdFilter requestIdFilter
        ) throws Exception {
            configureApiSecurity(http, authenticationEntryPoint, accessDeniedHandler, requestIdFilter);
            return http.build();
        }
    }

    private static void configureApiSecurity(
            HttpSecurity http,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            RequestIdFilter requestIdFilter
    ) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(requestIdFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/openapi.yaml", "/swagger-ui/**", "/v3/api-docs/swagger-config").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/authorization/*", "/api/auth/callback/*").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(authenticationEntryPoint, API_MATCHER)
                        .defaultAccessDeniedHandlerFor(accessDeniedHandler, API_MATCHER));
    }
}
