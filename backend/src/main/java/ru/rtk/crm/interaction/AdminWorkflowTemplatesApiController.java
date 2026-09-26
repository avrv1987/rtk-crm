package ru.rtk.crm.interaction;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
@RequestMapping("/api/admin/workflow-templates")
public class AdminWorkflowTemplatesApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkflowTemplateService workflowTemplateService;

    public AdminWorkflowTemplatesApiController(
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
        return workflowTemplateService.listManaged(
                currentProfileService.requireActiveProfile(user),
                WorkflowTemplateQuery.from(page, size, sort)
        );
    }

    @PostMapping
    public ResponseEntity<WorkflowTemplate> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkflowTemplateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowTemplateService.create(
                currentProfileService.requireActiveProfile(user), request, idempotencyKey
        ));
    }

    @GetMapping("/{id}")
    public WorkflowTemplate get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return workflowTemplateService.getManaged(
                currentProfileService.requireActiveProfile(user),
                parseId(id)
        );
    }

    @PatchMapping("/{id}")
    public WorkflowTemplate update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkflowTemplateRequest request
    ) {
        return workflowTemplateService.update(
                currentProfileService.requireActiveProfile(user),
                parseId(id),
                request,
                idempotencyKey
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestParam Integer version,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        workflowTemplateService.delete(
                currentProfileService.requireActiveProfile(user),
                parseId(id),
                version,
                idempotencyKey
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/default")
    public WorkflowTemplate makeDefault(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody WorkflowTemplateDefaultRequest request
    ) {
        return workflowTemplateService.makeDefault(
                currentProfileService.requireActiveProfile(user),
                parseId(id),
                request == null ? null : request.version(),
                idempotencyKey
        );
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор шаблона");
        }
    }
}
