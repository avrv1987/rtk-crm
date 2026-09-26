package ru.rtk.crm.training;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCommentRequest;
import ru.rtk.crm.interaction.InteractionCommentResult;
import ru.rtk.crm.interaction.InteractionCreateRequest;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class TrainingService {
    static final String NEXT_CYCLE_ACTION = "Следующий цикл повышения квалификации преподавателей";

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_COURSE_NAME_LENGTH = 300;
    private static final int MAX_PARTICIPANTS = 100_000;
    private static final LocalTime REMINDER_TIME = LocalTime.of(10, 0);

    private final InteractionService interactionService;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final TrainingRepository repository;

    public TrainingService(
            InteractionService interactionService,
            CommandIdempotencyRepository commandIdempotencyRepository,
            TrainingRepository repository
    ) {
        this.interactionService = interactionService;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TeacherTraining> trainings(CrmProfile profile, UUID interactionId) {
        interactionService.get(profile, interactionId);
        return repository.findTrainings(interactionId);
    }

    @Transactional
    public TeacherTrainingCreated createTraining(CrmProfile profile, UUID interactionId, TeacherTrainingRequest request,
                                                 String idempotencyKey) {
        interactionService.get(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        validate(request);
        InteractionNextStep nextStep = Boolean.TRUE.equals(request.remind())
                ? new InteractionNextStep(NEXT_CYCLE_ACTION, request.nextCycleOn().atTime(REMINDER_TIME).atZone(ZONE).toOffsetDateTime())
                : null;
        InteractionCommentResult comment = interactionService.comment(
                profile,
                interactionId,
                new InteractionCommentRequest(
                        request.version(),
                        request.stageId(),
                        text(request),
                        request.attachmentId() == null ? List.of() : List.of(request.attachmentId()),
                        nextStep
                ),
                idempotencyKey
        );
        UUID eventId = comment.event().id();
        Optional<TeacherTraining> replayed = repository.findTrainingByEvent(eventId);
        if (replayed.isPresent()) {
            return new TeacherTrainingCreated(replayed.get(), comment.interaction());
        }
        repository.insertTraining(UUID.randomUUID(), interactionId, eventId, request, profile.id(), OffsetDateTime.now());
        return new TeacherTrainingCreated(repository.findTrainingByEvent(eventId).orElseThrow(), comment.interaction());
    }

    @Transactional(readOnly = true)
    public InteractionCycle cycle(CrmProfile profile, UUID interactionId) {
        interactionService.get(profile, interactionId);
        return new InteractionCycle(
                interactionId,
                repository.findCycleStart(interactionId).orElse(null),
                repository.findPrevious(interactionId).orElse(null),
                repository.findNext(interactionId).orElse(null)
        );
    }

    @Transactional
    public Interaction startCycle(CrmProfile profile, UUID previousInteractionId, CycleStartRequest request,
                                  String idempotencyKey) {
        Interaction previous = interactionService.get(profile, previousInteractionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request == null) {
            throw new InteractionValidationException("title", "Укажите название нового цикла");
        }
        LocalDate startsOn = request.startsOn() == null ? LocalDate.now(ZONE) : request.startsOn();
        repository.lockInteraction(previousInteractionId);
        Optional<LocalDate> previousStart = repository.findCycleStart(previousInteractionId);
        if (previousStart.isPresent() && !startsOn.isAfter(previousStart.get())) {
            throw new InteractionValidationException("startsOn",
                    "Новый цикл должен начинаться позже прошлого: прошлый начался " + DATE.format(previousStart.get()));
        }
        Optional<CycleLink> next = repository.findNext(previousInteractionId);
        if (next.isPresent() && (idempotencyKey == null || commandIdempotencyRepository
                .find(profile.id(), CommandOperation.CREATE_INTERACTION, idempotencyKey).isEmpty())) {
            throw nextCycleExists(next.get());
        }
        Interaction created = interactionService.create(
                profile,
                new InteractionCreateRequest(
                        previous.organizationId(),
                        request.title(),
                        request.nextAction(),
                        request.nextActionAt(),
                        previous.contactIds(),
                        previous.programId(),
                        previous.productIds(),
                        null,
                        request.templateId()
                ),
                idempotencyKey
        );
        if (next.isPresent()) {
            if (next.get().interactionId().equals(created.id())) {
                return created;
            }
            throw nextCycleExists(next.get());
        }
        repository.insertCycle(created.id(), previousInteractionId, startsOn, profile.id(), OffsetDateTime.now());
        return created;
    }

    private static InteractionValidationException nextCycleExists(CycleLink next) {
        return new InteractionValidationException("previousInteractionId",
                "Следующий цикл этой работы уже начат: «" + next.title() + "»");
    }

    private static void validate(TeacherTrainingRequest request) {
        if (request == null || request.trainedOn() == null) {
            throw new InteractionValidationException("trainedOn", "Укажите дату обучения");
        }
        if (request.trainedOn().isAfter(LocalDate.now(ZONE))) {
            throw new InteractionValidationException("trainedOn", "Дата обучения не может быть позже сегодняшней: запись фиксирует факт");
        }
        if (request.courseName() == null || request.courseName().isBlank()) {
            throw new InteractionValidationException("courseName", "Укажите курс");
        }
        if (request.courseName().strip().length() > MAX_COURSE_NAME_LENGTH) {
            throw new InteractionValidationException("courseName", "Название курса длиннее " + MAX_COURSE_NAME_LENGTH + " символов");
        }
        if (request.enrolledCount() == null || request.enrolledCount() < 0 || request.enrolledCount() > MAX_PARTICIPANTS) {
            throw new InteractionValidationException("enrolledCount", "Укажите, сколько преподавателей записано: от 0 до " + MAX_PARTICIPANTS);
        }
        if (request.completedCount() != null
                && (request.completedCount() < 0 || request.completedCount() > request.enrolledCount())) {
            throw new InteractionValidationException("completedCount", "Завершивших не может быть больше записанных");
        }
        if (request.nextCycleOn() != null && !request.nextCycleOn().isAfter(request.trainedOn())) {
            throw new InteractionValidationException("nextCycleOn", "Следующий цикл должен быть позже даты обучения");
        }
        if (Boolean.TRUE.equals(request.remind()) && request.nextCycleOn() == null) {
            throw new InteractionValidationException("nextCycleOn", "Укажите дату следующего цикла, чтобы поставить напоминание");
        }
    }

    private static String text(TeacherTrainingRequest request) {
        StringBuilder text = new StringBuilder("Обучение преподавателей: курс «").append(request.courseName().strip())
                .append("», дата ").append(DATE.format(request.trainedOn()))
                .append(", записано ").append(request.enrolledCount())
                .append(", завершили ").append(request.completedCount() == null ? "нет данных" : request.completedCount());
        if (request.attachmentId() != null) {
            text.append(". Документ о повышении квалификации приложен");
        }
        if (request.nextCycleOn() != null) {
            text.append(". Следующий цикл повышения квалификации: ").append(DATE.format(request.nextCycleOn()));
        }
        return text.append('.').toString();
    }
}
