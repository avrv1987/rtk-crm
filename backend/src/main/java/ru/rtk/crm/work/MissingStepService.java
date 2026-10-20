package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalogimport.ImportSettingsService;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;

// «Задать шаг карточкам без шага»: руководитель отбирает карточки в «Контроле», подтверждает, и карточки своей команды
// без шага получают шаг одной командой. Каждая карточка пишет событие плана, вся операция — одну запись журнала.
@Service
public class MissingStepService {
    static final int MAX_CARDS = 1_000;

    private final InteractionService interactionService;
    private final ImportSettingsService importSettingsService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final ObjectMapper objectMapper;

    public MissingStepService(
            InteractionService interactionService,
            ImportSettingsService importSettingsService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            AuditJournalRepository auditJournalRepository,
            ObjectMapper objectMapper
    ) {
        this.interactionService = interactionService;
        this.importSettingsService = importSettingsService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MissingStepResult assign(CrmProfile profile, MissingStepRequest request, String idempotencyKey, String requestId) {
        if (profile.role() != UserRole.LEADER || profile.teamId() == null) {
            throw new WorkAccessDeniedException();
        }
        if (request == null || request.interactionIds() == null || request.interactionIds().isEmpty()) {
            throw new InteractionValidationException("interactionIds", "Выберите карточки");
        }
        if (request.interactionIds().size() > MAX_CARDS || request.interactionIds().stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException("interactionIds", "Не больше " + MAX_CARDS + " карточек без пустых значений");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InteractionValidationException("Idempotency-Key", "Заполните значение");
        }
        ImportSettingsService.ImportSettingsView defaults = importSettingsService.settings();
        String text = request.nextAction() == null || request.nextAction().isBlank()
                ? defaults.defaultStepText()
                : request.nextAction().strip();
        int days = request.dueDays() == null ? defaults.defaultStepDays() : request.dueDays();
        if (text.length() > 500) {
            throw new InteractionValidationException("nextAction", "Длиннее 500 символов");
        }
        if (days < 1 || days > 365) {
            throw new InteractionValidationException("dueDays", "Срок — от 1 до 365 дней");
        }
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(request.interactionIds()));
        String fingerprint = CommandFingerprint.of(objectMapper, List.of(ids, text, days));
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (!commandIdempotencyRepository.reserve(commandId, profile.id(), CommandOperation.ASSIGN_MISSING_STEPS, idempotencyKey,
                fingerprint, now)) {
            CommandIdempotencyRepository.CommandRecord previous = commandIdempotencyRepository
                    .find(profile.id(), CommandOperation.ASSIGN_MISSING_STEPS, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Missing step idempotency record is unavailable"));
            if (!previous.requestFingerprint().equals(fingerprint) || previous.resultJson() == null) {
                throw InteractionConflictException.idempotency();
            }
            return read(previous.resultJson());
        }
        InteractionNextStep step = new InteractionNextStep(text, now.plusDays(days));
        List<UUID> assigned = new ArrayList<>();
        for (UUID id : ids) {
            if (interactionService.assignMissingStep(profile, id, step, commandId, now)) {
                assigned.add(id);
            }
        }
        auditJournalRepository.record(AuditAction.MISSING_STEPS_ASSIGNED, profile.id(), "TEAM", profile.teamId(),
                "Карточки без шага", "выбрано: " + ids.size() + "; задан шаг: " + assigned.size() + "; «" + text + "», срок "
                        + days + " дн.", requestId);
        MissingStepResult result = new MissingStepResult(assigned.size(), ids.size() - assigned.size(), text, step.nextActionAt());
        commandIdempotencyRepository.complete(commandId, write(result));
        return result;
    }

    private MissingStepResult read(String json) {
        try {
            return objectMapper.readValue(json, MissingStepResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Missing step result is invalid", exception);
        }
    }

    private String write(MissingStepResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Missing step result cannot be written", exception);
        }
    }

    public record MissingStepRequest(List<UUID> interactionIds, String nextAction, Integer dueDays) {
    }

    public record MissingStepResult(int assigned, int skipped, String nextAction, OffsetDateTime nextActionAt) {
    }
}
