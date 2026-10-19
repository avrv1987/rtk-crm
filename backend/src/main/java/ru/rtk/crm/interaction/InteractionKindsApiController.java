package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKind;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRequest;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindResolution;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRule;
import ru.rtk.crm.interaction.InteractionKindModels.InteractionKindRuleRequest;
import ru.rtk.crm.interaction.InteractionKindModels.TeamTemplateRequest;

@RestController
public class InteractionKindsApiController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CurrentProfileService currentProfileService;
    private final InteractionKindService service;

    public InteractionKindsApiController(CurrentProfileService currentProfileService, InteractionKindService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/interaction-kinds")
    public List<InteractionKind> available(@AuthenticationPrincipal OidcUser user) {
        return service.available(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/api/interaction-kinds/resolution")
    public InteractionKindResolution resolve(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String kindId,
            @RequestParam(required = false) String programId,
            @RequestParam(required = false) List<String> productId
    ) {
        return service.resolve(
                currentProfileService.requireActiveProfile(user),
                optionalId(organizationId, "organizationId"),
                optionalId(kindId, "kindId"),
                optionalId(programId, "programId"),
                productId == null ? List.of() : productId.stream().map(value -> optionalId(value, "productId")).toList()
        );
    }

    @GetMapping("/api/admin/interaction-kinds")
    public List<InteractionKind> managed(@AuthenticationPrincipal OidcUser user) {
        return service.managed(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/admin/interaction-kinds")
    public ResponseEntity<InteractionKind> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody InteractionKindRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(currentProfileService.requireActiveProfile(user), request, idempotencyKey));
    }

    @PatchMapping("/api/admin/interaction-kinds/{id}")
    public InteractionKind update(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody InteractionKindRequest request
    ) {
        return service.update(currentProfileService.requireActiveProfile(user), WorkflowTemplatesApiController.parseId(id),
                request, idempotencyKey);
    }

    @PutMapping("/api/admin/interaction-kinds/{id}/team-template")
    public InteractionKind setTeamTemplate(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) TeamTemplateRequest request
    ) {
        return service.setTeamTemplate(currentProfileService.requireActiveProfile(user), WorkflowTemplatesApiController.parseId(id),
                request, idempotencyKey);
    }

    @GetMapping("/api/admin/interaction-kind-rules")
    public List<InteractionKindRule> rules(@AuthenticationPrincipal OidcUser user) {
        return service.rules(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/admin/interaction-kind-rules")
    public ResponseEntity<InteractionKindRule> createRule(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody InteractionKindRuleRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createRule(currentProfileService.requireActiveProfile(user), request, idempotencyKey));
    }

    @PatchMapping("/api/admin/interaction-kind-rules/{id}")
    public InteractionKindRule updateRule(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody InteractionKindRuleRequest request
    ) {
        return service.updateRule(currentProfileService.requireActiveProfile(user), WorkflowTemplatesApiController.parseId(id),
                request, idempotencyKey);
    }

    @DeleteMapping("/api/admin/interaction-kind-rules/{id}")
    public ResponseEntity<Void> deleteRule(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestParam Integer version,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey
    ) {
        service.deleteRule(currentProfileService.requireActiveProfile(user), WorkflowTemplatesApiController.parseId(id),
                version, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    private static UUID optionalId(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
