package ru.rtk.crm.mcp;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Транспорт SDK — обычный сервлет; он вызывается из контроллера, а не регистрируется отдельно. Так /mcp существует
// только при включённом флаге, проходит общий DispatcherServlet и проверяется тестами MockMvc вместе с цепочкой.
@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
@Profile("oidc")
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class McpEndpoint {
    private final HttpServletStatelessServerTransport transport;
    private final String publicBaseUrl;
    private final String issuerUri;
    private final String audience;
    private final String writeScope;

    McpEndpoint(
            HttpServletStatelessServerTransport transport,
            McpProperties properties,
            @Value("${app.oidc.public-base-url}") String publicBaseUrl,
            @Value("${app.oidc.issuer-uri}") String issuerUri
    ) {
        this.transport = transport;
        this.publicBaseUrl = publicBaseUrl;
        this.issuerUri = issuerUri;
        this.audience = properties.audience();
        this.writeScope = properties.writeScope();
    }

    @RequestMapping(McpProperties.PATH)
    void mcp(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        transport.service(request, response);
    }

    // Метаданные защищённого ресурса (RFC 9728): где Keycloak и какой scope просить. Открыты без токена — это описание
    // сервера, данных CRM в нём нет.
    @GetMapping({McpProperties.METADATA_PATH, McpProperties.METADATA_PATH + McpProperties.PATH})
    Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", publicBaseUrl + McpProperties.PATH);
        metadata.put("authorization_servers", List.of(issuerUri));
        metadata.put("scopes_supported", List.of(audience, writeScope));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", "CRM ИТ Школы РТК");
        return metadata;
    }
}
