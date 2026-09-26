package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
import ru.rtk.crm.source.SourceRepository.RunDates;

@Component
@Profile("demo-bootstrap")
@Order(2)
public class DemoSourcesBootstrap implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoSourcesBootstrap.class);
    private static final List<String> LEARNING_KINDS = List.of("COURSE", "GROUP");
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final DemoBootstrapProperties properties;
    private final UserProfileRepository userProfileRepository;
    private final SourceRepository repository;
    private final SourceSyncService sourceSyncService;

    public DemoSourcesBootstrap(
            DemoBootstrapProperties properties,
            UserProfileRepository userProfileRepository,
            SourceRepository repository,
            SourceSyncService sourceSyncService
    ) {
        this.properties = properties;
        this.userProfileRepository = userProfileRepository;
        this.repository = repository;
        this.sourceSyncService = sourceSyncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID adminId = administratorId();
        LocalDate runStartsOn = LocalDate.now(ZONE).withDayOfMonth(1);
        RunDates demoRun = new RunDates(runStartsOn, runStartsOn.plusYears(1));
        properties.learningMappings().forEach(mapping -> saveLearningMapping(mapping, demoRun, adminId));
        for (SourceCode source : SourceCode.values()) {
            sourceSyncService.synchronizeIfNeverSucceeded(adminId, source).ifPresent(run -> {
                if (run.status() != SyncRunStatus.SUCCEEDED) {
                    throw new IllegalStateException("Demo first sync of " + source + " failed: " + run.errorCode()
                            + " " + run.errorMessage());
                }
                LOGGER.info("Demo first sync of {}: fetched {}, created {}, updated {}, skipped {}, needs mapping {}, failed {}",
                        source, run.fetchedCount(), run.createdCount(), run.updatedCount(), run.skippedCount(),
                        run.needsMappingCount(), run.failedCount());
            });
        }
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

    private void saveLearningMapping(DemoBootstrapProperties.LearningMapping mapping, RunDates run, UUID adminId) {
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
            return;
        }
        UUID organizationId = repository.findOrganizationByName(mapping.organization())
                .orElseThrow(() -> new IllegalStateException("Demo learning mapping organization is missing: " + mapping.organization()))
                .id();
        List<UUID> programIds = repository.findActiveProgramIdsByName(mapping.program());
        if (programIds.size() != 1) {
            throw new IllegalStateException("Demo learning mapping program must be unique: " + mapping.program());
        }
        repository.saveMapping(SourceCode.MOODLE, mapping.kind(), mapping.externalKey(), organizationId, programIds.get(0),
                run, adminId, OffsetDateTime.now());
    }
}
