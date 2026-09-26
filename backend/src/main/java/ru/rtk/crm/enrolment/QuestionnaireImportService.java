package ru.rtk.crm.enrolment;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.attachment.AttachmentTooLargeException;
import ru.rtk.crm.attachment.AttachmentUploadInspection;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class QuestionnaireImportService {
    private static final String NOT_IN_STREAM = "Слушателя нет в потоке: сначала загрузите оплату";

    private final EnrolmentAccess enrolmentAccess;
    private final EnrolmentStreamService streamService;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerService learnerService;
    private final LearnerDataCipher cipher;
    private final LearnerWorkbookReader reader;
    private final AttachmentContentValidator contentValidator;
    private final AttachmentScanner scanner;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;

    QuestionnaireImportService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentStreamService streamService,
            EnrolmentRepository enrolmentRepository,
            LearnerService learnerService,
            LearnerDataCipher cipher,
            LearnerWorkbookReader reader,
            AttachmentContentValidator contentValidator,
            AttachmentScanner scanner,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.streamService = streamService;
        this.enrolmentRepository = enrolmentRepository;
        this.learnerService = learnerService;
        this.cipher = cipher;
        this.reader = reader;
        this.contentValidator = contentValidator;
        this.scanner = scanner;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
    }

    @Transactional
    QuestionnaireImport preview(CrmProfile actor, String id, MultipartFile file, String requestId) {
        enrolmentAccess.requireOperator(actor);
        StreamSummary stream = streamService.summary(EnrolmentAccess.parseId(id));
        Plan plan = plan(stream, file, inspect(file));
        QuestionnaireImport preview = plan.result(false);
        auditJournalRepository.record(AuditAction.LEARNER_TEMPLATE_PREVIEWED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                stream.id(), stream.title(), "строк: " + preview.rows().size() + "; обновить: " + preview.updated()
                        + ", без изменений: " + preview.unchanged() + ", ошибок: " + preview.errors()
                        + ", конфликтов: " + preview.conflicts(), requestId);
        return preview;
    }

    @Transactional
    QuestionnaireImport apply(
            CrmProfile actor,
            String id,
            MultipartFile file,
            String fingerprint,
            String idempotencyKey,
            String requestId
    ) {
        enrolmentAccess.requireOperator(actor);
        StreamSummary stream = streamService.summary(EnrolmentAccess.parseId(id));
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new InteractionValidationException("fingerprint", "Сначала постройте предпросмотр файла");
        }
        String checksum = inspect(file);
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.APPLY_QUESTIONNAIRE_IMPORT,
                idempotencyKey, commands.fingerprint(List.of(stream.id(), checksum, fingerprint)));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), QuestionnaireImport.class);
        }
        Plan plan = plan(stream, file, checksum);
        if (!plan.fingerprint().equals(fingerprint)) {
            throw InteractionConflictException.questionnaireChanged();
        }
        for (PlannedRow row : plan.rows()) {
            if (row.view().status() == QuestionnaireStatus.UPDATE) {
                learnerService.update(actor.id(), row.learner().id(), row.learner().version(), row.merged(), requestId);
            }
        }
        QuestionnaireImport result = plan.result(true);
        auditJournalRepository.record(AuditAction.LEARNER_TEMPLATE_IMPORTED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                stream.id(), stream.title(), "строк: " + result.rows().size() + "; применено: " + result.updated()
                        + ", без изменений: " + result.unchanged() + ", пропущено с ошибками и конфликтами: "
                        + (result.errors() + result.conflicts()), requestId);
        commands.complete(reservation, result.withoutNames());
        return result;
    }

    private String inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InteractionValidationException("file", "Выберите файл XLSX или XLS");
        }
        if (file.getSize() > LearnerWorkbookReader.MAX_FILE_BYTES) {
            throw new AttachmentTooLargeException();
        }
        AttachmentUploadInspection inspection = contentValidator.inspect(file);
        String name = inspection.originalName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw new InteractionValidationException("file", "Поддерживаются только книги XLSX и XLS");
        }
        AttachmentScanOutcome outcome;
        try (InputStream input = file.getInputStream()) {
            outcome = scanner.scan(input, inspection.sizeBytes());
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл не удалось прочитать");
        }
        if (outcome == AttachmentScanOutcome.REJECTED) {
            throw new InteractionValidationException("file", "Антивирусная проверка отклонила файл");
        }
        if (outcome != AttachmentScanOutcome.CLEAN) {
            throw new InteractionValidationException("file", "Антивирусная проверка недоступна; повторите позже");
        }
        return inspection.checksum();
    }

    private Plan plan(StreamSummary stream, MultipartFile file, String checksum) {
        LearnerWorkbook workbook = reader.read(file);
        LocalDate today = LocalDate.now(EnrolmentLearnerService.ZONE);
        List<StreamEnrolmentRow> enrolled = enrolmentRepository.findStreamRows(stream.id());
        Map<String, List<UUID>> byEmail = index(enrolled, StreamEnrolmentRow::emailHmac);
        Map<String, List<UUID>> byPhone = index(enrolled, StreamEnrolmentRow::phoneHmac);
        List<List<UUID>> candidates = workbook.rows().stream()
                .map(row -> candidates(row.profile(), byEmail, byPhone))
                .toList();
        Map<UUID, Learner> learners = learnerService.findAll(candidates.stream().flatMap(List::stream).distinct().toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        Map<UUID, Integer> matchedRows = new HashMap<>();
        Map<String, Integer> snilsRows = new HashMap<>();
        List<PlannedRow> rows = new ArrayList<>();
        for (int index = 0; index < workbook.rows().size(); index++) {
            rows.add(planRow(workbook.rows().get(index), candidates.get(index), learners, workbook.columns(), matchedRows, snilsRows,
                    today));
        }
        String fingerprint = commands.fingerprint(List.of(checksum, stream.id(), enrolled.stream()
                .map(row -> row.learnerId() + ":" + row.learnerVersion())
                .sorted()
                .toList()));
        return new Plan(fingerprint, workbook.ignoredHeaders(), List.copyOf(rows));
    }

    private PlannedRow planRow(
            LearnerWorkbookRow row,
            List<UUID> candidates,
            Map<UUID, Learner> learners,
            Map<LearnerField, LearnerWorkbookColumn> columns,
            Map<UUID, Integer> matchedRows,
            Map<String, Integer> snilsRows,
            LocalDate today
    ) {
        LearnerProfile values = row.profile();
        List<QuestionnaireIssue> issues = new ArrayList<>();
        row.errors().forEach(error -> issues.add(issue(columns, error, false)));
        row.warnings().forEach(warning -> issues.add(issue(columns, warning, true)));
        if (candidates.isEmpty()) {
            return rejected(row, QuestionnaireStatus.ERROR, null, issues, NOT_IN_STREAM);
        }
        List<Learner> matched = candidates.stream()
                .map(learners::get)
                .filter(Objects::nonNull)
                .filter(learner -> LearnerRules.samePerson(learner.profile(), values.lastName(), values.firstName(), values.middleName()))
                .toList();
        if (matched.isEmpty()) {
            return rejected(row, QuestionnaireStatus.CONFLICT, null, issues, "Конфликт: ФИО не совпадает с анкетой");
        }
        if (matched.size() > 1) {
            return rejected(row, QuestionnaireStatus.CONFLICT, null, issues,
                    "Конфликт: несколько слушателей потока с этими контактами и ФИО");
        }
        Learner learner = matched.getFirst();
        Integer previous = matchedRows.putIfAbsent(learner.id(), row.rowNumber());
        if (previous != null) {
            return rejected(row, QuestionnaireStatus.ERROR, learner.id(), issues,
                    "Строка относится к тому же слушателю, что строка " + previous);
        }
        if (learner.status() != PersonalDataStatus.ACTIVE) {
            return rejected(row, QuestionnaireStatus.CONFLICT, learner.id(), issues,
                    "Обработка анкеты ограничена по обращению субъекта: анкета не изменяется");
        }
        Map<LearnerField, String> changes = new EnumMap<>(LearnerField.class);
        for (LearnerField field : LearnerField.values()) {
            if (values.value(field) != null) {
                changes.put(field, values.stored(field));
            }
        }
        LearnerProfile merged = learner.profile().with(changes);
        LearnerProfile normalized = merged.normalized();
        Set<LearnerField> failed = row.errors().stream().map(LearnerFieldError::field).collect(Collectors.toSet());
        LearnerRules.check(normalized, today).stream()
                .filter(error -> !failed.contains(error.field()))
                .forEach(error -> issues.add(issue(columns, error, false)));
        snilsIssue(normalized.snils(), learner, row.rowNumber(), snilsRows).ifPresent(error -> issues.add(issue(columns, error, false)));
        boolean invalid = issues.stream().anyMatch(issue -> !issue.warning());
        List<LearnerField> changed = invalid ? List.of() : Arrays.stream(LearnerField.values())
                .filter(field -> !Objects.equals(learner.profile().value(field), normalized.value(field)))
                .toList();
        QuestionnaireStatus status = invalid ? QuestionnaireStatus.ERROR
                : changed.isEmpty() ? QuestionnaireStatus.UNCHANGED : QuestionnaireStatus.UPDATE;
        return new PlannedRow(new QuestionnaireRow(row.rowNumber(), status, learner.id(), values.lastName(), values.firstName(), changed,
                List.copyOf(issues)), learner, merged);
    }

    private Optional<LearnerFieldError> snilsIssue(String snils, Learner learner, int rowNumber, Map<String, Integer> snilsRows) {
        if (snils == null || snils.equals(learner.profile().snils()) || LearnerRules.normalizeSnils(snils) == null) {
            return Optional.empty();
        }
        Integer previous = snilsRows.putIfAbsent(snils, rowNumber);
        if (previous != null) {
            return Optional.of(new LearnerFieldError(LearnerField.SNILS, "СНИЛС повторяется в строке " + previous));
        }
        return learnerService.findBySnils(snils)
                .filter(owner -> !owner.id().equals(learner.id()))
                .map(owner -> new LearnerFieldError(LearnerField.SNILS, "СНИЛС уже есть у другого слушателя"));
    }

    private List<UUID> candidates(LearnerProfile profile, Map<String, List<UUID>> byEmail, Map<String, List<UUID>> byPhone) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (profile.email() != null) {
            ids.addAll(byEmail.getOrDefault(cipher.emailFingerprint(profile.email()), List.of()));
        }
        String phone = learnerService.phoneFingerprint(profile.phone());
        if (phone != null) {
            ids.addAll(byPhone.getOrDefault(phone, List.of()));
        }
        return List.copyOf(ids);
    }

    private static Map<String, List<UUID>> index(List<StreamEnrolmentRow> rows, Function<StreamEnrolmentRow, String> fingerprint) {
        return rows.stream()
                .filter(row -> fingerprint.apply(row) != null)
                .collect(Collectors.groupingBy(fingerprint, Collectors.mapping(StreamEnrolmentRow::learnerId, Collectors.toList())));
    }

    private static PlannedRow rejected(
            LearnerWorkbookRow row,
            QuestionnaireStatus status,
            UUID learnerId,
            List<QuestionnaireIssue> issues,
            String message
    ) {
        List<QuestionnaireIssue> all = new ArrayList<>(issues);
        all.add(new QuestionnaireIssue(null, null, null, message, false));
        return new PlannedRow(new QuestionnaireRow(row.rowNumber(), status, learnerId, row.profile().lastName(),
                row.profile().firstName(), List.of(), List.copyOf(all)), null, null);
    }

    private static QuestionnaireIssue issue(Map<LearnerField, LearnerWorkbookColumn> columns, LearnerFieldError error, boolean warning) {
        LearnerWorkbookColumn column = columns.get(error.field());
        return new QuestionnaireIssue(
                column == null ? null : column.letter(),
                column == null ? error.field().label() : column.header(),
                error.field(),
                error.message(),
                warning
        );
    }

    private record PlannedRow(QuestionnaireRow view, Learner learner, LearnerProfile merged) {
    }

    private record Plan(String fingerprint, List<String> ignoredHeaders, List<PlannedRow> rows) {
        QuestionnaireImport result(boolean applied) {
            List<QuestionnaireRow> views = rows.stream().map(PlannedRow::view).toList();
            return new QuestionnaireImport(applied, fingerprint, ignoredHeaders, views, count(views, QuestionnaireStatus.UPDATE),
                    count(views, QuestionnaireStatus.UNCHANGED), count(views, QuestionnaireStatus.ERROR),
                    count(views, QuestionnaireStatus.CONFLICT));
        }

        private static int count(List<QuestionnaireRow> rows, QuestionnaireStatus status) {
            return (int) rows.stream().filter(row -> row.status() == status).count();
        }
    }
}
