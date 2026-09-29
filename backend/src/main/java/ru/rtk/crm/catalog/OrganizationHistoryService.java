package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.OrganizationHistoryRepository.StoredHistoryItem;

@Service
public class OrganizationHistoryService {
    private static final TypeReference<List<ContactEvent.Change>> CHANGES = new TypeReference<>() {
    };

    private final OrganizationRepository organizationRepository;
    private final OrganizationHistoryRepository organizationHistoryRepository;
    private final ObjectMapper objectMapper;

    public OrganizationHistoryService(
            OrganizationRepository organizationRepository,
            OrganizationHistoryRepository organizationHistoryRepository,
            ObjectMapper objectMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationHistoryRepository = organizationHistoryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OrganizationHistoryPage history(CrmProfile profile, UUID organizationId, OrganizationHistoryQuery query) {
        organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
        return new OrganizationHistoryPage(
                organizationHistoryRepository.find(organizationId, query).stream().map(this::item).toList(),
                query.page(),
                query.size(),
                organizationHistoryRepository.count(organizationId, query)
        );
    }

    private OrganizationHistoryItem item(StoredHistoryItem stored) {
        boolean assignment = stored.kind() == OrganizationHistoryKind.ASSIGNMENT;
        return new OrganizationHistoryItem(
                stored.id(),
                stored.kind(),
                stored.occurredAt(),
                stored.actorName(),
                stored.interactionId(),
                stored.interactionTitle(),
                stored.fromStageName(),
                stored.stageName(),
                assignment ? assignmentText(stored) : null,
                stored.comment(),
                stored.contactId(),
                stored.contactName(),
                stored.changes() == null ? null : readChanges(stored.changes())
        );
    }

    private static String assignmentText(StoredHistoryItem stored) {
        String previous = manager(stored.previousOwnerId(), stored.previousOwnerName());
        String next = manager(stored.ownerId(), stored.ownerName());
        if (stored.ownerId() == null) {
            return "Снято назначение: " + previous + ".";
        }
        if (stored.previousOwnerId() == null) {
            return "Назначен ответственный: " + next + ".";
        }
        return "Ответственный изменён: " + previous + " → " + next + ".";
    }

    private static String manager(UUID id, String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return id == null ? "Не назначен" : "Профиль КАМ недоступен";
    }

    private List<ContactEvent.Change> readChanges(String changes) {
        try {
            return objectMapper.readValue(changes, CHANGES);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored contact changes cannot be read", exception);
        }
    }
}
