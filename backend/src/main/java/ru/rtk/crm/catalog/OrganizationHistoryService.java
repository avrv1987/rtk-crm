package ru.rtk.crm.catalog;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
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
        return new OrganizationHistoryItem(
                stored.id(),
                stored.kind(),
                stored.occurredAt(),
                stored.actorName(),
                stored.interactionId(),
                stored.interactionTitle(),
                stored.fromStageName(),
                stored.stageName(),
                switch (stored.kind()) {
                    case ASSIGNMENT -> assignmentText(stored);
                    case AGREEMENT_STATUS -> agreementText(stored);
                    default -> null;
                },
                stored.comment(),
                stored.contactId(),
                stored.contactName(),
                stored.changes() == null ? null : readChanges(stored.changes())
        );
    }

    // Автоматическое снятие или передача без причины выглядели бы как действие администратора без объяснения,
    // поэтому причина из события назначения дописывается к тексту истории.
    private static String assignmentText(StoredHistoryItem stored) {
        String previous = manager(stored.previousOwnerId(), stored.previousOwnerName());
        String next = manager(stored.ownerId(), stored.ownerName());
        String text = stored.ownerId() == null
                ? "Снято назначение: " + previous + "."
                : stored.previousOwnerId() == null
                        ? "Назначен ответственный: " + next + "."
                        : "Ответственный изменён: " + previous + " → " + next + ".";
        return stored.assignmentReason() == null ? text : text + " Причина: " + reasonText(stored.assignmentReason()) + ".";
    }

    static String reasonText(String reason) {
        return switch (OrganizationAssignmentReason.valueOf(reason)) {
            case PROFILE_BLOCKED -> "блокировка КАМ";
            case PROFILE_ROLE_CHANGED -> "у КАМ изменена роль";
            case PROFILE_TEAM_CHANGED -> "КАМ переведён в другую команду";
            case ORGANIZATION_TEAM_CHANGED -> "вуз передан в другую команду";
            case IMPORT -> "назначение из импорта справочника";
        };
    }

    private static String agreementText(StoredHistoryItem stored) {
        String to = AgreementStatus.valueOf(stored.toStatus()).title();
        return stored.fromStatus() == null
                ? "Договор № " + stored.agreementNumber() + " внесён: " + to + "."
                : "Договор № " + stored.agreementNumber() + ": " + AgreementStatus.valueOf(stored.fromStatus()).title() + " → " + to + ".";
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
