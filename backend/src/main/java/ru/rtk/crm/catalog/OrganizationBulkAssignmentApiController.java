package ru.rtk.crm.catalog;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
public class OrganizationBulkAssignmentApiController {
    private final CurrentProfileService currentProfileService;
    private final OrganizationAssignmentService organizationAssignmentService;

    public OrganizationBulkAssignmentApiController(
            CurrentProfileService currentProfileService,
            OrganizationAssignmentService organizationAssignmentService
    ) {
        this.currentProfileService = currentProfileService;
        this.organizationAssignmentService = organizationAssignmentService;
    }

    @PostMapping("/api/organization-assignments")
    public OrganizationBulkAssignmentResult assign(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) OrganizationBulkAssignmentRequest request,
            HttpServletRequest httpRequest
    ) {
        return organizationAssignmentService.bulkAssign(
                currentProfileService.requireActiveProfile(user),
                request,
                idempotencyKey,
                RequestId.from(httpRequest)
        );
    }
}
