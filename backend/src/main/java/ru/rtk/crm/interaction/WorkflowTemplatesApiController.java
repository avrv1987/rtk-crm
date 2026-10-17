package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
@RequestMapping("/api/workflow-templates")
public class WorkflowTemplatesApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowRolloutService workflowRolloutService;

    public WorkflowTemplatesApiController(
            CurrentProfileService currentProfileService,
            WorkflowTemplateService workflowTemplateService,
            WorkflowRolloutService workflowRolloutService
    ) {
        this.currentProfileService = currentProfileService;
        this.workflowTemplateService = workflowTemplateService;
        this.workflowRolloutService = workflowRolloutService;
    }

    @GetMapping("/{id}/versions")
    public List<WorkflowTemplateVersion> versions(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return workflowRolloutService.versions(currentProfileService.requireActiveProfile(user), parseId(id));
    }

    static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор шаблона");
        }
    }

    @GetMapping
    public WorkflowTemplatePage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "name,asc") String sort
    ) {
        return workflowTemplateService.listAvailable(
                currentProfileService.requireActiveProfile(user),
                WorkflowTemplateQuery.from(page, size, sort)
        );
    }
}
