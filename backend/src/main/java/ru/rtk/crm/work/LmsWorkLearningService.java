package ru.rtk.crm.work;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.settings.SystemSettings;
import ru.rtk.crm.work.LmsSignalModels.LmsSignalType;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;
import ru.rtk.crm.work.LmsSignalRepository.Activity;
import ru.rtk.crm.work.LmsSignalRepository.SignalSource;

/**
 * Обучение по работам для «Контроля» и отчёта «Работа менеджеров за период»: те же потоки и сигналы, что видит КАМ,
 * только в области видимости профиля. У работ без потоков LMS записи нет.
 */
@Service
public class LmsWorkLearningService {
    private final OrganizationRepository organizationRepository;
    private final LmsSignalRepository repository;
    private final LmsSignalProperties configured;
    private final SystemSettings settings;

    public LmsWorkLearningService(
            OrganizationRepository organizationRepository,
            LmsSignalRepository repository,
            LmsSignalProperties properties,
            SystemSettings settings
    ) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
        this.configured = properties;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Map<UUID, WorkLearning> byWork(CrmProfile profile, Collection<UUID> interactionIds) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty() || interactionIds.isEmpty()) {
            return Map.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        Set<UUID> wanted = new HashSet<>(interactionIds);
        Map<String, Boolean> dismissals = repository.findDismissals(scope.get(), null);
        Map<UUID, List<SignalSource>> byWork = repository.findSources(scope.get(), null).stream()
                .filter(source -> wanted.contains(source.interactionId()))
                .collect(Collectors.groupingBy(SignalSource::interactionId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, WorkLearning> result = new HashMap<>();
        LmsSignalProperties properties = configured.with(settings);
        byWork.forEach((interactionId, sources) -> result.put(interactionId, learning(sources, dismissals, now, properties)));
        return result;
    }

    private static WorkLearning learning(List<SignalSource> sources, Map<String, Boolean> dismissals, OffsetDateTime now,
                                         LmsSignalProperties properties) {
        LocalDate today = LmsSignalService.today(now);
        List<SignalSource> running = sources.stream().filter(source -> LmsSignalService.running(source, today)).toList();
        int participants = running.stream().mapToInt(SignalSource::participants).sum();
        // Устаревшая активность считается неизвестной, как и в сигналах.
        List<Activity> activity = running.stream()
                .filter(source -> LmsSignalService.fresh(source, now, properties))
                .map(SignalSource::activity).filter(Objects::nonNull).toList();
        // null — активности нет ни по одному идущему потоку: «нет данных» отличается от нуля.
        Integer active7d = activity.isEmpty() ? null : activity.stream().mapToInt(Activity::active7d).sum();
        List<String> titles = new ArrayList<>();
        for (SignalSource source : sources) {
            for (LmsSignalType type : LmsSignalService.types(source, now, properties)) {
                String key = LmsSignalRepository.dismissalKey(source.interactionId(), source.mappingId(), type,
                        source.dataVersion(type));
                // Скрытые сигналы, в том числе записанные как риск, не считаются: менеджер их уже учёл.
                if (!dismissals.containsKey(key)) {
                    titles.add(LmsSignalService.title(type));
                }
            }
        }
        return new WorkLearning(running.size(), participants, active7d, titles.size(), titles.stream().distinct().toList(),
                mismatch(sources.getFirst(), running, participants, activity));
    }

    /**
     * Сверка этапа с LMS — вопрос руководителя к менеджеру, а не запрет. Сравнивается только с идущими потоками:
     * закрытый или будущий поток ничего не говорит о текущем этапе. Скрытие сигнала сверку не снимает.
     */
    private static String mismatch(SignalSource work, List<SignalSource> running, int participants, List<Activity> activity) {
        if (work.classesStageOrder() == null || running.isEmpty()) {
            return null;
        }
        String classes = work.classesStageName();
        if (work.currentStageOrder() < work.classesStageOrder() && participants > 0) {
            return "Этап «" + work.currentStageName() + "» раньше этапа «" + classes + "», а в LMS идёт обучение: обучающихся "
                    + participants;
        }
        if (work.currentStageOrder() != work.classesStageOrder()) {
            return null;
        }
        if (participants == 0) {
            return "Этап «" + classes + "», а в идущем потоке LMS нет обучающихся";
        }
        // «Никто не заходил» — только если активность известна по всем идущим потокам: скрытое поле Moodle не значит,
        // что люди молчат.
        boolean nobody = activity.size() == running.size()
                && activity.stream().allMatch(value -> value.active7d() == 0 && value.teachersActive7d() == 0);
        return nobody ? "Этап «" + classes + "», а за 7 дней в курс LMS никто не заходил" : null;
    }
}
