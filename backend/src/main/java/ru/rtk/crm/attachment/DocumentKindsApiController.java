package ru.rtk.crm.attachment;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;

@RestController
public class DocumentKindsApiController {
    private final CurrentProfileService currentProfileService;
    private final DocumentKinds documentKinds;

    public DocumentKindsApiController(CurrentProfileService currentProfileService, DocumentKinds documentKinds) {
        this.currentProfileService = currentProfileService;
        this.documentKinds = documentKinds;
    }

    // Список нужен всем сотрудникам: выбор вида при загрузке, чек-лист этапа, редактор шаблона.
    @GetMapping("/api/document-kinds")
    public List<DocumentKinds.DocumentKind> list(@AuthenticationPrincipal OidcUser user) {
        currentProfileService.requireActiveProfile(user);
        return documentKinds.findAll();
    }
}
