package ru.rtk.crm.enrolment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ru.rtk.crm.catalog.PersonalDataStatus;

enum Gender {
    MALE("М"),
    FEMALE("Ж");

    private final String title;

    Gender(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }

    static Optional<Gender> fromTitle(String value) {
        String key = value.strip();
        return Arrays.stream(values()).filter(gender -> gender.title.equalsIgnoreCase(key)).findFirst();
    }
}

enum Education {
    NONE("Без образования"),
    BASIC_GENERAL("Основное общее образование - 9 классов"),
    SECONDARY_GENERAL("Среднее общее образование - 11 классов"),
    SECONDARY_VOCATIONAL("Среднее профессиональное образование"),
    HIGHER_BACHELOR("Высшее образование \u2013 бакалавриат"),
    HIGHER_SPECIALIST_MASTER("Высшее образование \u2013 специалитет, магистратура"),
    HIGHER_QUALIFICATION("Высшее образование \u2013 подготовка кадров высшей квалификации");

    private final String title;

    Education(String title) {
        this.title = title;
    }

    String title() {
        return title;
    }

    static Optional<Education> fromTitle(String value) {
        String key = key(value);
        return Arrays.stream(values()).filter(education -> key(education.title).equals(key)).findFirst();
    }

    private static String key(String value) {
        return LearnerRules.collapseSpaces(value)
                .replaceAll("\\s*[-\u2013\u2014]\\s*", " - ")
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
    }
}

record LearnerFieldError(LearnerField field, String message) {
}

record LearnerWorkbookRow(int rowNumber, LearnerProfile profile, List<LearnerFieldError> errors, List<LearnerFieldError> warnings) {
    boolean valid() {
        return errors.isEmpty();
    }
}

record LearnerWorkbookColumn(String letter, String header) {
}

record LearnerWorkbook(List<String> ignoredHeaders, Map<LearnerField, LearnerWorkbookColumn> columns, List<LearnerWorkbookRow> rows) {
}

record LearnerCompleteness(int filled, int required) {
    boolean complete() {
        return filled == required;
    }
}

record Learner(
        UUID id,
        LearnerProfile profile,
        PersonalDataStatus status,
        int version,
        Set<LearnerField> missingFields,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

record LearnerCard(
        UUID id,
        PersonalDataStatus status,
        int version,
        Map<LearnerField, String> maskedValues,
        Set<LearnerField> missingFields,
        List<LearnerEnrolment> enrolments,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

record EnrolmentStream(UUID id, String courseKey, String courseName, int streamNo, LocalDate endsOn, int version) {
}

record LearnerEnrolment(
        UUID id,
        UUID learnerId,
        UUID streamId,
        String courseName,
        int streamNo,
        LocalDate streamEndsOn,
        UUID sourceRecordId,
        String orderNumber,
        UUID lmsExportId,
        OffsetDateTime lmsExportedAt,
        OffsetDateTime lmsTransferredAt,
        OffsetDateTime lmsSentAt,
        BatchItemResult lmsResult
) {
}

// Файл сформирован, партия передана и строка принята LMS — разные факты. TRANSFERRED ставится только по результату
// приёма строки; отметка «передано» без результата даёт SENT.
enum LmsStatus {
    PENDING,
    EXPORTED,
    SENT,
    FAILED,
    TRANSFERRED;

    static LmsStatus of(OffsetDateTime exportedAt, OffsetDateTime transferredAt, OffsetDateTime sentAt, BatchItemResult result) {
        if (transferredAt != null) {
            return TRANSFERRED;
        }
        if (exportedAt == null) {
            return PENDING;
        }
        if (result == BatchItemResult.FAILED) {
            return FAILED;
        }
        return sentAt != null ? SENT : EXPORTED;
    }
}

enum BatchItemResult {
    PENDING,
    ACCEPTED,
    FAILED
}

enum RosterFormat {
    XLSX_TEMPLATE,
    MOODLE_CSV
}

enum EnrolmentQueueKind {
    MISSING_DATA,
    AMBIGUOUS_MATCH,
    READY,
    NOT_SENT,
    AWAITING_RESULT,
    LMS_ERROR,
    MOVED_AFTER_TRANSFER,
    CHANGED_AFTER_TRANSFER
}

record StreamEnrolmentRow(
        UUID id,
        UUID learnerId,
        UUID streamId,
        String orderNumber,
        UUID lmsExportId,
        OffsetDateTime lmsExportedAt,
        OffsetDateTime lmsTransferredAt,
        PersonalDataStatus status,
        Set<LearnerField> missingFields,
        String emailHmac,
        String phoneHmac,
        int learnerVersion,
        OffsetDateTime lmsSentAt,
        BatchItemResult lmsResult,
        String lmsError,
        Integer lmsSyncedVersion,
        String lmsReconcileReason
) {
    boolean available() {
        return status == PersonalDataStatus.ACTIVE;
    }

    LmsStatus lmsStatus() {
        return LmsStatus.of(lmsExportedAt, lmsTransferredAt, lmsSentAt, lmsResult);
    }

    boolean missingForLms() {
        return !LearnerRules.missingForLms(missingFields).isEmpty();
    }
}

record StreamSummary(UUID id, String courseName, int streamNo, String programName, LocalDate endsOn, int version) {
    String title() {
        return courseName + ", поток " + streamNo;
    }
}

record EnrolmentStreamsView(List<EnrolmentStreamView> streams, EnrolmentCountersView counters) {
}

record EnrolmentStreamView(
        UUID id,
        String courseName,
        int streamNo,
        String programName,
        LocalDate endsOn,
        LocalDate keepUntil,
        int version,
        int paid,
        int profilesComplete,
        int exported,
        int transferred,
        int pending
) {
}

record EnrolmentCountersView(long profiles, long profilesLimit, boolean nearLimit, long streamsWithoutEndDate) {
}

record EnrolmentStreamUpdate(Integer version, LocalDate endsOn, Boolean confirmPastDate) {
}

record EnrolmentStreamLearners(
        EnrolmentStreamView stream,
        List<StreamLearner> learners,
        List<RosterExport> exports,
        RosterSummary roster
) {
}

record StreamLearner(
        UUID learnerId,
        UUID enrolmentId,
        String orderNumber,
        PersonalDataStatus status,
        String lastName,
        String firstName,
        String middleName,
        String phone,
        String email,
        int filledFields,
        int requiredFields,
        boolean complete,
        Set<LearnerField> missingForLms,
        boolean duplicateEmail,
        LmsStatus lmsStatus,
        UUID lmsExportId,
        OffsetDateTime lmsExportedAt,
        OffsetDateTime lmsTransferredAt,
        String lmsError
) {
    @Override
    public String toString() {
        return "StreamLearner[learnerId=" + learnerId + ", masked]";
    }
}

record RosterExport(
        UUID exportId,
        OffsetDateTime exportedAt,
        int rows,
        int transferred,
        int failed,
        int pending,
        OffsetDateTime sentAt,
        RosterFormat format,
        String checksum,
        boolean legacy
) {
}

record RosterSummary(RosterScope pending, RosterScope all) {
}

record RosterScope(int rows, int missingRequired, int duplicateEmails, int unavailable) {
}

enum RosterMode {
    PENDING,
    ALL
}

enum IncompleteProfiles {
    INCLUDE,
    EXCLUDE
}

record LmsRosterRequest(RosterMode mode, IncompleteProfiles incomplete, RosterFormat format) {
}

record LmsRoster(UUID exportId, String fileName, int rows, byte[] content, RosterFormat format, String checksum) {
}

record EnrolmentBatchItemView(
        UUID id,
        int rowNo,
        UUID enrolmentId,
        UUID learnerId,
        String lastName,
        String firstName,
        String orderNumber,
        Integer learnerVersion,
        Integer currentLearnerVersion,
        BatchItemResult result,
        String error,
        String resultSource,
        OffsetDateTime resultAt
) {
    @Override
    public String toString() {
        return "EnrolmentBatchItemView[rowNo=" + rowNo + ", masked]";
    }
}

record EnrolmentBatchView(
        UUID id,
        UUID streamId,
        String streamTitle,
        RosterFormat format,
        String fileName,
        String checksum,
        boolean legacy,
        OffsetDateTime createdAt,
        String createdByName,
        OffsetDateTime sentAt,
        int rows,
        int accepted,
        int failed,
        int pending,
        List<EnrolmentBatchItemView> items,
        boolean moodleTransferAvailable
) {
}

record MoodleTransferResult(EnrolmentBatchView batch, int sent, int accepted, int failed, String stoppedReason) {
}

record MoodleMoveResult(UUID enrolmentId, long courseFrom, long courseTo) {
}

record BatchResultItem(UUID itemId, BatchItemResult result, String error) {
}

record BatchResultUpdate(List<BatchResultItem> items) {
}

record BatchResultsApplied(EnrolmentBatchView batch, int changed, List<String> skipped) {
}

record EnrolmentQueueItem(
        EnrolmentQueueKind kind,
        UUID enrolmentId,
        UUID learnerId,
        UUID streamId,
        String streamTitle,
        String orderNumber,
        String lastName,
        String firstName,
        String detail,
        UUID batchId,
        int learnerVersion
) {
    @Override
    public String toString() {
        return "EnrolmentQueueItem[kind=" + kind + ", masked]";
    }
}

record EnrolmentQueueGroup(EnrolmentQueueKind kind, int total, List<EnrolmentQueueItem> items) {
}

record EnrolmentQueue(List<EnrolmentQueueGroup> groups, boolean moodleWriteAvailable) {
}

record LmsReconcile(EnrolmentQueueKind kind, Integer learnerVersion) {
}

record LmsReconciled(UUID enrolmentId, EnrolmentQueueKind kind) {
}

record RosterExportMarked(UUID exportId, UUID streamId, int rows, int marked) {
}

enum LearnerSearchKind {
    EMAIL,
    PHONE,
    SNILS,
    LAST_NAME
}

record LearnerSearch(LearnerSearchKind kind, String value) {
    @Override
    public String toString() {
        return "LearnerSearch[kind=" + kind + ", value=masked]";
    }
}

record LearnerEnrolmentView(
        UUID id,
        UUID streamId,
        String courseName,
        int streamNo,
        LocalDate streamEndsOn,
        String orderNumber,
        LmsStatus lmsStatus,
        OffsetDateTime lmsExportedAt,
        OffsetDateTime lmsTransferredAt
) {
    static LearnerEnrolmentView of(LearnerEnrolment enrolment) {
        return new LearnerEnrolmentView(
                enrolment.id(), enrolment.streamId(), enrolment.courseName(), enrolment.streamNo(), enrolment.streamEndsOn(),
                enrolment.orderNumber(),
                LmsStatus.of(enrolment.lmsExportedAt(), enrolment.lmsTransferredAt(), enrolment.lmsSentAt(), enrolment.lmsResult()),
                enrolment.lmsExportedAt(), enrolment.lmsTransferredAt()
        );
    }
}

record LearnerSummary(
        UUID id,
        PersonalDataStatus status,
        String lastName,
        String firstName,
        String middleName,
        String phone,
        String email,
        int filledFields,
        int requiredFields,
        boolean complete,
        List<LearnerEnrolmentView> enrolments
) {
    @Override
    public String toString() {
        return "LearnerSummary[id=" + id + ", masked]";
    }
}

record LearnerCardView(
        UUID id,
        PersonalDataStatus status,
        int version,
        Map<LearnerField, String> values,
        Set<LearnerField> missingFields,
        int filledFields,
        int requiredFields,
        boolean complete,
        List<LearnerEnrolmentView> enrolments,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    @Override
    public String toString() {
        return "LearnerCardView[id=" + id + ", masked]";
    }
}

record LearnerReveal(Set<LearnerFieldGroup> groups) {
}

record LearnerRevealed(Map<LearnerField, String> values) {
    @Override
    public String toString() {
        return "LearnerRevealed[masked]";
    }
}

record LearnerUpdate(Integer version, Map<String, String> fields) {
    @Override
    public String toString() {
        return "LearnerUpdate[version=" + version + ", masked]";
    }
}

record LearnerMove(String snils, Integer version, Boolean confirmNameMismatch) {
    @Override
    public String toString() {
        return "LearnerMove[version=" + version + ", masked]";
    }
}

record LearnerMoveResult(UUID learnerId, int enrolmentsMoved, int enrolmentsDropped, List<LearnerField> fieldsMoved) {
}

record LearnerHistoryEntry(
        UUID id,
        OffsetDateTime occurredAt,
        String action,
        String actionLabel,
        String actorDisplayName,
        String details
) {
}

enum QuestionnaireStatus {
    UPDATE,
    UNCHANGED,
    ERROR,
    CONFLICT
}

record QuestionnaireIssue(String column, String header, LearnerField field, String message, boolean warning) {
}

record QuestionnaireRow(
        int rowNumber,
        QuestionnaireStatus status,
        UUID learnerId,
        String lastName,
        String firstName,
        List<LearnerField> changedFields,
        List<QuestionnaireIssue> issues
) {
    QuestionnaireRow withoutNames() {
        return new QuestionnaireRow(rowNumber, status, learnerId, null, null, changedFields, issues);
    }

    @Override
    public String toString() {
        return "QuestionnaireRow[rowNumber=" + rowNumber + ", status=" + status + "]";
    }
}

record QuestionnaireImport(
        boolean applied,
        String fingerprint,
        List<String> ignoredHeaders,
        List<QuestionnaireRow> rows,
        int updated,
        int unchanged,
        int errors,
        int conflicts
) {
    QuestionnaireImport withoutNames() {
        return new QuestionnaireImport(applied, fingerprint, ignoredHeaders,
                rows.stream().map(QuestionnaireRow::withoutNames).toList(), updated, unchanged, errors, conflicts);
    }
}
