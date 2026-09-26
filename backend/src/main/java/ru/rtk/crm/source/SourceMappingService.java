package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceRepository.RunDates;
import ru.rtk.crm.source.SourceRepository.StoredMapping;

@Service
public class SourceMappingService {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final SourceRepository repository;
    private final MoodleSnapshotApplier moodleApplier;
    private final SourceSyncService syncService;

    public SourceMappingService(SourceRepository repository, MoodleSnapshotApplier moodleApplier, SourceSyncService syncService) {
        this.repository = repository;
        this.moodleApplier = moodleApplier;
        this.syncService = syncService;
    }

    public List<SourceMappingView> mappings(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        return repository.findMappingViews(
                LocalDate.now(ZONE),
                repository.findLastFullSuccessStartedAt(SourceCode.MOODLE).orElse(null)
        );
    }

    public SourceMappingView update(CrmProfile profile, UUID mappingId, SourceMappingRequest request) {
        SourceSyncService.requireAdmin(profile);
        StoredMapping mapping = repository.findMapping(mappingId).orElseThrow(SourceException::mappingNotFound);
        int expectedVersion = requiredVersion(request == null ? null : request.version());
        if (mapping.version() != expectedVersion) {
            throw SourceException.mappingVersion(mapping.version());
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (mapping.learning()) {
            RunDates run = syncService.validatedRun(request.organizationId(), request.programId(), request.runStartsOn(),
                    request.runEndsOn());
            moodleApplier.updateRun(mapping, request.organizationId(), request.programId(), run, request.runKind(),
                    profile.id(), now);
        } else if (mapping.kind().equals("ORGANIZATION")) {
            if (request.organizationId() == null || repository.findOrganizationById(request.organizationId()).isEmpty()) {
                throw new InteractionValidationException("organizationId", "Выберите существующую организацию CRM");
            }
            updateReference(mapping, request.organizationId(), null, profile, now);
            syncService.reresolveWebsiteLinked("organization_id", mapping.organizationId(),
                    item -> mapping.externalKey().equals(item.organizationKey()), profile.id());
        } else {
            if (request.programId() == null || !repository.activeProgramExists(request.programId())) {
                throw new InteractionValidationException("programId", "Выберите активную программу CRM");
            }
            updateReference(mapping, null, request.programId(), profile, now);
            syncService.reresolveWebsiteLinked("program_id", mapping.programId(),
                    item -> mapping.externalKey().equals(SiteRecord.programKey(item.programName())), profile.id());
        }
        return view(mappingId);
    }

    public void remove(CrmProfile profile, UUID mappingId, Integer version) {
        SourceSyncService.requireAdmin(profile);
        StoredMapping mapping = repository.findMapping(mappingId).orElseThrow(SourceException::mappingNotFound);
        if (mapping.version() != requiredVersion(version)) {
            throw SourceException.mappingVersion(mapping.version());
        }
        if (mapping.learning()) {
            moodleApplier.removeRun(mapping, profile.id());
            return;
        }
        repository.deleteMapping(mappingId);
        if (mapping.kind().equals("ORGANIZATION")) {
            syncService.reresolveWebsiteLinked("organization_id", mapping.organizationId(),
                    item -> mapping.externalKey().equals(item.organizationKey()), profile.id());
        } else {
            syncService.reresolveWebsiteLinked("program_id", mapping.programId(),
                    item -> mapping.externalKey().equals(SiteRecord.programKey(item.programName())), profile.id());
        }
    }

    public SourceMappingView addRun(CrmProfile profile, UUID mappingId, SourceMappingRequest request) {
        SourceSyncService.requireAdmin(profile);
        StoredMapping mapping = repository.findMapping(mappingId).orElseThrow(SourceException::mappingNotFound);
        if (!mapping.learning()) {
            throw new InteractionValidationException("id", "Второй поток добавляется только курсу или группе Moodle");
        }
        if (request == null) {
            throw new InteractionValidationException("organizationId", "Выберите вуз, программу и даты нового потока");
        }
        RunDates run = syncService.validatedRun(request.organizationId(), request.programId(), request.runStartsOn(),
                request.runEndsOn());
        UUID created = moodleApplier.addRun(mapping, request.organizationId(), request.programId(), run, request.runKind(),
                profile.id(), OffsetDateTime.now());
        return view(created);
    }

    public void deleteSnapshot(CrmProfile profile, UUID mappingId) {
        SourceSyncService.requireAdmin(profile);
        StoredMapping mapping = repository.findMapping(mappingId).orElseThrow(SourceException::mappingNotFound);
        if (!mapping.learning()) {
            throw new InteractionValidationException("id", "Снимок обучения есть только у курса или группы Moodle");
        }
        if (repository.deleteSnapshot(mappingId) == 0) {
            throw new InteractionValidationException("id", "У этого потока нет сохранённого снимка");
        }
    }

    private void updateReference(StoredMapping mapping, UUID organizationId, UUID programId, CrmProfile profile,
                                 OffsetDateTime now) {
        if (!repository.updateMapping(mapping.id(), mapping.version(), organizationId, programId, null, null, profile.id(), now)) {
            throw SourceException.mappingVersion(repository.findMapping(mapping.id()).map(StoredMapping::version).orElse(0));
        }
    }

    private SourceMappingView view(UUID mappingId) {
        return repository.findMappingViews(LocalDate.now(ZONE),
                        repository.findLastFullSuccessStartedAt(SourceCode.MOODLE).orElse(null)).stream()
                .filter(view -> view.id().equals(mappingId))
                .findFirst()
                .orElseThrow(SourceException::mappingNotFound);
    }

    private static int requiredVersion(Integer version) {
        if (version == null || version < 0) {
            throw new InteractionValidationException("version", "Передайте версию сопоставления");
        }
        return version;
    }
}
