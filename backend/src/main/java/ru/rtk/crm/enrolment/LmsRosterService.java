package ru.rtk.crm.enrolment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class LmsRosterService {
    private static final Comparator<LearnerProfile> BY_NAME = Comparator
            .comparing(LearnerProfile::lastName, EnrolmentStreamService.RUSSIAN)
            .thenComparing(LearnerProfile::firstName, EnrolmentStreamService.RUSSIAN);

    private final EnrolmentAccess enrolmentAccess;
    private final EnrolmentStreamService streamService;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerService learnerService;
    private final LmsRosterWorkbookWriter writer;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;
    private final EnrolmentBatchRepository batchRepository;

    LmsRosterService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentStreamService streamService,
            EnrolmentRepository enrolmentRepository,
            LearnerService learnerService,
            LmsRosterWorkbookWriter writer,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands,
            EnrolmentBatchRepository batchRepository
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.streamService = streamService;
        this.enrolmentRepository = enrolmentRepository;
        this.learnerService = learnerService;
        this.writer = writer;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
        this.batchRepository = batchRepository;
    }

    @Transactional
    LmsRoster export(CrmProfile actor, String id, LmsRosterRequest request, String requestId) {
        enrolmentAccess.requireOperator(actor);
        StreamSummary stream = streamService.summary(EnrolmentAccess.parseId(id));
        if (request == null || request.mode() == null) {
            throw new InteractionValidationException("mode", "Выберите, кого выгрузить: только не переданных или весь поток");
        }
        if (request.incomplete() == null) {
            throw new InteractionValidationException("incomplete", "Выберите, выгружать ли анкеты без обязательных полей");
        }
        List<StreamEnrolmentRow> scope = enrolmentRepository.findStreamRows(stream.id()).stream()
                .filter(row -> request.mode() == RosterMode.ALL || row.lmsTransferredAt() == null)
                .toList();
        List<StreamEnrolmentRow> available = scope.stream().filter(StreamEnrolmentRow::available).toList();
        List<StreamEnrolmentRow> rows = request.incomplete() == IncompleteProfiles.EXCLUDE
                ? available.stream().filter(row -> !row.missingForLms()).toList()
                : available;
        if (rows.isEmpty()) {
            throw new InteractionValidationException("mode", emptyMessage(request.mode(), scope, available));
        }
        Map<UUID, Learner> learners = learnerService.findAll(rows.stream().map(StreamEnrolmentRow::learnerId).toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        List<StreamEnrolmentRow> ordered = rows.stream()
                .sorted(Comparator.comparing((StreamEnrolmentRow row) -> learners.get(row.learnerId()).profile(), BY_NAME))
                .toList();
        List<LearnerProfile> profiles = ordered.stream().map(row -> learners.get(row.learnerId()).profile()).toList();
        RosterFormat format = request.format() == null ? RosterFormat.XLSX_TEMPLATE : request.format();
        UUID exportId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        byte[] content = format == RosterFormat.MOODLE_CSV ? MoodleCsvWriter.write(profiles) : workbook(profiles);
        String checksum = sha256(content);
        String fileName = fileName(stream, format);
        // Партия неизменяема: состав, номер строки в файле и версия анкеты на момент выгрузки записываются один раз.
        // Повторная выгрузка создаёт новую партию; прежняя остаётся воспроизводимой вместе с её результатами.
        // Сам файл не хранится (в нём персональные данные), контрольная сумма позволяет сверить сохранённую копию.
        batchRepository.insertBatch(exportId, stream.id(), format, request.mode(), fileName, checksum, ordered.size(), actor.id(), now);
        for (int index = 0; index < ordered.size(); index++) {
            StreamEnrolmentRow row = ordered.get(index);
            batchRepository.insertItem(exportId, index + 2, row.id(), row.learnerId(), row.learnerVersion());
        }
        enrolmentRepository.markExported(rows.stream().map(StreamEnrolmentRow::id).toList(), exportId, now);
        auditJournalRepository.record(AuditAction.LMS_ROSTER_EXPORTED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                stream.id(), stream.title(), "строк: " + rows.size() + "; выгрузка: " + exportId + "; режим: "
                        + (request.mode() == RosterMode.ALL ? "все слушатели потока" : "только не переданные")
                        + "; анкеты без обязательных полей: "
                        + (request.incomplete() == IncompleteProfiles.EXCLUDE ? "исключены" : "выгружены с пустыми ячейками")
                        + "; формат: " + (format == RosterFormat.MOODLE_CSV ? "CSV Moodle" : "XLSX по шаблону") + "; SHA-256: " + checksum,
                requestId);
        return new LmsRoster(exportId, fileName, rows.size(), content, format, checksum);
    }

    // «Отметить переданными» фиксирует факт передачи партии в LMS, но не приём: строки остаются «ожидает
    // результата», пока оператор не загрузит протокол LMS или не отметит строки. Сформированный и не переданный
    // файл передачей не считается.
    @Transactional
    RosterExportMarked markTransferred(CrmProfile actor, String id, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID exportId = EnrolmentAccess.parseId(id);
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.MARK_ROSTER_TRANSFERRED,
                idempotencyKey, commands.fingerprint(List.of(exportId)));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), RosterExportMarked.class);
        }
        batchRepository.lock(exportId).orElseThrow(() -> new EnrolmentNotFoundException("Выгрузка для LMS не найдена"));
        EnrolmentBatchRepository.BatchRow batch = batchRepository.find(exportId).orElseThrow();
        int marked = 0;
        if (batchRepository.markSent(exportId, actor.id(), OffsetDateTime.now())) {
            marked = batchRepository.countPending(exportId);
            StreamSummary stream = streamService.summary(batch.streamId());
            auditJournalRepository.record(AuditAction.LMS_ROSTER_MARKED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                    batch.streamId(), stream.title(), "выгрузка: " + exportId + "; партия передана в LMS, ожидается результат строк: "
                            + marked, requestId);
        }
        RosterExportMarked result = new RosterExportMarked(exportId, batch.streamId(), batch.rows(), marked);
        commands.complete(reservation, result);
        return result;
    }

    static String fileName(StreamSummary stream) {
        return fileName(stream, RosterFormat.XLSX_TEMPLATE);
    }

    static String fileName(StreamSummary stream, RosterFormat format) {
        return "LMS_" + stream.courseName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + "_поток" + stream.streamNo() + "_"
                + LocalDate.now(EnrolmentLearnerService.ZONE) + (format == RosterFormat.MOODLE_CSV ? ".csv" : ".xlsx");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] workbook(List<LearnerProfile> profiles) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writer.write(profiles, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("LMS roster workbook cannot be written", exception);
        }
        return output.toByteArray();
    }

    private static String emptyMessage(RosterMode mode, List<StreamEnrolmentRow> scope, List<StreamEnrolmentRow> available) {
        if (scope.isEmpty()) {
            return mode == RosterMode.PENDING
                    ? "Новых оплативших нет: все слушатели потока переданы в LMS"
                    : "В потоке нет слушателей";
        }
        if (available.isEmpty()) {
            return "Анкеты этих слушателей не выгружаются: обработка ограничена или анкета обезличена";
        }
        return "У всех слушателей для выгрузки не заполнены обязательные поля; выберите «Выгрузить с пустыми ячейками»";
    }
}
