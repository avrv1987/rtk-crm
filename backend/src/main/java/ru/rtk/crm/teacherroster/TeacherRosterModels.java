package ru.rtk.crm.teacherroster;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ru.rtk.crm.catalog.ContactRole;

public final class TeacherRosterModels {
    private TeacherRosterModels() {
    }

    public record TeacherRoster(
            UUID id,
            UUID interactionId,
            String lmsCourse,
            String lmsGroup,
            OffsetDateTime createdAt,
            String createdByName,
            int readyCount,
            int pendingCount,
            int transferredCount,
            List<TeacherRosterMember> members,
            List<TeacherRosterExport> exports
    ) {
    }

    public record TeacherRosterMember(
            UUID contactId,
            String name,
            String position,
            String email,
            ContactRole role,
            String lastName,
            String firstName,
            String middleName,
            List<String> problems,
            MemberStatus status,
            OffsetDateTime exportedAt,
            OffsetDateTime transferredAt
    ) {
        boolean ready() {
            return problems.isEmpty();
        }

        boolean transferred() {
            return transferredAt != null;
        }

        @Override
        public String toString() {
            return "TeacherRosterMember[" + contactId + "]";
        }
    }

    public enum MemberStatus {
        LISTED,
        EXPORTED,
        TRANSFERRED
    }

    public record TeacherRosterExport(
            UUID id,
            int rows,
            String exportedByName,
            OffsetDateTime exportedAt,
            String transferredByName,
            OffsetDateTime transferredAt
    ) {
    }

    public record TeacherRosterRequest(String lmsCourse, String lmsGroup) {
    }

    public record TeacherRosterFileRequest(FileMode mode) {
    }

    public enum FileMode {
        PENDING,
        ALL
    }

    public record TeacherRosterFile(UUID exportId, String fileName, int rows, int skipped, byte[] content) {
    }

    public record TeacherRosterMarked(int marked, TeacherRoster roster) {
    }
}
