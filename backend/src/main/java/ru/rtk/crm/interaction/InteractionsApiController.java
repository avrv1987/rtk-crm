package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.attachment.Attachment;
import ru.rtk.crm.attachment.AttachmentService;

@RestController
@RequestMapping("/api/interactions")
public class InteractionsApiController {
    private final CurrentProfileService currentProfileService;
    private final InteractionService interactionService;
    private final AttachmentService attachmentService;

    public InteractionsApiController(
            CurrentProfileService currentProfileService,
            InteractionService interactionService,
            AttachmentService attachmentService
    ) {
        this.currentProfileService = currentProfileService;
        this.interactionService = interactionService;
        this.attachmentService = attachmentService;
    }

    @GetMapping
    public InteractionPage list(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String due,
            @RequestParam(required = false) String stage
    ) {
        return interactionService.list(
                currentProfileService.requireActiveProfile(user),
                InteractionFilter.from(parseOptionalUuid(organizationId, "organizationId"), q, due, stage),
                InteractionQuery.from(page, size, sort)
        );
    }

    @PostMapping
    public ResponseEntity<Interaction> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InteractionCreateRequest request
    ) {
        Interaction created = interactionService.create(
                currentProfileService.requireActiveProfile(user),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public Interaction get(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return interactionService.get(currentProfileService.requireActiveProfile(user), parseRequiredUuid(id, "id"));
    }

    @PatchMapping("/{id}")
    public Interaction updatePlan(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionPlanRequest request
    ) {
        return interactionService.updatePlan(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @GetMapping("/{id}/events")
    public List<InteractionEvent> events(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return interactionService.events(currentProfileService.requireActiveProfile(user), parseRequiredUuid(id, "id"));
    }

    @PostMapping("/{id}/transitions")
    public Interaction transition(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InteractionTransitionRequest request
    ) {
        return interactionService.transition(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @PostMapping("/{id}/comments")
    public InteractionCommentResult comment(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InteractionCommentRequest request
    ) {
        return interactionService.comment(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @PostMapping("/{id}/stage-edits")
    public Interaction stageEdits(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InteractionStageEditRequest request
    ) {
        return interactionService.stageEdits(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Attachment> uploadAttachment(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam String stageId,
            @RequestPart("file") MultipartFile file
    ) {
        Attachment attachment = attachmentService.upload(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                parseRequiredUuid(stageId, "stageId"),
                file,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(attachment);
    }

    private UUID parseOptionalUuid(String value, String field) {
        return value == null || value.isBlank() ? null : parseRequiredUuid(value, field);
    }

    private UUID parseRequiredUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Укажите идентификатор");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
