package ru.rtk.crm.mcp;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.mcp.AgentPlanService.AgentPlanListItem;
import ru.rtk.crm.mcp.AgentPlanService.AgentPlanNotFoundException;
import ru.rtk.crm.mcp.AgentPlanService.AgentPlanScreen;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

// Экран подтверждения плана ИИ-агента: обычная сессия CRM с cookie и CSRF, а не токен агента. Поэтому «Записать»
// нажимает сам сотрудник в браузере, и агент не может подтвердить свой план.
@RestController
@RequestMapping("/api/agent-plans")
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public class AgentPlansApiController {
    private final CurrentProfileService currentProfileService;
    private final AgentPlanService agentPlanService;

    public AgentPlansApiController(CurrentProfileService currentProfileService, AgentPlanService agentPlanService) {
        this.currentProfileService = currentProfileService;
        this.agentPlanService = agentPlanService;
    }

    // Свои планы: по умолчанию только ожидающие подтверждения (status=PENDING), status=ALL — и решённые.
    @GetMapping
    public List<AgentPlanListItem> list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "PENDING") String status
    ) {
        AgentPlanService.Kind parsedKind;
        try {
            parsedKind = kind == null || kind.isBlank() ? null : AgentPlanService.Kind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("kind", "Неизвестный вид плана");
        }
        if (!status.equals("PENDING") && !status.equals("ALL")) {
            throw new InteractionValidationException("status", "Укажите PENDING или ALL");
        }
        return agentPlanService.list(currentProfileService.requireActiveProfile(user), parsedKind, status.equals("PENDING"));
    }

    @GetMapping("/{id}")
    public AgentPlanScreen get(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        return agentPlanService.screen(currentProfileService.requireActiveProfile(user), id, RequestId.from(request));
    }

    @PostMapping("/{id}/apply")
    public AgentPlanScreen apply(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        agentPlanService.apply(profile, id, RequestId.from(request));
        return agentPlanService.screen(profile, id, RequestId.from(request));
    }

    @PostMapping("/{id}/reject")
    public AgentPlanScreen reject(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        CrmProfile profile = currentProfileService.requireActiveProfile(user);
        agentPlanService.reject(profile, id, RequestId.from(request));
        return agentPlanService.screen(profile, id, RequestId.from(request));
    }

    @ExceptionHandler(AgentPlanNotFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "План не найден или недоступен", RequestId.from(request)));
    }
}
