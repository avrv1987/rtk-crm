package ru.rtk.crm.partner;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerMaterialReceipt;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerReceipt;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestCancel;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestClarify;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestCreate;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestReview;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerRequestView;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerResponseKind;
import ru.rtk.crm.partner.PartnerRequestModels.PartnerReviewItem;
import ru.rtk.crm.security.RequestId;

@RestController
public class PartnerRequestApiController {
    private final CurrentProfileService currentProfileService;
    private final PartnerRequestService service;

    public PartnerRequestApiController(CurrentProfileService currentProfileService, PartnerRequestService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/interactions/{id}/partner-requests")
    public List<PartnerRequestView> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return service.list(currentProfileService.requireActiveProfile(user), parseId(id));
    }

    @PostMapping("/api/interactions/{id}/partner-requests")
    public ResponseEntity<PartnerRequestView> create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PartnerRequestCreate body,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentProfileService.requireActiveProfile(user), parseId(id),
                body, idempotencyKey, RequestId.from(request)));
    }

    @GetMapping("/api/partner-requests/review")
    public List<PartnerReviewItem> awaitingReview(@AuthenticationPrincipal OidcUser user) {
        return service.awaitingReview(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/partner-requests/{id}/review")
    public PartnerRequestView review(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PartnerRequestReview body,
            HttpServletRequest request
    ) {
        return service.review(currentProfileService.requireActiveProfile(user), parseId(id), body, idempotencyKey, RequestId.from(request));
    }

    @PostMapping("/api/partner-requests/{id}/clarify")
    public PartnerRequestView clarify(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PartnerRequestClarify body,
            HttpServletRequest request
    ) {
        return service.clarify(currentProfileService.requireActiveProfile(user), parseId(id), body, idempotencyKey, RequestId.from(request));
    }

    @PostMapping("/api/partner-requests/{id}/cancel")
    public PartnerRequestView cancel(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PartnerRequestCancel body,
            HttpServletRequest request
    ) {
        return service.cancel(currentProfileService.requireActiveProfile(user), parseId(id), body, idempotencyKey, RequestId.from(request));
    }

    @PostMapping(path = "/api/partner/requests/{id}/responses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PartnerReceipt> respond(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "kind", required = false) PartnerResponseKind kind,
            @RequestParam(name = "version", required = false) Integer version,
            @RequestParam(name = "message", required = false) String message,
            @RequestPart(name = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.submit(
                currentProfileService.requirePartnerProfile(user), parseId(id), kind, version, message, file, idempotencyKey,
                RequestId.from(request)));
    }

    @PutMapping("/api/partner/documents/{id}/receipt")
    public PartnerMaterialReceipt confirmReceived(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                                  HttpServletRequest request) {
        return service.confirmReceived(currentProfileService.requirePartnerProfile(user), parseId(id), RequestId.from(request));
    }

    private UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
