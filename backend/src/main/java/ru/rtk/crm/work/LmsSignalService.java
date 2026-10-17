package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionIssueKind;
import ru.rtk.crm.interaction.InteractionIssueRequest;
import ru.rtk.crm.interaction.InteractionIssueService;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionRiskLevel;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.work.LmsSignalModels.LmsSignal;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalAction;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalDismissalRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalRiskRequest;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.LmsSignals;
import ru.rtk.crm.work.LmsSignalRepository.Activity;
import ru.rtk.crm.work.LmsSignalRepository.SignalSource;

@Service
public class LmsSignalService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int RISK_TEXT_LIMIT = 1_000;

    private final OrganizationRepository organizationRepository;
    private final LmsSignalRepository repository;
    private final LmsSignalProperties properties;
    private final InteractionIssueService issueService;

    public LmsSignalService(
            OrganizationRepository organizationRepository,
            LmsSignalRepository repository,
            LmsSignalProperties properties,
            InteractionIssueService issueService
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.properties = properties;
        this.issueService = issueService;
    }

    // Список для КАМ и «Контроля» — только не скрытые сигналы; карточка (forInteraction) возвращает и скрытые с
    // признаком, чтобы показать «скрыт» и «записан как риск».
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
        if (request == null) {
            throw new InteractionValidationException("type", "Укажите вид сигнала");
        }
        requireSignal(request.type(), request.mappingId());
        VisibilityScope scope = visibleScope(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = today(now);
        SignalSource source = activeSource(scope, interactionId, request.type(), request.mappingId(), today);
        // Скрытие привязано к версии данных потока: изменились данные — сигнал вернётся.
        repository.saveDismissal(interactionId, source.mappingId(), request.type(), source.dataVersion(request.type()),
                profile.id(), now, false);
        return new LmsSignals(now, signals(scope, interactionId, today));
    }

    /**
     * Переносит действующий сигнал в реестр проблем и рисков работы тем же действием, что «Добавить риск» в карточке
     * (права, версия работы, ключ идемпотентности), и скрывает сигнал с отметкой «записан как риск» до изменения данных.
     */
    @Transactional
    public Interaction recordRisk(CrmProfile profile, UUID interactionId, LmsSignalRiskRequest request, String idempotencyKey) {
        if (request == null) {
            throw new InteractionValidationException("type", "Укажите вид сигнала");
        }
        requireSignal(request.type(), request.mappingId());
        VisibilityScope scope = visibleScope(profile, interactionId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        OffsetDateTime now = OffsetDateTime.now();
        SignalSource source = activeSource(scope, interactionId, request.type(), request.mappingId(), today(now));
        String dataVersion = source.dataVersion(request.type());
        String key = LmsSignalRepository.dismissalKey(interactionId, source.mappingId(), request.type(), dataVersion);
        // Пока данные не изменились, сигнал уже записан: второй риск по нему дублировал бы запись в реестре.
        if (Boolean.TRUE.equals(repository.findDismissals(scope, interactionId).get(key))) {
            throw new InteractionValidationException("type", "Этот сигнал уже записан в реестр проблем и рисков");
        }
        LmsSignal signal = signal(source, request.type(), false, false);
        String description = "Сигнал LMS «" + signal.title() + "»: " + signal.message();
        Interaction interaction = issueService.create(profile, interactionId, new InteractionIssueRequest(
                request.version(),
                InteractionIssueKind.RISK,
                description.length() <= RISK_TEXT_LIMIT ? description : description.substring(0, RISK_TEXT_LIMIT - 1) + "…",
                InteractionRiskLevel.MEDIUM,
                null,
                null
        ), idempotencyKey);
        // Скрытие ставится после создания риска в той же транзакции: если риск не создался, сигнал остаётся видимым.
        repository.saveDismissal(interactionId, source.mappingId(), request.type(), dataVersion, profile.id(), now, true);
        return interaction;
    }

    private static void requireSignal(LmsSignalType type, UUID mappingId) {
        if (type == null) {
            throw new InteractionValidationException("type", "Укажите вид сигнала");
        }
        if (mappingId == null) {
            throw new InteractionValidationException("mappingId", "Укажите поток LMS");
        }
    }

    // Скрыть и записать можно только действующий сигнал, найденный заново по текущим данным: сигнал мог перестать
    // действовать, а присланному клиентом верить нельзя.
    private SignalSource activeSource(VisibilityScope scope, UUID interactionId, LmsSignalType type, UUID mappingId,
                                      LocalDate today) {
        return repository.findSources(scope, interactionId, properties.classesStageName()).stream()
                .filter(candidate -> candidate.mappingId().equals(mappingId))
                .filter(candidate -> types(candidate, today, properties).contains(type))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException(
                        "mappingId",
                        "Сигнал уже не действует: данные LMS или этап взаимодействия изменились. Обновите карточку"
                ));
    }

    private List<LmsSignal> signals(VisibilityScope scope, UUID interactionId, LocalDate today) {
        Map<String, Boolean> dismissals = repository.findDismissals(scope, interactionId);
        List<LmsSignal> signals = new ArrayList<>();
        for (SignalSource source : repository.findSources(scope, interactionId, properties.classesStageName())) {
            for (LmsSignalType type : types(source, today, properties)) {
                Boolean recordedAsRisk = dismissals.get(LmsSignalRepository.dismissalKey(
                        source.interactionId(), source.mappingId(), type, source.dataVersion(type)
                ));
                signals.add(signal(source, type, recordedAsRisk != null, Boolean.TRUE.equals(recordedAsRisk)));
            }
        }
        return signals;
    }

    // Поток «идёт», когда и наблюдение, и сегодняшний день внутри него: по закрытому или будущему потоку сигналы о ходе
    // обучения не нужны.
    static boolean running(SignalSource source, LocalDate today) {
        LocalDate observed = observedOn(source);
        return !observed.isBefore(source.runStartsOn()) && observed.isBefore(source.runEndsOn())
                && today.isBefore(source.runEndsOn());
    }

    static List<LmsSignalType> types(SignalSource source, LocalDate today, LmsSignalProperties properties) {
        LocalDate observed = observedOn(source);
        boolean started = !observed.isBefore(source.runStartsOn());
        boolean running = running(source, today);
        List<LmsSignalType> types = new ArrayList<>();
        // Сигнал только предлагает перейти к этапу занятий и сам этап не меняет: переход — решение менеджера, он идёт
        // по маршруту работы с обычными правами и комментарием.
        if (running && source.participants() > 0 && source.classesStageOrder() != null
                && source.currentStageOrder() < source.classesStageOrder()) {
            types.add(LmsSignalType.STUDENTS_APPEARED);
        }
        // Порог в днях от начала потока: курс создают заранее, обучающихся зачисляют не сразу.
        if (running && source.participants() == 0
                && ChronoUnit.DAYS.between(source.runStartsOn(), observed) > properties.noStudentsAfterDays()) {
            types.add(LmsSignalType.NO_STUDENTS);
        }
        // Доля сравнивается умножением, без деления: нет ошибок округления. Проверка в последние дни потока и после его
        // окончания.
        if (started && source.completed() != null && source.participants() > 0
                && !today.isBefore(source.runEndsOn().minusDays(properties.completionWindowDays()))
                && source.completed() * 100L < (long) properties.lowCompletionPercent() * source.participants()) {
            types.add(LmsSignalType.LOW_COMPLETION);
        }
        Activity activity = source.activity();
        // Активность смотрится после льготного срока от начала потока: в первые дни входов ещё мало, сигнал был бы
        // ложным. Если Moodle скрыл вход (activity == null), сигналов по активности нет.
        boolean watched = running && activity != null
                && ChronoUnit.DAYS.between(source.runStartsOn(), observed) >= properties.activityGraceDays();
        if (watched && source.participants() > 0) {
            types.addAll(activityTypes(activity, source.participants(), properties));
            // Пока у потока нет преподавателя с ролью в курсе (например, роль назначена на категорию), молчание не сигнал.
            if (source.teachers() > 0 && activity.teachersActive14d() == 0) {
                types.add(LmsSignalType.TEACHER_INACTIVE);
            }
        }
        return types;
    }

    // Одна причина — один сигнал: «не начали» объясняет и нулевую, и низкую активность, «нет активности» — низкую.
    private static List<LmsSignalType> activityTypes(Activity activity, int participants, LmsSignalProperties properties) {
        List<LmsSignalType> types = new ArrayList<>();
        boolean notStarted = activity.neverAccessed() * 100L > (long) properties.notStartedPercent() * participants;
        boolean nobody = activity.active7d() == 0 && activity.teachersActive7d() == 0;
        if (notStarted) {
            types.add(LmsSignalType.NOT_STARTED);
        } else if (nobody) {
            types.add(LmsSignalType.NO_ACTIVITY);
        } else if (activity.active7d() * 100L < (long) properties.lowActivityPercent() * participants) {
            types.add(LmsSignalType.LOW_ACTIVITY);
        }
        if (activity.inactive() * 100L > (long) properties.dropoutPercent() * participants) {
            types.add(LmsSignalType.DROPOUT);
        }
        return types;
    }

    static String title(LmsSignalType type) {
        return switch (type) {
            case STUDENTS_APPEARED -> "В LMS появились обучающиеся";
            case NO_STUDENTS -> "Поток идёт, обучающихся нет";
            case LOW_COMPLETION -> "Низкая доля завершивших";
            case LOW_ACTIVITY -> "Мало заходят в курс";
            case NOT_STARTED -> "Обучающиеся не начали";
            case DROPOUT -> "Обучающиеся перестали заходить";
            case TEACHER_INACTIVE -> "Преподаватель не заходит в курс";
            case NO_ACTIVITY -> "Поток идёт, активности нет";
        };
    }

    // Сообщение строится из данных потока на момент наблюдения; пороги в тексте берутся из настроек, чтобы менеджер
    // видел, почему сигнал сработал.
    private LmsSignal signal(SignalSource source, LmsSignalType type, boolean dismissed, boolean recordedAsRisk) {
        String run = "«" + source.courseName() + (source.groupName() == null ? "" : ", группа «" + source.groupName() + "»")
                + "» (" + period(source) + ")";
        int participants = source.participants();
        Activity activity = source.activity();
        LmsSignalAction action = null;
        String actionHint = null;
        String message = switch (type) {
            case STUDENTS_APPEARED -> "В потоке " + run + " обучающихся: " + participants + ", а взаимодействие на этапе «"
                    + source.currentStageName() + "» — раньше этапа «" + source.classesStageName()
                    + "». Предлагаем перейти к этапу «" + source.classesStageName() + "».";
            case NO_STUDENTS -> "Поток " + run + " идёт " + days(runDays(source))
                    + " (порог " + days(properties.noStudentsAfterDays()) + "), а на " + DATE.format(observedOn(source))
                    + " обучающихся в LMS нет. Проверьте запись учащихся на курс.";
            case LOW_COMPLETION -> "Поток " + run + ": завершили " + source.completed() + " из " + participants + " ("
                    + percent(source.completed(), participants) + " %), порог "
                    + properties.lowCompletionPercent() + " %. Поток закрыт или заканчивается в ближайшие "
                    + days(properties.completionWindowDays()) + ".";
            case LOW_ACTIVITY -> "Поток " + run + ": за 7 дней в курс заходили " + activity.active7d() + " из "
                    + participants + " обучающихся (" + percent(activity.active7d(), participants) + " %), порог "
                    + properties.lowActivityPercent() + " %. Уточните у вуза, как идут занятия.";
            case NOT_STARTED -> "Поток " + run + " идёт " + days(runDays(source)) + ", а ни разу не заходили в курс "
                    + activity.neverAccessed() + " из " + participants + " обучающихся ("
                    + percent(activity.neverAccessed(), participants) + " %), порог " + properties.notStartedPercent()
                    + " %. Проверьте, получили ли студенты доступ к курсу.";
            case DROPOUT -> "Поток " + run + ": " + activity.inactive() + " из " + participants
                    + " обучающихся заходили в курс раньше, но не заходят больше " + days(properties.inactiveAfterDays())
                    + " (" + percent(activity.inactive(), participants) + " %), порог " + properties.dropoutPercent()
                    + " %. Уточните у вуза причину.";
            case TEACHER_INACTIVE -> "Поток " + run + " идёт, а преподаватели потока (" + source.teachers()
                    + ") не заходили в курс " + days(LmsSignalProperties.TEACHER_INACTIVE_DAYS)
                    + ". Свяжитесь с ответственным в вузе.";
            case NO_ACTIVITY -> "Поток " + run + " идёт " + days(runDays(source))
                    + ", а за 7 дней в курс не заходили ни обучающиеся (" + participants
                    + "), ни преподаватели. Уточните у вуза, идут ли занятия.";
        };
        if (type == LmsSignalType.STUDENTS_APPEARED) {
            // Действие предлагается, только если в маршруте работы есть прямой переход к этапу занятий; иначе вместо
            // кнопки подсказка: обходить маршрут нельзя.
            if (source.classesReachable()) {
                action = new LmsSignalAction(source.classesStageId(), source.classesStageName(), source.classesCommentRequired());
            } else {
                actionHint = "Прямого перехода из этапа «" + source.currentStageName() + "» к этапу «"
                        + source.classesStageName() + "» в маршруте этого взаимодействия нет: пройдите промежуточные этапы"
                        + " или добавьте переход: «⋯» → «Изменить этапы этого взаимодействия…».";
            }
        }
        return new LmsSignal(
                source.interactionId() + ":" + source.mappingId() + ":" + type,
                type,
                title(type),
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
                participants,
                source.completed(),
                source.observedAt(),
                action,
                actionHint,
                dismissed,
                recordedAsRisk
        );
    }

    private VisibilityScope visibleScope(CrmProfile profile, UUID interactionId) {
        VisibilityScope scope = organizationRepository.visibilityScope(profile).orElseThrow(InteractionNotFoundException::new);
        if (!repository.interactionVisible(interactionId, scope)) {
            throw new InteractionNotFoundException();
        }
        return scope;
    }

    // Даты наблюдения и «сегодня» считаются по МСК, как и сам срок потока.
    private static LocalDate observedOn(SignalSource source) {
        return source.observedAt().atZoneSameInstant(WorkProperties.ZONE).toLocalDate();
    }

    private static long runDays(SignalSource source) {
        return ChronoUnit.DAYS.between(source.runStartsOn(), observedOn(source));
    }

    private static long percent(int part, int whole) {
        return Math.round(part * 100.0 / whole);
    }

    private static String period(SignalSource source) {
        return "с " + DATE.format(source.runStartsOn()) + " по " + DATE.format(source.runEndsOn().minusDays(1));
    }

    // Склонение по правилам русского языка: 11–14 — «дней», иначе по последней цифре.
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
