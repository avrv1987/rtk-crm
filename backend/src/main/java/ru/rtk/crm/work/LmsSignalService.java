package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalAction;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalDismissalRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.LmsSignals;
import ru.rtk.crm.work.LmsSignalRepository.SignalSource;

@Service
public class LmsSignalService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final OrganizationRepository organizationRepository;
    private final LmsSignalRepository repository;
    private final LmsSignalProperties properties;

    public LmsSignalService(
            OrganizationRepository organizationRepository,
            LmsSignalRepository repository,
            LmsSignalProperties properties
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public LmsSignals list(CrmProfile profile) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(WorkAccessDeniedException::new);
        OffsetDateTime now = OffsetDateTime.now();
        List<LmsSignal> active = signals(scope, null, today(now)).stream().filter(signal -> !signal.dismissed()).toList();
        return new LmsSignals(now, active);
    }

    @Transactional(readOnly = true)
    public LmsSignals forInteraction(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = visibleScope(profile, interactionId);
        OffsetDateTime now = OffsetDateTime.now();
        return new LmsSignals(now, signals(scope, interactionId, today(now)));
    }

    @Transactional
    public LmsSignals dismiss(CrmProfile profile, UUID interactionId, LmsSignalDismissalRequest request) {
        if (request == null || request.type() == null) {
            throw new InteractionValidationException("type", "Укажите вид сигнала");
        }
        if (request.mappingId() == null) {
            throw new InteractionValidationException("mappingId", "Укажите поток LMS");
        }
        VisibilityScope scope = visibleScope(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = today(now);
        SignalSource source = repository.findSources(scope, interactionId, properties.classesStageName()).stream()
                .filter(candidate -> candidate.mappingId().equals(request.mappingId()))
                .filter(candidate -> types(candidate, today, properties).contains(request.type()))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException(
                        "mappingId",
                        "Сигнал уже не действует: данные LMS или этап работы изменились. Обновите карточку"
                ));
        repository.saveDismissal(interactionId, source.mappingId(), request.type(), source.dataVersion(), profile.id(), now);
        return new LmsSignals(now, signals(scope, interactionId, today));
    }

    private List<LmsSignal> signals(VisibilityScope scope, UUID interactionId, LocalDate today) {
        Set<String> dismissals = repository.findDismissals(scope, interactionId);
        List<LmsSignal> signals = new ArrayList<>();
        for (SignalSource source : repository.findSources(scope, interactionId, properties.classesStageName())) {
            for (LmsSignalType type : types(source, today, properties)) {
                boolean dismissed = dismissals.contains(LmsSignalRepository.dismissalKey(
                        source.interactionId(), source.mappingId(), type, source.dataVersion()
                ));
                signals.add(signal(source, type, dismissed));
            }
        }
        return signals;
    }

    static List<LmsSignalType> types(SignalSource source, LocalDate today, LmsSignalProperties properties) {
        LocalDate observed = source.observedAt().atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
        boolean started = !observed.isBefore(source.runStartsOn());
        boolean running = started && observed.isBefore(source.runEndsOn()) && today.isBefore(source.runEndsOn());
        List<LmsSignalType> types = new ArrayList<>();
        if (running && source.participants() > 0 && source.classesStageOrder() != null
                && source.currentStageOrder() < source.classesStageOrder()) {
            types.add(LmsSignalType.STUDENTS_APPEARED);
        }
        if (running && source.participants() == 0
                && ChronoUnit.DAYS.between(source.runStartsOn(), observed) > properties.noStudentsAfterDays()) {
            types.add(LmsSignalType.NO_STUDENTS);
        }
        if (started && source.completed() != null && source.participants() > 0
                && !today.isBefore(source.runEndsOn().minusDays(properties.completionWindowDays()))
                && source.completed() * 100L < (long) properties.lowCompletionPercent() * source.participants()) {
            types.add(LmsSignalType.LOW_COMPLETION);
        }
        return types;
    }

    private LmsSignal signal(SignalSource source, LmsSignalType type, boolean dismissed) {
        String run = "«" + source.courseName() + (source.groupName() == null ? "" : ", группа «" + source.groupName() + "»")
                + "» (" + period(source) + ")";
        String title;
        String message;
        LmsSignalAction action = null;
        String actionHint = null;
        switch (type) {
            case STUDENTS_APPEARED -> {
                title = "В LMS появились обучающиеся";
                message = "В потоке " + run + " обучающихся: " + source.participants() + ", а работа на этапе «"
                        + source.currentStageName() + "» — раньше этапа «" + source.classesStageName()
                        + "». Предлагаем перейти к этапу «" + source.classesStageName() + "».";
                if (source.classesReachable()) {
                    action = new LmsSignalAction(source.classesStageId(), source.classesStageName(), source.classesCommentRequired());
                } else {
                    actionHint = "Прямого перехода из этапа «" + source.currentStageName() + "» к этапу «"
                            + source.classesStageName() + "» в маршруте этой работы нет: пройдите промежуточные этапы"
                            + " или добавьте переход во вкладке «Маршрут».";
                }
            }
            case NO_STUDENTS -> {
                LocalDate observed = source.observedAt().atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
                title = "Поток идёт, обучающихся нет";
                message = "Поток " + run + " идёт " + days(ChronoUnit.DAYS.between(source.runStartsOn(), observed))
                        + " (порог " + days(properties.noStudentsAfterDays()) + "), а на " + DATE.format(observed)
                        + " обучающихся в LMS нет. Проверьте запись учащихся на курс.";
            }
            default -> {
                title = "Низкая доля завершивших";
                message = "Поток " + run + ": завершили " + source.completed() + " из " + source.participants() + " ("
                        + Math.round(source.completed() * 100.0 / source.participants()) + " %), порог "
                        + properties.lowCompletionPercent() + " %. Поток закрыт или заканчивается в ближайшие "
                        + days(properties.completionWindowDays()) + ".";
            }
        }
        return new LmsSignal(
                source.interactionId() + ":" + source.mappingId() + ":" + type,
                type,
                title,
                message,
                source.interactionId(),
                source.interactionTitle(),
                source.organizationId(),
                source.organizationName(),
                source.ownerManagerId(),
                source.ownerManagerName(),
                source.currentStageName(),
                source.mappingId(),
                source.courseName(),
                source.groupName(),
                source.runStartsOn(),
                source.runEndsOn(),
                source.participants(),
                source.completed(),
                source.observedAt(),
                action,
                actionHint,
                dismissed
        );
    }

    private VisibilityScope visibleScope(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(InteractionNotFoundException::new);
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        return scope;
    }

    private static String period(SignalSource source) {
        return "с " + DATE.format(source.runStartsOn()) + " по " + DATE.format(source.runEndsOn().minusDays(1));
    }

    static String days(long value) {
        long lastTwo = value % 100;
        long last = value % 10;
        if (last == 1 && lastTwo != 11) {
            return value + " день";
        }
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
            return value + " дня";
        }
        return value + " дней";
    }

    private static LocalDate today(OffsetDateTime now) {
        return now.atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
    }
}
