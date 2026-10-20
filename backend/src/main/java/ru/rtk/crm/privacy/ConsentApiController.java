package ru.rtk.crm.privacy;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

// Согласия там, где правят запись: у контакта в карточке вуза и у слушателя в анкете модуля «Слушатели».
@RestController
public class ConsentApiController {
    private final CurrentProfileService currentProfileService;
    private final ConsentService service;

    public ConsentApiController(CurrentProfileService currentProfileService, ConsentService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/consent-settings")
    public ConsentSettings settings(@AuthenticationPrincipal OidcUser user) {
        currentProfileService.requireActiveProfile(user);
        return service.settings();
    }

    @GetMapping("/api/organizations/{id}/contacts/{contactId}/consents")
    public Consents contactConsents(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @PathVariable String contactId) {
        return service.list(currentProfileService.requireActiveProfile(user), contact(id, contactId));
    }

    @PostMapping("/api/organizations/{id}/contacts/{contactId}/consents")
    public Consent createContactConsent(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @PathVariable String contactId,
                                        @RequestBody(required = false) ConsentInput body, HttpServletRequest request) {
        return service.create(currentProfileService.requireActiveProfile(user), contact(id, contactId), body, RequestId.from(request));
    }

    @PutMapping("/api/organizations/{id}/contacts/{contactId}/consents/{consentId}")
    public Consent updateContactConsent(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @PathVariable String contactId,
                                        @PathVariable String consentId, @RequestBody(required = false) ConsentInput body,
                                        HttpServletRequest request) {
        return service.update(currentProfileService.requireActiveProfile(user), contact(id, contactId), parseId(consentId), body,
                RequestId.from(request));
    }

    @GetMapping("/api/enrolment/learners/{id}/consents")
    public Consents learnerConsents(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return service.list(currentProfileService.requireActiveProfile(user), learner(id));
    }

    @PostMapping("/api/enrolment/learners/{id}/consents")
    public Consent createLearnerConsent(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                        @RequestBody(required = false) ConsentInput body, HttpServletRequest request) {
        return service.create(currentProfileService.requireActiveProfile(user), learner(id), body, RequestId.from(request));
    }

    @PutMapping("/api/enrolment/learners/{id}/consents/{consentId}")
    public Consent updateLearnerConsent(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @PathVariable String consentId,
                                        @RequestBody(required = false) ConsentInput body, HttpServletRequest request) {
        return service.update(currentProfileService.requireActiveProfile(user), learner(id), parseId(consentId), body,
                RequestId.from(request));
    }

    private static ConsentService.Subject contact(String organizationId, String contactId) {
        return new ConsentService.Subject(ConsentSubjectType.CONTACT, parseId(contactId), parseId(organizationId));
    }

    private static ConsentService.Subject learner(String learnerId) {
        return new ConsentService.Subject(ConsentSubjectType.LEARNER, parseId(learnerId), null);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
