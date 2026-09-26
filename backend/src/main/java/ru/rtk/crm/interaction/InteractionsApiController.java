package ru.rtk.crm.interaction;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.attachment.Attachment;
import ru.rtk.crm.attachment.AttachmentDeletionRequest;
import ru.rtk.crm.attachment.AttachmentKind;
import ru.rtk.crm.attachment.AttachmentService;
import ru.rtk.crm.attachment.AttachmentUploadRequest;

@RestController
@RequestMapping("/api/interactions")
public class InteractionsApiController {
    private final CurrentProfileService currentProfileService;
    private final InteractionService interactionService;
    private final AttachmentService attachmentService;
    private final ProductAgreementService productAgreementService;

    public InteractionsApiController(
            CurrentProfileService currentProfileService,
            InteractionService interactionService,
            AttachmentService attachmentService,
            ProductAgreementService productAgreementService
    ) {
        this.currentProfileService = currentProfileService;
        this.interactionService = interactionService;
        this.attachmentService = attachmentService;
        this.productAgreementService = productAgreementService;
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
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String flag,
            @RequestParam(required = false) Integer licenseExpiresBy,
            @RequestParam(required = false) String responsible,
            @RequestParam(required = false) String minDaysOnStage
    ) {
        return interactionService.list(
                currentProfileService.requireActiveProfile(user),
                InteractionFilter.from(
                        parseOptionalUuid(organizationId, "organizationId"),
                        q,
                        due,
                        stage,
                        status,
                        flag,
                        licenseExpiresBy,
                        responsible,
                        minDaysOnStage
                ),
                InteractionQuery.from(page, size, sort)
        );
    }

    @PostMapping("/{id}/step-completions")
    public Interaction completeStep(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) InteractionStepCompletionRequest request
    ) {
        return interactionService.completeStep(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
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

    @PostMapping("/{id}/status")
    public Interaction changeStatus(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionStatusRequest request
    ) {
        return interactionService.changeStatus(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @PostMapping("/{id}/flags")
    public Interaction updateFlags(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InteractionFlagsRequest request
    ) {
        return interactionService.updateFlags(
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

    @PostMapping("/{id}/stage-completions")
    public Interaction completeStage(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InteractionStageCompletionRequest request
    ) {
        return interactionService.completeStage(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                request,
                idempotencyKey
        );
    }

    @DeleteMapping("/{id}/stage-completions/{stageId}")
    public Interaction clearStageCompletion(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String stageId,
            @RequestParam(required = false) Integer version,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return interactionService.clearStageCompletion(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                parseRequiredUuid(stageId, "stageId"),
                version,
                idempotencyKey
        );
    }

    @PostMapping(path = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Attachment> uploadAttachment(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam String stageId,
            @RequestParam(required = false) AttachmentKind kind,
            @RequestParam(required = false) String replacesId,
            @RequestPart("file") MultipartFile file
    ) {
        Attachment attachment = attachmentService.upload(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                new AttachmentUploadRequest(
                        parseRequiredUuid(stageId, "stageId"),
                        kind,
                        parseOptionalUuid(replacesId, "replacesId")
                ),
                file,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(attachment);
    }

    @PostMapping("/{id}/attachments/{attachmentId}/deletion")
    public Interaction deleteAttachment(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String attachmentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AttachmentDeletionRequest request
    ) {
        return attachmentService.delete(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                parseRequiredUuid(attachmentId, "attachmentId"),
                request,
                idempotencyKey
        );
    }

    @PatchMapping("/{id}/product-agreements/{agreementId}")
    public Interaction updateProductAgreement(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @PathVariable String agreementId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ProductAgreementUpdateRequest request
    ) {
        return productAgreementService.update(
                currentProfileService.requireActiveProfile(user),
                parseRequiredUuid(id, "id"),
                parseRequiredUuid(agreementId, "agreementId"),
                request,
                idempotencyKey
        );
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
