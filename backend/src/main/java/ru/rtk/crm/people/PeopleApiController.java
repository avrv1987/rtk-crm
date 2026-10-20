package ru.rtk.crm.people;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.people.PeopleModels.ImportResult;
import ru.rtk.crm.people.PeopleModels.InternshipInput;
import ru.rtk.crm.people.PeopleModels.InternshipRecord;
import ru.rtk.crm.people.PeopleModels.PeopleOverview;
import ru.rtk.crm.people.PeopleModels.PeopleReport;
import ru.rtk.crm.people.PeopleModels.PeopleSettings;
import ru.rtk.crm.people.PeopleModels.PeopleSettingsRequest;
import ru.rtk.crm.people.PeopleModels.QualificationInput;
import ru.rtk.crm.people.PeopleModels.QualificationRecord;
import ru.rtk.crm.people.PeopleModels.TeachingInput;
import ru.rtk.crm.people.PeopleModels.TeachingRecord;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.web.ApiError;

@RestController
public class PeopleApiController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CurrentProfileService currentProfileService;
    private final PeopleService service;
    private final PeopleReportService reportService;

    public PeopleApiController(CurrentProfileService currentProfileService, PeopleService service, PeopleReportService reportService) {
        this.currentProfileService = currentProfileService;
        this.service = service;
        this.reportService = reportService;
    }

    @GetMapping("/api/organizations/{id}/people")
    public ResponseEntity<PeopleOverview> overview(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                                   @RequestParam(required = false) String interactionId) {
        return noStore(HttpStatus.OK, service.overview(currentProfileService.requireActiveProfile(user), uuid(id, "id"),
                interactionId == null || interactionId.isBlank() ? null : uuid(interactionId, "interactionId")));
    }

    @PostMapping("/api/organizations/{id}/university-teaching")
    public ResponseEntity<TeachingRecord> createTeaching(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                                         @RequestBody(required = false) TeachingInput body, HttpServletRequest request) {
        return noStore(HttpStatus.CREATED, service.createTeaching(currentProfileService.requireActiveProfile(user), uuid(id, "id"),
                body, RequestId.from(request)));
    }

    @PutMapping("/api/university-teaching/{recordId}")
    public ResponseEntity<TeachingRecord> updateTeaching(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                                         @RequestBody(required = false) TeachingInput body, HttpServletRequest request) {
        return noStore(HttpStatus.OK, service.updateTeaching(currentProfileService.requireActiveProfile(user), uuid(recordId, "recordId"),
                body, RequestId.from(request)));
    }

    @DeleteMapping("/api/university-teaching/{recordId}")
    public ResponseEntity<Void> deleteTeaching(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                               @RequestParam Integer version, HttpServletRequest request) {
        service.delete(currentProfileService.requireActiveProfile(user), PeopleService.TEACHING, uuid(recordId, "recordId"), version,
                RequestId.from(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/organizations/{id}/internships")
    public ResponseEntity<InternshipRecord> createInternship(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                                             @RequestBody(required = false) InternshipInput body, HttpServletRequest request) {
        return noStore(HttpStatus.CREATED, service.createInternship(currentProfileService.requireActiveProfile(user), uuid(id, "id"),
                body, RequestId.from(request)));
    }

    @PutMapping("/api/internships/{recordId}")
    public ResponseEntity<InternshipRecord> updateInternship(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                                             @RequestBody(required = false) InternshipInput body, HttpServletRequest request) {
        return noStore(HttpStatus.OK, service.updateInternship(currentProfileService.requireActiveProfile(user),
                uuid(recordId, "recordId"), body, RequestId.from(request)));
    }

    @DeleteMapping("/api/internships/{recordId}")
    public ResponseEntity<Void> deleteInternship(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                                 @RequestParam Integer version, HttpServletRequest request) {
        service.delete(currentProfileService.requireActiveProfile(user), PeopleService.INTERNSHIPS, uuid(recordId, "recordId"), version,
                RequestId.from(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/organizations/{id}/teacher-qualifications")
    public ResponseEntity<QualificationRecord> createQualification(@AuthenticationPrincipal OidcUser user, @PathVariable String id,
                                                                   @RequestBody(required = false) QualificationInput body,
                                                                   HttpServletRequest request) {
        return noStore(HttpStatus.CREATED, service.createQualification(currentProfileService.requireActiveProfile(user), uuid(id, "id"),
                body, RequestId.from(request)));
    }

    @PutMapping("/api/teacher-qualifications/{recordId}")
    public ResponseEntity<QualificationRecord> updateQualification(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                                                   @RequestBody(required = false) QualificationInput body,
                                                                   HttpServletRequest request) {
        return noStore(HttpStatus.OK, service.updateQualification(currentProfileService.requireActiveProfile(user),
                uuid(recordId, "recordId"), body, RequestId.from(request)));
    }

    @DeleteMapping("/api/teacher-qualifications/{recordId}")
    public ResponseEntity<Void> deleteQualification(@AuthenticationPrincipal OidcUser user, @PathVariable String recordId,
                                                    @RequestParam Integer version, HttpServletRequest request) {
        service.delete(currentProfileService.requireActiveProfile(user), PeopleService.QUALIFICATIONS, uuid(recordId, "recordId"),
                version, RequestId.from(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/teacher-qualifications/import-template")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal OidcUser user) {
        service.settings(currentProfileService.requireActiveProfile(user));
        byte[] content = QualificationWorkbook.template();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .contentLength(content.length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Шаблон_повышение_квалификации.xlsx", StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    @PostMapping(path = "/api/organizations/{id}/teacher-qualifications/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importQualifications(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String interactionId,
            @RequestParam(required = false) String teacherRosterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            HttpServletRequest request
    ) {
        return noStore(HttpStatus.OK, service.importQualifications(currentProfileService.requireActiveProfile(user), uuid(id, "id"), file,
                optionalUuid(interactionId, "interactionId"), optionalUuid(teacherRosterId, "teacherRosterId"), dryRun,
                RequestId.from(request)));
    }

    @GetMapping("/api/people-settings")
    public ResponseEntity<PeopleSettings> settings(@AuthenticationPrincipal OidcUser user) {
        return noStore(HttpStatus.OK, service.settings(currentProfileService.requireActiveProfile(user)));
    }

    @PutMapping("/api/admin/people-settings")
    public ResponseEntity<PeopleSettings> updateSettings(@AuthenticationPrincipal OidcUser user,
                                                         @RequestBody(required = false) PeopleSettingsRequest body,
                                                         HttpServletRequest request) {
        return noStore(HttpStatus.OK, service.updateSettings(currentProfileService.requireActiveProfile(user), body, RequestId.from(request)));
    }

    @DeleteMapping("/api/admin/people-settings")
    public ResponseEntity<Void> resetSettings(@AuthenticationPrincipal OidcUser user, HttpServletRequest request) {
        service.resetSettings(currentProfileService.requireActiveProfile(user), RequestId.from(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/reports/people")
    public ResponseEntity<PeopleReport> report(
            @AuthenticationPrincipal OidcUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return noStore(HttpStatus.OK, reportService.report(currentProfileService.requireActiveProfile(user), from, to));
    }

    @ExceptionHandler(PeopleRecordNotFoundException.class)
    public ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "Запись не найдена или недоступна", RequestId.from(request)));
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID optionalUuid(String value, String field) {
        return value == null || value.isBlank() ? null : uuid(value, field);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException(field, "Некорректный идентификатор");
        }
    }
}
