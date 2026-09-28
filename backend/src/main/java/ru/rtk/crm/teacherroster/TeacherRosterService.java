package ru.rtk.crm.teacherroster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Collator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationStatus;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.enrolment.LmsRosterWorkbookWriter;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.teacherroster.TeacherRosterModels.FileMode;
import ru.rtk.crm.teacherroster.TeacherRosterModels.MemberStatus;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRoster;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterExport;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFile;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterFileRequest;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterMarked;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterMember;
import ru.rtk.crm.teacherroster.TeacherRosterModels.TeacherRosterRequest;
import ru.rtk.crm.teacherroster.TeacherRosterRepository.ExportRow;
import ru.rtk.crm.teacherroster.TeacherRosterRepository.MemberRow;
import ru.rtk.crm.teacherroster.TeacherRosterRepository.RosterRow;
import ru.rtk.crm.teacherroster.TeacherRosterRepository.WorkRow;

@Service
public class TeacherRosterService {
    static final String OBJECT_TYPE = "INTERACTION";
    static final int MAX_NAME_LENGTH = 300;
    static final int MAX_MEMBERS = 500;

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Collator RUSSIAN = Collator.getInstance(Locale.forLanguageTag("ru-RU"));
    private static final Comparator<TeacherRosterMember> BY_NAME = Comparator
            .comparing(TeacherRosterMember::lastName, RUSSIAN)
            .thenComparing(member -> member.firstName() == null ? "" : member.firstName(), RUSSIAN);

    private final TeacherRosterRepository repository;
    private final OrganizationRepository organizationRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final LmsRosterWorkbookWriter writer;

    public TeacherRosterService(
            TeacherRosterRepository repository,
            OrganizationRepository organizationRepository,
            AuditJournalRepository auditJournalRepository,
            LmsRosterWorkbookWriter writer
    ) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.writer = writer;
    }

    @Transactional(readOnly = true)
    public List<TeacherRoster> list(CrmProfile profile, UUID interactionId) {
        WorkRow work = requireVisibleWork(profile, interactionId).work();
        return rosters(repository.findRosters(work.id()));
    }

    @Transactional
    public TeacherRoster create(CrmProfile profile, UUID interactionId, TeacherRosterRequest request) {
        VisibleWork visible = requireVisibleWork(profile, interactionId);
        requireEditor(profile, visible.organization());
        String course = requiredName(request == null ? null : request.lmsCourse(), "lmsCourse", "Укажите курс LMS");
        String group = optionalName(request.lmsGroup(), "lmsGroup");
        if (repository.rosterExists(interactionId, course, group)) {
            throw new InteractionValidationException("lmsCourse", group == null
                    ? "Список для курса «" + course + "» без группы уже есть в этой работе"
                    : "Список для курса «" + course + "» и группы «" + group + "» уже есть в этой работе");
        }
        UUID id = UUID.randomUUID();
        repository.insertRoster(id, visible.work(), course, group, profile.id(), OffsetDateTime.now());
        return roster(id);
    }

    @Transactional
    public void delete(CrmProfile profile, UUID rosterId) {
        RosterRow roster = requireEditableRoster(profile, rosterId);
        if (repository.hasExports(roster.id())) {
            throw new InteractionValidationException("rosterId",
                    "Файл по этому списку уже выгружался, поэтому список не удаляется: история передачи в LMS сохраняется");
        }
        repository.deleteRoster(roster.id());
    }

    @Transactional
    public TeacherRoster addMember(CrmProfile profile, UUID rosterId, UUID contactId) {
        RosterRow roster = requireEditableRoster(profile, rosterId);
        TeacherRosterRepository.ContactRow contact = repository.findContact(roster.organizationId(), contactId)
                .orElseThrow(() -> new InteractionValidationException("contactId", "Контакт не относится к вузу этой работы"));
        if (contact.personalDataStatus() != PersonalDataStatus.ACTIVE) {
            throw new InteractionValidationException("contactId",
                    "Обработка данных контакта ограничена или контакт обезличен; выберите другой контакт");
        }
        if (contact.inactive()) {
            throw new InteractionValidationException("contactId", "Контакт отмечен как неактуальный; выберите действующий контакт");
        }
        if (repository.findMembers(List.of(roster.id())).size() >= MAX_MEMBERS) {
            throw new InteractionValidationException("contactId", "В списке не больше " + MAX_MEMBERS + " преподавателей");
        }
        repository.insertMember(roster, contact.id(), profile.id(), OffsetDateTime.now());
        return roster(roster.id());
    }

    @Transactional
    public TeacherRoster removeMember(CrmProfile profile, UUID rosterId, UUID contactId) {
        RosterRow roster = requireEditableRoster(profile, rosterId);
        if (repository.deleteMember(roster.id(), contactId) == 0 && repository.findMembers(List.of(roster.id())).stream()
                .anyMatch(member -> member.contactId().equals(contactId))) {
            throw new InteractionValidationException("contactId",
                    "Преподаватель уже передан LMS-команде и остаётся в списке; исключить его из курса можно только в LMS");
        }
        return roster(roster.id());
    }

    @Transactional
    public TeacherRosterFile export(CrmProfile profile, UUID rosterId, TeacherRosterFileRequest request, String requestId) {
        RosterRow roster = requireEditableRoster(profile, rosterId);
        if (request == null || request.mode() == null) {
            throw new InteractionValidationException("mode", "Выберите, кого выгрузить: только не переданных или всех");
        }
        List<TeacherRosterMember> scope = members(repository.findMembers(List.of(roster.id()))).stream()
                .filter(member -> request.mode() == FileMode.ALL || !member.transferred())
                .toList();
        List<TeacherRosterMember> rows = scope.stream().filter(TeacherRosterMember::ready).sorted(BY_NAME).toList();
        if (rows.isEmpty()) {
            throw new InteractionValidationException("mode", emptyMessage(request.mode(), scope));
        }
        UUID exportId = UUID.randomUUID();
        byte[] content = workbook(rows);
        OffsetDateTime now = OffsetDateTime.now();
        repository.insertExport(exportId, roster.id(), rows.size(), profile.id(), now);
        repository.markExported(roster.id(), rows.stream().filter(member -> !member.transferred())
                .map(TeacherRosterMember::contactId).toList(), exportId);
        int skipped = scope.size() - rows.size();
        WorkRow work = repository.findWork(roster.interactionId()).orElseThrow(InteractionNotFoundException::new);
        auditJournalRepository.record(AuditAction.TEACHER_ROSTER_EXPORTED, profile.id(), OBJECT_TYPE, work.id(), work.title(),
                target(roster) + "; строк: " + rows.size() + "; не готовы и не выгружены: " + skipped + "; выгрузка: " + exportId
                        + "; режим: " + (request.mode() == FileMode.ALL ? "все преподаватели списка" : "только не переданные"),
                requestId);
        return new TeacherRosterFile(exportId, fileName(roster), rows.size(), skipped, content);
    }

    @Transactional
    public TeacherRosterMarked markTransferred(CrmProfile profile, UUID exportId, String requestId) {
        UUID rosterId = repository.findExportRoster(exportId).orElseThrow(TeacherRosterNotFoundException::new);
        RosterRow roster = requireEditableRoster(profile, rosterId);
        int marked = repository.markTransferred(exportId, profile.id(), OffsetDateTime.now());
        if (marked > 0) {
            WorkRow work = repository.findWork(roster.interactionId()).orElseThrow(InteractionNotFoundException::new);
            auditJournalRepository.record(AuditAction.TEACHER_ROSTER_MARKED, profile.id(), OBJECT_TYPE, work.id(), work.title(),
                    target(roster) + "; выгрузка: " + exportId + "; отмечено переданными: " + marked, requestId);
        }
        return new TeacherRosterMarked(marked, roster(roster.id()));
    }

    static String fileName(RosterRow roster) {
        String group = roster.lmsGroup() == null ? "" : "_" + safe(roster.lmsGroup());
        return "LMS_преподаватели_" + safe(roster.lmsCourse()) + group + "_" + LocalDate.now(ZONE) + ".xlsx";
    }

    static List<String> problems(MemberRow member, NameParts name, Set<String> repeatedEmails) {
        List<String> problems = new ArrayList<>();
        if (member.personalDataStatus() == PersonalDataStatus.RESTRICTED) {
            problems.add("Обработка данных контакта ограничена");
        } else if (member.personalDataStatus() == PersonalDataStatus.ANONYMIZED) {
            problems.add("Контакт обезличен");
        }
        if (member.inactive()) {
            problems.add("Контакт отмечен как неактуальный");
        }
        String email = member.email() == null ? null : member.email().strip();
        if (email == null || email.isEmpty()) {
            problems.add("Не указана электронная почта: она нужна для входа в LMS");
        } else if (!LmsRosterWorkbookWriter.acceptsEmail(email)) {
            problems.add("Электронная почта указана с ошибкой");
        } else if (repeatedEmails.contains(email.toLowerCase(Locale.ROOT))) {
            problems.add("Эта почта указана у другого преподавателя списка");
        }
        if (name.firstName() == null) {
            problems.add("В ФИО контакта нет имени: запишите «Фамилия Имя Отчество»");
        } else if (name.lastName().endsWith(".") || name.firstName().endsWith(".")) {
            problems.add("Имя или фамилия записаны инициалом: укажите ФИО полностью");
        }
        return problems;
    }

    static NameParts split(String fullName) {
        String[] words = fullName == null ? new String[0] : fullName.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return new NameParts("", null, null);
        }
        return new NameParts(
                words[0],
                words.length > 1 ? words[1] : null,
                words.length > 2 ? String.join(" ", List.of(words).subList(2, words.length)) : null
        );
    }

    private TeacherRoster roster(UUID rosterId) {
        return rosters(List.of(repository.findRoster(rosterId).orElseThrow(TeacherRosterNotFoundException::new))).getFirst();
    }

    private List<TeacherRoster> rosters(List<RosterRow> rows) {
        List<UUID> ids = rows.stream().map(RosterRow::id).toList();
        Map<UUID, List<MemberRow>> members = repository.findMembers(ids).stream()
                .collect(Collectors.groupingBy(MemberRow::rosterId));
        Map<UUID, List<TeacherRosterExport>> exports = repository.findExports(ids).stream()
                .collect(Collectors.groupingBy(ExportRow::rosterId, Collectors.mapping(ExportRow::export, Collectors.toList())));
        return rows.stream().map(row -> {
            List<TeacherRosterMember> rosterMembers = members(members.getOrDefault(row.id(), List.of()));
            return new TeacherRoster(
                    row.id(),
                    row.interactionId(),
                    row.lmsCourse(),
                    row.lmsGroup(),
                    row.createdAt(),
                    row.createdByName(),
                    (int) rosterMembers.stream().filter(member -> member.ready() && !member.transferred()).count(),
                    (int) rosterMembers.stream().filter(member -> !member.transferred()).count(),
                    (int) rosterMembers.stream().filter(TeacherRosterMember::transferred).count(),
                    rosterMembers,
                    exports.getOrDefault(row.id(), List.of())
            );
        }).toList();
    }

    private static List<TeacherRosterMember> members(List<MemberRow> rows) {
        Set<String> repeatedEmails = rows.stream()
                .map(MemberRow::email)
                .filter(email -> email != null && !email.isBlank())
                .map(email -> email.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.groupingBy(email -> email, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return rows.stream().map(row -> {
            NameParts name = split(row.name());
            return new TeacherRosterMember(
                    row.contactId(),
                    row.name(),
                    row.position(),
                    row.email(),
                    row.role(),
                    name.lastName(),
                    name.firstName(),
                    name.middleName(),
                    problems(row, name, repeatedEmails),
                    row.transferredAt() != null ? MemberStatus.TRANSFERRED
                            : row.exportId() != null ? MemberStatus.EXPORTED : MemberStatus.LISTED,
                    row.exportedAt(),
                    row.transferredAt()
            );
        }).toList();
    }

    private byte[] workbook(List<TeacherRosterMember> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writer.writeTeachers(rows.stream()
                    .map(member -> new LmsRosterWorkbookWriter.Teacher(
                            member.lastName(), member.firstName(), member.middleName(), member.email().strip()))
                    .toList(), output);
        } catch (IOException exception) {
            throw new UncheckedIOException("Teacher roster workbook cannot be written", exception);
        }
        return output.toByteArray();
    }

    private VisibleWork requireVisibleWork(CrmProfile profile, UUID interactionId) {
        WorkRow work = repository.findWork(interactionId).orElseThrow(InteractionNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, work.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        return new VisibleWork(work, organization);
    }

    private RosterRow requireEditableRoster(CrmProfile profile, UUID rosterId) {
        RosterRow roster = repository.findRoster(rosterId).orElseThrow(TeacherRosterNotFoundException::new);
        Organization organization = organizationRepository.findVisibleById(profile, roster.organizationId())
                .orElseThrow(TeacherRosterNotFoundException::new);
        requireEditor(profile, organization);
        repository.lockRoster(roster.id());
        return roster;
    }

    private static void requireEditor(CrmProfile profile, Organization organization) {
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (organization.status() == OrganizationStatus.ARCHIVED) {
            throw new InteractionValidationException("organizationId", "Организация в архиве; восстановите её, чтобы вести список");
        }
    }

    private static String requiredName(String value, String field, String message) {
        String name = optionalName(value, field);
        if (name == null) {
            throw new InteractionValidationException(field, message);
        }
        return name;
    }

    private static String optionalName(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String name = value.strip().replaceAll("\\s+", " ");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InteractionValidationException(field, "Название длиннее " + MAX_NAME_LENGTH + " символов");
        }
        return name;
    }

    private static String target(RosterRow roster) {
        return "курс LMS «" + roster.lmsCourse() + "»" + (roster.lmsGroup() == null ? "" : ", группа «" + roster.lmsGroup() + "»");
    }

    private static String safe(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static String emptyMessage(FileMode mode, List<TeacherRosterMember> scope) {
        if (scope.isEmpty()) {
            return mode == FileMode.PENDING
                    ? "Новых преподавателей нет: все из списка уже переданы LMS-команде"
                    : "В списке нет преподавателей";
        }
        return "Ни один преподаватель не готов к выгрузке: исправьте почту и ФИО контактов по подсказкам в списке";
    }

    record NameParts(String lastName, String firstName, String middleName) {
    }

    private record VisibleWork(WorkRow work, Organization organization) {
    }
}
