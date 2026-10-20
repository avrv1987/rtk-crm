package ru.rtk.crm.privacy;

import java.time.LocalDate;
import java.util.UUID;

// Запись учёта людей и часов, где субъект указан по ФИО (или почте): исполнитель преподавания не из справочника
// сотрудников, стажёр, наставник не из справочника, преподаватель на повышении квалификации.
public record SubjectPersonRecord(
        UUID id,
        Kind kind,
        String organizationName,
        String name,
        String details,
        LocalDate startsOn,
        LocalDate endsOn,
        boolean anonymized
) {
    public enum Kind {
        TEACHING_PERFORMER("исполнитель преподавания в вузе"),
        INTERN("стажёр"),
        MENTOR("наставник стажировки"),
        QUALIFICATION("повышение квалификации преподавателя");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Ref(UUID id, Kind kind) {
    }
}
