package ru.rtk.crm.source;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
public class LearningCoverageApiController {
    private final CurrentProfileService currentProfileService;
    private final LearningCoverageService learningCoverageService;

    public LearningCoverageApiController(CurrentProfileService currentProfileService, LearningCoverageService learningCoverageService) {
        this.currentProfileService = currentProfileService;
        this.learningCoverageService = learningCoverageService;
    }

    @GetMapping("/api/learning-coverage")
    public LearningCoverage coverage(@AuthenticationPrincipal OidcUser user) {
        return learningCoverageService.coverage(currentProfileService.requireActiveProfile(user));
    }
}
