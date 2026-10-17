package ru.rtk.crm.source;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
record LearningUnit(
        long courseId,
        Long groupId,
        String courseShortname,
        String courseName,
        String groupName,
        int participants,
        int teachers,
        Integer completed,
        Integer notCompleted,
        int unknown,
        int groupsCount,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int shared,
        @JsonInclude(JsonInclude.Include.NON_NULL) Activity activity
) {
    static final String COURSE_RECORD = "moodle_course";
    static final String GROUP_RECORD = "moodle_group";

    static LearningUnit parseStored(ObjectMapper objectMapper, String payload) {
        try {
            return objectMapper.readValue(payload, LearningUnit.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Moodle snapshot payload cannot be read", exception);
        }
    }

    boolean group() {
        return groupId != null;
    }

    String recordType() {
        return group() ? GROUP_RECORD : COURSE_RECORD;
    }

    String externalId() {
        return group() ? courseKey() + ":" + groupId : courseKey();
    }

    String courseKey() {
        return Long.toString(courseId);
    }

    String mappingKind() {
        return group() ? "GROUP" : "COURSE";
    }

    String label() {
        String course = "«" + courseName + "» (" + courseShortname + ")";
        return group() ? "Группа «" + groupName + "», курс " + course : "Курс " + course;
    }

    /**
     * Активность (последний вход в курс) меняется каждый день, поэтому в сравнение не входит: иначе каждый запуск писал бы
     * событие «Данные LMS», сдвигал changed_at снимка и возвращал скрытые сигналы, версия которых — changed_at.
     */
    boolean sameCounts(LearningUnit other) {
        return participants == other.participants
                && teachers == other.teachers
                && Objects.equals(completed, other.completed)
                && Objects.equals(notCompleted, other.notCompleted)
                && unknown == other.unknown
                && groupsCount == other.groupsCount
                && shared == other.shared;
    }

    /**
     * Числа по последнему входу в курс на момент чтения; null — Moodle не отдал поле lastcourseaccess.
     * inactive — заходили когда-то, но не за последние app.lms-signals.inactive-after-days дней.
     */
    record Activity(int active7d, int active30d, int neverAccessed, int inactive, int teachersActive7d, int teachersActive14d) {
    }
}
