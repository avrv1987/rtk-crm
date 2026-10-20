package ru.rtk.crm.people;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.ContactInteractionMutationAccessDeniedException;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.people.PeopleModels.ConfirmationStatus;
import ru.rtk.crm.people.PeopleModels.ContactOption;
import ru.rtk.crm.people.PeopleModels.FrdoReminder;
import ru.rtk.crm.people.PeopleModels.HourUnit;
import ru.rtk.crm.people.PeopleModels.InternshipInput;
import ru.rtk.crm.people.PeopleModels.InternshipRecord;
import ru.rtk.crm.people.PeopleModels.InternshipResult;
import ru.rtk.crm.people.PeopleModels.PeopleOptions;
import ru.rtk.crm.people.PeopleModels.PeopleOverview;
import ru.rtk.crm.people.PeopleModels.PeopleSettings;
import ru.rtk.crm.people.PeopleModels.PeopleSettingsRequest;
import ru.rtk.crm.people.PeopleModels.QualificationInput;
import ru.rtk.crm.people.PeopleModels.QualificationRecord;
import ru.rtk.crm.people.PeopleModels.QualificationResult;
import ru.rtk.crm.people.PeopleModels.TeachingInput;
import ru.rtk.crm.people.PeopleModels.TeachingRecord;

// Учёт людей и часов по вузу. Права те же, что у карточек: читает тот, кому виден вуз (КАМ — свои вузы, руководитель —
// вузы команды, «Руководство» — все), меняют КАМ и руководитель; администратор ведёт только настройки.
@Service
public class PeopleService {
    static final String TEACHING = "university_teaching";
    static final String INTERNSHIPS = "internships";
    static final String QUALIFICATIONS = "teacher_qualifications";
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    static final int MAX_TEXT = 300;
    static final BigDecimal MAX_HOURS = BigDecimal.valueOf(10_000);
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String OBJECT_TYPE = "ORGANIZATION";

    private final PeopleRepository repository;
    private final OrganizationRepository organizationRepository;
    private final AuditJournalRepository auditJournalRepository;

    public PeopleService(PeopleRepository repository, OrganizationRepository organizationRepository,
                         AuditJournalRepository auditJournalRepository) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.auditJournalRepository = auditJournalRepository;
    }

    // ---------- настройки ----------

    @Transactional(readOnly = true)
    public PeopleSettings settings(CrmProfile profile) {
        if (profile.role() == UserRole.PARTNER) {
            throw new ContactInteractionMutationAccessDeniedException();
        }
        return currentSettings();
    }

    PeopleSettings currentSettings() {
        return repository.findSettings().orElse(PeopleSettings.DEFAULT);
    }

    @Transactional
    public PeopleSettings updateSettings(CrmProfile profile, PeopleSettingsRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте настройки учёта людей и часов");
        }
        PeopleSettings next = new PeopleSettings(
                required(request.teachingUnit(), "teachingUnit"),
                required(request.internshipUnit(), "internshipUnit"),
                required(request.qualificationUnit(), "qualificationUnit"),
                range(request.frdoDeadlineDays(), "frdoDeadlineDays", 1, 365, false),
                range(request.frdoNoticeDays(), "frdoNoticeDays", 0, 90, true),
                true, null, null
        );
        PeopleSettings before = currentSettings();
        repository.saveSettings(next, profile.id(), OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.PEOPLE_SETTINGS_CHANGED, profile.id(), "PEOPLE_SETTINGS", null,
                "Учёт людей и часов", changes(before, next), requestId);
        return currentSettings();
    }

    @Transactional
    public void resetSettings(CrmProfile profile, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        PeopleSettings before = currentSettings();
        repository.deleteSettings();
        auditJournalRepository.record(AuditAction.PEOPLE_SETTINGS_CHANGED, profile.id(), "PEOPLE_SETTINGS", null,
                "Учёт людей и часов", "восстановлены значения по умолчанию; " + changes(before, PeopleSettings.DEFAULT), requestId);
    }

    static String changes(PeopleSettings before, PeopleSettings after) {
        List<String> changes = new ArrayList<>();
        diff(changes, "единица часов преподавания", before.teachingUnit(), after.teachingUnit());
        diff(changes, "единица часов стажировок", before.internshipUnit(), after.internshipUnit());
        diff(changes, "единица часов повышения квалификации", before.qualificationUnit(), after.qualificationUnit());
        diff(changes, "срок передачи в ФРДО, дней", before.frdoDeadlineDays(), after.frdoDeadlineDays());
        diff(changes, "напоминать о ФРДО за, дней", before.frdoNoticeDays(), after.frdoNoticeDays());
        return changes.isEmpty() ? "значения сохранены без изменений" : String.join("; ", changes);
    }

    private static void diff(List<String> changes, String label, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(label + ": " + (before == null ? "не задан" : before) + " → " + (after == null ? "не задан" : after));
        }
    }

    // ---------- обзор вуза ----------

    @Transactional(readOnly = true)
    public PeopleOverview overview(CrmProfile profile, UUID organizationId, UUID interactionId) {
        Organization organization = requireReadable(profile, organizationId);
        if (interactionId != null && repository.organizationOf("interactions", interactionId)
                .filter(organizationId::equals).isEmpty()) {
            throw new InteractionValidationException("interactionId", "Взаимодействие не относится к этому вузу");
        }
        PeopleSettings settings = currentSettings();
        LocalDate today = LocalDate.now(ZONE);
        List<QualificationRecord> qualifications = repository.qualifications(organizationId, interactionId).stream()
                .map(row -> row.record(settings.frdoDeadlineDays(), today))
                .toList();
        boolean canEdit = editable(profile, organization);
        PeopleOptions options = canEdit ? new PeopleOptions(
                repository.employees(),
                repository.interactions(organizationId),
                repository.agreements(organizationId),
                repository.activities(organizationId),
                repository.teacherRosters(organizationId),
                repository.teachers(organizationId)
        ) : new PeopleOptions(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new PeopleOverview(organizationId, canEdit, settings, repository.teaching(organizationId, interactionId),
                repository.internships(organizationId, interactionId), qualifications, options);
    }

    // ---------- преподавание ----------

    @Transactional
    public TeachingRecord createTeaching(CrmProfile profile, UUID organizationId, TeachingInput input, String requestId) {
        Organization organization = requireEditable(profile, organizationId);
        TeachingInput clean = teaching(organizationId, input);
        UUID id = UUID.randomUUID();
        repository.insertTeaching(id, organizationId, clean, profile.id(), OffsetDateTime.now());
        journal(profile, organization, "преподавание сотрудником: создана запись " + shortId(id)
                + "; " + hours(clean.hours(), clean.hourUnit()) + "; " + period(clean.startsOn(), clean.endsOn()), requestId);
        return repository.teaching(id).orElseThrow();
    }

    @Transactional
    public TeachingRecord updateTeaching(CrmProfile profile, UUID recordId, TeachingInput input, String requestId) {
        UUID organizationId = repository.organizationOf(TEACHING, recordId).orElseThrow(PeopleRecordNotFoundException::new);
        Organization organization = requireEditable(profile, organizationId);
        int version = requiredVersion(input == null ? null : input.version());
        TeachingInput clean = teaching(organizationId, input);
        if (!repository.updateTeaching(recordId, version, clean, profile.id(), OffsetDateTime.now())) {
            throw conflict(TEACHING, recordId);
        }
        journal(profile, organization, "преподавание сотрудником: изменена запись " + shortId(recordId)
                + "; " + hours(clean.hours(), clean.hourUnit()) + "; подтверждение: " + clean.confirmationStatus(), requestId);
        return repository.teaching(recordId).orElseThrow();
    }

    private TeachingInput teaching(UUID organizationId, TeachingInput input) {
        if (input == null) {
            throw new InteractionValidationException("body", "Заполните запись о преподавании");
        }
        Links links = links(organizationId, input.interactionId(), input.agreementId(), input.agreementActivityId());
        UUID performer = employee(input.performerProfileId(), "performerProfileId");
        String performerName = performer == null ? text(input.performerName(), "performerName", false) : null;
        if (performer == null && performerName == null) {
            throw new InteractionValidationException("performerName",
                    "Выберите сотрудника из справочника или укажите исполнителя группы компаний");
        }
        LocalDate startsOn = date(input.startsOn(), "startsOn", true);
        LocalDate endsOn = endDate(startsOn, input.endsOn());
        ConfirmationStatus status = input.confirmationStatus() == null ? ConfirmationStatus.NOT_REQUESTED : input.confirmationStatus();
        return new TeachingInput(links.interactionId(), links.agreementId(), links.activityId(),
                text(input.discipline(), "discipline", true), performer, performerName,
                text(input.performerCompany(), "performerCompany", false), startsOn, endsOn,
                hoursValue(input.hours(), "hours", true), unit(input.hourUnit(), currentSettings().teachingUnit()),
                text(input.studentGroup(), "studentGroup", false), status, text(input.confirmationDocument(), "confirmationDocument", false),
                date(input.confirmedOn(), "confirmedOn", false), input.version());
    }

    // ---------- стажировки ----------

    @Transactional
    public InternshipRecord createInternship(CrmProfile profile, UUID organizationId, InternshipInput input, String requestId) {
        Organization organization = requireEditable(profile, organizationId);
        InternshipInput clean = internship(organizationId, input);
        UUID id = UUID.randomUUID();
        repository.insertInternship(id, organizationId, clean, profile.id(), OffsetDateTime.now());
        journal(profile, organization, "стажировка: создана запись " + shortId(id) + "; часы стажёра: "
                + hours(clean.internHours(), clean.hourUnit()) + "; " + period(clean.startsOn(), clean.endsOn()), requestId);
        return repository.internship(id).orElseThrow();
    }

    @Transactional
    public InternshipRecord updateInternship(CrmProfile profile, UUID recordId, InternshipInput input, String requestId) {
        InternshipRecord current = repository.internship(recordId).orElseThrow(PeopleRecordNotFoundException::new);
        Organization organization = requireEditable(profile, current.organizationId());
        requireNotAnonymized(current.anonymized());
        int version = requiredVersion(input == null ? null : input.version());
        InternshipInput clean = internship(current.organizationId(), input);
        if (!repository.updateInternship(recordId, version, clean, profile.id(), OffsetDateTime.now())) {
            throw conflict(INTERNSHIPS, recordId);
        }
        journal(profile, organization, "стажировка: изменена запись " + shortId(recordId) + "; итог: " + clean.result(), requestId);
        return repository.internship(recordId).orElseThrow();
    }

    private InternshipInput internship(UUID organizationId, InternshipInput input) {
        if (input == null) {
            throw new InteractionValidationException("body", "Заполните запись о стажировке");
        }
        Links links = links(organizationId, input.interactionId(), null, input.agreementActivityId());
        UUID mentor = employee(input.mentorProfileId(), "mentorProfileId");
        String mentorName = mentor == null ? text(input.mentorName(), "mentorName", false) : null;
        if (mentor == null && mentorName == null) {
            throw new InteractionValidationException("mentorName", "Выберите наставника из справочника сотрудников или укажите его ФИО");
        }
        LocalDate startsOn = date(input.startsOn(), "startsOn", true);
        return new InternshipInput(links.interactionId(), links.activityId(), text(input.internName(), "internName", true),
                required(input.internKind(), "internKind"), text(input.place(), "place", true), mentor, mentorName, startsOn,
                endDate(startsOn, input.endsOn()), hoursValue(input.internHours(), "internHours", true),
                hoursValue(input.mentorHours(), "mentorHours", false), unit(input.hourUnit(), currentSettings().internshipUnit()),
                input.result() == null ? InternshipResult.PLANNED : input.result(), Boolean.TRUE.equals(input.employed()),
                text(input.document(), "document", false), input.version());
    }

    // ---------- повышение квалификации ----------

    @Transactional
    public QualificationRecord createQualification(CrmProfile profile, UUID organizationId, QualificationInput input, String requestId) {
        Organization organization = requireEditable(profile, organizationId);
        QualificationInput clean = qualification(organizationId, input);
        UUID id = UUID.randomUUID();
        repository.insertQualification(id, organizationId, clean, profile.id(), OffsetDateTime.now());
        journal(profile, organization, "повышение квалификации: создана запись " + shortId(id) + "; "
                + hours(clean.hours(), clean.hourUnit()) + "; итог: " + clean.result(), requestId);
        return qualificationRecord(id);
    }

    @Transactional
    public QualificationRecord updateQualification(CrmProfile profile, UUID recordId, QualificationInput input, String requestId) {
        PeopleRepository.QualificationRow current = repository.qualification(recordId).orElseThrow(PeopleRecordNotFoundException::new);
        Organization organization = requireEditable(profile, current.organizationId());
        requireNotAnonymized(current.anonymized());
        int version = requiredVersion(input == null ? null : input.version());
        QualificationInput clean = qualification(current.organizationId(), input);
        if (!repository.updateQualification(recordId, version, clean, profile.id(), OffsetDateTime.now())) {
            throw conflict(QUALIFICATIONS, recordId);
        }
        journal(profile, organization, "повышение квалификации: изменена запись " + shortId(recordId) + "; итог: " + clean.result()
                + (clean.frdoSubmittedOn() == null ? "" : "; передано в ФРДО " + DATE.format(clean.frdoSubmittedOn())), requestId);
        return qualificationRecord(recordId);
    }

    QualificationRecord qualificationRecord(UUID id) {
        return repository.qualification(id).orElseThrow().record(currentSettings().frdoDeadlineDays(), LocalDate.now(ZONE));
    }

    QualificationInput qualification(UUID organizationId, QualificationInput input) {
        if (input == null) {
            throw new InteractionValidationException("body", "Заполните запись о повышении квалификации");
        }
        Links links = links(organizationId, input.interactionId(), null, input.agreementActivityId());
        UUID roster = null;
        if (input.teacherRosterId() != null) {
            roster = repository.organizationOf("teacher_rosters", input.teacherRosterId()).filter(organizationId::equals)
                    .map(ignored -> input.teacherRosterId())
                    .orElseThrow(() -> new InteractionValidationException("teacherRosterId", "Список на курс LMS не относится к этому вузу"));
        }
        ContactOption contact = null;
        if (input.contactId() != null) {
            contact = repository.contact(organizationId, input.contactId()).orElseThrow(() -> new InteractionValidationException(
                    "contactId", "Контакт не относится к вузу, обезличен или его обработка ограничена"));
        }
        String name = text(input.personName(), "personName", false);
        if (name == null && contact != null) {
            name = contact.name();
        }
        if (name == null) {
            throw new InteractionValidationException("personName", "Укажите ФИО преподавателя или выберите контакт вуза");
        }
        String email = text(input.personEmail(), "personEmail", false);
        if (email == null && contact != null) {
            email = contact.email();
        }
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
            throw new InteractionValidationException("personEmail", "Электронная почта указана с ошибкой");
        }
        LocalDate startsOn = date(input.startsOn(), "startsOn", true);
        QualificationResult result = input.result() == null ? QualificationResult.IN_PROGRESS : input.result();
        LocalDate frdo = date(input.frdoSubmittedOn(), "frdoSubmittedOn", false);
        if (frdo != null && result != QualificationResult.CERTIFIED) {
            throw new InteractionValidationException("frdoSubmittedOn", "В ФРДО передают сведения только об аттестованных");
        }
        String certificate = text(input.certificateNumber(), "certificateNumber", false);
        if (certificate != null && certificate.length() > 100) {
            throw new InteractionValidationException("certificateNumber", "Номер удостоверения длиннее 100 символов");
        }
        return new QualificationInput(links.interactionId(), links.activityId(), roster, contact == null ? null : contact.id(), name,
                email, text(input.program(), "program", true), startsOn, endDate(startsOn, input.endsOn()),
                hoursValue(input.hours(), "hours", true), unit(input.hourUnit(), currentSettings().qualificationUnit()), result,
                certificate, date(input.certificateDate(), "certificateDate", false), frdo, input.version());
    }

    // ---------- импорт XLSX ----------

    // Загрузка «всё или ничего»: при любой ошибке в строках не создаётся ни одной записи, чтобы повторная загрузка
    // исправленного файла не дала дублей. Предпросмотр (dryRun) проверяет те же правила и ничего не пишет.
    @Transactional
    public PeopleModels.ImportResult importQualifications(CrmProfile profile, UUID organizationId, MultipartFile file,
                                                          UUID interactionId, UUID teacherRosterId, boolean dryRun, String requestId) {
        Organization organization = requireEditable(profile, organizationId);
        List<QualificationWorkbook.ParsedRow> rows = QualificationWorkbook.read(file);
        List<PeopleModels.ImportError> errors = new ArrayList<>();
        List<QualificationInput> valid = new ArrayList<>();
        for (QualificationWorkbook.ParsedRow row : rows) {
            UUID contactId = row.email() == null ? null
                    : repository.contactByEmail(organizationId, row.email()).map(ContactOption::id).orElse(null);
            // Совпала почта с контактом — ФИО и почта берутся из контакта, как при выборе контакта в ручной форме:
            // иначе один человек окажется в таблице под двумя разными ФИО.
            QualificationInput input = new QualificationInput(interactionId, null, teacherRosterId, contactId,
                    contactId == null ? row.name() : null, contactId == null ? row.email() : null, row.program(), row.startsOn(),
                    row.endsOn(), row.hours(), null,
                    row.result(), row.certificateNumber(), row.certificateDate(), row.frdoSubmittedOn(), null);
            Map<String, String> rowErrors = new LinkedHashMap<>(row.problems());
            // Все ошибки строки сразу, а не по одной за загрузку: поле с ошибкой заменяется заведомо верным значением,
            // и строка проверяется снова теми же правилами, что и ручной ввод. Число попыток ограничено числом полей.
            for (int attempt = 0; attempt <= QualificationWorkbook.Column.values().length && input != null; attempt++) {
                try {
                    QualificationInput checked = qualification(organizationId, input);
                    if (rowErrors.isEmpty()) {
                        valid.add(checked);
                    }
                    break;
                } catch (InteractionValidationException exception) {
                    rowErrors.putIfAbsent(exception.field(), importField(exception.field()) + exception.getMessage());
                    input = neutralized(input, exception.field());
                }
            }
            rowErrors.values().forEach(message -> errors.add(new PeopleModels.ImportError(row.row(), message)));
        }
        boolean apply = !dryRun && errors.isEmpty();
        if (apply) {
            OffsetDateTime now = OffsetDateTime.now();
            for (QualificationInput input : valid) {
                repository.insertQualification(UUID.randomUUID(), organizationId, input, profile.id(), now);
            }
            auditJournalRepository.record(AuditAction.PEOPLE_IMPORT_APPLIED, profile.id(), OBJECT_TYPE, organization.id(),
                    organization.name(), "повышение квалификации: загружено строк " + valid.size(), requestId);
        }
        return new PeopleModels.ImportResult(apply, rows.size(), apply ? valid.size() : 0, errors);
    }

    // Заведомо верное значение поля для повторной проверки строки; null — поле не из файла, проверять дальше нечего.
    private static QualificationInput neutralized(QualificationInput in, String field) {
        if (!List.of("personName", "personEmail", "program", "startsOn", "endsOn", "hours", "certificateNumber", "certificateDate",
                "frdoSubmittedOn").contains(field)) {
            return null;
        }
        return new QualificationInput(in.interactionId(), in.agreementActivityId(), in.teacherRosterId(), in.contactId(),
                field.equals("personName") ? "—" : in.personName(),
                field.equals("personEmail") ? null : in.personEmail(),
                field.equals("program") ? "—" : in.program(),
                field.equals("startsOn") ? MIN_DATE : in.startsOn(),
                field.equals("endsOn") ? in.startsOn() : in.endsOn(),
                field.equals("hours") ? BigDecimal.ONE : in.hours(),
                in.hourUnit(), in.result(),
                field.equals("certificateNumber") ? null : in.certificateNumber(),
                field.equals("certificateDate") ? null : in.certificateDate(),
                field.equals("frdoSubmittedOn") ? null : in.frdoSubmittedOn(),
                in.version());
    }

    private static String importField(String field) {
        return switch (field) {
            case "personName" -> "«ФИО»: ";
            case "personEmail" -> "«Электронная почта»: ";
            case "program" -> "«Программа»: ";
            case "startsOn" -> "«Дата начала»: ";
            case "endsOn" -> "«Дата окончания»: ";
            case "hours" -> "«Часы»: ";
            case "certificateNumber" -> "«Номер удостоверения»: ";
            case "certificateDate" -> "«Дата удостоверения»: ";
            case "frdoSubmittedOn" -> "«Передано в ФРДО»: ";
            default -> "";
        };
    }

    // ---------- удаление ----------

    @Transactional
    public void delete(CrmProfile profile, String table, UUID recordId, Integer version, String requestId) {
        UUID organizationId = repository.organizationOf(table, recordId).orElseThrow(PeopleRecordNotFoundException::new);
        Organization organization = requireEditable(profile, organizationId);
        if (repository.delete(table, recordId, requiredVersion(version)) == 0) {
            throw conflict(table, recordId);
        }
        journal(profile, organization, kindLabel(table) + ": удалена запись " + shortId(recordId), requestId);
    }

    // ---------- напоминание о ФРДО ----------

    // Для «Напоминаний»: пока срок передачи не задан администратором, напоминать не о чем.
    public List<FrdoReminder> frdoReminders(VisibilityScope scope, LocalDate today, int limit) {
        PeopleSettings settings = currentSettings();
        if (settings.frdoDeadlineDays() == null) {
            return List.of();
        }
        return repository.frdoDue(scope, settings.frdoDeadlineDays(), today.plusDays(settings.frdoNoticeDays()), limit);
    }

    // ---------- права ----------

    Organization requireReadable(CrmProfile profile, UUID organizationId) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER && profile.role() != UserRole.MANAGEMENT) {
            throw new ContactInteractionMutationAccessDeniedException();
        }
        return organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
    }

    Organization requireEditable(CrmProfile profile, UUID organizationId) {
        Organization organization = requireReadable(profile, organizationId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (organization.status() == OrganizationStatus.ARCHIVED) {
            throw new InteractionValidationException("organizationId", "Вуз в архиве; восстановите его, чтобы вести учёт");
        }
        return organization;
    }

    private static boolean editable(CrmProfile profile, Organization organization) {
        return (profile.role() == UserRole.USER || profile.role() == UserRole.LEADER) && organization.status() != OrganizationStatus.ARCHIVED;
    }

    void journal(CrmProfile profile, Organization organization, String details, String requestId) {
        auditJournalRepository.record(AuditAction.PEOPLE_RECORD_CHANGED, profile.id(), OBJECT_TYPE, organization.id(),
                organization.name(), details, requestId);
    }

    // ---------- проверки ----------

    private Links links(UUID organizationId, UUID interactionId, UUID agreementId, UUID activityId) {
        if (interactionId != null && repository.organizationOf("interactions", interactionId).filter(organizationId::equals).isEmpty()) {
            throw new InteractionValidationException("interactionId", "Взаимодействие не относится к этому вузу");
        }
        if (agreementId != null && repository.organizationOf("agreements", agreementId).filter(organizationId::equals).isEmpty()) {
            throw new InteractionValidationException("agreementId", "Договор не относится к этому вузу");
        }
        UUID agreement = agreementId;
        if (activityId != null) {
            if (repository.activityOrganization(activityId).filter(organizationId::equals).isEmpty()) {
                throw new InteractionValidationException("agreementActivityId", "Мероприятие не относится к договорам этого вуза");
            }
            UUID activityAgreement = repository.activityAgreement(activityId).orElseThrow();
            if (agreement != null && !agreement.equals(activityAgreement)) {
                throw new InteractionValidationException("agreementActivityId", "Мероприятие относится к другому договору");
            }
            agreement = activityAgreement;
        }
        return new Links(interactionId, agreement, activityId);
    }

    private UUID employee(UUID profileId, String field) {
        if (profileId != null && !repository.employeeExists(profileId)) {
            throw new InteractionValidationException(field, "Сотрудник не найден в справочнике");
        }
        return profileId;
    }

    static String text(String value, String field, boolean required) {
        String text = value == null ? null : value.strip().replaceAll("\\s+", " ");
        if (text == null || text.isEmpty()) {
            if (required) {
                throw new InteractionValidationException(field, "Заполните поле");
            }
            return null;
        }
        if (text.length() > MAX_TEXT) {
            throw new InteractionValidationException(field, "Значение длиннее " + MAX_TEXT + " символов");
        }
        return text;
    }

    static BigDecimal hoursValue(BigDecimal value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw new InteractionValidationException(field, "Укажите число часов");
            }
            return null;
        }
        if (value.signum() < 0 || required && value.signum() == 0) {
            throw new InteractionValidationException(field, required ? "Часов должно быть больше нуля" : "Часы не могут быть отрицательными");
        }
        if (value.compareTo(MAX_HOURS) > 0) {
            throw new InteractionValidationException(field, "Не больше " + MAX_HOURS + " часов в одной записи");
        }
        if (value.stripTrailingZeros().scale() > 1) {
            throw new InteractionValidationException(field, "Часы — с точностью до десятой");
        }
        return value;
    }

    static LocalDate date(LocalDate value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw new InteractionValidationException(field, "Укажите дату");
            }
            return null;
        }
        if (value.isBefore(MIN_DATE) || value.isAfter(MAX_DATE)) {
            throw new InteractionValidationException(field, "Дата вне допустимого диапазона 2000–2100");
        }
        return value;
    }

    static LocalDate endDate(LocalDate startsOn, LocalDate endsOn) {
        LocalDate end = date(endsOn, "endsOn", true);
        if (end.isBefore(startsOn)) {
            throw new InteractionValidationException("endsOn", "Окончание раньше начала");
        }
        return end;
    }

    private static HourUnit unit(HourUnit value, HourUnit fallback) {
        return value == null ? fallback : value;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new InteractionValidationException(field, "Выберите значение");
        }
        return value;
    }

    private static Integer range(Integer value, String field, int min, int max, boolean required) {
        if (value == null) {
            if (required) {
                throw new InteractionValidationException(field, "Укажите значение");
            }
            return null;
        }
        if (value < min || value > max) {
            throw new InteractionValidationException(field, "Значение от " + min + " до " + max);
        }
        return value;
    }

    private static int requiredVersion(Integer version) {
        if (version == null || version < 0) {
            throw new InteractionValidationException("version", "Не передана версия записи");
        }
        return version;
    }

    private InteractionConflictException conflict(String table, UUID id) {
        return InteractionConflictException.peopleRecordVersion(repository.version(table, id).orElse(-1));
    }

    private static void requireNotAnonymized(boolean anonymized) {
        if (anonymized) {
            throw new InteractionValidationException("id", "Запись обезличена; изменить её нельзя, только удалить");
        }
    }

    static String hours(BigDecimal hours, HourUnit unit) {
        return hours.stripTrailingZeros().toPlainString() + " " + unit.shortLabel();
    }

    private static String period(LocalDate from, LocalDate to) {
        return "период " + DATE.format(from) + "–" + DATE.format(to);
    }

    static String kindLabel(String table) {
        return switch (table) {
            case TEACHING -> "преподавание сотрудником";
            case INTERNSHIPS -> "стажировка";
            default -> "повышение квалификации";
        };
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private record Links(UUID interactionId, UUID agreementId, UUID activityId) {
    }
}
