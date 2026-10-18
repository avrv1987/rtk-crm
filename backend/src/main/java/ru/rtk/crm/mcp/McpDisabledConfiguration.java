package ru.rtk.crm.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// При выключенном MCP обработчиков /mcp, метаданных и экрана планов нет. Эта цепочка лишь пропускает запрос до
// DispatcherServlet, чтобы ответ был честным 404, а не отказом CSRF или входа основной цепочки (401 без сеанса).
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "false", matchIfMissing = true)
class McpDisabledConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain mcpDisabledSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(McpProperties.PATH, McpProperties.METADATA_PATH, McpProperties.METADATA_PATH + "/**",
                        "/api/agent-plans/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
