package ru.rtk.crm.people;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PeopleModels {
    private PeopleModels() {
    }

    public enum HourUnit {
        ACADEMIC(45, "академ. ч"),
        ASTRONOMICAL(60, "астроном. ч");

        private final int minutes;
        private final String shortLabel;

        HourUnit(int minutes, String shortLabel) {
            this.minutes = minutes;
            this.shortLabel = shortLabel;
        }

        public String shortLabel() {
            return shortLabel;
        }

        // Пересчёт по минутам: 1 астрономический час = 60/45 академического. Одна цифра после запятой, как в записях.
        public BigDecimal convert(BigDecimal hours, HourUnit target) {
            if (hours == null || target == this) {
                return hours;
            }
            return hours.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(target.minutes), 1, RoundingMode.HALF_UP);
        }
    }

    public enum ConfirmationStatus {
        NOT_REQUESTED, REQUESTED, CONFIRMED, REJECTED
    }

    public enum InternKind {
        STUDENT, TEACHER
    }

    public enum InternshipResult {
        PLANNED, IN_PROGRESS, COMPLETED, NOT_COMPLETED
    }

    public enum QualificationResult {
        IN_PROGRESS, CERTIFIED, NOT_CERTIFIED
    }

    public record PeopleSettings(
            HourUnit teachingUnit,
            HourUnit internshipUnit,
            HourUnit qualificationUnit,
            Integer frdoDeadlineDays,
            int frdoNoticeDays,
            boolean saved,
            OffsetDateTime updatedAt,
            String updatedByName
    ) {
        static final PeopleSettings DEFAULT = new PeopleSettings(
                HourUnit.ACADEMIC, HourUnit.ASTRONOMICAL, HourUnit.ACADEMIC, null, 7, false, null, null);
    }

    public record PeopleSettingsRequest(
            HourUnit teachingUnit,
            HourUnit internshipUnit,
            HourUnit qualificationUnit,
            Integer frdoDeadlineDays,
            Integer frdoNoticeDays
    ) {
    }

    public record TeachingInput(
            UUID interactionId,
            UUID agreementId,
            UUID agreementActivityId,
            String discipline,
            UUID performerProfileId,
            String performerName,
            String performerCompany,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            HourUnit hourUnit,
            String studentGroup,
            ConfirmationStatus confirmationStatus,
            String confirmationDocument,
            LocalDate confirmedOn,
            Integer version
    ) {
    }

    public record TeachingRecord(
            UUID id,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            UUID agreementId,
            String agreementNumber,
            UUID agreementActivityId,
            String agreementActivityTitle,
            String discipline,
            UUID performerProfileId,
            String performerName,
            String performerCompany,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            HourUnit hourUnit,
            String studentGroup,
            ConfirmationStatus confirmationStatus,
            String confirmationDocument,
            LocalDate confirmedOn,
            int version,
            String updatedByName,
            OffsetDateTime updatedAt
    ) {
    }

    public record InternshipInput(
            UUID interactionId,
            UUID agreementActivityId,
            String internName,
            InternKind internKind,
            String place,
            UUID mentorProfileId,
            String mentorName,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal internHours,
            BigDecimal mentorHours,
            HourUnit hourUnit,
            InternshipResult result,
            Boolean employed,
            String document,
            Integer version
    ) {
        @Override
        public String toString() {
            return "InternshipInput[masked]";
        }
    }

    public record InternshipRecord(
            UUID id,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            UUID agreementActivityId,
            String agreementActivityTitle,
            String internName,
            InternKind internKind,
            String place,
            UUID mentorProfileId,
            String mentorName,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal internHours,
            BigDecimal mentorHours,
            HourUnit hourUnit,
            InternshipResult result,
            boolean employed,
            String document,
            boolean anonymized,
            int version,
            String updatedByName,
            OffsetDateTime updatedAt
    ) {
        @Override
        public String toString() {
            return "InternshipRecord[" + id + "]";
        }
    }

    public record QualificationInput(
            UUID interactionId,
            UUID agreementActivityId,
            UUID teacherRosterId,
            UUID contactId,
            String personName,
            String personEmail,
            String program,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            HourUnit hourUnit,
            QualificationResult result,
            String certificateNumber,
            LocalDate certificateDate,
            LocalDate frdoSubmittedOn,
            Integer version
    ) {
        @Override
        public String toString() {
            return "QualificationInput[masked]";
        }
    }

    public record QualificationRecord(
            UUID id,
            UUID organizationId,
            String organizationName,
            UUID interactionId,
            String interactionTitle,
            UUID agreementActivityId,
            String agreementActivityTitle,
            UUID teacherRosterId,
            String teacherRosterTitle,
            UUID contactId,
            String personName,
            String personEmail,
            String program,
            LocalDate startsOn,
            LocalDate endsOn,
            BigDecimal hours,
            HourUnit hourUnit,
            QualificationResult result,
            String certificateNumber,
            LocalDate certificateDate,
            LocalDate frdoSubmittedOn,
            LocalDate frdoDueOn,
            boolean frdoOverdue,
            boolean anonymized,
            int version,
            String updatedByName,
            OffsetDateTime updatedAt
    ) {
        @Override
        public String toString() {
            return "QualificationRecord[" + id + "]";
        }
    }

    public record Option(UUID id, String title) {
    }

    public record ContactOption(UUID id, String name, String email) {
        @Override
        public String toString() {
            return "ContactOption[" + id + "]";
        }
    }

    public record ActivityOption(UUID id, UUID agreementId, String agreementNumber, String title, String kindName) {
    }

    public record PeopleOptions(
            List<Option> employees,
            List<Option> interactions,
            List<Option> agreements,
            List<ActivityOption> activities,
            List<Option> teacherRosters,
            List<ContactOption> teachers
    ) {
    }

    public record PeopleOverview(
            UUID organizationId,
            boolean canEdit,
            PeopleSettings settings,
            List<TeachingRecord> teaching,
            List<InternshipRecord> internships,
            List<QualificationRecord> qualifications,
            PeopleOptions options
    ) {
    }

    public record ImportError(int row, String message) {
    }

    public record ImportResult(boolean applied, int rows, int created, List<ImportError> errors) {
    }

    // Строка «Напоминаний»: удостоверение выдано, а в ФРДО ещё не передано, и срок подходит или прошёл.
    public record FrdoReminder(
            UUID qualificationId,
            UUID organizationId,
            String organizationName,
            String program,
            String certificateNumber,
            LocalDate certificateDate,
            LocalDate dueOn
    ) {
    }

    public record HoursRow(String key, String title, int records, BigDecimal hours) {
    }

    public record TeachingReport(
            HourUnit unit,
            int records,
            BigDecimal hours,
            BigDecimal confirmedHours,
            List<HoursRow> byOrganization,
            List<HoursRow> byPerformer,
            List<HoursRow> byMonth
    ) {
    }

    public record InternshipReport(
            HourUnit unit,
            int interns,
            int students,
            int teachers,
            int completed,
            int employed,
            BigDecimal internHours,
            BigDecimal mentorHours,
            List<HoursRow> byOrganization,
            List<HoursRow> byPlace
    ) {
    }

    public record QualificationReport(
            HourUnit unit,
            int people,
            int certified,
            int notCertified,
            int inProgress,
            BigDecimal hours,
            int frdoSubmitted,
            int frdoPending,
            int frdoOverdue,
            Integer frdoDeadlineDays,
            List<HoursRow> byOrganization,
            List<HoursRow> byProgram
    ) {
    }

    public record PeopleReport(
            LocalDate from,
            LocalDate to,
            OffsetDateTime generatedAt,
            TeachingReport teaching,
            InternshipReport internships,
            QualificationReport qualifications,
            List<String> notes
    ) {
    }
}
