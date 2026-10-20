package ru.rtk.crm.enrolment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Вариант файла в формате Moodle «Загрузка пользователей» (CSV, UTF-8, запятая). В него идут только поля учётной
// записи; паспорт, СНИЛС, адрес и диплом не выгружаются. Колонки зачисления на курс (course1, role1) не пишутся:
// краткое имя курса Moodle для потока в CRM не хранится, его нужно согласовать с приёмником.
final class MoodleCsvWriter {
    static final List<String> HEADER = List.of("username", "firstname", "lastname", "middlename", "email", "phone1");
    private static final Pattern PHONE = Pattern.compile("\\+\\d{6,15}");

    private MoodleCsvWriter() {
    }

    static byte[] write(List<LearnerProfile> profiles, LmsLogin login) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADER)).append("\r\n");
        for (LearnerProfile profile : profiles) {
            String email = profile.email() == null ? null : profile.email().strip().toLowerCase(Locale.ROOT);
            csv.append(Stream.of(login.of(email), profile.firstName(), profile.lastName(), profile.middleName(), email, profile.phone())
                    .map(MoodleCsvWriter::cell)
                    .collect(Collectors.joining(","))).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Значение, похожее на формулу, получает апостроф: файл часто открывают в табличном редакторе. Телефон
    // в международном формате формулой не считается, иначе он испортится.
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = LmsRosterWorkbookWriter.isFormulaLike(value) && !PHONE.matcher(value).matches() ? "'" + value : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
