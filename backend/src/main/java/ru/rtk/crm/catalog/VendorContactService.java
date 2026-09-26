package ru.rtk.crm.catalog;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.catalog.VendorContactRepository.VendorContactValues;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class VendorContactService {
    private final VendorContactRepository repository;
    private final CatalogChangeEventRepository catalogChangeEventRepository;
    private final IdempotentCommandRunner commandRunner;

    public VendorContactService(
            VendorContactRepository repository,
            CatalogChangeEventRepository catalogChangeEventRepository,
            IdempotentCommandRunner commandRunner
    ) {
        this.repository = repository;
        this.catalogChangeEventRepository = catalogChangeEventRepository;
        this.commandRunner = commandRunner;
    }

    @Transactional(readOnly = true)
    public VendorContactList list(CrmProfile actor, UUID vendorId) {
        AdminAuthorization.requireAdmin(actor);
        repository.findVendor(vendorId).orElseThrow(CatalogEntryNotFoundException::new);
        return new VendorContactList(repository.findByVendor(vendorId), repository.findProducts(vendorId));
    }

    @Transactional
    public VendorContact create(
            CrmProfile actor,
            UUID vendorId,
            VendorContactRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (request == null) {
            throw new InteractionValidationException("body", "Не переданы данные контакта");
        }
        VendorContactValues values = new VendorContactValues(
                requiredName(request.name()),
                phone(request.phone()),
                email(request.email()),
                Boolean.TRUE.equals(request.prefersEmail()),
                Boolean.TRUE.equals(request.prefersTelegram()),
                false
        );
        List<UUID> productIds = productIds(request.productIds());
        CreateCommand command = new CreateCommand(vendorId, values, productIds);
        return commandRunner.run(actor.id(), CommandOperation.CREATE_VENDOR_CONTACT, idempotencyKey, command,
                VendorContact.class, commandId -> {
                    AdminCatalogEntry vendor = repository.findVendorForUpdate(vendorId)
                            .orElseThrow(CatalogEntryNotFoundException::new);
                    if (vendor.archived()) {
                        throw new InteractionValidationException("vendorId", "Вендор «" + vendor.name() + "» в архиве");
                    }
                    requireFreeEmail(vendorId, values.email(), null);
                    List<CatalogReference> products = products(vendorId, productIds);
                    UUID id = UUID.randomUUID();
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        repository.insert(id, vendorId, values, null, actor.id(), now);
                    } catch (DuplicateKeyException exception) {
                        throw duplicateEmail();
                    }
                    repository.linkProducts(vendorId, id, productIds, now);
                    catalogChangeEventRepository.insert(
                            CatalogEntityType.VENDOR_CONTACT, id, CatalogChangeAction.CREATE, values.name(),
                            "Вендор: " + vendor.name() + productsText(products), actor.id(), auditRequestId, now
                    );
                    return repository.findById(vendorId, id).orElseThrow(CatalogEntryNotFoundException::new);
                });
    }

    @Transactional
    public VendorContact update(
            CrmProfile actor,
            UUID vendorId,
            UUID id,
            VendorContactRequest request,
            String idempotencyKey,
            String requestId
    ) {
        AdminAuthorization.requireAdmin(actor);
        String auditRequestId = AdminAuthorization.requiredRequestId(requestId);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Некорректная версия записи; обновите страницу");
        }
        UpdateCommand command = new UpdateCommand(
                vendorId,
                id,
                request.version(),
                request.name() == null ? null : requiredName(request.name()),
                request.phone() == null ? null : CatalogNames.clean(request.phone()),
                request.email() == null ? null : CatalogNames.clean(request.email()),
                request.prefersEmail(),
                request.prefersTelegram(),
                request.archived(),
                request.productIds() == null ? null : productIds(request.productIds())
        );
        String phone = command.phone() == null ? null : phone(command.phone());
        String email = command.email() == null ? null : email(command.email());
        return commandRunner.run(actor.id(), CommandOperation.UPDATE_VENDOR_CONTACT, idempotencyKey, command,
                VendorContact.class, commandId -> {
                    AdminCatalogEntry vendor = repository.findVendorForUpdate(vendorId)
                            .orElseThrow(CatalogEntryNotFoundException::new);
                    VendorContact current = repository.findByIdForUpdate(vendorId, id)
                            .orElseThrow(CatalogEntryNotFoundException::new);
                    if (current.version() != command.version()) {
                        throw InteractionConflictException.contactVersion(current.version());
                    }
                    VendorContactValues next = new VendorContactValues(
                            command.name() == null ? current.name() : command.name(),
                            command.phone() == null ? current.phone() : phone,
                            command.email() == null ? current.email() : email,
                            command.prefersEmail() == null ? current.prefersEmail() : command.prefersEmail(),
                            command.prefersTelegram() == null ? current.prefersTelegram() : command.prefersTelegram(),
                            command.archived() == null ? current.archived() : command.archived()
                    );
                    List<UUID> currentProducts = current.products().stream().map(CatalogReference::id).sorted().toList();
                    List<UUID> nextProducts = next.archived() ? List.of()
                            : command.productIds() == null ? currentProducts : command.productIds().stream().sorted().toList();
                    List<String> changes = changes(VendorContactValues.of(current), next);
                    boolean productsChanged = !currentProducts.equals(nextProducts);
                    boolean archiveChanged = current.archived() != next.archived();
                    if (changes.isEmpty() && !productsChanged && !archiveChanged) {
                        throw new InteractionValidationException("body", "Контакт уже имеет указанные значения");
                    }
                    if (!next.archived() && vendor.archived()) {
                        throw new InteractionValidationException("vendorId", "Вендор «" + vendor.name() + "» в архиве");
                    }
                    requireFreeEmail(vendorId, next.email(), id);
                    List<CatalogReference> products = products(vendorId, nextProducts);
                    OffsetDateTime now = OffsetDateTime.now();
                    try {
                        if (!repository.update(id, current.version(), next, null, now)) {
                            throw InteractionConflictException.contactVersion(current.version());
                        }
                    } catch (DuplicateKeyException exception) {
                        throw duplicateEmail();
                    }
                    if (productsChanged) {
                        repository.linkProducts(vendorId, id, nextProducts, now);
                        changes.add(products.isEmpty() ? "продукты откреплены"
                                : "продукты: " + products.stream().map(CatalogReference::name).collect(Collectors.joining(", ")));
                    }
                    if (!changes.isEmpty()) {
                        catalogChangeEventRepository.insert(
                                CatalogEntityType.VENDOR_CONTACT, id, CatalogChangeAction.UPDATE, next.name(),
                                "Вендор: " + vendor.name() + "; изменено: " + String.join(", ", changes),
                                actor.id(), auditRequestId, now
                        );
                    }
                    if (archiveChanged) {
                        catalogChangeEventRepository.insert(
                                CatalogEntityType.VENDOR_CONTACT, id,
                                next.archived() ? CatalogChangeAction.ARCHIVE : CatalogChangeAction.RESTORE,
                                next.name(), "Вендор: " + vendor.name(), actor.id(), auditRequestId, now
                        );
                    }
                    return repository.findById(vendorId, id).orElseThrow(CatalogEntryNotFoundException::new);
                });
    }

    private void requireFreeEmail(UUID vendorId, String email, UUID exceptId) {
        if (email != null && repository.emailTaken(vendorId, email, exceptId)) {
            throw duplicateEmail();
        }
    }

    private List<CatalogReference> products(UUID vendorId, List<UUID> productIds) {
        List<CatalogReference> products = repository.findProducts(vendorId, productIds);
        if (products.size() != productIds.size()) {
            throw new InteractionValidationException("productIds", "Выберите продукты этого вендора");
        }
        return products;
    }

    private static List<String> changes(VendorContactValues current, VendorContactValues next) {
        List<String> changes = new ArrayList<>();
        if (!current.name().equals(next.name())) {
            changes.add("ФИО");
        }
        if (!Objects.equals(current.phone(), next.phone())) {
            changes.add("телефон");
        }
        if (!Objects.equals(current.email(), next.email())) {
            changes.add("почта");
        }
        if (current.prefersEmail() != next.prefersEmail() || current.prefersTelegram() != next.prefersTelegram()) {
            changes.add("способ связи");
        }
        return changes;
    }

    private static String productsText(List<CatalogReference> products) {
        return products.isEmpty() ? ""
                : "; продукты: " + products.stream().map(CatalogReference::name).collect(Collectors.joining(", "));
    }

    private static List<UUID> productIds(List<UUID> value) {
        List<UUID> ids = value == null ? List.of() : value;
        if (ids.stream().anyMatch(Objects::isNull) || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new InteractionValidationException("productIds", "Продукты не должны повторяться");
        }
        return ids.stream().sorted().toList();
    }

    private static String requiredName(String value) {
        String name = CatalogNames.clean(value);
        if (name.isEmpty() || name.length() > VendorContactRules.NAME_LIMIT) {
            throw new InteractionValidationException("name", "ФИО контакта должно содержать от 1 до 200 символов");
        }
        return name;
    }

    private static String phone(String value) {
        if (CatalogNames.clean(value).isEmpty()) {
            return null;
        }
        return VendorContactRules.phone(value)
                .orElseThrow(() -> new InteractionValidationException("phone", VendorContactRules.PHONE_RULE));
    }

    private static String email(String value) {
        String email = CatalogNames.clean(value);
        if (email.isEmpty()) {
            return null;
        }
        if (!VendorContactRules.email(email)) {
            throw new InteractionValidationException("email", VendorContactRules.EMAIL_RULE);
        }
        return email;
    }

    private static InteractionValidationException duplicateEmail() {
        return new InteractionValidationException("email", "Контакт с этой почтой уже есть у вендора");
    }

    private record CreateCommand(UUID vendorId, VendorContactValues values, List<UUID> productIds) {
    }

    private record UpdateCommand(
            UUID vendorId,
            UUID id,
            int version,
            String name,
            String phone,
            String email,
            Boolean prefersEmail,
            Boolean prefersTelegram,
            Boolean archived,
            List<UUID> productIds
    ) {
    }
}
