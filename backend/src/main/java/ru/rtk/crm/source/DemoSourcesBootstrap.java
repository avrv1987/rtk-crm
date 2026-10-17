package ru.rtk.crm.source;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.bootstrap.DemoBootstrapProperties;
import ru.rtk.crm.enrolment.PaidOrderParser;
import ru.rtk.crm.enrolment.PaidOrderUpload;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.source.SourceRepository.RunDates;
import ru.rtk.crm.source.SourceRepository.StoredMapping;
import ru.rtk.crm.source.SourceRepository.StoredSnapshot;

@Component
@Profile("demo-bootstrap")
@Order(2)
public class DemoSourcesBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSourcesBootstrap.class);
    private static final List<String> LEARNING_KINDS = List.of("COURSE", "GROUP");
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final int DEMO_HISTORY_MONTHS = 5;
    private static final List<String> LABOR_MARKET_DIRECTIONS = List.of(
            "Демо-направление: цифровая трансформация",
            "Кибербезопасность",
            "Облачные технологии и DevOps"
    );
    private static final Map<String, String> LABOR_MARKET_CODES = Map.of(
            "Демо-направление: цифровая трансформация", "digital-transformation"
    );

    static final String DEMO_ORDER_NUMBER = "ORD-1001-DEMO1";
    private static final String DEMO_PAID_ORDERS = """
            [
              {"Номер заявки": "ORD-1001-DEMO1", "Курс": "Промпт-инжиниринг", "Фамилия": "Демонстрационная",
               "Имя": "Анна", "Телефон": "+7 900 000-00-91", "Email": "anna.demo@example.test", "Номер потока": 1},
              {"Номер заявки": "ORD-1002-DEMO2", "Курс": "Промпт-инжиниринг", "Фамилия": "Демонстрационный",
               "Имя": "Борис", "Телефон": "+7 900 000-00-92", "Email": "boris.demo@example.test", "Номер потока": 1}
            ]
            """;

    private final DemoBootstrapProperties properties;
    private final UserProfileRepository userProfileRepository;
    private final SourceRepository repository;
    private final SourceSyncService sourceSyncService;
    private final LaborMarketService laborMarketService;
    private final ModuleProperties modules;
    private final PaidOrderParser paidOrderParser;

    public DemoSourcesBootstrap(
            DemoBootstrapProperties properties,
            UserProfileRepository userProfileRepository,
            SourceRepository repository,
            SourceSyncService sourceSyncService,
            LaborMarketService laborMarketService,
            ModuleProperties modules,
            PaidOrderParser paidOrderParser
    ) {
        this.properties = properties;
        this.userProfileRepository = userProfileRepository;
        this.repository = repository;
        this.sourceSyncService = sourceSyncService;
        this.laborMarketService = laborMarketService;
        this.modules = modules;
        this.paidOrderParser = paidOrderParser;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.demoData()) {
            return;
        }
        UUID adminId = administratorId();
        boolean added = false;
        for (DemoBootstrapProperties.LearningMapping mapping : properties.learningMappings()) {
            added |= saveLearningMapping(mapping, demoRun(mapping), adminId);
        }
        if (sourceSyncService.configured(SourceCode.LABOR_MARKET)
                && repository.findLastFullSuccessAt(SourceCode.LABOR_MARKET).isEmpty()) {
            laborMarketService.prepareDemo(adminId, LABOR_MARKET_DIRECTIONS, LABOR_MARKET_CODES);
        }
        for (SourceCode source : SourceCode.values()) {
            Optional<SyncRunView> firstRun = added && source == SourceCode.MOODLE
                    ? sourceSyncService.synchronizeNow(adminId, source)
                    : sourceSyncService.synchronizeIfNeverSucceeded(adminId, source);
            firstRun.ifPresent(run -> {
                if (run.status() != SyncRunStatus.SUCCEEDED) {
                    throw new IllegalStateException("Demo first sync of " + source + " failed: " + run.errorCode()
                            + " " + run.errorMessage());
                }
                LOGGER.info("Demo first sync of {}: fetched {}, created {}, updated {}, skipped {}, needs mapping {}, failed {}",
                        source, run.fetchedCount(), run.createdCount(), run.updatedCount(), run.skippedCount(),
                        run.needsMappingCount(), run.failedCount());
            });
        }
        List<DemoBootstrapProperties.LearningMapping> mappings = properties.learningMappings();
        for (int index = 0; index < mappings.size(); index++) {
            addDemoHistory(mappings.get(index), index % 2 == 0);
        }
        uploadDemoPaidOrders();
    }

    // Без оплат экраны «Зачисления» (поток, анкета) на демо-стенде пусты. Оплаты идут тем же путём, что файл
    // оператора зачисления, и только если демо-оплат ещё нет: повтор bootstrap не добавляет запусков.
    void uploadDemoPaidOrders() {
        if (!modules.enrolmentEnabled() || repository.findRecord(SourceCode.WEBSITE, SiteRecord.PAID_ORDER, DEMO_ORDER_NUMBER).isPresent()) {
            return;
        }
        Optional<UUID> operatorId = properties.identities().stream()
                .filter(identity -> Boolean.TRUE.equals(identity.enrolmentOperator()))
                .map(identity -> userProfileRepository.findActiveByIdentity(identity.issuer(), identity.subject()))
                .flatMap(Optional::stream)
                .map(CrmProfile::id)
                .findFirst();
        if (operatorId.isEmpty()) {
            return;
        }
        try {
            PaidOrderUpload upload = sourceSyncService.uploadPaidOrders(operatorId.get(),
                    paidOrderParser.parse(new ByteArrayInputStream(DEMO_PAID_ORDERS.getBytes(StandardCharsets.UTF_8))));
            LOGGER.info("Demo paid orders: created {}, needs mapping {}", upload.created(), upload.needsMapping());
        } catch (SourceException exception) {
            // Идёт плановая синхронизация сайта: демо-оплаты загрузит следующий запуск bootstrap.
            LOGGER.warn("Demo paid orders were not uploaded: {}", exception.getMessage());
        }
    }

    private void addDemoHistory(DemoBootstrapProperties.LearningMapping mapping, boolean growing) {
        List<StoredMapping> runs = repository.findMappings(SourceCode.MOODLE, mapping.kind(), mapping.externalKey());
        if (runs.size() != 1 || runs.get(0).runStartsOn() == null || repository.hasDemoObservations(runs.get(0).id())) {
            return;
        }
        UUID mappingId = runs.get(0).id();
        Optional<LearningUnit> latest = repository.findSnapshot(mappingId).map(StoredSnapshot::unit);
        Optional<OffsetDateTime> first = repository.findFirstObservation(mappingId);
        if (latest.isEmpty() || first.isEmpty()) {
            return;
        }
        YearMonth firstMonth = YearMonth.from(first.get().atZoneSameInstant(ZONE)).minusMonths(DEMO_HISTORY_MONTHS);
        for (int step = 0; step < DEMO_HISTORY_MONTHS; step++) {
            OffsetDateTime observedFrom = firstMonth.plusMonths(step).atDay(15).atTime(10, 0).atZone(ZONE).toOffsetDateTime();
            int distance = DEMO_HISTORY_MONTHS - step;
            repository.saveDemoObservation(mappingId, demoCounts(latest.get(), growing ? -distance : distance), observedFrom,
                    observedFrom.plusDays(7));
        }
        repository.moveDemoRunStart(mappingId, firstMonth.atDay(1), OffsetDateTime.now());
        LOGGER.info("Demo learning history: {} synthetic observations for {} {}", DEMO_HISTORY_MONTHS, mapping.kind(),
                mapping.externalKey());
    }

    private static LearningUnit demoCounts(LearningUnit latest, int shift) {
        int participants = Math.max(0, latest.participants() + shift);
        Integer completed = latest.completed() == null ? null : Math.min(latest.completed(), participants);
        return new LearningUnit(latest.courseId(), latest.groupId(), latest.courseShortname(), latest.courseName(),
                latest.groupName(), participants, latest.teachers(), completed,
                completed == null ? null : participants - completed, completed == null ? participants : 0, latest.groupsCount(), 0, null);
    }

    private UUID administratorId() {
        return properties.identities().stream()
                .filter(identity -> identity.role() == UserRole.ADMIN)
                .map(identity -> userProfileRepository.findActiveByIdentity(identity.issuer(), identity.subject()))
                .flatMap(Optional::stream)
                .map(CrmProfile::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Demo sources bootstrap requires an active administrator profile"));
    }

    private static RunDates demoRun(DemoBootstrapProperties.LearningMapping mapping) {
        LocalDate today = LocalDate.now(ZONE);
        if (mapping.runStartedDaysAgo() == null || mapping.runEndsInDays() == null) {
            LocalDate runStartsOn = today.withDayOfMonth(1);
            return new RunDates(runStartsOn, runStartsOn.plusYears(1));
        }
        return new RunDates(today.minusDays(mapping.runStartedDaysAgo()), today.plusDays(mapping.runEndsInDays()));
    }

    private boolean saveLearningMapping(DemoBootstrapProperties.LearningMapping mapping, RunDates run, UUID adminId) {
        if (!LEARNING_KINDS.contains(mapping.kind()) || mapping.externalKey() == null
                || !mapping.externalKey().matches(mapping.kind().equals("COURSE") ? "\\d+" : "\\d+:\\d+")) {
            throw new IllegalStateException("Demo learning mapping must be COURSE <id> or GROUP <courseId>:<groupId>");
        }
        String courseKey = mapping.externalKey().split(":", 2)[0];
        boolean conflicting = mapping.kind().equals("COURSE")
                ? repository.hasMapping(SourceCode.MOODLE, "GROUP", courseKey + ":%")
                : repository.hasMapping(SourceCode.MOODLE, "COURSE", courseKey);
        if (conflicting || repository.hasMapping(SourceCode.MOODLE, mapping.kind(), mapping.externalKey())) {
            repository.confirmUndatedRun(SourceCode.MOODLE, mapping.kind(), mapping.externalKey(), run, OffsetDateTime.now());
            return false;
        }
        UUID organizationId = repository.findOrganizationByName(mapping.organization())
                .orElseThrow(() -> new IllegalStateException("Demo learning mapping organization is missing: " + mapping.organization()))
                .id();
        List<UUID> programIds = repository.findActiveProgramIdsByName(mapping.program());
        if (programIds.size() != 1) {
            throw new IllegalStateException("Demo learning mapping program must be unique: " + mapping.program());
        }
        RunKind runKind = mapping.runKind() == null ? null : RunKind.valueOf(mapping.runKind());
        repository.saveMapping(SourceCode.MOODLE, mapping.kind(), mapping.externalKey(), organizationId, programIds.get(0),
                run, runKind, adminId, OffsetDateTime.now());
        return true;
    }
}
