package ru.rtk.crm.people;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.people.PeopleModels.HourUnit;
import ru.rtk.crm.people.PeopleModels.HoursRow;
import ru.rtk.crm.people.PeopleModels.InternKind;
import ru.rtk.crm.people.PeopleModels.InternshipRecord;
import ru.rtk.crm.people.PeopleModels.InternshipReport;
import ru.rtk.crm.people.PeopleModels.InternshipResult;
import ru.rtk.crm.people.PeopleModels.PeopleReport;
import ru.rtk.crm.people.PeopleModels.PeopleSettings;
import ru.rtk.crm.people.PeopleModels.QualificationRecord;
import ru.rtk.crm.people.PeopleModels.QualificationReport;
import ru.rtk.crm.people.PeopleModels.QualificationResult;
import ru.rtk.crm.people.PeopleModels.TeachingRecord;
import ru.rtk.crm.people.PeopleModels.TeachingReport;
import ru.rtk.crm.people.PeopleModels.ConfirmationStatus;

// Отчёт «Люди и часы» за период по видимым вузам: КАМ — свои, руководитель — команды, «Руководство» — все.
@Service
public class PeopleReportService {
    private static final Locale RU = Locale.forLanguageTag("ru-RU");

    private final PeopleRepository repository;
    private final PeopleService peopleService;
    private final OrganizationRepository organizationRepository;

    public PeopleReportService(PeopleRepository repository, PeopleService peopleService, OrganizationRepository organizationRepository) {
        this.repository = repository;
        this.peopleService = peopleService;
        this.organizationRepository = organizationRepository;
    }

    @Transactional(readOnly = true)
    public PeopleReport report(CrmProfile profile, LocalDate requestedFrom, LocalDate requestedTo) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT) {
            throw new ContactInteractionMutationAccessDeniedException();
        }
        LocalDate today = LocalDate.now(PeopleService.ZONE);
        LocalDate from = PeopleService.date(requestedFrom == null ? today.withDayOfYear(1) : requestedFrom, "from", true);
        LocalDate to = PeopleService.date(requestedTo == null ? today : requestedTo, "to", true);
        if (from.isAfter(to)) {
            throw new InteractionValidationException("to", "Дата окончания периода раньше даты начала");
        }
        PeopleSettings settings = peopleService.currentSettings();
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElse(null);
        List<TeachingRecord> teaching = scope == null ? List.of() : repository.teachingReport(scope, from, to);
        List<InternshipRecord> internships = scope == null ? List.of() : repository.internshipReport(scope, from, to);
        List<QualificationRecord> qualifications = scope == null ? List.of() : repository.qualificationReport(scope, from, to).stream()
                .map(row -> row.record(settings.frdoDeadlineDays(), today))
                .toList();
        List<String> notes = new ArrayList<>(List.of(
                "Запись входит в отчёт, если её период пересекается с отчётным; часы записи учитываются целиком, без деления по дням.",
                "Часы приведены к единице из настроек: 1 астрономический час = 60 минут, 1 академический = 45 минут."
        ));
        if (settings.frdoDeadlineDays() == null) {
            notes.add("Срок передачи сведений в ФРДО не задан администратором, поэтому просрочка не считается.");
        }
        return new PeopleReport(from, to, OffsetDateTime.now(), teaching(teaching, settings.teachingUnit()),
                internships(internships, settings.internshipUnit()),
                qualifications(qualifications, settings.qualificationUnit(), settings.frdoDeadlineDays()), notes);
    }

    static TeachingReport teaching(List<TeachingRecord> records, HourUnit unit) {
        Function<TeachingRecord, BigDecimal> hours = record -> record.hourUnit().convert(record.hours(), unit);
        return new TeachingReport(
                unit,
                records.size(),
                sum(records, hours),
                sum(records.stream().filter(record -> record.confirmationStatus() == ConfirmationStatus.CONFIRMED).toList(), hours),
                group(records, record -> record.organizationId().toString(), TeachingRecord::organizationName, hours),
                group(records, record -> record.performerProfileId() == null ? "name:" + record.performerName()
                        : record.performerProfileId().toString(), record -> record.performerName()
                        + (record.performerCompany() == null ? "" : " (" + record.performerCompany() + ")"), hours),
                months(records, hours)
        );
    }

    static InternshipReport internships(List<InternshipRecord> records, HourUnit unit) {
        Function<InternshipRecord, BigDecimal> hours = record -> record.hourUnit().convert(record.internHours(), unit);
        return new InternshipReport(
                unit,
                records.size(),
                (int) records.stream().filter(record -> record.internKind() == InternKind.STUDENT).count(),
                (int) records.stream().filter(record -> record.internKind() == InternKind.TEACHER).count(),
                (int) records.stream().filter(record -> record.result() == InternshipResult.COMPLETED).count(),
                (int) records.stream().filter(InternshipRecord::employed).count(),
                sum(records, hours),
                sum(records, record -> record.hourUnit().convert(record.mentorHours(), unit)),
                group(records, record -> record.organizationId().toString(), InternshipRecord::organizationName, hours),
                group(records, InternshipRecord::place, InternshipRecord::place, hours)
        );
    }

    static QualificationReport qualifications(List<QualificationRecord> records, HourUnit unit, Integer deadlineDays) {
        Function<QualificationRecord, BigDecimal> hours = record -> record.hourUnit().convert(record.hours(), unit);
        List<QualificationRecord> certified = records.stream().filter(record -> record.result() == QualificationResult.CERTIFIED).toList();
        return new QualificationReport(
                unit,
                records.size(),
                certified.size(),
                (int) records.stream().filter(record -> record.result() == QualificationResult.NOT_CERTIFIED).count(),
                (int) records.stream().filter(record -> record.result() == QualificationResult.IN_PROGRESS).count(),
                sum(records, hours),
                (int) certified.stream().filter(record -> record.frdoSubmittedOn() != null).count(),
                (int) certified.stream().filter(record -> record.frdoSubmittedOn() == null).count(),
                (int) records.stream().filter(QualificationRecord::frdoOverdue).count(),
                deadlineDays,
                group(records, record -> record.organizationId().toString(), QualificationRecord::organizationName, hours),
                group(records, QualificationRecord::program, QualificationRecord::program, hours)
        );
    }

    private static <T> BigDecimal sum(List<T> records, Function<T, BigDecimal> hours) {
        return records.stream().map(hours).filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> List<HoursRow> group(List<T> records, Function<T, String> key, Function<T, String> title,
                                            Function<T, BigDecimal> hours) {
        Map<String, HoursRow> rows = new LinkedHashMap<>();
        for (T record : records) {
            BigDecimal value = hours.apply(record) == null ? BigDecimal.ZERO : hours.apply(record);
            rows.merge(key.apply(record), new HoursRow(key.apply(record), title.apply(record), 1, value),
                    (left, right) -> new HoursRow(left.key(), left.title(), left.records() + 1, left.hours().add(right.hours())));
        }
        return rows.values().stream().sorted(Comparator.comparing(HoursRow::hours).reversed().thenComparing(HoursRow::title)).toList();
    }

    // По месяцам начала записи: так видно распределение нагрузки по семестру.
    private static List<HoursRow> months(List<TeachingRecord> records, Function<TeachingRecord, BigDecimal> hours) {
        Map<String, HoursRow> rows = new LinkedHashMap<>();
        records.stream().sorted(Comparator.comparing(TeachingRecord::startsOn)).forEach(record -> {
            String key = record.startsOn().withDayOfMonth(1).toString().substring(0, 7);
            String title = record.startsOn().getMonth().getDisplayName(TextStyle.FULL_STANDALONE, RU) + " " + record.startsOn().getYear();
            rows.merge(key, new HoursRow(key, title, 1, hours.apply(record)),
                    (left, right) -> new HoursRow(left.key(), left.title(), left.records() + 1, left.hours().add(right.hours())));
        });
        return List.copyOf(rows.values());
    }
}
