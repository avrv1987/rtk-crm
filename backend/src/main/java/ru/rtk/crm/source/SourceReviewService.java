package ru.rtk.crm.source;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.catalog.OrganizationType;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.SourceRepository.SourceOrganization;
import ru.rtk.crm.source.SourceRepository.StoredRecord;

@Service
public class SourceReviewService {
    private static final Logger log = LoggerFactory.getLogger(SourceReviewService.class);
    private static final int PENDING_LIMIT = 200;
    private static final int MAX_NAME_LENGTH = 300;

    private final SourceRepository repository;
    private final OrganizationRepository organizationRepository;
    private final SourceSyncService syncService;
    private final SiteRecordApplier applier;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public SourceReviewService(
            SourceRepository repository,
            OrganizationRepository organizationRepository,
            SourceSyncService syncService,
            SiteRecordApplier applier,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.syncService = syncService;
        this.applier = applier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public List<PendingSourceRecordView> pending(CrmProfile profile) {
        Optional<VisibilityScope> scope = organizationRepository.visibilityScope(profile);
        if (scope.isEmpty()) {
            return List.of();
        }
        boolean leader = profile.role() == UserRole.LEADER;
        return repository.findPendingWebsiteRecords(scope.get(), leader, PENDING_LIMIT).stream()
                .flatMap(stored -> pendingView(profile, stored, leader).stream())
                .toList();
    }

    public SourceRecordApplyResult resolve(CrmProfile profile, UUID recordId, SourceRecordResolveRequest request) {
        StoredRecord stored = requireVisibleRecord(profile, recordId);
        if (profile.role() != UserRole.LEADER) {
            throw SourceException.reviewForbidden();
        }
        if (request == null || request.organizationId() == null) {
            throw new InteractionValidationException("organizationId", "Выберите вуз CRM");
        }
        if (stored.status() != SourceRecordStatus.NEEDS_MAPPING && stored.status() != SourceRecordStatus.FAILED) {
            return new SourceRecordApplyResult(syncService.recordView(stored), 0);
        }
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        if (organizationOf(stored, item) != null) {
            throw SourceException.recordResolved();
        }
        if (item.organizationKey() == null) {
            throw new InteractionValidationException("organizationId", "В заявке нет вуза для сопоставления");
        }
        organizationRepository.findVisibleById(profile, request.organizationId()).orElseThrow(OrganizationNotFoundException::new);
        try {
            repository.insertMapping(SourceCode.WEBSITE, "ORGANIZATION", item.organizationKey(), request.organizationId(), null,
                    null, null, profile.id(), OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw SourceException.recordResolved();
        }
        log.info("Source record {} was mapped by leader {}", recordId, profile.id());
        return syncService.applyWebsiteMatching(recordId, sameOrganization(item), profile.id());
    }

    public SourceOrganizationCreated createOrganization(CrmProfile profile, UUID recordId, SourceOrganizationCreateRequest request) {
        StoredRecord stored = profile.role() == UserRole.ADMIN
                ? repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound)
                : requireVisibleRecord(profile, recordId);
        if (profile.role() != UserRole.LEADER && profile.role() != UserRole.ADMIN) {
            throw SourceException.reviewForbidden();
        }
        if (stored.source() != SourceCode.WEBSITE) {
            throw new InteractionValidationException("id", "Организация создаётся только из заявки сайта");
        }
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        if (organizationOf(stored, item) != null) {
            throw SourceException.recordResolved();
        }
        if (item.organizationKey() == null) {
            throw new InteractionValidationException("name", "В заявке нет вуза, из которого можно создать организацию");
        }
        UUID teamId = profile.role() == UserRole.ADMIN ? requiredTeam(request) : profile.teamId();
        String name = organizationName(request, item);
        String type = organizationType(request);
        UUID organizationId = UUID.randomUUID();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                StoredRecord locked = repository.findRecordForUpdate(recordId).orElseThrow(SourceException::recordNotFound);
                if (organizationOf(locked, item) != null) {
                    throw SourceException.recordResolved();
                }
                if (repository.organizationNameTaken(name)) {
                    throw new InteractionValidationException("name",
                            "Организация «" + name + "» уже есть в CRM; сопоставьте заявку с ней или обратитесь к администратору");
                }
                OffsetDateTime now = OffsetDateTime.now();
                repository.insertOrganization(organizationId, name, type, teamId, now);
                repository.insertMapping(SourceCode.WEBSITE, "ORGANIZATION", item.organizationKey(), organizationId, null, null,
                        null, profile.id(), now);
            });
        } catch (DuplicateKeyException exception) {
            throw SourceException.recordResolved();
        }
        log.info("Organization {} was created from source record {} by profile {}", organizationId, recordId, profile.id());
        SourceRecordApplyResult result = syncService.applyWebsiteMatching(recordId, sameOrganization(item), profile.id());
        return new SourceOrganizationCreated(organizationId, name, result);
    }

    private StoredRecord requireVisibleRecord(CrmProfile profile, UUID recordId) {
        if (organizationRepository.visibilityScope(profile).isEmpty()) {
            throw SourceException.recordNotFound();
        }
        StoredRecord stored = repository.findRecord(recordId).orElseThrow(SourceException::recordNotFound);
        if (stored.source() != SourceCode.WEBSITE
                || !visibleTo(profile, organizationOf(stored, SiteRecord.parseStored(objectMapper, stored.payload())))) {
            throw SourceException.recordNotFound();
        }
        return stored;
    }

    private boolean visibleTo(CrmProfile profile, UUID organizationId) {
        return organizationId == null
                ? profile.role() == UserRole.LEADER
                : organizationRepository.findVisibleById(profile, organizationId).isPresent();
    }

    private UUID organizationOf(StoredRecord stored, SiteRecord item) {
        return stored.organizationId() != null ? stored.organizationId() : applier.resolvedOrganizationId(item).orElse(null);
    }

    private Optional<PendingSourceRecordView> pendingView(CrmProfile profile, StoredRecord stored, boolean leader) {
        SiteRecord item = SiteRecord.parseStored(objectMapper, stored.payload());
        UUID organizationId = organizationOf(stored, item);
        if (stored.organizationId() == null && organizationId != null && !visibleTo(profile, organizationId)) {
            return Optional.empty();
        }
        String crmOrganizationName = organizationId == null
                ? null
                : repository.findOrganizationById(organizationId).map(SourceOrganization::name).orElse(null);
        return Optional.of(new PendingSourceRecordView(
                stored.id(),
                stored.recordType(),
                stored.externalId(),
                item.submittedAt(),
                stored.status(),
                stored.error(),
                item.organizationName(),
                item.organizationExternalId(),
                item.programName(),
                organizationId,
                crmOrganizationName,
                leader && organizationId == null
        ));
    }

    private UUID requiredTeam(SourceOrganizationCreateRequest request) {
        if (request == null || request.teamId() == null || !repository.teamExists(request.teamId())) {
            throw new InteractionValidationException("teamId", "Выберите команду новой организации");
        }
        return request.teamId();
    }

    private static String organizationName(SourceOrganizationCreateRequest request, SiteRecord item) {
        String name = request == null || request.name() == null || request.name().isBlank()
                ? item.organizationName()
                : request.name().strip().replaceAll("\\s+", " ");
        if (name == null || name.isBlank()) {
            throw new InteractionValidationException("name", "Укажите название организации");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InteractionValidationException("name", "Название организации длиннее " + MAX_NAME_LENGTH + " символов");
        }
        return name;
    }

    private static String organizationType(SourceOrganizationCreateRequest request) {
        String value = request == null || request.type() == null ? OrganizationType.UNIVERSITY.name()
                : request.type().strip().toUpperCase(Locale.ROOT);
        try {
            return OrganizationType.valueOf(value).name();
        } catch (IllegalArgumentException exception) {
            throw new InteractionValidationException("type", "Выберите тип организации из списка");
        }
    }

    private static Predicate<SiteRecord> sameOrganization(SiteRecord item) {
        String organizationKey = item.organizationKey();
        return other -> organizationKey.equals(other.organizationKey());
    }
}
