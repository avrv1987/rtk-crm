package ru.rtk.crm.source;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.sources")
public record SourceProperties(
        int slots,
        int queueCapacity,
        String syncCron,
        Duration staleAfter,
        String settingsKey,
        Website website,
        Moodle moodle,
        Crm crm
) {
    public SourceProperties {
        crm = crm == null ? new Crm(null, null, Duration.ofSeconds(5), Duration.ofSeconds(20), 100, DataSize.ofMegabytes(5)) : crm;
    }

    public record Website(
            String baseUrl,
            String token,
            Duration connectTimeout,
            Duration readTimeout,
            int maxPages,
            DataSize maxPageSize
    ) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Moodle(
            String baseUrl,
            String token,
            List<Long> courseIds,
            List<String> studentRoles,
            List<String> teacherRoles,
            Duration connectTimeout,
            Duration readTimeout,
            DataSize maxResponseSize,
            String teacherCourseLabel
    ) {
        public static final String DEFAULT_TEACHER_COURSE_LABEL = "ППС";

        @ConstructorBinding
        public Moodle {
            courseIds = courseIds == null ? List.of() : List.copyOf(courseIds);
            studentRoles = studentRoles == null ? List.of() : List.copyOf(studentRoles);
            teacherRoles = teacherRoles == null ? List.of() : List.copyOf(teacherRoles);
            teacherCourseLabel = teacherCourseLabel == null ? DEFAULT_TEACHER_COURSE_LABEL : teacherCourseLabel.strip();
        }

        public Moodle(
                String baseUrl,
                String token,
                List<Long> courseIds,
                List<String> studentRoles,
                List<String> teacherRoles,
                Duration connectTimeout,
                Duration readTimeout,
                DataSize maxResponseSize
        ) {
            this(baseUrl, token, courseIds, studentRoles, teacherRoles, connectTimeout, readTimeout, maxResponseSize, null);
        }

        /**
         * Курс обучения преподавателей узнаётся по метке (по умолчанию «ППС») в кратком или полном названии курса.
         * Метка ищется как отдельное слово без учёта регистра: «ППС 2026» подходит, «реппс» нет. Пустая метка отключает признак.
         */
        public boolean teacherCourse(String shortname, String fullname) {
            if (teacherCourseLabel.isEmpty()) {
                return false;
            }
            return containsWord(shortname, teacherCourseLabel) || containsWord(fullname, teacherCourseLabel);
        }

        private static boolean containsWord(String text, String word) {
            if (text == null) {
                return false;
            }
            String haystack = text.toLowerCase(Locale.ROOT);
            String needle = word.toLowerCase(Locale.ROOT);
            for (int from = haystack.indexOf(needle); from >= 0; from = haystack.indexOf(needle, from + 1)) {
                int end = from + needle.length();
                boolean startsWord = from == 0 || !Character.isLetterOrDigit(haystack.charAt(from - 1));
                boolean endsWord = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
                if (startsWord && endsWord) {
                    return true;
                }
            }
            return false;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank() && !courseIds.isEmpty();
        }
    }

    public record Crm(
            String baseUrl,
            String token,
            Duration connectTimeout,
            Duration readTimeout,
            int maxPages,
            DataSize maxPageSize
    ) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
