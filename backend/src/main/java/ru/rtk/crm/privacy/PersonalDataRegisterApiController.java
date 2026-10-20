package ru.rtk.crm.privacy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;

// Учёт по ПДн у администратора: журнал обращений субъектов, просмотр согласий и напоминания колокольчика.
@RestController
@RequestMapping("/api/admin")
public class PersonalDataRegisterApiController {
    private final CurrentProfileService currentProfileService;
    private final PersonalDataRequestService requests;
    private final ConsentService consents;

    public PersonalDataRegisterApiController(CurrentProfileService currentProfileService, PersonalDataRequestService requests,
                                             ConsentService consents) {
        this.currentProfileService = currentProfileService;
        this.requests = requests;
        this.consents = consents;
    }

    @GetMapping("/personal-data/requests")
    public PersonalDataRequests list(@AuthenticationPrincipal OidcUser user) {
        return requests.list(currentProfileService.requireActiveProfile(user));
    }

    @PostMapping("/personal-data/requests")
    public PersonalDataRequest create(@AuthenticationPrincipal OidcUser user, @RequestBody(required = false) PersonalDataRequestInput body,
                                      HttpServletRequest request) {
        return requests.create(currentProfileService.requireActiveProfile(user), body, RequestId.from(request));
    }

    @PutMapping("/personal-data/requests/{id}")
    public PersonalDataRequest update(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                      @RequestBody(required = false) PersonalDataRequestInput body, HttpServletRequest request) {
        return requests.update(currentProfileService.requireActiveProfile(user), parseId(id), body, RequestId.from(request));
    }

    @GetMapping("/personal-data/requests/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        byte[] content = requests.export(currentProfileService.requireActiveProfile(user), RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Журнал обращений субъектов ПДн " + LocalDate.now() + ".csv", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    @GetMapping("/reminders")
    public AdminReminders reminders(@AuthenticationPrincipal OidcUser user) {
        return requests.reminders(currentProfileService.requireActiveProfile(user));
    }

    @GetMapping("/personal-data/consents")
    public Consents consents(@AuthenticationPrincipal OidcUser user, @RequestParam(required = false) ConsentSubjectType subjectType,
                             @RequestParam(required = false) String subjectId) {
        if (subjectType == null || subjectId == null) {
            throw new InteractionValidationException("subjectId", "Укажите субъекта");
        }
        // Администратор согласия только видит; записывают их КАМ в карточке контакта и МУП в анкете.
        ru.rtk.crm.access.CrmProfile profile = currentProfileService.requireActiveProfile(user);
        ru.rtk.crm.access.AdminAuthorization.requireAdmin(profile);
        return consents.list(profile, new ConsentService.Subject(subjectType, parseId(subjectId), null));
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("id", "Некорректный идентификатор");
        }
    }
}
