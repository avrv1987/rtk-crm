package ru.rtk.crm.interaction;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
@RequestMapping("/api/workflow-templates")
public class WorkflowTemplatesApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkflowTemplateService workflowTemplateService;

    public WorkflowTemplatesApiController(
            CurrentProfileService currentProfileService,
            WorkflowTemplateService workflowTemplateService
    ) {
        this.currentProfileService = currentProfileService;
        this.workflowTemplateService = workflowTemplateService;
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
