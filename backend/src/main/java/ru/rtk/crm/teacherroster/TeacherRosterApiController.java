package ru.rtk.crm.teacherroster;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRoster;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFile;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFileRequest;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterMarked;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterRequest;

@RestController
public class TeacherRosterApiController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CurrentProfileService currentProfileService;
    private final TeacherRosterService service;

    public TeacherRosterApiController(CurrentProfileService currentProfileService, TeacherRosterService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/interactions/{id}/teacher-rosters")
    public ResponseEntity<List<TeacherRoster>> list(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return noStore(HttpStatus.OK, service.list(currentProfileService.requireActiveProfile(user), parseUuid(id, "id")));
    }

    @PostMapping("/api/interactions/{id}/teacher-rosters")
    public ResponseEntity<TeacherRoster> create(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) TeacherRosterRequest request
    ) {
        return noStore(HttpStatus.CREATED,
                service.create(currentProfileService.requireActiveProfile(user), parseUuid(id, "id"), request));
    }

    @DeleteMapping("/api/teacher-rosters/{rosterId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal OidcUser user, @PathVariable String rosterId) {
        service.delete(currentProfileService.requireActiveProfile(user), parseUuid(rosterId, "rosterId"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/teacher-rosters/{rosterId}/members/{contactId}")
    public ResponseEntity<TeacherRoster> addMember(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String rosterId,
            @PathVariable String contactId
    ) {
        return noStore(HttpStatus.OK, service.addMember(currentProfileService.requireActiveProfile(user),
                parseUuid(rosterId, "rosterId"), parseUuid(contactId, "contactId")));
    }

    @DeleteMapping("/api/teacher-rosters/{rosterId}/members/{contactId}")
    public ResponseEntity<TeacherRoster> removeMember(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String rosterId,
            @PathVariable String contactId
    ) {
        return noStore(HttpStatus.OK, service.removeMember(currentProfileService.requireActiveProfile(user),
                parseUuid(rosterId, "rosterId"), parseUuid(contactId, "contactId")));
    }

    @PostMapping("/api/teacher-rosters/{rosterId}/lms-file")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String rosterId,
            @RequestBody(required = false) TeacherRosterFileRequest fileRequest,
            HttpServletRequest request
    ) {
        TeacherRosterFile file = service.export(currentProfileService.requireActiveProfile(user),
                parseUuid(rosterId, "rosterId"), fileRequest, RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .contentLength(file.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Roster-Export-Id", file.exportId().toString())
                .header("X-Roster-Rows", Integer.toString(file.rows()))
                .header("X-Roster-Skipped", Integer.toString(file.skipped()))
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    @PostMapping("/api/teacher-roster-exports/{exportId}/transferred")
    public ResponseEntity<TeacherRosterMarked> markTransferred(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String exportId,
            HttpServletRequest request
    ) {
        return noStore(HttpStatus.OK, service.markTransferred(currentProfileService.requireActiveProfile(user),
                parseUuid(exportId, "exportId"), RequestId.from(request)));
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
