package ru.rtk.crm.max;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

// Привязка и настройки канала в обычной сессии CRM (cookie и CSRF) и экран администратора. Мини-приложение сюда не
// ходит: у него своя цепочка безопасности на /api/max/app/**.
@RestController
@MaxEnabled
class MaxLinkController {
    private final CurrentProfileService currentProfileService;
    private final MaxLinkService linkService;
    private final MaxSettingsService settingsService;

    MaxLinkController(CurrentProfileService currentProfileService, MaxLinkService linkService, MaxSettingsService settingsService) {
        this.currentProfileService = currentProfileService;
        this.linkService = linkService;
        this.settingsService = settingsService;
    }

    @GetMapping("/api/max/link")
    MaxLinkService.LinkStatus status(@AuthenticationPrincipal OidcUser user) {
        return linkService.status(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/max/link")
    MaxLinkService.LinkStart start(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return linkService.start(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PostMapping("/api/max/link/confirm")
    MaxLinkService.LinkStatus confirm(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return linkService.confirm(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PostMapping("/api/max/link/reject")
    MaxLinkService.LinkStatus reject(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return linkService.reject(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @DeleteMapping("/api/max/link")
    MaxLinkService.LinkStatus unlink(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return linkService.unlink(currentProfileService.requireActiveProfile(user), RequestId.from(request));
    }

    @PutMapping("/api/max/link/preferences")
    MaxLinkService.LinkStatus preferences(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) MaxLinkRepository.Preferences preferences
    ) {
        return linkService.savePreferences(currentProfileService.requireActiveProfile(user), preferences);
    }

    @GetMapping("/api/max/admin/settings")
    MaxSettingsService.View settings(@AuthenticationPrincipal OidcUser user) {
        return settingsService.view(currentProfileService.requireActiveProfile(user));
    }

    @PutMapping("/api/max/admin/settings")
    MaxSettingsService.View updateSettings(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) MaxSettingsService.UpdateRequest body,
            HttpServletRequest request
    ) {
        return settingsService.update(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @PostMapping("/api/max/admin/check")
    MaxSettingsService.CheckResult check(@AuthenticationPrincipal OidcUser user) {
        return settingsService.check(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/max/admin/subscription")
    MaxSettingsService.View subscribe(@AuthenticationPrincipal OidcUser user) {
        return settingsService.subscribe(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/api/max/admin/outbox/requeue")
    Map<String, Integer> requeue(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        return Map.of("requeued", settingsService.requeue(currentProfileService.requireActiveProfile(user), RequestId.from(request)));
    }

    @GetMapping("/api/max/admin/links")
    List<MaxLinkRepository.AdminLink> links(@AuthenticationPrincipal OidcUser user) {
        return linkService.adminLinks(currentProfileService.requireActiveProfile(user));
    }

    @DeleteMapping("/api/max/admin/links/{profileId}")
    ResponseEntity<Void> adminUnlink(@AuthenticationPrincipal OidcUser user, @PathVariable UUID profileId, HttpServletRequest request) {
        linkService.adminUnlink(currentProfileService.requireActiveProfile(user), profileId, RequestId.from(request));
        return ResponseEntity.noContent().build();
    }
}
