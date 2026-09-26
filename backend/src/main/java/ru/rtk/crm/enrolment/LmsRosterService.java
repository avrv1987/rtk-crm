package ru.rtk.crm.enrolment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
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

    LmsRosterService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentStreamService streamService,
            EnrolmentRepository enrolmentRepository,
            LearnerService learnerService,
            LmsRosterWorkbookWriter writer,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.streamService = streamService;
        this.enrolmentRepository = enrolmentRepository;
        this.learnerService = learnerService;
        this.writer = writer;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
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
        List<LearnerProfile> profiles = rows.stream()
                .map(row -> learners.get(row.learnerId()).profile())
                .sorted(BY_NAME)
                .toList();
        UUID exportId = UUID.randomUUID();
        byte[] content = workbook(profiles);
        enrolmentRepository.markExported(rows.stream().map(StreamEnrolmentRow::id).toList(), exportId, OffsetDateTime.now());
        auditJournalRepository.record(AuditAction.LMS_ROSTER_EXPORTED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                stream.id(), stream.title(), "строк: " + rows.size() + "; выгрузка: " + exportId + "; режим: "
                        + (request.mode() == RosterMode.ALL ? "все слушатели потока" : "только не переданные")
                        + "; анкеты без обязательных полей: "
                        + (request.incomplete() == IncompleteProfiles.EXCLUDE ? "исключены" : "выгружены с пустыми ячейками"),
                requestId);
        return new LmsRoster(exportId, fileName(stream), rows.size(), content);
    }

    @Transactional
    RosterExportMarked markTransferred(CrmProfile actor, String id, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID exportId = EnrolmentAccess.parseId(id);
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.MARK_ROSTER_TRANSFERRED,
                idempotencyKey, commands.fingerprint(List.of(exportId)));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), RosterExportMarked.class);
        }
        UUID streamId = enrolmentRepository.findExportStream(exportId)
                .orElseThrow(() -> new EnrolmentNotFoundException("Выгрузка для LMS не найдена"));
        int marked = enrolmentRepository.markTransferred(exportId, OffsetDateTime.now());
        if (marked > 0) {
            StreamSummary stream = streamService.summary(streamId);
            auditJournalRepository.record(AuditAction.LMS_ROSTER_MARKED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                    streamId, stream.title(), "выгрузка: " + exportId + "; отмечено переданными: " + marked, requestId);
        }
        RosterExportMarked result = new RosterExportMarked(exportId, streamId, enrolmentRepository.countExport(exportId), marked);
        commands.complete(reservation, result);
        return result;
    }

    static String fileName(StreamSummary stream) {
        return "LMS_" + stream.courseName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + "_поток" + stream.streamNo() + "_"
                + LocalDate.now(EnrolmentLearnerService.ZONE) + ".xlsx";
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
