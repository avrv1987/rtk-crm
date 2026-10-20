package ru.rtk.crm.enrolment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceSettingsService;

@Service
public class EnrolmentBatchService {
    static final int QUEUE_ITEMS = 50;
    static final int QUEUE_PAGE_LIMIT = 200;
    private static final long MAX_PROTOCOL_BYTES = 5L * 1024L * 1024L;
    private static final int MAX_PROTOCOL_ROWS = 5_000;
    private static final int ERROR_LIMIT = 500;
    private static final Set<String> ACCEPTED_WORDS = Set.of("принята", "принято", "принят", "ok", "успешно", "успех", "accepted",
            "success", "добавлен", "создан", "added", "created", "updated");
    private static final Set<String> FAILED_WORDS = Set.of("ошибка", "error", "failed", "отклонена", "отклонено", "не принята");
    private static final Set<String> PENDING_WORDS = Set.of("ожидает", "pending");

    private final EnrolmentAccess enrolmentAccess;
    private final EnrolmentBatchRepository batchRepository;
    private final EnrolmentRepository enrolmentRepository;
    private final EnrolmentStreamService streamService;
    private final LearnerService learnerService;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;
    private final SourceSettingsService sourceSettings;

    EnrolmentBatchService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentBatchRepository batchRepository,
            EnrolmentRepository enrolmentRepository,
            EnrolmentStreamService streamService,
            LearnerService learnerService,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands,
            SourceSettingsService sourceSettings
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.batchRepository = batchRepository;
        this.enrolmentRepository = enrolmentRepository;
        this.streamService = streamService;
        this.learnerService = learnerService;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
        this.sourceSettings = sourceSettings;
    }

    @Transactional
    EnrolmentBatchView batch(CrmProfile actor, String id, String requestId) {
        enrolmentAccess.requireOperator(actor);
        EnrolmentBatchView view = view(EnrolmentAccess.parseId(id));
        auditJournalRepository.record(AuditAction.LEARNER_LIST_VIEWED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                view.streamId(), view.streamTitle(), "партия LMS: " + view.id() + "; строк: " + view.rows(), requestId);
        return view;
    }

    @Transactional
    BatchResultsApplied record(CrmProfile actor, String id, BatchResultUpdate update, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID batchId = EnrolmentAccess.parseId(id);
        if (update == null || update.items() == null || update.items().isEmpty()) {
            throw new InteractionValidationException("items", "Отметьте хотя бы одну строку партии");
        }
        List<ResultLine> lines = new ArrayList<>();
        for (BatchResultItem item : update.items()) {
            if (item == null || item.itemId() == null || item.result() == null) {
                throw new InteractionValidationException("items", "У каждой строки укажите результат");
            }
            lines.add(new ResultLine(item.itemId(), null, item.result(), item.error()));
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.RECORD_LMS_RESULTS, idempotencyKey,
                commands.fingerprint(Arrays.asList(batchId, update.items())));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), BatchResultsApplied.class);
        }
        BatchResultsApplied result = apply(actor, batchId, lines, "MANUAL", List.of(), requestId);
        commands.complete(reservation, result);
        return result;
    }

    // Протокол приёма LMS: строки «номер строки файла; результат; ошибка». Номер строки — как в выгруженном файле
    // (первая строка — заголовок), поэтому протокол сопоставляется с партией без персональных данных.
    @Transactional
    BatchResultsApplied importProtocol(CrmProfile actor, String id, MultipartFile file, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID batchId = EnrolmentAccess.parseId(id);
        if (file == null || file.isEmpty()) {
            throw new InteractionValidationException("file", "Выберите файл протокола LMS");
        }
        if (file.getSize() > MAX_PROTOCOL_BYTES) {
            throw new InteractionValidationException("file", "Файл протокола больше 5 МиБ");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл протокола не читается");
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.IMPORT_LMS_PROTOCOL, idempotencyKey,
                commands.fingerprint(Arrays.asList(batchId, sha256(content))));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), BatchResultsApplied.class);
        }
        List<String> skipped = new ArrayList<>();
        List<ResultLine> lines = new ArrayList<>();
        for (List<String> cells : protocolRows(content)) {
            parseLine(cells, lines, skipped);
        }
        if (lines.isEmpty()) {
            throw new InteractionValidationException("file", "В протоколе нет строк вида «номер строки, результат, ошибка»");
        }
        BatchResultsApplied result = apply(actor, batchId, lines, "PROTOCOL", skipped, requestId);
        commands.complete(reservation, result);
        return result;
    }

    @Transactional
    EnrolmentQueue queue(CrmProfile actor, String requestId) {
        return queue(actor, requestId, null, null, 0, QUEUE_ITEMS);
    }

    // Отбор по потоку и группе и листание: в группе kind строки с offset, в остальных группах — первые limit.
    // Счётчики групп считаются после отбора по потоку, список потоков — по всей очереди.
    @Transactional
    EnrolmentQueue queue(CrmProfile actor, String requestId, UUID streamId, EnrolmentQueueKind onlyKind, int offset, int limit) {
        enrolmentAccess.requireOperator(actor);
        if (offset < 0) {
            throw new InteractionValidationException("offset", "Смещение не может быть отрицательным");
        }
        if (limit < 1 || limit > QUEUE_PAGE_LIMIT) {
            throw new InteractionValidationException("limit", "Размер страницы — от 1 до " + QUEUE_PAGE_LIMIT);
        }
        List<StreamEnrolmentRow> rows = enrolmentRepository.findAllStreamRows();
        Map<UUID, StreamSummary> streams = enrolmentRepository.findSummaries().stream()
                .collect(Collectors.toMap(StreamSummary::id, Function.identity()));
        Map<UUID, List<StreamEnrolmentRow>> byStream = rows.stream().collect(Collectors.groupingBy(StreamEnrolmentRow::streamId));
        Map<EnrolmentQueueKind, List<Candidate>> found = new EnumMap<>(EnrolmentQueueKind.class);
        for (List<StreamEnrolmentRow> streamRows : byStream.values()) {
            List<StreamEnrolmentRow> available = streamRows.stream().filter(StreamEnrolmentRow::available).toList();
            Map<String, Long> emails = EnrolmentStreamService.emailCounts(available);
            Map<String, Long> phones = available.stream().filter(row -> row.phoneHmac() != null)
                    .collect(Collectors.groupingBy(StreamEnrolmentRow::phoneHmac, Collectors.counting()));
            for (StreamEnrolmentRow row : streamRows) {
                classify(row, emails, phones).forEach((kind, detail) -> found.computeIfAbsent(kind, ignored -> new ArrayList<>())
                        .add(new Candidate(row, detail)));
            }
        }
        Comparator<Candidate> order = Comparator
                .comparing((Candidate candidate) -> streams.get(candidate.row().streamId()).title(), EnrolmentStreamService.RUSSIAN)
                .thenComparing(candidate -> candidate.row().orderNumber(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.row().id());
        Map<UUID, Integer> perStream = new HashMap<>();
        found.values().forEach(candidates -> candidates.forEach(candidate -> perStream.merge(candidate.row().streamId(), 1, Integer::sum)));
        if (streamId != null) {
            found.replaceAll((kind, candidates) -> candidates.stream().filter(candidate -> candidate.row().streamId().equals(streamId)).toList());
        }
        Map<EnrolmentQueueKind, List<Candidate>> shown = new EnumMap<>(EnrolmentQueueKind.class);
        found.forEach((kind, candidates) -> shown.put(kind, onlyKind != null && kind != onlyKind
                ? List.of()
                : candidates.stream().sorted(order).skip(onlyKind == null ? 0 : offset).limit(limit).toList()));
        Map<UUID, Learner> learners = learnerService.findAll(shown.values().stream().flatMap(List::stream)
                        .map(candidate -> candidate.row().learnerId()).distinct().toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        List<EnrolmentQueueGroup> groups = new ArrayList<>();
        int total = 0;
        for (EnrolmentQueueKind kind : EnrolmentQueueKind.values()) {
            List<EnrolmentQueueItem> items = shown.getOrDefault(kind, List.of()).stream()
                    .map(candidate -> item(kind, candidate, streams.get(candidate.row().streamId()), learners.get(candidate.row().learnerId())))
                    .toList();
            total += items.size();
            groups.add(new EnrolmentQueueGroup(kind, found.getOrDefault(kind, List.of()).size(), items));
        }
        List<EnrolmentQueueStream> queueStreams = perStream.entrySet().stream()
                .map(entry -> new EnrolmentQueueStream(entry.getKey(), streams.get(entry.getKey()).title(), entry.getValue()))
                .sorted(Comparator.comparing(EnrolmentQueueStream::title, EnrolmentStreamService.RUSSIAN))
                .toList();
        auditJournalRepository.record(AuditAction.LEARNER_LIST_VIEWED, actor.id(), "ENROLMENT_QUEUE", null, "Очередь зачисления",
                "строк: " + total, requestId);
        return new EnrolmentQueue(groups, sourceSettings.moodleWrite().isPresent(), queueStreams);
    }

    @Transactional
    LmsReconciled reconcile(CrmProfile actor, String id, LmsReconcile request, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID enrolmentId = EnrolmentAccess.parseId(id);
        if (request == null || request.kind() == null || request.kind() != EnrolmentQueueKind.MOVED_AFTER_TRANSFER
                && request.kind() != EnrolmentQueueKind.CHANGED_AFTER_TRANSFER) {
            throw new InteractionValidationException("kind", "Укажите, какая сверка с LMS выполнена");
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.RECONCILE_LMS, idempotencyKey,
                commands.fingerprint(Arrays.asList(enrolmentId, request.kind(), request.learnerVersion())));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), LmsReconciled.class);
        }
        StreamEnrolmentRow row = enrolmentRepository.findStreamRow(enrolmentId)
                .orElseThrow(() -> new EnrolmentNotFoundException("Зачисление не найдено"));
        OffsetDateTime now = OffsetDateTime.now();
        String details;
        if (request.kind() == EnrolmentQueueKind.MOVED_AFTER_TRANSFER) {
            if (!enrolmentRepository.clearReconcile(enrolmentId, now)) {
                throw InteractionConflictException.stateConflict("Сверка переноса уже отмечена");
            }
            details = "перенос после выгрузки сверен с LMS";
        } else {
            // Версия анкеты из запроса должна совпасть с текущей: если анкету успели снова изменить, менеджер учебного процесса
            // подтверждает не то, что внёс в LMS.
            if (request.learnerVersion() == null || request.learnerVersion() != row.learnerVersion()) {
                throw InteractionConflictException.learnerVersion(row.learnerVersion());
            }
            if (!enrolmentRepository.markSynced(enrolmentId, row.learnerVersion(), now)) {
                throw InteractionConflictException.stateConflict("Изменений анкеты после передачи в LMS нет");
            }
            details = "изменения анкеты внесены в LMS, версия анкеты " + row.learnerVersion();
        }
        StreamSummary stream = streamService.summary(row.streamId());
        auditJournalRepository.record(AuditAction.LMS_RECONCILED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE, stream.id(),
                stream.title(), details, requestId);
        LmsReconciled result = new LmsReconciled(enrolmentId, request.kind());
        commands.complete(reservation, result);
        return result;
    }

    // Признаки очереди считаются по текущей партии зачисления. Одно зачисление может попасть в несколько очередей:
    // например, без телефона и с совпадающей почтой.
    static Map<EnrolmentQueueKind, String> classify(StreamEnrolmentRow row, Map<String, Long> emails, Map<String, Long> phones) {
        Map<EnrolmentQueueKind, String> kinds = new LinkedHashMap<>();
        boolean accepted = row.lmsTransferredAt() != null;
        if (row.lmsReconcileReason() != null) {
            kinds.put(EnrolmentQueueKind.MOVED_AFTER_TRANSFER, row.lmsReconcileReason());
        }
        if (!row.available()) {
            return kinds;
        }
        if (accepted) {
            if (row.lmsSyncedVersion() != null && row.learnerVersion() > row.lmsSyncedVersion()) {
                kinds.put(EnrolmentQueueKind.CHANGED_AFTER_TRANSFER, "в LMS ушла версия " + row.lmsSyncedVersion()
                        + ", сейчас версия " + row.learnerVersion());
            }
            return kinds;
        }
        boolean missing = row.missingForLms();
        boolean ambiguous = row.emailHmac() != null && emails.getOrDefault(row.emailHmac(), 0L) > 1
                || row.phoneHmac() != null && phones.getOrDefault(row.phoneHmac(), 0L) > 1;
        if (missing) {
            kinds.put(EnrolmentQueueKind.MISSING_DATA, LearnerRules.missingForLms(row.missingFields()).stream()
                    .map(LearnerField::label).collect(Collectors.joining(", ")));
        }
        if (ambiguous) {
            kinds.put(EnrolmentQueueKind.AMBIGUOUS_MATCH, "та же почта или телефон у другого слушателя потока");
        }
        if (row.lmsExportId() == null) {
            // Н4: пока перенос из прежнего потока не выполнен, слушатель только в очереди переноса — иначе передача
            // нового потока партией зачислила бы его на новый курс, не сняв со старого.
            if (!missing && !ambiguous && row.lmsReconcileReason() == null) {
                kinds.put(EnrolmentQueueKind.READY, null);
            }
        } else if (row.lmsResult() == BatchItemResult.FAILED) {
            kinds.put(EnrolmentQueueKind.LMS_ERROR, row.lmsError());
        } else if (row.lmsSentAt() == null) {
            kinds.put(EnrolmentQueueKind.NOT_SENT, null);
        } else {
            kinds.put(EnrolmentQueueKind.AWAITING_RESULT, null);
        }
        return kinds;
    }

    private BatchResultsApplied apply(CrmProfile actor, UUID batchId, List<ResultLine> lines, String source, List<String> skippedBefore,
                                      String requestId) {
        batchRepository.lock(batchId).orElseThrow(() -> new EnrolmentNotFoundException("Партия для LMS не найдена"));
        EnrolmentBatchRepository.BatchRow batch = batchRepository.find(batchId).orElseThrow();
        List<EnrolmentBatchRepository.ItemRow> items = batchRepository.findItems(batchId);
        Map<UUID, EnrolmentBatchRepository.ItemRow> byId = items.stream()
                .collect(Collectors.toMap(EnrolmentBatchRepository.ItemRow::id, Function.identity()));
        Map<Integer, EnrolmentBatchRepository.ItemRow> byRow = items.stream()
                .collect(Collectors.toMap(EnrolmentBatchRepository.ItemRow::rowNo, Function.identity()));
        List<String> skipped = new ArrayList<>(skippedBefore);
        OffsetDateTime now = OffsetDateTime.now();
        batchRepository.markSent(batchId, actor.id(), now);
        int changed = 0;
        for (ResultLine line : lines) {
            EnrolmentBatchRepository.ItemRow item = line.itemId() != null ? byId.get(line.itemId()) : byRow.get(line.rowNo());
            if (item == null) {
                if (line.itemId() != null) {
                    throw new InteractionValidationException("items", "Строка не относится к этой партии");
                }
                skipped.add("строка " + line.rowNo() + ": такой строки в партии нет");
                continue;
            }
            String error = line.result() == BatchItemResult.FAILED ? errorText(line.error()) : null;
            if (item.result() == line.result() && Objects.equals(item.error(), error)) {
                continue;
            }
            if (!batchRepository.recordResult(item.id(), line.result(), error, source, actor.id(), now)) {
                skipped.add("строка " + item.rowNo() + ": уже принята LMS, не изменена");
                continue;
            }
            changed++;
            if (line.result() == BatchItemResult.ACCEPTED) {
                enrolmentRepository.markAccepted(item.enrolmentId(), batchId, batch.streamId(), item.learnerVersion(), now);
            }
        }
        EnrolmentBatchView view = view(batchId);
        auditJournalRepository.record(AuditAction.LMS_RESULTS_RECORDED, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                view.streamId(), view.streamTitle(), "партия: " + batchId + "; источник: "
                        + ("PROTOCOL".equals(source) ? "протокол LMS" : "отметка менеджера учебного процесса") + "; изменено строк: " + changed
                        + "; принято: " + view.accepted() + ", ошибок: " + view.failed() + ", ожидает: " + view.pending(), requestId);
        return new BatchResultsApplied(view, changed, skipped);
    }

    EnrolmentBatchView view(UUID batchId) {
        EnrolmentBatchRepository.BatchRow batch = batchRepository.find(batchId)
                .orElseThrow(() -> new EnrolmentNotFoundException("Партия для LMS не найдена"));
        StreamSummary stream = streamService.summary(batch.streamId());
        List<EnrolmentBatchRepository.ItemRow> items = batchRepository.findItems(batchId);
        Map<UUID, Learner> learners = learnerService.findAll(items.stream().map(EnrolmentBatchRepository.ItemRow::learnerId).distinct()
                        .toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        return new EnrolmentBatchView(
                batch.id(), batch.streamId(), stream.title(), batch.format(), batch.fileName(), batch.checksum(), batch.legacy(),
                batch.createdAt(), batch.createdByName(), batch.sentAt(), batch.rows(), batch.accepted(), batch.failed(), batch.pending(),
                items.stream().map(item -> {
                    Learner learner = learners.get(item.learnerId());
                    LearnerProfile profile = learner == null ? null : learner.profile();
                    return new EnrolmentBatchItemView(item.id(), item.rowNo(), item.enrolmentId(), item.learnerId(),
                            profile == null ? null : profile.lastName(), profile == null ? null : profile.firstName(), item.orderNumber(),
                            item.learnerVersion(), item.currentVersion(), item.result(), item.error(), item.resultSource(), item.resultAt());
                }).toList(),
                sourceSettings.moodleWrite().isPresent()
        );
    }

    private static EnrolmentQueueItem item(EnrolmentQueueKind kind, Candidate candidate, StreamSummary stream, Learner learner) {
        StreamEnrolmentRow row = candidate.row();
        LearnerProfile profile = learner == null || learner.status() == PersonalDataStatus.ANONYMIZED
                ? null : learner.profile();
        return new EnrolmentQueueItem(kind, row.id(), row.learnerId(), row.streamId(), stream.title(), row.orderNumber(),
                profile == null ? null : profile.lastName(), profile == null ? null : profile.firstName(), candidate.detail(),
                row.lmsExportId(), row.learnerVersion());
    }

    private static void parseLine(List<String> cells, List<ResultLine> lines, List<String> skipped) {
        if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) {
            return;
        }
        String first = cells.getFirst().strip();
        Integer rowNo = parseRowNo(first);
        if (rowNo == null) {
            if (lines.isEmpty() && skipped.isEmpty()) {
                return;
            }
            skipped.add("«" + abbreviate(first) + "»: номер строки не распознан");
            return;
        }
        String word = cells.size() > 1 ? cells.get(1).strip().toLowerCase(Locale.ROOT).replace('ё', 'е') : "";
        BatchItemResult result = ACCEPTED_WORDS.contains(word) ? BatchItemResult.ACCEPTED
                : FAILED_WORDS.contains(word) ? BatchItemResult.FAILED
                : PENDING_WORDS.contains(word) ? BatchItemResult.PENDING : null;
        if (result == null) {
            skipped.add("строка " + rowNo + ": результат «" + abbreviate(word) + "» не распознан");
            return;
        }
        lines.add(new ResultLine(null, rowNo, result, cells.size() > 2 ? cells.get(2) : null));
    }

    private static Integer parseRowNo(String value) {
        String digits = value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
        try {
            int number = Integer.parseInt(digits);
            return number >= 1 ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<List<String>> protocolRows(byte[] content) {
        boolean zip = content.length > 1 && content[0] == 'P' && content[1] == 'K';
        boolean legacyExcel = content.length > 3 && (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF;
        List<List<String>> rows = zip || legacyExcel ? workbookRows(content) : csvRows(content);
        if (rows.size() > MAX_PROTOCOL_ROWS) {
            throw new InteractionValidationException("file", "В протоколе больше " + MAX_PROTOCOL_ROWS + " строк");
        }
        return rows;
    }

    private static List<List<String>> workbookRows(byte[] content) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<List<String>> rows = new ArrayList<>();
        try (InputStream input = new ByteArrayInputStream(content); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (rows.size() > MAX_PROTOCOL_ROWS) {
                    break;
                }
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < 3; column++) {
                    cells.add(row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)));
                }
                rows.add(cells);
            }
        } catch (IOException | RuntimeException exception) {
            throw new InteractionValidationException("file", "Протокол не читается как книга XLSX или файл CSV");
        }
        return rows;
    }

    // Разделитель — точка с запятой или запятая (что встречается в заголовке чаще); кавычки по правилам CSV.
    static List<List<String>> csvRows(byte[] content) {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            Character delimiter = null;
            while ((line = reader.readLine()) != null) {
                if (rows.isEmpty() && line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                if (delimiter == null) {
                    delimiter = line.chars().filter(ch -> ch == ';').count() > line.chars().filter(ch -> ch == ',').count() ? ';' : ',';
                }
                rows.add(splitCsv(line, delimiter));
                if (rows.size() > MAX_PROTOCOL_ROWS) {
                    break;
                }
            }
        } catch (IOException exception) {
            throw new InteractionValidationException("file", "Файл протокола не читается");
        }
        return rows;
    }

    private static List<String> splitCsv(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (quoted) {
                if (ch == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String errorText(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            return "LMS сообщила об ошибке без описания";
        }
        return text.length() > ERROR_LIMIT ? text.substring(0, ERROR_LIMIT) : text;
    }

    private static String abbreviate(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ResultLine(UUID itemId, Integer rowNo, BatchItemResult result, String error) {
    }

    private record Candidate(StreamEnrolmentRow row, String detail) {
    }
}
