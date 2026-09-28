package ru.rtk.crm.work;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
public class LearningTrendApiController {
    private final CurrentProfileService currentProfileService;
    private final LearningTrendService learningTrendService;

    public LearningTrendApiController(CurrentProfileService currentProfileService, LearningTrendService learningTrendService) {
        this.currentProfileService = currentProfileService;
        this.learningTrendService = learningTrendService;
    }

    @GetMapping("/api/work/learning-trend")
    public LearningTrend trend(@AuthenticationPrincipal OidcUser user, @RequestParam(required = false) Integer days) {
        return learningTrendService.trend(currentProfileService.requireActiveProfile(user), days);
    }
}
