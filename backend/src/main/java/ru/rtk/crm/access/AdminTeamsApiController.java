package ru.rtk.crm.access;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamsApiController {
    private final CurrentProfileService currentProfileService;
    private final AdminTeamService adminTeamService;

    public AdminTeamsApiController(CurrentProfileService currentProfileService, AdminTeamService adminTeamService) {
        this.currentProfileService = currentProfileService;
        this.adminTeamService = adminTeamService;
    }

    @GetMapping
    public List<AdminTeam> list(@AuthenticationPrincipal OidcUser user) {
        return adminTeamService.list(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping
    public ResponseEntity<Team> create(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TeamRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminTeamService.create(
                currentProfileService.requireActiveProfile(user),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        ));
    }

    @PatchMapping("/{id}")
    public Team rename(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TeamRequest request,
            HttpServletRequest httpRequest
    ) {
        return adminTeamService.rename(
                currentProfileService.requireActiveProfile(user),
                parseTeamId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    @PatchMapping("/{id}/archive")
    public Team changeArchived(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TeamArchiveRequest request,
            HttpServletRequest httpRequest
    ) {
        return adminTeamService.changeArchived(
                currentProfileService.requireActiveProfile(user),
                parseTeamId(id),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }

    private UUID parseTeamId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор команды");
        }
    }
}
