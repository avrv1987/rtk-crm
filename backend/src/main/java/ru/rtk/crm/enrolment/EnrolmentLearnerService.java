package ru.rtk.crm.enrolment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class EnrolmentLearnerService {
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    static final int HISTORY_LIMIT = 200;
    static final Set<LearnerField> DATE_FIELDS =
            EnumSet.of(LearnerField.PASSPORT_ISSUE_DATE, LearnerField.BIRTH_DATE, LearnerField.DIPLOMA_ISSUE_DATE);

    private final EnrolmentAccess enrolmentAccess;
    private final LearnerService learnerService;
    private final EnrolmentRepository enrolmentRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;

    EnrolmentLearnerService(
            EnrolmentAccess enrolmentAccess,
            LearnerService learnerService,
            EnrolmentRepository enrolmentRepository,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.learnerService = learnerService;
        this.enrolmentRepository = enrolmentRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
    }

    @Transactional
    List<LearnerSummary> search(CrmProfile actor, LearnerSearch request, String requestId) {
        enrolmentAccess.requireOperator(actor);
        if (request == null || request.kind() == null) {
            throw new InteractionValidationException("kind", "Выберите, по какому полю искать");
        }
        String value = request.value() == null ? "" : request.value().strip();
        if (value.isEmpty()) {
            throw new InteractionValidationException("value", "Введите значение для поиска");
        }
        List<Learner> found = switch (request.kind()) {
            case EMAIL -> {
                if (!LearnerRules.isEmail(value)) {
                    throw new InteractionValidationException("value", "Email указан неверно");
                }
                yield learnerService.findByContacts(value, null);
            }
            case PHONE -> {
                if (LearnerRules.normalizePhone(value) == null) {
                    throw new InteractionValidationException("value", "Телефон должен содержать 10 цифр после +7 или 8");
                }
                yield learnerService.findByContacts(null, value);
            }
            case SNILS -> {
                if (LearnerRules.normalizeSnils(value) == null) {
                    throw new InteractionValidationException("value", "СНИЛС должен состоять из 11 цифр");
                }
                yield learnerService.findBySnils(value).stream().toList();
            }
            case LAST_NAME -> learnerService.findByLastName(value);
        };
        auditJournalRepository.record(AuditAction.LEARNER_SEARCHED, actor.id(), LearnerService.OBJECT_TYPE, null, "Поиск слушателя",
                "вид: " + request.kind() + "; найдено: " + found.size(), requestId);
        Map<UUID, List<LearnerEnrolment>> enrolments = enrolmentRepository.findByLearners(found.stream().map(Learner::id).toList())
                .stream()
                .collect(Collectors.groupingBy(LearnerEnrolment::learnerId));
        return found.stream()
                .map(learner -> summary(learner, enrolments.getOrDefault(learner.id(), List.of())))
                .sorted(Comparator.comparing(LearnerSummary::lastName, EnrolmentStreamService.RUSSIAN)
                        .thenComparing(LearnerSummary::firstName, EnrolmentStreamService.RUSSIAN)
                        .thenComparing(LearnerSummary::id))
                .toList();
    }

    LearnerCardView card(CrmProfile actor, String id, String requestId) {
        enrolmentAccess.requireOperator(actor);
        return view(learnerService.card(actor.id(), EnrolmentAccess.parseId(id), requestId));
    }

    LearnerRevealed reveal(CrmProfile actor, String id, LearnerReveal request, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID learnerId = EnrolmentAccess.parseId(id);
        return new LearnerRevealed(learnerService.reveal(actor.id(), learnerId, request == null ? null : request.groups(), requestId));
    }

    @Transactional
    LearnerCardView update(CrmProfile actor, String id, LearnerUpdate request, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID learnerId = EnrolmentAccess.parseId(id);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Укажите версию анкеты");
        }
        if (request.fields() == null || request.fields().isEmpty()) {
            throw new InteractionValidationException("fields", "Укажите изменяемые поля анкеты");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Map<LearnerField, String> changes = changes(request.fields(), errors);
        if (!errors.isEmpty()) {
            throw new LearnerValidationException(errors);
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.UPDATE_LEARNER, idempotencyKey,
                commands.personalFingerprint(Arrays.asList(learnerId, request.version(), changes)));
        if (reservation.repeated()) {
            return view(learnerService.view(learnerId));
        }
        Learner current = learnerService.find(learnerId).orElseThrow(LearnerNotFoundException::new);
        LearnerService.requireActive(current);
        if (current.version() != request.version()) {
            throw InteractionConflictException.learnerVersion(current.version());
        }
        LearnerProfile changed = current.profile().with(changes);
        validate(changed, changes.keySet());
        learnerService.update(actor.id(), learnerId, request.version(), changed, requestId);
        commands.complete(reservation, Map.of("learnerId", learnerId));
        return view(learnerService.view(learnerId));
    }

    @Transactional
    LearnerMoveResult move(CrmProfile actor, String id, LearnerMove request, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID sourceId = EnrolmentAccess.parseId(id);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Укажите версию анкеты");
        }
        String snils = request.snils() == null ? null : LearnerRules.normalizeSnils(request.snils());
        if (snils == null) {
            throw new InteractionValidationException("snils", "СНИЛС должен состоять из 11 цифр");
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.MOVE_LEARNER_ENROLMENTS,
                idempotencyKey, commands.personalFingerprint(List.of(sourceId, snils, request.version())));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), LearnerMoveResult.class);
        }
        Learner owner = learnerService.findBySnils(snils)
                .orElseThrow(() -> new InteractionValidationException("snils", "Слушателя с таким СНИЛС нет"));
        if (owner.id().equals(sourceId)) {
            throw new InteractionValidationException("snils", "Этот СНИЛС уже указан в открытой анкете");
        }
        boolean sourceFirst = sourceId.compareTo(owner.id()) < 0;
        Learner first = learnerService.lock(sourceFirst ? sourceId : owner.id()).orElseThrow(LearnerNotFoundException::new);
        Learner second = learnerService.lock(sourceFirst ? owner.id() : sourceId).orElseThrow(LearnerNotFoundException::new);
        Learner source = sourceFirst ? first : second;
        Learner target = sourceFirst ? second : first;
        LearnerService.requireActive(source);
        LearnerService.requireActive(target);
        if (source.version() != request.version()) {
            throw InteractionConflictException.learnerVersion(source.version());
        }
        Map<LearnerField, String> changes = new EnumMap<>(LearnerField.class);
        for (LearnerField field : LearnerField.values()) {
            if (target.profile().value(field) == null && source.profile().value(field) != null) {
                changes.put(field, source.profile().stored(field));
            }
        }
        LearnerProfile merged = target.profile().with(changes);
        if (!changes.isEmpty()) {
            validate(merged, Set.of());
        }
        Set<UUID> targetStreams = enrolmentRepository.findByLearners(List.of(target.id())).stream()
                .map(LearnerEnrolment::streamId)
                .collect(Collectors.toSet());
        int moved = 0;
        int dropped = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (LearnerEnrolment enrolment : enrolmentRepository.findByLearners(List.of(sourceId))) {
            if (targetStreams.contains(enrolment.streamId())) {
                enrolmentRepository.delete(enrolment.id());
                dropped++;
            } else {
                enrolmentRepository.reassign(enrolment.id(), target.id(), now);
                moved++;
            }
        }
        learnerService.delete(sourceId);
        if (!changes.isEmpty()) {
            learnerService.update(actor.id(), target.id(), target.version(), merged, requestId);
        }
        List<LearnerField> fields = List.copyOf(changes.keySet());
        learnerService.journal(AuditAction.LEARNER_ENROLMENTS_MOVED, actor.id(), target.id(),
                "из анкеты " + sourceId + ": зачислений перенесено " + moved + ", удалено повторных " + dropped + "; поля: "
                        + (fields.isEmpty() ? "нет" : fields.stream().map(Enum::name).collect(Collectors.joining(", "))),
                requestId);
        LearnerMoveResult result = new LearnerMoveResult(target.id(), moved, dropped, fields);
        commands.complete(reservation, result);
        return result;
    }

    List<LearnerHistoryEntry> history(CrmProfile actor, String id) {
        enrolmentAccess.requireOperator(actor);
        UUID learnerId = EnrolmentAccess.parseId(id);
        learnerService.find(learnerId).orElseThrow(LearnerNotFoundException::new);
        return auditJournalRepository.findByObject(LearnerService.OBJECT_TYPE, learnerId, HISTORY_LIMIT).stream()
                .map(entry -> new LearnerHistoryEntry(entry.id(), entry.occurredAt(), entry.action().name(), entry.actionLabel(),
                        entry.actorDisplayName(), entry.details()))
                .toList();
    }

    static LearnerCardView view(LearnerCard card) {
        LearnerCompleteness completeness = EnrolmentStreamService.completeness(card.status(), card.missingFields());
        return new LearnerCardView(card.id(), card.status(), card.version(), card.maskedValues(), card.missingFields(),
                completeness.filled(), completeness.required(), completeness.complete(),
                card.enrolments().stream().map(LearnerEnrolmentView::of).toList(), card.createdAt(), card.updatedAt());
    }

    static void validate(LearnerProfile profile, Set<LearnerField> changedFields) {
        LearnerProfile normalized = profile.normalized();
        List<LearnerFieldError> errors = new ArrayList<>(LearnerRules.check(normalized, LocalDate.now(ZONE)));
        errors.addAll(LearnerRules.clearedRequired(normalized, changedFields));
        if (!errors.isEmpty()) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            errors.forEach(error -> fieldErrors.putIfAbsent(error.field().name(), error.message()));
            throw new LearnerValidationException(fieldErrors);
        }
    }

    private static Map<LearnerField, String> changes(Map<String, String> fields, Map<String, String> errors) {
        Map<LearnerField, String> changes = new EnumMap<>(LearnerField.class);
        fields.forEach((code, raw) -> {
            Optional<LearnerField> field = Arrays.stream(LearnerField.values()).filter(value -> value.name().equals(code)).findFirst();
            if (field.isEmpty()) {
                errors.put(code, "Такого поля в анкете нет");
                return;
            }
            String value = raw == null || raw.isBlank() ? null : raw.strip();
            if (value == null) {
                changes.put(field.get(), null);
            } else if (value.length() > LearnerRules.MAX_TEXT_LENGTH) {
                errors.put(code, "Значение длиннее " + LearnerRules.MAX_TEXT_LENGTH + " символов");
            } else if (DATE_FIELDS.contains(field.get())) {
                try {
                    changes.put(field.get(), LocalDate.parse(value).toString());
                } catch (DateTimeParseException exception) {
                    errors.put(code, "Дата должна быть в формате ГГГГ-ММ-ДД");
                }
            } else if (field.get() == LearnerField.GENDER) {
                Gender.fromTitle(value).ifPresentOrElse(gender -> changes.put(LearnerField.GENDER, gender.name()),
                        () -> errors.put(code, "Пол: выберите «М» или «Ж»"));
            } else if (field.get() == LearnerField.EDUCATION) {
                Education.fromTitle(value).ifPresentOrElse(education -> changes.put(LearnerField.EDUCATION, education.name()),
                        () -> errors.put(code, "Образование: выберите значение из списка"));
            } else {
                changes.put(field.get(), value);
            }
        });
        return changes;
    }

    private static LearnerSummary summary(Learner learner, List<LearnerEnrolment> enrolments) {
        LearnerProfile profile = learner.profile();
        LearnerCompleteness completeness = EnrolmentStreamService.completeness(learner.status(), learner.missingFields());
        return new LearnerSummary(
                learner.id(),
                learner.status(),
                profile == null ? null : profile.lastName(),
                profile == null ? null : profile.firstName(),
                profile == null ? null : profile.middleName(),
                profile == null ? null : LearnerFieldGroup.mask(LearnerField.PHONE, profile.phone()),
                profile == null ? null : LearnerFieldGroup.mask(LearnerField.EMAIL, profile.email()),
                completeness.filled(),
                completeness.required(),
                completeness.complete(),
                enrolments.stream().map(LearnerEnrolmentView::of).toList()
        );
    }
}
