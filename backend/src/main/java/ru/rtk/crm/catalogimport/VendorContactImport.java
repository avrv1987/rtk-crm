package ru.rtk.crm.catalogimport;

import static ru.rtk.crm.catalogimport.CatalogImportIndex.clean;
import static ru.rtk.crm.catalogimport.CatalogImportIndex.productKey;
import static ru.rtk.crm.catalogimport.CatalogImportRepository.VENDORS;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.rtk.crm.catalog.CatalogChangeAction;
import ru.rtk.crm.catalog.CatalogChangeEventRepository;
import ru.rtk.crm.catalog.CatalogEntityType;
import ru.rtk.crm.catalog.CatalogNames;
import ru.rtk.crm.catalog.VendorContactRepository.VendorContactValues;
import ru.rtk.crm.catalog.VendorContactRules;
import ru.rtk.crm.catalog.VendorContactRules.Channels;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.InteractionConflictException;

@Component
class VendorContactImport {
    static final Set<String> REQUIRED_FIELDS = Set.of("vendorName");
    static final Set<String> MAPPING_FIELDS = Set.of(
            "vendorName", "productNames", "vendorContactName", "vendorContactPhone", "vendorContactEmail", "vendorContactChannels"
    );
    private static final int MAX_KEY_LENGTH = 200;
    private static final int NAME_LIMIT = 200;

    private final CatalogImportRepository repository;
    private final CatalogChangeEventRepository catalogChangeEventRepository;
    private final ObjectMapper objectMapper;

    VendorContactImport(
            CatalogImportRepository repository,
            CatalogChangeEventRepository catalogChangeEventRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.catalogChangeEventRepository = catalogChangeEventRepository;
        this.objectMapper = objectMapper;
    }

    List<CatalogImportStoredRow> plan(CatalogImportWorkbookSheet sheet, CatalogImportMapping mapping) {
        List<ImportVendorContact> contacts = repository.findVendorContacts();
        Catalogs catalogs = new Catalogs(
                new CatalogImportIndex<>(repository.findCatalogEntries(VENDORS), vendor -> CatalogNames.vendorKey(vendor.name())),
                new CatalogImportIndex<>(repository.findVendorProducts(), product -> productKey(product.vendorId(), product.name())),
                new CatalogImportIndex<>(contacts, contact -> contact.vendorId() + "|" + emailKey(contact.values().email())),
                new CatalogImportIndex<>(contacts, contact -> contact.vendorId() + "|" + CatalogNames.normalized(contact.values().name())),
                new HashMap<>()
        );
        List<PlannedRow> rows = sheet.rows().stream().map(row -> new PlannedRow(row, sheet.name(), mapping)).toList();
        rows.forEach(row -> planRow(row, catalogs));
        PlannedRow.resolveConflicts(rows);
        return rows.stream().map(PlannedRow::stored).toList();
    }

    void apply(CatalogImportPlan plan, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        UUID vendorId = ensureVendor(plan, actorProfileId, commandId, now);
        UUID contactId = values.getOrDefault("vendorContactName", "").isEmpty()
                ? null
                : ensureContact(plan, vendorId, actorProfileId, commandId, now);
        int products = Integer.parseInt(values.getOrDefault("productCount", "0"));
        for (int index = 1; index <= products; index++) {
            ensureProduct(plan, "product" + index, vendorId, contactId, actorProfileId, commandId, now);
        }
    }

    private void planRow(PlannedRow row, Catalogs catalogs) {
        String vendorName = text(row, "vendorName", NAME_LIMIT);
        if (vendorName.isEmpty()) {
            row.error("vendorName", "Заполните значение");
        }
        List<String> productNames = productNames(row);
        String contactName = text(row, "vendorContactName", NAME_LIMIT);
        String phoneText = text(row, "vendorContactPhone", 50);
        String email = text(row, "vendorContactEmail", VendorContactRules.EMAIL_LIMIT);
        String channelsText = text(row, "vendorContactChannels", 100);
        String phone = phoneText.isEmpty() ? null : VendorContactRules.phone(phoneText).orElse(null);
        if (!phoneText.isEmpty() && phone == null) {
            row.error("vendorContactPhone", VendorContactRules.PHONE_RULE);
        }
        if (!email.isEmpty() && !VendorContactRules.email(email)) {
            row.error("vendorContactEmail", VendorContactRules.EMAIL_RULE);
        }
        Channels channels = channelsText.isEmpty() ? null : VendorContactRules.channels(channelsText).orElse(null);
        if (!channelsText.isEmpty() && channels == null) {
            row.error("vendorContactChannels", "Способ связи «" + channelsText
                    + "» не распознан: укажите «Почта», «Чат в ТГ» или оба через запятую");
        }
        boolean hasContact = !contactName.isEmpty() || !phoneText.isEmpty() || !email.isEmpty() || !channelsText.isEmpty();
        if (hasContact && contactName.isEmpty()) {
            row.error("vendorContactName", "Укажите ФИО контакта");
        }
        if (row.hasErrors()) {
            return;
        }
        Vendor vendor = planVendor(row, catalogs, vendorName);
        if (row.hasErrors()) {
            return;
        }
        Contact contact = hasContact
                ? planContact(row, catalogs, vendor, contactName, phone, email.isEmpty() ? null : email, channels)
                : null;
        if (row.hasErrors()) {
            return;
        }
        planProducts(row, catalogs, vendor, contact, productNames);
    }

    private Vendor planVendor(PlannedRow row, Catalogs catalogs, String vendorName) {
        String vendorKey = CatalogNames.vendorKey(vendorName);
        String derivedKey = derivedKey("vendor", vendorKey);
        Optional<ImportCatalogEntry> found = catalogs.vendors().byKey(derivedKey);
        List<ImportCatalogEntry> sameName = catalogs.vendors().byNaturalKey(vendorKey);
        if (found.isPresent() ? sameName.stream().anyMatch(value -> !value.id().equals(found.get().id())) : sameName.size() > 1) {
            row.conflict("vendorName", "В CRM несколько вендоров с таким названием без учёта правовой формы и кавычек: "
                    + sameName.stream().map(value -> "«" + value.name() + "»").collect(Collectors.joining(", "))
                    + "; объедините их в справочнике");
            return null;
        }
        Optional<ImportCatalogEntry> vendor = found.or(() -> sameName.stream().findFirst());
        String identity = vendor.map(value -> "id:" + value.id()).orElse("key:" + derivedKey);
        String name = vendor.map(ImportCatalogEntry::name).orElseGet(() -> catalogs.newVendorNames().computeIfAbsent(identity, ignored -> vendorName));
        String externalKey = vendor.map(ImportCatalogEntry::externalKey).filter(Objects::nonNull).orElse(derivedKey);
        row.values.put("vendorId", vendor.map(value -> value.id().toString()).orElse(""));
        row.values.put("vendorExternalKey", externalKey);
        row.values.put("vendorName", name);
        row.expected("vendor", vendor.map(ImportCatalogEntry::version).orElse(-1));
        row.compare(vendor.isPresent(), "vendorName", vendor.map(ImportCatalogEntry::name).orElse(""), name);
        if (vendor.isPresent() && vendor.get().externalKey() == null) {
            row.compare(true, "vendorExternalKey", "", externalKey);
        }
        return new Vendor(vendor.map(ImportCatalogEntry::id).orElse(null), vendorKey, identity);
    }

    private Contact planContact(
            PlannedRow row,
            Catalogs catalogs,
            Vendor vendor,
            String contactName,
            String phone,
            String email,
            Channels channels
    ) {
        String nameKey = CatalogNames.normalized(contactName);
        List<ImportVendorContact> candidates = vendor.id() == null ? List.of() : email != null
                ? catalogs.contactsByEmail().byNaturalKey(vendor.id() + "|" + emailKey(email))
                : catalogs.contactsByName().byNaturalKey(vendor.id() + "|" + nameKey);
        if (candidates.size() > 1) {
            row.conflict("vendorContactName", "У вендора несколько контактов с этим ФИО; укажите почту контакта");
            return null;
        }
        Optional<ImportVendorContact> found = candidates.stream().findFirst();
        if (found.isPresent() && !CatalogNames.normalized(found.get().values().name()).equals(nameKey)) {
            row.conflict("vendorContactEmail", "Эта почта уже указана у контакта вендора с другим ФИО");
            return null;
        }
        if (found.isPresent() && !found.get().personalDataActive()) {
            row.conflict("vendorContactName", "Контакт обезличен или его обработка ограничена; уточнение — через «Субъект ПДн»");
            return null;
        }
        Optional<VendorContactValues> current = found.map(ImportVendorContact::values);
        String derivedKey = derivedKey("vendor-contact", vendor.key(), email != null ? emailKey(email) : nameKey);
        String externalKey = found.map(ImportVendorContact::externalKey).filter(Objects::nonNull).orElse(derivedKey);
        VendorContactValues next = new VendorContactValues(
                current.map(VendorContactValues::name).orElse(contactName),
                phone != null ? phone : current.map(VendorContactValues::phone).orElse(null),
                current.map(VendorContactValues::email).orElse(email),
                channels != null ? channels.email() : current.map(VendorContactValues::prefersEmail).orElse(false),
                channels != null ? channels.telegram() : current.map(VendorContactValues::prefersTelegram).orElse(false),
                false
        );
        String identity = found.map(value -> "id:" + value.id()).orElse("key:" + derivedKey);
        row.values.put("vendorContactId", found.map(value -> value.id().toString()).orElse(""));
        row.values.put("vendorContactExternalKey", externalKey);
        row.values.put("vendorContactName", next.name());
        row.values.put("vendorContactPhone", text(next.phone()));
        row.values.put("vendorContactEmail", text(next.email()));
        row.values.put("vendorContactPrefersEmail", String.valueOf(next.prefersEmail()));
        row.values.put("vendorContactPrefersTelegram", String.valueOf(next.prefersTelegram()));
        row.expected("vendorContact", found.map(ImportVendorContact::version).orElse(-1));
        boolean exists = found.isPresent();
        row.compare(exists, "vendorContactName", current.map(VendorContactValues::name).orElse(""), next.name());
        row.compare(exists, "vendorContactPhone", current.map(VendorContactValues::phone).orElse(""), next.phone());
        row.compare(exists, "vendorContactEmail", current.map(VendorContactValues::email).orElse(""), next.email());
        row.compare(exists, "vendorContactChannels",
                current.map(value -> VendorContactRules.channelsLabel(value.prefersEmail(), value.prefersTelegram())).orElse(""),
                VendorContactRules.channelsLabel(next.prefersEmail(), next.prefersTelegram()));
        if (current.filter(VendorContactValues::archived).isPresent()) {
            row.compare(true, "vendorContactArchived", "да", "нет");
        }
        if (exists && found.get().externalKey() == null) {
            row.compare(true, "vendorContactExternalKey", "", externalKey);
        }
        row.claim("vendorContact", vendor.identity() + "|" + identity, "vendorContactName",
                String.join("\n", nameKey, text(next.phone()), text(emailKey(next.email())),
                        String.valueOf(next.prefersEmail()), String.valueOf(next.prefersTelegram())),
                "другие данные контакта вендора");
        return new Contact(found.map(ImportVendorContact::id).orElse(null), identity, next.name());
    }

    private void planProducts(PlannedRow row, Catalogs catalogs, Vendor vendor, Contact contact, List<String> names) {
        List<String> previousNames = new ArrayList<>();
        List<String> previousContacts = new ArrayList<>();
        List<String> storedNames = new ArrayList<>();
        boolean allExist = true;
        boolean allLinked = true;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            String entity = "product" + (index + 1);
            String nameKey = CatalogNames.normalized(name);
            String derivedKey = derivedKey("product", vendor.key(), nameKey);
            List<ImportVendorProduct> sameName = vendor.id() == null ? List.of() : catalogs.products().byNaturalKey(productKey(vendor.id(), name));
            Optional<ImportVendorProduct> byKey = catalogs.products().byKey(derivedKey)
                    .filter(value -> Objects.equals(value.vendorId(), vendor.id()));
            if (byKey.isEmpty() && sameName.size() > 1) {
                row.conflict("productNames", "У вендора несколько продуктов с названием «" + name + "»; объедините их в справочнике");
                return;
            }
            Optional<ImportVendorProduct> found = byKey.or(() -> sameName.stream().findFirst());
            String storedName = found.map(ImportVendorProduct::name).orElse(name);
            String identity = found.map(value -> "id:" + value.id()).orElse("key:" + derivedKey);
            row.values.put(entity + "Id", found.map(value -> value.id().toString()).orElse(""));
            row.values.put(entity + "ExternalKey", found.map(ImportVendorProduct::externalKey).filter(Objects::nonNull).orElse(derivedKey));
            row.values.put(entity + "Name", storedName);
            row.expected(entity, found.map(ImportVendorProduct::version).orElse(-1));
            storedNames.add(storedName);
            allExist &= found.isPresent();
            found.ifPresent(value -> previousNames.add(value.name()));
            if (contact != null) {
                UUID linked = found.map(ImportVendorProduct::contactId).orElse(null);
                allLinked &= found.isPresent() && contact.id() != null && contact.id().equals(linked);
                found.ifPresent(value -> previousContacts.add(value.name() + ": "
                        + (linked == null ? "нет" : catalogs.contactsByEmail().byId(linked)
                                .map(existing -> existing.values().name()).orElse("другой контакт"))));
                row.claim("productContact", identity, "productNames", contact.identity(), "у продукта другой контакт");
            }
            if (found.isPresent() && found.get().externalKey() == null) {
                row.compare(true, entity + "ExternalKey", "", derivedKey);
            }
            row.claim("productVendor", nameKey, "productNames", vendor.identity(), "продукт указан у другого вендора");
        }
        row.values.put("productCount", String.valueOf(names.size()));
        if (names.isEmpty()) {
            return;
        }
        row.compare(allExist, "productNames", String.join(", ", previousNames), String.join(", ", storedNames));
        if (contact != null) {
            row.compare(allExist, "productContact", String.join("; ", previousContacts),
                    allLinked ? String.join("; ", previousContacts)
                            : storedNames.stream().map(name -> name + ": " + contact.name()).collect(Collectors.joining("; ")));
        }
    }

    private UUID ensureVendor(CatalogImportPlan plan, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        String key = values.get("vendorExternalKey");
        String name = values.get("vendorName");
        UUID id = uuid(values.get("vendorId"));
        Optional<ImportCatalogEntry> existing = repository.findCatalogEntry(VENDORS, id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            UUID createdId = UUID.randomUUID();
            repository.insertCatalogEntry(VENDORS, createdId, key, name, now);
            catalogChangeEventRepository.insert(CatalogEntityType.VENDOR, createdId, CatalogChangeAction.CREATE, name,
                    "импорт каталога", actorProfileId, "catalog-import:" + commandId, now);
            return createdId;
        }
        ImportCatalogEntry vendor = existing.get();
        if (vendor.externalKey() == null) {
            requireExpectedVersion(plan, "vendor", vendor.version());
            if (!repository.updateCatalogEntry(VENDORS, vendor.id(), key, vendor.name(), vendor.version(), now)) {
                throw InteractionConflictException.catalogImportVersion(vendor.version() + 1);
            }
        }
        return vendor.id();
    }

    private UUID ensureContact(CatalogImportPlan plan, UUID vendorId, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        String key = values.get("vendorContactExternalKey");
        UUID id = uuid(values.get("vendorContactId"));
        VendorContactValues next = new VendorContactValues(
                values.get("vendorContactName"),
                nullable(values.get("vendorContactPhone")),
                nullable(values.get("vendorContactEmail")),
                Boolean.parseBoolean(values.get("vendorContactPrefersEmail")),
                Boolean.parseBoolean(values.get("vendorContactPrefersTelegram")),
                false
        );
        String vendorName = values.get("vendorName");
        Optional<ImportVendorContact> existing = repository.findVendorContact(id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            UUID createdId = UUID.randomUUID();
            repository.insertVendorContact(createdId, key, vendorId, next, actorProfileId, now);
            catalogChangeEventRepository.insert(CatalogEntityType.VENDOR_CONTACT, createdId, CatalogChangeAction.CREATE, next.name(),
                    "Вендор: " + vendorName + "; импорт каталога", actorProfileId, "catalog-import:" + commandId, now);
            return createdId;
        }
        ImportVendorContact contact = existing.get();
        if (!contact.vendorId().equals(vendorId) || !contact.personalDataActive()) {
            throw InteractionConflictException.catalogImportVersion(contact.version());
        }
        List<String> changes = changes(contact.values(), next);
        if (changes.isEmpty() && contact.externalKey() != null) {
            return contact.id();
        }
        requireExpectedVersion(plan, "vendorContact", contact.version());
        if (!repository.updateVendorContact(contact.id(), key, next, contact.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(contact.version() + 1);
        }
        if (!changes.isEmpty()) {
            catalogChangeEventRepository.insert(CatalogEntityType.VENDOR_CONTACT, contact.id(), CatalogChangeAction.UPDATE, next.name(),
                    "Вендор: " + vendorName + "; импорт каталога; изменено: " + String.join(", ", changes),
                    actorProfileId, "catalog-import:" + commandId, now);
        }
        return contact.id();
    }

    private void ensureProduct(
            CatalogImportPlan plan,
            String entity,
            UUID vendorId,
            UUID contactId,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        Map<String, String> values = plan.values();
        String key = values.get(entity + "ExternalKey");
        UUID id = uuid(values.get(entity + "Id"));
        String contact = contactId == null ? "" : "; контакт: " + values.get("vendorContactName");
        Optional<ImportVendorProduct> existing = repository.findVendorProduct(id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            UUID createdId = UUID.randomUUID();
            repository.insertVendorProduct(createdId, key, vendorId, values.get(entity + "Name"), contactId, now);
            catalogChangeEventRepository.insert(CatalogEntityType.PRODUCT, createdId, CatalogChangeAction.CREATE,
                    values.get(entity + "Name"), "Вендор: " + values.get("vendorName") + contact + "; импорт каталога",
                    actorProfileId, "catalog-import:" + commandId, now);
            return;
        }
        ImportVendorProduct product = existing.get();
        if (!product.vendorId().equals(vendorId)) {
            throw InteractionConflictException.catalogImportVersion(product.version());
        }
        UUID nextContact = contactId == null ? product.contactId() : contactId;
        if (Objects.equals(product.contactId(), nextContact) && product.externalKey() != null) {
            return;
        }
        requireExpectedVersion(plan, entity, product.version());
        if (!repository.updateVendorProduct(product.id(), key, nextContact, product.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(product.version() + 1);
        }
        if (!Objects.equals(product.contactId(), nextContact)) {
            catalogChangeEventRepository.insert(CatalogEntityType.PRODUCT, product.id(), CatalogChangeAction.UPDATE, product.name(),
                    "Вендор: " + values.get("vendorName") + contact + "; импорт каталога",
                    actorProfileId, "catalog-import:" + commandId, now);
        }
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
        if (current.archived()) {
            changes.add("восстановлен из архива");
        }
        return changes;
    }

    private static List<String> productNames(PlannedRow row) {
        String cell = clean(row.values.get("productNames"));
        row.values.put("productNames", cell);
        List<String> names = new ArrayList<>();
        if (cell.indexOf('«') >= 0) {
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (char symbol : cell.toCharArray()) {
                if (symbol == '«') {
                    if (depth++ > 0) {
                        current.append(symbol);
                    }
                } else if (symbol == '»' && depth > 0) {
                    if (--depth > 0) {
                        current.append(symbol);
                    } else {
                        names.add(clean(current.toString()));
                        current.setLength(0);
                    }
                } else if (depth > 0) {
                    current.append(symbol);
                }
            }
            if (depth != 0) {
                row.error("productNames", "В ячейке непарные кавычки «»");
            }
        } else {
            names.addAll(Arrays.stream(cell.split("[,;]")).map(CatalogImportIndex::clean).toList());
        }
        Map<String, String> unique = new LinkedHashMap<>();
        names.stream().filter(name -> !name.isEmpty()).forEach(name -> unique.putIfAbsent(CatalogNames.normalized(name), name));
        if (unique.values().stream().anyMatch(name -> name.length() > NAME_LIMIT)) {
            row.error("productNames", "Название продукта длиннее " + NAME_LIMIT + " символов");
        }
        return List.copyOf(unique.values());
    }

    private String derivedKey(String prefix, String... parts) {
        String joined = String.join("|", parts);
        String key = prefix + ":" + joined;
        return key.length() <= MAX_KEY_LENGTH ? key : prefix + ":" + CommandFingerprint.of(objectMapper, joined);
    }

    private static String text(PlannedRow row, String field, int maxLength) {
        String value = clean(row.values.get(field));
        if (value.length() > maxLength) {
            row.error(field, "Длиннее " + maxLength + " символов");
        }
        row.values.put(field, value);
        return value;
    }

    private static String emailKey(String email) {
        return email == null ? "" : VendorContactRules.emailKey(email);
    }

    private static void requireCreatable(UUID resolvedId) {
        if (resolvedId != null) {
            throw InteractionConflictException.catalogImportVersion(0);
        }
    }

    private static void requireExpectedVersion(CatalogImportPlan plan, String entity, int currentVersion) {
        Integer expected = plan.expectedVersions().get(entity);
        if (expected == null || expected != currentVersion) {
            throw InteractionConflictException.catalogImportVersion(currentVersion);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private record Vendor(UUID id, String key, String identity) {
    }

    private record Contact(UUID id, String identity, String name) {
    }

    private record Catalogs(
            CatalogImportIndex<ImportCatalogEntry> vendors,
            CatalogImportIndex<ImportVendorProduct> products,
            CatalogImportIndex<ImportVendorContact> contactsByEmail,
            CatalogImportIndex<ImportVendorContact> contactsByName,
            Map<String, String> newVendorNames
    ) {
    }
}
