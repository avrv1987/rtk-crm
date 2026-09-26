package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class AdminCatalogService {
    private static final int NAME_LIMIT = 200;
    private static final int SEARCH_LIMIT = 200;

    private final AdminCatalogRepository adminCatalogRepository;
    private final CatalogChangeEventRepository catalogChangeEventRepository;
    private final IdempotentCommandRunner commandRunner;

    public AdminCatalogService(
            AdminCatalogRepository adminCatalogRepository,
            CatalogChangeEventRepository catalogChangeEventRepository,
            IdempotentCommandRunner commandRunner
    ) {
        this.adminCatalogRepository = adminCatalogRepository;
        this.catalogChangeEventRepository = catalogChangeEventRepository;
        this.commandRunner = commandRunner;
    }

    @Transactional(readOnly = true)
    public AdminCatalogPage list(CrmProfile actor, CatalogKind kind, String search, String state, int page, int size) {
        AdminAuthorization.requireAdmin(actor);
        CatalogQuery query = CatalogQuery.from(page, size);
        String normalizedSearch = search == null || search.isBlank() ? null : search.strip();
        if (normalizedSearch != null && normalizedSearch.length() > SEARCH_LIMIT) {
            throw new InvalidOrganizationQueryException("q", "Строка поиска длиннее " + SEARCH_LIMIT + " символов");
        }
        return adminCatalogRepository.findPage(kind, normalizedSearch, CatalogEntryState.parse(state), query.page(), query.size());
    }

    @Transactional(readOnly = true)
    public CatalogChangeEventPage events(CrmProfile actor, String entityType, int page, int size) {
        AdminAuthorization.requireAdmin(actor);
        CatalogQuery query = CatalogQuery.from(page, size);
        CatalogEntityType type = null;
        if (entityType != null && !entityType.isBlank()) {
            try {
                type = CatalogEntityType.valueOf(entityType);
            } catch (IllegalArgumentException exception) {
                throw new InvalidOrganizationQueryException("entityType", "Такого вида записей нет");
            }
        }
        return catalogChangeEventRepository.findPage(type, query.page(), query.size());
    }

    @Transactional
    public AdminCatalogEntry create(
            CrmProfile actor,
            CatalogKind kind,
            CatalogEntryRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные записи");
        }
        String name = requiredName(request.name());
        UUID parentId = request.parentId();
        if (kind.hasParent() && parentId == null) {
            throw new InteractionValidationException("parentId", kind == CatalogKind.PROGRAMS
                    ? "Выберите ИТ-направление программы" : "Выберите вендора продукта");
        }
        CreateCommand command = new CreateCommand(kind, name, kind.hasParent() ? parentId : null);
        return commandRunner.run(actor.id(), CommandOperation.CREATE_CATALOG_ENTRY, idempotencyKey, command,
                AdminCatalogEntry.class, commandId -> {
                    catalogChangeEventRepository.lockNames(kind.entityType());
                    String parentName = null;
                    if (kind.hasParent()) {
                        AdminCatalogEntry parent = adminCatalogRepository.findParent(kind, command.parentId())
                                .orElseThrow(() -> new InteractionValidationException("parentId", "Родительская запись не найдена"));
                        if (parent.archived()) {
                            throw new InteractionValidationException("parentId", "«" + parent.name() + "» в архиве");
                        }
                        parentName = parent.name();
                    }
                    requireNoDuplicate(kind, name, command.parentId(), null);
                    UUID id = UUID.randomUUID();
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        adminCatalogRepository.insert(kind, id, name, command.parentId(), now);
                    } catch (DuplicateKeyException exception) {
                        throw duplicate(name, false);
                    }
                    catalogChangeEventRepository.insert(
                            kind.entityType(), id, CatalogChangeAction.CREATE, name,
                            parentName == null ? null : parentLabel(kind) + ": " + parentName,
                            actor.id(), auditRequestId, now
                    );
                    return adminCatalogRepository.findById(kind, id).orElseThrow(CatalogEntryNotFoundException::new);
                });
    }

    @Transactional
    public AdminCatalogEntry update(
            CrmProfile actor,
            CatalogKind kind,
            UUID id,
            CatalogEntryRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (id == null) {
            throw new InteractionValidationException("id", "Укажите запись справочника");
        }
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        if (request.name() == null && request.archived() == null) {
            throw new InteractionValidationException("body", "Укажите новое название или состояние записи");
        }
        String requestedName = request.name() == null ? null : requiredName(request.name());
        UpdateCommand command = new UpdateCommand(kind, id, request.version(), requestedName, request.archived());
        return commandRunner.run(actor.id(), CommandOperation.UPDATE_CATALOG_ENTRY, idempotencyKey, command,
                AdminCatalogEntry.class, commandId -> {
                    AdminCatalogEntry current = adminCatalogRepository.findByIdForUpdate(kind, id)
                            .orElseThrow(CatalogEntryNotFoundException::new);
                    if (current.version() != command.version()) {
                        throw InteractionConflictException.catalogEntryVersion(current.version());
                    }
                    String name = command.name() == null ? current.name() : command.name();
                    boolean archived = command.archived() == null ? current.archived() : command.archived();
                    boolean renamed = !name.equals(current.name());
                    boolean archiveChanged = archived != current.archived();
                    if (!renamed && !archiveChanged) {
                        throw new InteractionValidationException("body", "Запись уже имеет указанные значения");
                    }
                    if (renamed && !CatalogNames.normalized(name).equals(CatalogNames.normalized(current.name()))) {
                        catalogChangeEventRepository.lockNames(kind.entityType());
                        requireNoDuplicate(kind, name, current.parentId(), id);
                    }
                    if (archiveChanged && archived) {
                        requireNoActiveChildren(kind, current);
                    }
                    if (archiveChanged && !archived && kind.hasParent()) {
                        adminCatalogRepository.findParent(kind, current.parentId())
                                .filter(AdminCatalogEntry::archived)
                                .ifPresent(parent -> {
                                    throw new InteractionValidationException(
                                            "archived", "Сначала восстановите «" + parent.name() + "»"
                                    );
                                });
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        if (!adminCatalogRepository.update(kind, id, command.version(), name, archived, now)) {
                            throw InteractionConflictException.catalogEntryVersion(current.version());
                        }
                    } catch (DuplicateKeyException exception) {
                        throw duplicate(name, false);
                    }
                    if (renamed) {
                        catalogChangeEventRepository.insert(
                                kind.entityType(), id, CatalogChangeAction.UPDATE, name,
                                "Название: «" + current.name() + "» → «" + name + "»", actor.id(), auditRequestId, now
                        );
                    }
                    if (archiveChanged) {
                        catalogChangeEventRepository.insert(
                                kind.entityType(), id, archived ? CatalogChangeAction.ARCHIVE : CatalogChangeAction.RESTORE,
                                name, null, actor.id(), auditRequestId, now
                        );
                    }
                    return adminCatalogRepository.findById(kind, id).orElseThrow(CatalogEntryNotFoundException::new);
                });
    }

    private void requireNoActiveChildren(CatalogKind kind, AdminCatalogEntry current) {
        CatalogKind childKind = kind == CatalogKind.DIRECTIONS ? CatalogKind.PROGRAMS
                : kind == CatalogKind.VENDORS ? CatalogKind.PRODUCTS : null;
        if (childKind == null) {
            return;
        }
        long children = adminCatalogRepository.countActiveChildren(childKind, current.id());
        if (children > 0) {
            throw new InteractionValidationException("archived", childKind == CatalogKind.PROGRAMS
                    ? "У направления есть действующие программы (" + children + "): сначала архивируйте их"
                    : "У вендора есть действующие продукты (" + children + "): сначала архивируйте их");
        }
    }

    private void requireNoDuplicate(CatalogKind kind, String name, UUID parentId, UUID exceptId) {
        String normalized = CatalogNames.normalized(name);
        adminCatalogRepository.findAll(kind).stream()
                .filter(entry -> !entry.id().equals(exceptId))
                .filter(entry -> Objects.equals(entry.parentId(), parentId))
                .filter(entry -> CatalogNames.normalized(entry.name()).equals(normalized))
                .findFirst()
                .ifPresent(entry -> {
                    throw duplicate(entry.name(), entry.archived());
                });
    }

    private static InteractionValidationException duplicate(String name, boolean archived) {
        return new InteractionValidationException("name", "«" + name + "» уже есть в справочнике"
                + (archived ? " (в архиве — восстановите запись)" : ""));
    }

    private static String parentLabel(CatalogKind kind) {
        return kind == CatalogKind.PROGRAMS ? "Направление" : "Вендор";
    }

    private static String requiredName(String value) {
        String name = CatalogNames.clean(value);
        if (name.isEmpty() || name.length() > NAME_LIMIT) {
            throw new InteractionValidationException("name", "Название должно содержать от 1 до 200 символов");
        }
        return name;
    }

    private record CreateCommand(CatalogKind kind, String name, UUID parentId) {
    }

    private record UpdateCommand(CatalogKind kind, UUID id, int version, String name, Boolean archived) {
    }
}
