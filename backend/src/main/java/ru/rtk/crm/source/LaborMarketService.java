package ru.rtk.crm.source;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.CatalogNames;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.LaborMarketRepository.FlaggedDirection;
import ru.rtk.crm.source.LaborMarketRepository.Latest;
import ru.rtk.crm.source.LaborMarketRepository.SourceDirection;
import ru.rtk.crm.source.LaborMarketRepository.StoredMapping;

@Service
public class LaborMarketService {
    private final LaborMarketRepository repository;
    private final AuditJournalRepository auditJournalRepository;

    public LaborMarketService(LaborMarketRepository repository, AuditJournalRepository auditJournalRepository) {
        this.repository = repository;
        this.auditJournalRepository = auditJournalRepository;
    }

    public LaborMarketMappings mappings(CrmProfile profile) {
        SourceSyncService.requireAdmin(profile);
        List<SourceDirection> sourceDirections = repository.findSourceDirections();
        Map<String, String> names = sourceDirections.stream()
                .collect(Collectors.toMap(SourceDirection::code, SourceDirection::name, (first, second) -> first));
        Map<String, Latest> latest = repository.findLatest();
        List<LaborMarketDirection> directions = repository.findFlagged().stream()
                .map(direction -> direction(direction, names, latest))
                .toList();
        Latest newest = latest.values().stream().max(Comparator.comparing(Latest::asOf)).orElse(null);
        return new LaborMarketMappings(
                directions,
                sourceDirections.stream().map(item -> new LaborMarketSourceDirection(item.code(), item.name())).toList(),
                newest == null ? null : newest.asOf(),
                newest == null ? null : newest.dataSource()
        );
    }

    public LaborMarketDirection update(CrmProfile profile, UUID directionId, LaborMarketMappingRequest request, String requestId) {
        SourceSyncService.requireAdmin(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте сопоставление направления");
        }
        if (!repository.directionExists(directionId)) {
            throw new InteractionValidationException("directionId", "ИТ-направление не найдено или в архиве");
        }
        String code = request.externalCode() == null || request.externalCode().isBlank() ? null : request.externalCode().strip();
        List<SourceDirection> known = repository.findSourceDirections();
        if (code != null && known.stream().noneMatch(item -> item.code().equals(code))) {
            throw new InteractionValidationException("externalCode",
                    "Такого направления нет в данных источника; запустите синхронизацию источника «Рынок труда»");
        }
        Optional<StoredMapping> current = repository.findMapping(directionId);
        OffsetDateTime now = OffsetDateTime.now();
        if (current.isPresent()) {
            // Отсутствующая или отрицательная версия заведомо не совпадёт: правка существующего сопоставления без
            // версии отклоняется.
            int expected = request.version() == null || request.version() < 0 ? -1 : request.version();
            if (expected != current.get().version()) {
                throw SourceException.mappingVersion(current.get().version());
            }
            boolean changed = code == null
                    ? repository.deleteMapping(directionId, expected)
                    : repository.updateMapping(directionId, expected, code, profile.id(), now);
            if (!changed) {
                throw SourceException.mappingVersion(repository.findMapping(directionId).map(StoredMapping::version).orElse(0));
            }
        } else if (code != null) {
            try {
                repository.insertMapping(directionId, code, "MANUAL", profile.id(), now);
            } catch (DuplicateKeyException exception) {
                // Параллельное создание: другой администратор успел раньше, для клиента это конфликт версий.
                throw SourceException.mappingVersion(repository.findMapping(directionId).map(StoredMapping::version).orElse(0));
            }
        }
        auditJournalRepository.record(AuditAction.SOURCE_SETTINGS_CHANGED, profile.id(), "SOURCE", null,
                "Сопоставление направлений с рынком труда",
                code == null ? "сопоставление снято; направление подберётся по названию при следующей синхронизации"
                        : "направление сопоставлено вручную с кодом " + code, requestId);
        Map<String, String> names = known.stream()
                .collect(Collectors.toMap(SourceDirection::code, SourceDirection::name, (first, second) -> first));
        Map<String, Latest> latest = repository.findLatest();
        return repository.findFlagged().stream()
                .filter(direction -> direction.id().equals(directionId))
                .findFirst()
                .map(direction -> direction(direction, names, latest))
                .orElseGet(() -> new LaborMarketDirection(directionId, "", null, null, null, null, null, null, null, null));
    }

    // Автоматически сопоставляется только однозначное совпадение нормализованного названия; при нуле или нескольких
    // направление остаётся без сопоставления, выбор за человеком. Уже сопоставленные, в том числе вручную,
    // пропускаются.
    int autoMap(UUID actorProfileId) {
        List<SourceDirection> sourceDirections = repository.findSourceDirections();
        int unmapped = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (FlaggedDirection direction : repository.findFlagged()) {
            if (direction.externalCode() != null) {
                continue;
            }
            String key = CatalogNames.normalized(direction.name());
            List<SourceDirection> matches = sourceDirections.stream()
                    .filter(item -> CatalogNames.normalized(item.name()).equals(key))
                    .toList();
            if (matches.size() != 1) {
                unmapped++;
                continue;
            }
            repository.insertMappingIfAbsent(direction.id(), matches.get(0).code(), "AUTO", actorProfileId, now);
        }
        return unmapped;
    }

    void prepareDemo(UUID actorProfileId, List<String> flaggedNames, Map<String, String> manualCodes) {
        OffsetDateTime now = OffsetDateTime.now();
        for (String name : flaggedNames) {
            repository.findDirectionIdByName(name).ifPresent(repository::markFlagged);
        }
        manualCodes.forEach((name, code) -> repository.findDirectionIdByName(name)
                .filter(id -> repository.findMapping(id).isEmpty())
                .ifPresent(id -> repository.insertMapping(id, code, "MANUAL", actorProfileId, now)));
    }

    private static LaborMarketDirection direction(FlaggedDirection direction, Map<String, String> names, Map<String, Latest> latest) {
        Latest last = direction.externalCode() == null ? null : latest.get(direction.externalCode());
        return new LaborMarketDirection(
                direction.id(),
                direction.name(),
                direction.externalCode(),
                direction.externalCode() == null ? null : names.get(direction.externalCode()),
                direction.origin(),
                direction.version(),
                last == null ? null : last.vacancies(),
                last == null ? null : last.month(),
                direction.updatedAt(),
                direction.updatedByName()
        );
    }
}

record LaborMarketMappings(
        List<LaborMarketDirection> directions,
        List<LaborMarketSourceDirection> sourceDirections,
        LocalDate asOf,
        String dataSource
) {
}

record LaborMarketDirection(
        UUID directionId,
        String directionName,
        String externalCode,
        String externalName,
        String origin,
        Integer version,
        Integer latestVacancies,
        LocalDate latestMonth,
        OffsetDateTime updatedAt,
        String updatedByName
) {
}

record LaborMarketSourceDirection(String code, String name) {
}

record LaborMarketMappingRequest(String externalCode, Integer version) {
}
