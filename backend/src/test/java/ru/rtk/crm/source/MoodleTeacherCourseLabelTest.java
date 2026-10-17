package ru.rtk.crm.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class MoodleTeacherCourseLabelTest {
    @Test
    void labelIsFoundAsSeparateWordInShortOrFullNameIgnoringCase() {
        SourceProperties.Moodle moodle = moodle(null);

        assertThat(moodle.teacherCourseLabel()).isEqualTo("ППС");
        assertThat(moodle.teacherCourse("PPS-2026", "Курс ППС 2026")).isTrue();
        assertThat(moodle.teacherCourse("ппс_java", "Java")).isTrue();
        assertThat(moodle.teacherCourse("JAVA", "Обучение (ППС)")).isTrue();
        assertThat(moodle.teacherCourse("JAVA", "Java-разработчик")).isFalse();
        assertThat(moodle.teacherCourse("JAVA", "Реппс и аппарат")).isFalse();
        assertThat(moodle.teacherCourse(null, null)).isFalse();
    }

    @Test
    void configuredLabelReplacesDefaultAndEmptyLabelSwitchesTheMarkOff() {
        assertThat(moodle("Преподаватели").teacherCourse("X", "Курс для преподавателей"))
                .as("слово другой формы не совпадает с меткой").isFalse();
        assertThat(moodle("Преподаватели").teacherCourse("X", "Преподаватели: Java")).isTrue();
        assertThat(moodle("Преподаватели").teacherCourse("X", "Курс ППС")).isFalse();
        assertThat(moodle("  ").teacherCourse("X", "Курс ППС")).isFalse();
    }

    @Test
    void labelIsBoundFromApplicationProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.sources.moodle.base-url", "https://lms.example.test",
                "app.sources.moodle.teacher-course-label", "МПС")));

        SourceProperties.Moodle bound = binder.bind("app.sources.moodle", SourceProperties.Moodle.class).get();

        assertThat(bound.teacherCourseLabel()).isEqualTo("МПС");
        assertThat(bound.baseUrl()).isEqualTo("https://lms.example.test");
        assertThat(binder.bind("app.sources.moodle", SourceProperties.Moodle.class).isBound()).isTrue();
    }

    private static SourceProperties.Moodle moodle(String label) {
        return new SourceProperties.Moodle("https://lms.example.test", "token", List.of(2L), List.of("student"), List.of("teacher"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), DataSize.ofMegabytes(1), label);
    }
}
