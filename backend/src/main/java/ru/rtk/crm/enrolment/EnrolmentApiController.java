package ru.rtk.crm.enrolment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.security.RequestId;

@RestController
@RequestMapping("/api/enrolment")
public class EnrolmentApiController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final CurrentProfileService currentProfileService;
    private final PaidOrderUploadService paidOrderUploadService;
    private final EnrolmentStreamService streamService;
    private final EnrolmentLearnerService learnerService;
    private final QuestionnaireImportService questionnaireImportService;
    private final LmsRosterService lmsRosterService;

    public EnrolmentApiController(
            CurrentProfileService currentProfileService,
            PaidOrderUploadService paidOrderUploadService,
            EnrolmentStreamService streamService,
            EnrolmentLearnerService learnerService,
            QuestionnaireImportService questionnaireImportService,
            LmsRosterService lmsRosterService
    ) {
        this.currentProfileService = currentProfileService;
        this.paidOrderUploadService = paidOrderUploadService;
        this.streamService = streamService;
        this.learnerService = learnerService;
        this.questionnaireImportService = questionnaireImportService;
        this.lmsRosterService = lmsRosterService;
    }

    @PostMapping(path = "/paid-orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PaidOrderUpload uploadPaidOrders(
            @AuthenticationPrincipal OidcUser user,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart(name = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        return paidOrderUploadService.upload(currentProfileService.requireActiveProfile(user), file, idempotencyKey,
                RequestId.from(request));
    }

    @GetMapping("/streams")
    public EnrolmentStreamsView streams(@AuthenticationPrincipal OidcUser user) {
        return streamService.streams(currentProfileService.requireActiveProfile(user));
    }

    @PatchMapping("/streams/{id}")
    public EnrolmentStreamView updateStream(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) EnrolmentStreamUpdate update,
            HttpServletRequest request
    ) {
        return streamService.update(currentProfileService.requireActiveProfile(user), id, update, idempotencyKey,
                RequestId.from(request));
    }

    @GetMapping("/streams/{id}/learners")
    public ResponseEntity<EnrolmentStreamLearners> streamLearners(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) {
        return noStore(streamService.learners(currentProfileService.requireActiveProfile(user), id, RequestId.from(request)));
    }

    @PostMapping(path = "/streams/{id}/questionnaire-imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionnaireImport> previewQuestionnaireImport(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            HttpServletRequest request
    ) {
        return noStore(questionnaireImportService.preview(currentProfileService.requireActiveProfile(user), id, file,
                RequestId.from(request)));
    }

    @PostMapping(path = "/streams/{id}/questionnaire-imports/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionnaireImport> applyQuestionnaireImport(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "fingerprint", required = false) String fingerprint,
            HttpServletRequest request
    ) {
        return noStore(questionnaireImportService.apply(currentProfileService.requireActiveProfile(user), id, file, fingerprint,
                idempotencyKey, RequestId.from(request)));
    }

    @PostMapping("/streams/{id}/lms-roster")
    public ResponseEntity<byte[]> exportLmsRoster(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) LmsRosterRequest rosterRequest,
            HttpServletRequest request
    ) {
        LmsRoster roster = lmsRosterService.export(currentProfileService.requireActiveProfile(user), id, rosterRequest,
                RequestId.from(request));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .contentLength(roster.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(roster.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Roster-Export-Id", roster.exportId().toString())
                .header("X-Roster-Rows", Integer.toString(roster.rows()))
                .header("X-Content-Type-Options", "nosniff")
                .body(roster.content());
    }

    @PostMapping("/roster-exports/{id}/transferred")
    public RosterExportMarked markRosterTransferred(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        return lmsRosterService.markTransferred(currentProfileService.requireActiveProfile(user), id, idempotencyKey,
                RequestId.from(request));
    }

    @PostMapping("/learners/search")
    public ResponseEntity<List<LearnerSummary>> searchLearners(
            @AuthenticationPrincipal OidcUser user,
            @RequestBody(required = false) LearnerSearch search,
            HttpServletRequest request
    ) {
        return noStore(learnerService.search(currentProfileService.requireActiveProfile(user), search, RequestId.from(request)));
    }

    @GetMapping("/learners/{id}")
    public ResponseEntity<LearnerCardView> learner(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            HttpServletRequest request
    ) {
        return noStore(learnerService.card(currentProfileService.requireActiveProfile(user), id, RequestId.from(request)));
    }

    @PatchMapping("/learners/{id}")
    public ResponseEntity<LearnerCardView> updateLearner(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) LearnerUpdate update,
            HttpServletRequest request
    ) {
        return noStore(learnerService.update(currentProfileService.requireActiveProfile(user), id, update, idempotencyKey,
                RequestId.from(request)));
    }

    @PostMapping("/learners/{id}/reveal")
    public ResponseEntity<LearnerRevealed> revealLearner(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestBody(required = false) LearnerReveal reveal,
            HttpServletRequest request
    ) {
        return noStore(learnerService.reveal(currentProfileService.requireActiveProfile(user), id, reveal, RequestId.from(request)));
    }

    @PostMapping("/learners/{id}/move-enrolments")
    public LearnerMoveResult moveLearnerEnrolments(
            @AuthenticationPrincipal OidcUser user,
            @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) LearnerMove move,
            HttpServletRequest request
    ) {
        return learnerService.move(currentProfileService.requireActiveProfile(user), id, move, idempotencyKey, RequestId.from(request));
    }

    @GetMapping("/learners/{id}/history")
    public List<LearnerHistoryEntry> learnerHistory(@AuthenticationPrincipal OidcUser user, @PathVariable String id) {
        return learnerService.history(currentProfileService.requireActiveProfile(user), id);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
