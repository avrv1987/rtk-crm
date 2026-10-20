package ru.rtk.crm.mail;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

// Настройки сотрудника, экран администратора и открытие приглашения. Права проверяют сервисы: экран «Почта» — только
// ADMIN; открытие приглашения — без входа, по одноразовому токену.
@RestController
@MailEnabled
class MailController {
    private final CurrentProfileService currentProfileService;
    private final MailSettingsService settingsService;
    private final MailInvitationService invitationService;

    MailController(CurrentProfileService currentProfileService, MailSettingsService settingsService, MailInvitationService invitationService) {
        this.currentProfileService = currentProfileService;
        this.settingsService = settingsService;
        this.invitationService = invitationService;
    }

    // Настройки тем открыты и координатору вуза: у него одна отключаемая тема — «Школа приняла мой ответ».
    @GetMapping("/api/mail/preferences")
    MailSettingsService.Preferences preferences(@AuthenticationPrincipal OidcUser user) {
        return settingsService.preferences(currentProfileService.requireSessionProfile(user));
    }

    @PutMapping("/api/mail/preferences")
    MailSettingsService.Preferences savePreferences(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) MailSettingsService.PreferencesUpdate body
    ) {
        return settingsService.savePreferences(currentProfileService.requireSessionProfile(user), body);
    }

    @GetMapping("/api/mail/admin/settings")
    MailSettingsService.View settings(@AuthenticationPrincipal OidcUser user) {
        return settingsService.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/api/mail/admin/settings")
    MailSettingsService.View updateSettings(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) MailSettingsService.UpdateRequest body,
            HttpServletRequest request
    ) {
        return settingsService.update(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @PostMapping("/api/mail/admin/check")
    MailSettingsService.CheckResult check(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return settingsService.check(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PostMapping("/api/mail/admin/test")
    MailRepository.JournalRow test(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return settingsService.test(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @GetMapping("/api/mail/admin/outbox")
    MailSettingsService.JournalPage journal(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return settingsService.journal(currentProfileService.requireActiveProfile(user), status, page, size);
    }

    @GetMapping("/api/mail/admin/outbox/{id}")
    MailOutbox.LetterView letter(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        return settingsService.letter(currentProfileService.requireActiveProfile(user), id, RequestId.from(request));
    }

    @PostMapping("/api/mail/admin/outbox/{id}/retry")
    MailRepository.JournalRow retry(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        return settingsService.retry(currentProfileService.requireActiveProfile(user), id, RequestId.from(request));
    }

    @PostMapping("/api/mail/admin/outbox/{id}/cancel")
    MailRepository.JournalRow cancel(@AuthenticationPrincipal OidcUser user, @PathVariable UUID id, HttpServletRequest request) {
        return settingsService.cancel(currentProfileService.requireActiveProfile(user), id, RequestId.from(request));
    }

    @PostMapping("/api/mail/admin/outbox/requeue")
    Map<String, Integer> requeue(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return Map.of("requeued", settingsService.requeue(currentProfileService.requireActiveProfile(user), RequestId.from(request)));
    }

    @PostMapping("/api/mail/admin/subject-report")
    MailRepository.JournalRow subjectReport(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) MailSettingsService.SubjectReportRequest body,
            HttpServletRequest request
    ) {
        return settingsService.sendSubjectReport(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    // Токен — в теле, а не в адресе: адрес запроса пишут журналы прокси и сервера. Пароль в ответе — единственный
    // раз, поэтому ответ не кэшируется ни браузером, ни прокси.
    @PostMapping("/api/mail/invitations")
    ResponseEntity<MailInvitationService.Redeemed> redeem(@RequestBody(required = false) RedeemRequest body, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invitationService.redeem(body == null ? null : body.token(), RequestId.from(request)));
    }

    record RedeemRequest(String token) {
    }
}
