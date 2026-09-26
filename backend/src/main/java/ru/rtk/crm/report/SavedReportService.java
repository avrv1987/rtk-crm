package ru.rtk.crm.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.report.SavedReportRepository.StoredReport;

@Service
public class SavedReportService {
    static final int MAX_SAVED_REPORTS = 50;

    private static final int MAX_NAME_LENGTH = 200;

    private final SavedReportRepository repository;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public SavedReportService(
            SavedReportRepository repository,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SavedReport> list(CrmProfile profile) {
        return repository.findOwned(profile.id()).stream().map(this::view).toList();
    }

    @Transactional
    public SavedReport create(CrmProfile profile, SavedReportRequest request, String idempotencyKey) {
        String key = requiredIdempotencyKey(idempotencyKey);
        String name = name(request);
        SavedReportPeriod period = request.period();
        ReportRequest definition = definition(request);
        String fingerprint = CommandFingerprint.of(objectMapper, new CreateCommand(name, definition, period));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.CREATE_SAVED_REPORT, key, fingerprint, now)) {
            return replay(profile.id(), CommandOperation.CREATE_SAVED_REPORT, key, fingerprint);
        }
        if (repository.countOwned(profile.id()) >= MAX_SAVED_REPORTS) {
            throw new InteractionValidationException(
                    "name", "Можно сохранить не более " + MAX_SAVED_REPORTS + " отчётов; удалите ненужные"
            );
        }
        requireFreeName(profile.id(), name, null);
        UUID id = UUID.randomUUID();
        try {
            repository.insert(id, profile.id(), name, write(definition), period, now);
        } catch (DuplicateKeyException exception) {
            throw nameTaken();
        }
        return store(commandId, requireOwned(profile, id));
    }

    @Transactional
    public SavedReport update(CrmProfile profile, UUID id, SavedReportRequest request, String idempotencyKey) {
        String key = requiredIdempotencyKey(idempotencyKey);
        int expectedVersion = requiredVersion(request == null ? null : request.version());
        String name = name(request);
        SavedReportPeriod period = request.period();
        ReportRequest definition = definition(request);
        String fingerprint = CommandFingerprint.of(objectMapper, new UpdateCommand(id, expectedVersion, name, definition, period));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.UPDATE_SAVED_REPORT, key, fingerprint, now)) {
            return replay(profile.id(), CommandOperation.UPDATE_SAVED_REPORT, key, fingerprint);
        }
        requireVersion(requireOwned(profile, id), expectedVersion);
        requireFreeName(profile.id(), name, id);
        boolean updated;
        try {
            updated = repository.update(id, profile.id(), expectedVersion, name, write(definition), period, now);
        } catch (DuplicateKeyException exception) {
            throw nameTaken();
        }
        if (!updated) {
            throw versionConflict();
        }
        return store(commandId, requireOwned(profile, id));
    }

    @Transactional
    public void delete(CrmProfile profile, UUID id, Integer version, String idempotencyKey) {
        String key = requiredIdempotencyKey(idempotencyKey);
        int expectedVersion = requiredVersion(version);
        String fingerprint = CommandFingerprint.of(objectMapper, new DeleteCommand(id, expectedVersion));
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(
                commandId, profile.id(), CommandOperation.DELETE_SAVED_REPORT, key, fingerprint, OffsetDateTime.now()
        )) {
            requireSameCommand(profile.id(), CommandOperation.DELETE_SAVED_REPORT, key, fingerprint);
            return;
        }
        requireVersion(requireOwned(profile, id), expectedVersion);
        if (!repository.delete(id, profile.id(), expectedVersion)) {
            throw versionConflict();
        }
        commandIdempotencyRepository.complete(commandId, "{}");
    }

    private SavedReport requireOwned(CrmProfile profile, UUID id) {
        return repository.findOwned(id, profile.id()).map(this::view).orElseThrow(ReportException::savedReportNotFound);
    }

    private static void requireVersion(SavedReport current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private void requireFreeName(UUID ownerProfileId, String name, UUID exceptId) {
        if (repository.nameTaken(ownerProfileId, name, exceptId)) {
            throw nameTaken();
        }
    }

    private static InteractionValidationException nameTaken() {
        return new InteractionValidationException("name", "Отчёт с таким названием уже сохранён; выберите другое название");
    }

    private static ReportException versionConflict() {
        return new ReportException(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Сохранённый отчёт уже изменили или удалили в другой вкладке; обновите список"
        );
    }

    private static String name(SavedReportRequest request) {
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank()) {
            throw new InteractionValidationException("name", "Укажите название отчёта");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new InteractionValidationException("name", "Название длиннее " + MAX_NAME_LENGTH + " символов");
        }
        return trimmed;
    }

    private static ReportRequest definition(SavedReportRequest request) {
        ReportRequest source = request.definition();
        if (source == null) {
            throw new InteractionValidationException("definition", "Передайте набор фильтров и колонок отчёта");
        }
        boolean relative = request.period() != null;
        if (relative && source.kind() == ReportKind.SNAPSHOT) {
            throw new InteractionValidationException(
                    "period",
                    "Для состояния портфеля на дату период не задаётся: оставьте дату пустой, и отчёт будет строиться на сегодня"
            );
        }
        ReportRequest definition = new ReportRequest(
                source.kind(),
                relative ? null : source.from(),
                relative ? null : source.to(),
                source.periodBasis(),
                source.filters(),
                source.columns(),
                null,
                null,
                source.sortBy(),
                source.asOf(),
                null,
                null
        );
        definition.normalized();
        return definition;
    }

    private SavedReport view(StoredReport stored) {
        ReportRequest definition = read(stored.definitionJson(), ReportRequest.class);
        SavedReportPeriod period = stored.period();
        if (period != null) {
            LocalDate today = LocalDate.now(ReportRequest.ZONE);
            definition = new ReportRequest(
                    definition.kind(),
                    period.from(today),
                    period.to(today),
                    definition.periodBasis(),
                    definition.filters(),
                    definition.columns(),
                    null,
                    null,
                    definition.sortBy(),
                    definition.asOf(),
                    null,
                    null
            );
        }
        return new SavedReport(
                stored.id(),
                stored.name(),
                definition,
                period,
                stored.version(),
                stored.createdAt(),
                stored.updatedAt()
        );
    }

    private SavedReport replay(UUID actorProfileId, CommandOperation operation, String key, String fingerprint) {
        return read(requireSameCommand(actorProfileId, operation, key, fingerprint), SavedReport.class);
    }

    private String requireSameCommand(UUID actorProfileId, CommandOperation operation, String key, String fingerprint) {
        CommandIdempotencyRepository.CommandRecord command = commandIdempotencyRepository.find(actorProfileId, operation, key)
                .orElseThrow(() -> new IllegalStateException("Reserved saved report command is unavailable"));
        if (!fingerprint.equals(command.requestFingerprint())) {
            throw InteractionConflictException.idempotency();
        }
        if (command.resultJson() == null) {
            throw new IllegalStateException("Reserved saved report command has no result");
        }
        return command.resultJson();
    }

    private SavedReport store(UUID commandId, SavedReport result) {
        commandIdempotencyRepository.complete(commandId, write(result));
        return result;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored saved report cannot be read", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved report cannot be stored", exception);
        }
    }

    private static int requiredVersion(Integer value) {
        if (value == null || value < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите список");
        }
        return value;
    }

    private static String requiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException("Idempotency-Key", "Не передан ключ повтора запроса Idempotency-Key");
        }
        if (value.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Ключ повтора запроса Idempotency-Key длиннее 255 символов");
        }
        return value;
    }

    private record CreateCommand(String name, ReportRequest definition, SavedReportPeriod period) {
    }

    private record UpdateCommand(UUID id, int version, String name, ReportRequest definition, SavedReportPeriod period) {
    }

    private record DeleteCommand(UUID id, int version) {
    }
}
