package ru.rtk.crm.catalogimport;

import static ru.rtk.crm.catalogimport.CatalogImportIndex.clean;
import static ru.rtk.crm.catalogimport.CatalogImportIndex.naturalKey;
import static ru.rtk.crm.catalogimport.CatalogImportIndex.normalized;
import static ru.rtk.crm.catalogimport.CatalogImportRepository.DIRECTIONS;
import static ru.rtk.crm.catalogimport.CatalogImportRepository.PRODUCTS;
import static ru.rtk.crm.catalogimport.CatalogImportRepository.PROGRAMS;
import static ru.rtk.crm.catalogimport.CatalogImportRepository.VENDORS;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.attachment.AttachmentContentValidator;
import ru.rtk.crm.attachment.AttachmentScanOutcome;
import ru.rtk.crm.attachment.AttachmentScanner;
import ru.rtk.crm.attachment.AttachmentTooLargeException;
import ru.rtk.crm.attachment.AttachmentUploadInspection;
import ru.rtk.crm.attachment.AttachmentValidationException;
import ru.rtk.crm.catalog.OrganizationAssignmentRepository;
import ru.rtk.crm.interaction.CommandFingerprint;
import ru.rtk.crm.interaction.CommandIdempotencyRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class CatalogImportService {
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;
    private static final int MAX_KEY_LENGTH = 200;
    private static final DateTimeFormatter RUSSIAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu");
    private static final Set<String> AGREEMENT_REQUIRED_FIELDS = Set.of("organizationName", "vendorName", "productName");
    private static final Set<String> AGREEMENT_MAPPING_FIELDS = Set.of(
            "organizationName", "organizationExternalKey", "organizationType",
            "vendorName", "vendorExternalKey", "productName", "productExternalKey",
            "agreementExternalKey", "contractNumber", "licenseSigned", "licenseExpiryYear",
            "transferStatus", "managerName", "contactName", "contactExternalKey", "contactPosition",
            "contactEmail", "contactPhone", "interactionId", "comment"
    );
    private static final List<String> AGREEMENT_VALUE_FIELDS = List.of(
            "agreementExternalKey", "contractNumber", "licenseSigned", "licenseExpiryYear", "transferStatus", "comment",
            "interactionId"
    );
    private static final Set<String> DIRECTION_PROGRAM_REQUIRED_FIELDS = Set.of("directionExternalKey", "directionName");
    private static final Set<String> DIRECTION_PROGRAM_MAPPING_FIELDS = Set.of(
            "directionExternalKey", "directionName", "programExternalKey", "programName", "programDirectionRef"
    );
    private static final CatalogImportRowTarget EMPTY_TARGET = new CatalogImportRowTarget(null, null, null, null);

    private final CatalogImportRepository repository;
    private final OrganizationAssignmentRepository organizationAssignmentRepository;
    private final InteractionService interactionService;
    private final CatalogImportWorkbookReader workbookReader;
    private final AttachmentContentValidator attachmentContentValidator;
    private final AttachmentScanner attachmentScanner;
    private final CommandIdempotencyRepository commandIdempotencyRepository;
    private final ObjectMapper objectMapper;

    public CatalogImportService(
            CatalogImportRepository repository,
            CatalogImportWorkbookReader workbookReader,
            AttachmentContentValidator attachmentContentValidator,
            AttachmentScanner attachmentScanner,
            CommandIdempotencyRepository commandIdempotencyRepository,
            ObjectMapper objectMapper,
            OrganizationAssignmentRepository organizationAssignmentRepository,
            InteractionService interactionService
    ) {
        this.repository = repository;
        this.organizationAssignmentRepository = organizationAssignmentRepository;
        this.interactionService = interactionService;
        this.workbookReader = workbookReader;
        this.attachmentContentValidator = attachmentContentValidator;
        this.attachmentScanner = attachmentScanner;
        this.commandIdempotencyRepository = commandIdempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public CatalogImportInspectResponse inspect(CrmProfile profile, MultipartFile file) {
        requireAdmin(profile);
        inspectWorkbookFile(file);
        return workbookReader.inspect(file);
    }

    @Transactional
    public CatalogImportPreviewResponse preview(
            CrmProfile profile,
            MultipartFile file,
            CatalogImportProfile profileType,
            String sheetName,
            CatalogImportMapping mapping
    ) {
        requireAdmin(profile);
        if (profileType == null) {
            throw new InteractionValidationException("profile", "Выберите профиль импорта");
        }
        inspectWorkbookFile(file);
        CatalogImportMapping normalizedMapping = normalizedMapping(mapping);
        CatalogImportWorkbookSheet sheet = workbookReader.read(file, requiredText(sheetName, "sheet", 255));
        validateMapping(profileType, normalizedMapping, sheet.headers());
        List<PlannedRow> plans = sheet.rows().stream()
                .map(row -> new PlannedRow(row, sheet.name(), normalizedMapping))
                .toList();
        if (profileType == CatalogImportProfile.DIRECTION_PROGRAM) {
            DirectionCatalogs catalogs = new DirectionCatalogs(
                    new CatalogImportIndex<>(repository.findCatalogEntries(DIRECTIONS), entry -> normalized(entry.name())),
                    new CatalogImportIndex<>(repository.findChildEntries(PROGRAMS), entry -> naturalKey(entry.parentId(), entry.name()))
            );
            plans.forEach(plan -> planDirectionProgram(plan, catalogs));
        } else {
            AgreementCatalogs catalogs = agreementCatalogs(normalizedMapping);
            AgreementPlanning planning = new AgreementPlanning(new HashMap<>(), new HashSet<>(), new HashMap<>());
            plans.stream()
                    .sorted(Comparator.comparing((PlannedRow plan) -> !agreementRequested(plan)))
                    .forEach(plan -> planAgreement(plan, catalogs, planning));
            inheritManagers(plans, planning);
        }
        resolveRowConflicts(plans);
        List<CatalogImportStoredRow> rows = plans.stream().map(PlannedRow::stored).toList();
        UUID importId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        repository.insertPreview(importId, jobId, profile.id(), profileType, normalizedMapping, rows, OffsetDateTime.now());
        return new CatalogImportPreviewResponse(jobId, importId);
    }

    public CatalogImportView get(CrmProfile profile, UUID importId) {
        requireAdmin(profile);
        return repository.findById(importId)
                .map(this::view)
                .orElseThrow(CatalogImportNotFoundException::new);
    }

    public CatalogImportJobView getJob(CrmProfile profile, UUID jobId) {
        requireAdmin(profile);
        return repository.findJob(jobId).orElseThrow(CatalogImportJobNotFoundException::new);
    }

    @Transactional
    public CatalogImportApplyResponse apply(
            CrmProfile profile,
            UUID importId,
            CatalogImportApplyRequest request,
            String idempotencyKey
    ) {
        requireAdmin(profile);
        CatalogImportApplyRequest normalizedRequest = normalizedApplyRequest(request);
        String normalizedIdempotencyKey = requiredText(idempotencyKey, "Idempotency-Key", 255);
        String fingerprint = CommandFingerprint.of(objectMapper, new CatalogImportApplyFingerprint(importId, normalizedRequest));
        UUID commandId = UUID.randomUUID();
        if (!commandIdempotencyRepository.reserve(
                commandId,
                profile.id(),
                CommandOperation.APPLY_CATALOG_IMPORT,
                normalizedIdempotencyKey,
                fingerprint,
                OffsetDateTime.now()
        )) {
            CommandIdempotencyRepository.CommandRecord previous = commandIdempotencyRepository
                    .find(profile.id(), CommandOperation.APPLY_CATALOG_IMPORT, normalizedIdempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Catalog import idempotency record is unavailable"));
            if (!previous.requestFingerprint().equals(fingerprint) || previous.resultJson() == null) {
                throw InteractionConflictException.idempotency();
            }
            return readApplyResponse(previous.resultJson());
        }

        CatalogImportStored stored = repository.findByIdForUpdate(importId)
                .orElseThrow(CatalogImportNotFoundException::new);
        if (stored.status() != CatalogImportStatus.PREVIEWED || stored.version() != normalizedRequest.version()) {
            throw InteractionConflictException.catalogImportVersion(stored.version());
        }
        Map<UUID, CatalogImportStoredRow> rowsById = stored.rows().stream()
                .collect(Collectors.toMap(CatalogImportStoredRow::id, row -> row));
        List<CatalogImportStoredRow> selectedRows = new ArrayList<>();
        for (UUID rowId : normalizedRequest.confirmedRowIds()) {
            CatalogImportStoredRow row = rowsById.get(rowId);
            if (row == null || row.applied() || !canApply(row.status())) {
                throw new InteractionValidationException(
                        "confirmedRowIds", "Применить можно только строки «Создать», «Обновить» или «Без изменений»"
                );
            }
            selectedRows.add(row);
        }
        selectedRows.sort(Comparator.comparingInt(CatalogImportStoredRow::rowNumber));
        try {
            OffsetDateTime now = OffsetDateTime.now();
            for (CatalogImportStoredRow row : selectedRows) {
                if (stored.profile() == CatalogImportProfile.DIRECTION_PROGRAM) {
                    applyDirectionProgram(row.plan(), now);
                } else {
                    applyAgreement(row.plan(), profile.id(), commandId, now);
                }
            }
            if (!repository.markApplied(stored.id(), stored.version(), now)) {
                throw InteractionConflictException.catalogImportVersion(stored.version() + 1);
            }
            repository.markRowsApplied(stored.id(), selectedRows.stream().map(CatalogImportStoredRow::id).toList());
            UUID jobId = UUID.randomUUID();
            repository.insertApplyJob(jobId, stored.id(), profile.id(), now);
            CatalogImportApplyResponse response = new CatalogImportApplyResponse(jobId, stored.id());
            commandIdempotencyRepository.complete(commandId, write(response));
            return response;
        } catch (DataIntegrityViolationException exception) {
            throw InteractionConflictException.catalogImportVersion(stored.version());
        }
    }

    private AgreementCatalogs agreementCatalogs(CatalogImportMapping mapping) {
        return new AgreementCatalogs(
                new CatalogImportIndex<>(repository.findOrganizations(), organization -> normalized(organization.name())),
                new CatalogImportIndex<>(repository.findCatalogEntries(VENDORS), vendor -> normalized(vendor.name())),
                new CatalogImportIndex<>(repository.findChildEntries(PRODUCTS), product -> naturalKey(product.parentId(), product.name())),
                new CatalogImportIndex<>(repository.findContacts(), contact -> naturalKey(contact.organizationId(), contact.values().name())),
                new CatalogImportIndex<>(repository.findAgreements(),
                        agreement -> naturalKey(agreement.organizationId(), agreement.productId().toString())),
                repository.findActiveManagers().stream()
                        .collect(Collectors.groupingBy(manager -> normalized(manager.displayName()))),
                repository.findTransferStatuses().stream()
                        .collect(Collectors.toMap(CatalogImportIndex::normalized, status -> status, (first, second) -> first)),
                mapping.transferStatuses().entrySet().stream()
                        .collect(Collectors.toMap(entry -> normalized(entry.getKey()), Map.Entry::getValue, (first, second) -> first))
        );
    }

    private void planDirectionProgram(PlannedRow row, DirectionCatalogs catalogs) {
        String directionKey = required(row, "directionExternalKey", MAX_KEY_LENGTH);
        String directionName = required(row, "directionName", 200);
        if (row.hasErrors()) {
            return;
        }
        Resolved<ImportCatalogEntry> direction = resolve(
                row, "direction", "directionName", catalogs.directions(), null, new KeyChoice(directionKey, false),
                catalogs.directions().byNaturalKey(normalized(directionName)), true
        );
        row.compare(direction.exists(), "directionName", direction.found().map(ImportCatalogEntry::name).orElse(""), directionName);
        row.claim("direction", direction.identity(), "directionName", directionName, "другое написание направления");
        row.claim("directionName", normalized(directionName), "directionName", direction.identity(), "то же название у другого направления");

        String programKey = optional(row, "programExternalKey", MAX_KEY_LENGTH);
        String programName = optional(row, "programName", 200);
        String directionRef = optional(row, "programDirectionRef", MAX_KEY_LENGTH);
        if (programKey.isEmpty() && programName.isEmpty() && directionRef.isEmpty()) {
            return;
        }
        required(row, "programExternalKey", programKey);
        required(row, "programName", programName);
        if (!directionKey.equals(directionRef)) {
            row.error("programDirectionRef", "Должен совпадать с ключом направления в этой строке");
        }
        if (row.hasErrors()) {
            return;
        }
        Resolved<ImportChildEntry> program = resolve(
                row, "program", "programName", catalogs.programs(), null, new KeyChoice(programKey, false),
                direction.found().map(value -> catalogs.programs().byNaturalKey(naturalKey(value.id(), programName))).orElse(List.of()),
                true
        );
        row.compare(program.exists(), "programName", program.found().map(ImportChildEntry::name).orElse(""), programName);
        if (program.exists() && direction.found().map(value -> !value.id().equals(program.found().get().parentId())).orElse(true)) {
            row.compare(true, "programDirection", "другое направление", directionName);
        }
        row.claim("program", program.identity(), "programName", programName + "\n" + direction.identity(), "другое название или направление программы");
        row.claim("programName", direction.identity() + "|" + normalized(programName), "programName", program.identity(),
                "то же название у другой программы направления");
    }

    private void planAgreement(PlannedRow row, AgreementCatalogs catalogs, AgreementPlanning planning) {
        String organizationName = required(row, "organizationName", 300);
        String vendorName = required(row, "vendorName", 200);
        String productName = required(row, "productName", 200);
        if (row.hasErrors()) {
            return;
        }
        Optional<ImportProfile> manager = planManager(row, catalogs);
        Resolved<ImportOrganization> organization = planOrganization(row, catalogs, planning, organizationName, manager);

        Resolved<ImportCatalogEntry> vendor = resolve(
                row, "vendor", "vendorName", catalogs.vendors(), null,
                keyChoice(row, "vendorExternalKey", "vendor", vendorName),
                catalogs.vendors().byNaturalKey(normalized(vendorName)), true
        );
        row.compare(vendor.exists(), "vendorName", vendor.found().map(ImportCatalogEntry::name).orElse(""), vendorName);
        row.claim("vendor", vendor.identity(), "vendorName", vendorName, "другое написание вендора");
        row.claim("vendorName", normalized(vendorName), "vendorName", vendor.identity(), "то же название у другого вендора");

        Resolved<ImportChildEntry> product = resolve(
                row, "product", "productName", catalogs.products(), null,
                keyChoice(row, "productExternalKey", "product", vendorName, productName),
                vendor.found().map(value -> catalogs.products().byNaturalKey(naturalKey(value.id(), productName))).orElse(List.of()),
                true
        );
        row.compare(product.exists(), "productName", product.found().map(ImportChildEntry::name).orElse(""), productName);
        if (product.exists() && vendor.found().map(value -> !value.id().equals(product.found().get().parentId())).orElse(true)) {
            row.compare(true, "productVendor", "другой вендор", vendorName);
        }
        row.claim("product", product.identity(), "productName", productName + "\n" + vendor.identity(), "другое написание ПО или вендор");
        row.claim("productName", vendor.identity() + "|" + normalized(productName), "productName", product.identity(),
                "то же ПО у другой записи каталога");

        planContact(row, catalogs, organization);
        planProductAgreement(row, catalogs, planning, organization, product, productName);
    }

    private Optional<ImportProfile> planManager(PlannedRow row, AgreementCatalogs catalogs) {
        String managerName = optional(row, "managerName", 200);
        UUID explicitId = row.target.managerProfileId();
        if (explicitId != null) {
            Optional<ImportProfile> manager = repository.findActiveProfile(explicitId)
                    .filter(value -> value.role() == UserRole.USER && value.teamId() != null);
            if (manager.isEmpty()) {
                row.error("managerName", "UUID КАМ не относится к активному пользователю с ролью USER и командой");
                return Optional.empty();
            }
            if (!managerName.isEmpty() && !normalized(manager.get().displayName()).equals(normalized(managerName))) {
                row.conflict("managerName", "UUID КАМ принадлежит «" + manager.get().displayName()
                        + "», а в файле указано «" + managerName + "»");
                return Optional.empty();
            }
            return manager;
        }
        if (managerName.isEmpty()) {
            return Optional.empty();
        }
        List<ImportProfile> candidates = catalogs.managers().getOrDefault(normalized(managerName), List.of());
        if (candidates.isEmpty()) {
            row.error("managerName", "Активный КАМ «" + managerName + "» (роль USER) не найден; проверьте ФИО в профилях CRM");
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            row.conflict("managerName", "Найдено " + candidates.size() + " активных КАМ с ФИО «" + managerName
                    + "»; укажите UUID КАМ для строки в разделе «Дополнительно»");
            return Optional.empty();
        }
        return Optional.of(candidates.getFirst());
    }

    private Resolved<ImportOrganization> planOrganization(
            PlannedRow row,
            AgreementCatalogs catalogs,
            AgreementPlanning planning,
            String organizationName,
            Optional<ImportProfile> manager
    ) {
        Resolved<ImportOrganization> organization = resolve(
                row, "organization", "organizationName", catalogs.organizations(), row.target.organizationId(),
                keyChoice(row, "organizationExternalKey", "org", organizationName),
                catalogs.organizations().byNaturalKey(normalized(organizationName)), true
        );
        Optional<ImportOrganization> existing = organization.found();
        String type = organizationType(row, existing);
        row.values.put("organizationType", type);
        row.values.put("managerProfileId", manager.map(value -> value.id().toString()).orElse(""));
        manager.ifPresent(value -> planning.managers()
                .computeIfAbsent(organization.identity(), ignored -> new LinkedHashSet<>())
                .add(value));
        if (existing.isEmpty() && manager.isEmpty() && !row.errors.containsKey("managerName")) {
            row.managerPendingFor = organization.identity();
        }
        if (existing.isPresent() && manager.isPresent() && !existing.get().teamId().equals(manager.get().teamId())) {
            row.conflict("managerName", "КАМ из другой команды; передачу вуза между командами выполните в карточке вуза");
        }
        String currentManager = existing.map(ImportOrganization::ownerDisplayName).orElse("");
        row.compare(organization.exists(), "organizationName", existing.map(ImportOrganization::name).orElse(""), organizationName);
        row.compare(organization.exists(), "organizationType", existing.map(value -> typeLabel(value.type())).orElse(""), typeLabel(type));
        row.compare(organization.exists(), "managerName", currentManager, manager.map(ImportProfile::displayName).orElse(currentManager));
        row.claim("organization", organization.identity(), "organizationName", organizationName + "\n" + type,
                "другое написание или тип вуза");
        row.claim("organizationName", normalized(organizationName), "organizationName", organization.identity(),
                "то же название у другого вуза");
        manager.ifPresent(value -> row.claim("manager", organization.identity(), "managerName", value.id().toString(),
                "другой КАМ для этого вуза"));
        return organization;
    }

    private void planContact(PlannedRow row, AgreementCatalogs catalogs, Resolved<ImportOrganization> organization) {
        String contactName = optional(row, "contactName", 200);
        if (contactName.isEmpty()) {
            return;
        }
        Resolved<ImportContact> contact = resolve(
                row, "contact", "contactName", catalogs.contacts(), null,
                keyChoice(row, "contactExternalKey", "contact", organization.key(), contactName),
                organization.found().map(value -> catalogs.contacts().byNaturalKey(naturalKey(value.id(), contactName))).orElse(List.of()),
                false
        );
        Optional<ContactValues> existing = contact.found().map(ImportContact::values);
        if (contact.exists() && organization.found().map(value -> !value.id().equals(contact.found().get().organizationId())).orElse(true)) {
            row.conflict("contactName", "Ответственный с этим ключом относится к другому вузу");
        }
        ContactValues next = new ContactValues(
                contactName,
                mappedOrExisting(row, "contactPosition", 200, existing.map(ContactValues::position)),
                mappedOrExisting(row, "contactEmail", 320, existing.map(ContactValues::email)),
                mappedOrExisting(row, "contactPhone", 50, existing.map(ContactValues::phone))
        );
        row.values.put("contactPosition", valueOf(next.position()));
        row.values.put("contactEmail", valueOf(next.email()));
        row.values.put("contactPhone", valueOf(next.phone()));
        row.compare(contact.exists(), "contactName", existing.map(ContactValues::name).orElse(""), contactName);
        if (existing.isPresent() && !existing.get().equals(next)) {
            row.compare(true, "contactDetails", contactDetails(existing.get()), contactDetails(next));
        }
        row.claim("contact", contact.identity(), "contactName", contactDetails(next) + "\n" + contactName,
                "другие данные ответственного");
    }

    private void planProductAgreement(
            PlannedRow row,
            AgreementCatalogs catalogs,
            AgreementPlanning planning,
            Resolved<ImportOrganization> organization,
            Resolved<ImportChildEntry> product,
            String productName
    ) {
        List<ImportAgreement> linked = linkedAgreements(catalogs, organization, product);
        String pair = organization.identity() + "|" + product.identity();
        if (!agreementRequested(row) && (!linked.isEmpty() || planning.agreementPairs().contains(pair))) {
            return;
        }
        planning.agreementPairs().add(pair);
        UUID explicitInteractionId = row.target.interactionId() != null ? row.target.interactionId() : uuid(row, "interactionId");
        String contractNumber = optional(row, "contractNumber", 200);
        Boolean licenseSigned = licenseSigned(row);
        Integer licenseExpiryYear = licenseExpiryYear(row);
        String transferStatus = transferStatus(row, catalogs);
        String comment = comment(row);
        KeyChoice agreementKey = keyChoice(row, "agreementExternalKey", "agreement", organization.key(), product.key(), contractNumber);
        Resolved<ImportAgreement> agreement = resolve(
                row, "agreement", "contractNumber", catalogs.agreements(), row.target.productAgreementId(),
                agreementKey, naturalAgreements(linked, contractNumber), false
        );
        Optional<ImportAgreement> existing = agreement.found();
        if (existing.isPresent() && organization.found().map(value -> !value.id().equals(existing.get().organizationId())).orElse(true)) {
            row.conflict("contractNumber", "Договор с этим ключом относится к другому вузу");
        }
        if (existing.isEmpty() && agreementKey.derived() && explicitInteractionId == null
                && row.target.productAgreementId() == null && !linked.isEmpty()) {
            String numbers = linked.stream()
                    .map(value -> "№ " + value.values().contractNumber())
                    .distinct()
                    .collect(Collectors.joining(", "));
            row.conflict("contractNumber", "У вуза уже есть договор по этому ПО (" + numbers + "); чтобы изменить его, "
                    + "укажите UUID договора для строки, чтобы добавить ещё один — UUID взаимодействия или ключ договора "
                    + "в разделе «Дополнительно»");
        }
        planInteraction(row, planning, organization, product, productName, existing, explicitInteractionId, linked);

        Optional<AgreementValues> current = existing.map(ImportAgreement::values);
        AgreementValues next = new AgreementValues(
                row.mapped("contractNumber") ? nullable(contractNumber) : current.map(AgreementValues::contractNumber).orElse(null),
                row.mapped("licenseSigned") ? licenseSigned : current.map(AgreementValues::licenseSigned).orElse(null),
                row.mapped("licenseExpiryYear") ? licenseExpiryYear : current.map(AgreementValues::licenseExpiryYear).orElse(null),
                row.mapped("transferStatus") ? transferStatus : current.map(AgreementValues::transferStatus).orElse(null)
        );
        row.values.put("contractNumber", valueOf(next.contractNumber()));
        row.values.put("licenseSigned", valueOf(next.licenseSigned()));
        row.values.put("licenseExpiryYear", valueOf(next.licenseExpiryYear()));
        row.values.put("transferStatus", valueOf(next.transferStatus()));
        row.compare(agreement.exists(), "contractNumber", current.map(value -> valueOf(value.contractNumber())).orElse(""),
                valueOf(next.contractNumber()));
        row.compare(agreement.exists(), "licenseSigned", current.map(value -> yesNo(value.licenseSigned())).orElse(""),
                yesNo(next.licenseSigned()));
        row.compare(agreement.exists(), "licenseExpiryYear", current.map(value -> valueOf(value.licenseExpiryYear())).orElse(""),
                valueOf(next.licenseExpiryYear()));
        row.compare(agreement.exists(), "transferStatus", current.map(value -> valueOf(value.transferStatus())).orElse(""),
                valueOf(next.transferStatus()));
        if (existing.isPresent() && product.found().map(value -> !value.id().equals(existing.get().productId())).orElse(true)) {
            row.compare(true, "agreementProduct", "другое ПО", productName);
        }
        row.claim("agreement", agreement.identity(), "contractNumber", next + "\n" + comment, "");
        if (!comment.isEmpty()) {
            String commentKey = "comment:" + CommandFingerprint.of(objectMapper, List.of(agreement.key(), comment));
            boolean imported = repository.hasImportEvent(commentKey);
            row.values.put("commentExternalKey", commentKey);
            row.compare(imported, "comment", comment, comment);
        }
    }

    private void planInteraction(
            PlannedRow row,
            AgreementPlanning planning,
            Resolved<ImportOrganization> organization,
            Resolved<ImportChildEntry> product,
            String productName,
            Optional<ImportAgreement> agreement,
            UUID explicitInteractionId,
            List<ImportAgreement> linked
    ) {
        if (agreement.isPresent()) {
            if (explicitInteractionId != null && !explicitInteractionId.equals(agreement.get().interactionId())) {
                row.conflict("interactionId", "Договор привязан к другому взаимодействию");
            }
            useInteraction(row, agreement.get().interactionId(), agreement.get().interactionTitle());
            return;
        }
        if (explicitInteractionId != null) {
            Optional<ImportInteraction> interaction = repository.findInteractionById(explicitInteractionId);
            if (interaction.isEmpty()) {
                row.error("interactionId", "Взаимодействие с указанным UUID не найдено");
            } else if (organization.found().map(value -> !value.id().equals(interaction.get().organizationId())).orElse(true)) {
                row.conflict("interactionId", "Взаимодействие относится к другому вузу");
            } else {
                useInteraction(row, explicitInteractionId, interaction.get().title());
            }
            return;
        }
        List<UUID> interactionIds = linked.stream().map(ImportAgreement::interactionId).distinct().toList();
        if (interactionIds.size() > 1) {
            row.conflict("interactionId", "У вуза несколько взаимодействий с этим ПО; укажите UUID взаимодействия для строки "
                    + "в разделе «Дополнительно»");
            return;
        }
        if (interactionIds.size() == 1) {
            useInteraction(row, interactionIds.getFirst(), linked.getFirst().interactionTitle());
            return;
        }
        row.values.put("interactionId", "");
        row.values.put("interactionTitle", productName);
        Integer plannedBy = row.hasErrors()
                ? null
                : planning.interactions().putIfAbsent(organization.identity() + "|" + product.identity(), row.rowNumber);
        row.compare(false, "interaction", "", plannedBy == null
                ? "Новое взаимодействие «" + productName + "» по базовому процессу"
                : "Взаимодействие, создаваемое строкой " + plannedBy);
    }

    private void useInteraction(PlannedRow row, UUID interactionId, String title) {
        row.values.put("interactionId", interactionId.toString());
        row.values.put("interactionTitle", title);
        row.compare(true, "interaction", title, title);
    }

    private static List<ImportAgreement> linkedAgreements(
            AgreementCatalogs catalogs,
            Resolved<ImportOrganization> organization,
            Resolved<ImportChildEntry> product
    ) {
        return organization.found().isPresent() && product.found().isPresent()
                ? catalogs.agreements().byNaturalKey(naturalKey(organization.found().get().id(), product.found().get().id().toString()))
                : List.of();
    }

    private static List<ImportAgreement> naturalAgreements(List<ImportAgreement> linked, String contractNumber) {
        List<ImportAgreement> sameContract = linked.stream()
                .filter(value -> normalized(value.values().contractNumber()).equals(normalized(contractNumber)))
                .toList();
        if (!sameContract.isEmpty() || contractNumber.isEmpty()) {
            return sameContract;
        }
        return linked.stream().filter(value -> normalized(value.values().contractNumber()).isEmpty()).toList();
    }

    private <T extends ImportKeyed> Resolved<T> resolve(
            PlannedRow row,
            String entity,
            String field,
            CatalogImportIndex<T> index,
            UUID explicitId,
            KeyChoice key,
            List<T> sameName,
            boolean uniqueName
    ) {
        Optional<T> found = match(row, field, index, explicitId, key, sameName, uniqueName);
        String finalKey = found.map(ImportKeyed::externalKey).orElse(key.value());
        row.values.put(entity + "Id", found.map(value -> value.id().toString()).orElse(""));
        row.values.put(entity + "ExternalKey", finalKey);
        row.expected(entity, found.map(ImportKeyed::version).orElse(-1));
        if (found.isPresent() && found.get().externalKey() == null) {
            row.compare(true, entity + "ExternalKey", "", finalKey);
        }
        return new Resolved<>(found, finalKey, found.map(value -> "id:" + value.id()).orElse("key:" + finalKey));
    }

    private <T extends ImportKeyed> Optional<T> match(
            PlannedRow row,
            String field,
            CatalogImportIndex<T> index,
            UUID explicitId,
            KeyChoice key,
            List<T> sameName,
            boolean uniqueName
    ) {
        Optional<T> explicit = index.byId(explicitId);
        if (explicitId != null && explicit.isEmpty()) {
            row.error(field, "Запись с указанным UUID не найдена");
            return Optional.empty();
        }
        Optional<T> byKey = index.byKey(key.value());
        if (explicit.isPresent() && byKey.isPresent() && !explicit.get().id().equals(byKey.get().id())) {
            row.conflict(field, "Ключ и указанный UUID относятся к разным записям");
            return Optional.empty();
        }
        Optional<T> found = explicit.or(() -> byKey);
        if (found.isPresent()) {
            if (uniqueName && sameName.stream().anyMatch(value -> !value.id().equals(found.get().id()))) {
                row.conflict(field, "Такое название уже есть у другой записи CRM");
            }
            return found;
        }
        List<T> candidates = sameName.stream().filter(value -> key.derived() || value.externalKey() == null).toList();
        if (candidates.isEmpty() && !sameName.isEmpty()) {
            row.conflict(field, "Запись с таким названием уже связана с ключом «" + sameName.getFirst().externalKey() + "»");
        } else if (candidates.size() > 1) {
            row.conflict(field, "Найдено несколько записей CRM с таким названием; укажите ключ или UUID");
        }
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static boolean agreementRequested(PlannedRow row) {
        return row.target.interactionId() != null || row.target.productAgreementId() != null
                || AGREEMENT_VALUE_FIELDS.stream().anyMatch(field -> !clean(row.values.get(field)).isEmpty());
    }

    private static void inheritManagers(List<PlannedRow> rows, AgreementPlanning planning) {
        for (PlannedRow row : rows) {
            if (row.managerPendingFor == null) {
                continue;
            }
            Set<ImportProfile> managers = planning.managers().getOrDefault(row.managerPendingFor, Set.of());
            if (managers.size() != 1) {
                row.error("managerName", "Для нового вуза укажите ФИО менеджера");
                continue;
            }
            ImportProfile manager = managers.iterator().next();
            row.values.put("managerProfileId", manager.id().toString());
            row.compare(false, "managerName", "", manager.displayName());
        }
    }

    private void resolveRowConflicts(List<PlannedRow> rows) {
        List<PlannedRow> candidates = rows.stream().filter(row -> !row.hasErrors()).toList();
        Map<String, PlannedRow> firstByClaim = new HashMap<>();
        for (PlannedRow row : candidates) {
            for (Map.Entry<String, Claim> entry : row.claims.entrySet()) {
                PlannedRow first = firstByClaim.putIfAbsent(entry.getKey(), row);
                if (first == null) {
                    continue;
                }
                Claim claim = entry.getValue();
                boolean same = first.claims.get(entry.getKey()).signature().equals(claim.signature());
                if (claim.description().isEmpty()) {
                    row.conflict(claim.field(), "Этот договор уже указан в строке " + first.rowNumber);
                    if (!same) {
                        first.conflict(claim.field(), "Строка " + row.rowNumber + " задаёт для этого договора другие значения");
                    }
                } else if (!same) {
                    row.conflict(claim.field(), "Противоречит строке " + first.rowNumber + ": " + claim.description());
                    first.conflict(claim.field(), "Противоречит строке " + row.rowNumber + ": " + claim.description());
                }
            }
        }
    }

    private void applyDirectionProgram(CatalogImportPlan plan, OffsetDateTime now) {
        UUID directionId = ensureCatalogEntry(DIRECTIONS, "direction", "directionName", plan, now);
        if (!plan.values().getOrDefault("programExternalKey", "").isEmpty()) {
            ensureChildEntry(PROGRAMS, "program", "programName", directionId, plan, now);
        }
    }

    private void applyAgreement(CatalogImportPlan plan, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        UUID organizationId = ensureOrganization(plan, actorProfileId, commandId, now);
        UUID vendorId = ensureCatalogEntry(VENDORS, "vendor", "vendorName", plan, now);
        UUID productId = ensureChildEntry(PRODUCTS, "product", "productName", vendorId, plan, now);
        if (!plan.values().getOrDefault("contactName", "").isEmpty()) {
            ensureContact(plan, organizationId, actorProfileId, now);
        }
        if (!plan.values().getOrDefault("agreementExternalKey", "").isEmpty()) {
            ensureProductAgreement(plan, organizationId, productId, actorProfileId, commandId, now);
        }
    }

    private UUID ensureCatalogEntry(String table, String entity, String nameField, CatalogImportPlan plan, OffsetDateTime now) {
        String key = plan.values().get(entity + "ExternalKey");
        String name = plan.values().get(nameField);
        UUID id = uuid(plan.values().get(entity + "Id"));
        Optional<ImportCatalogEntry> existing = repository.findCatalogEntry(table, id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            UUID createdId = UUID.randomUUID();
            repository.insertCatalogEntry(table, createdId, key, name, now);
            return createdId;
        }
        ImportCatalogEntry entry = existing.get();
        if (entry.name().equals(name) && entry.externalKey() != null) {
            return entry.id();
        }
        requireExpectedVersion(plan, entity, entry.version());
        if (!repository.updateCatalogEntry(table, entry.id(), key, name, entry.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(entry.version() + 1);
        }
        return entry.id();
    }

    private UUID ensureChildEntry(
            String table,
            String entity,
            String nameField,
            UUID parentId,
            CatalogImportPlan plan,
            OffsetDateTime now
    ) {
        String key = plan.values().get(entity + "ExternalKey");
        String name = plan.values().get(nameField);
        UUID id = uuid(plan.values().get(entity + "Id"));
        Optional<ImportChildEntry> existing = repository.findChildEntry(table, id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            UUID createdId = UUID.randomUUID();
            repository.insertChildEntry(table, createdId, key, parentId, name, now);
            return createdId;
        }
        ImportChildEntry entry = existing.get();
        if (entry.name().equals(name) && entry.parentId().equals(parentId) && entry.externalKey() != null) {
            return entry.id();
        }
        requireExpectedVersion(plan, entity, entry.version());
        if (!repository.updateChildEntry(table, entry.id(), key, parentId, name, entry.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(entry.version() + 1);
        }
        return entry.id();
    }

    private UUID ensureOrganization(CatalogImportPlan plan, UUID actorProfileId, UUID commandId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        String key = values.get("organizationExternalKey");
        String name = values.get("organizationName");
        String type = values.get("organizationType");
        UUID id = uuid(values.get("organizationId"));
        UUID managerId = uuid(values.get("managerProfileId"));
        ImportProfile manager = managerId == null ? null : repository.findActiveProfile(managerId)
                .filter(value -> value.role() == UserRole.USER && value.teamId() != null)
                .orElseThrow(() -> InteractionConflictException.catalogImportVersion(0));
        Optional<ImportOrganization> existing = id == null
                ? repository.findOrganizationByExternalKey(key)
                : repository.findOrganizationById(id);
        if (existing.isEmpty()) {
            requireCreatable(id);
            if (manager == null) {
                throw InteractionConflictException.catalogImportVersion(0);
            }
            UUID createdId = UUID.randomUUID();
            repository.insertOrganization(createdId, key, name, type, manager.teamId(), manager.id(), now);
            organizationAssignmentRepository.incrementAccessRevisions(null, manager.id());
            return createdId;
        }
        ImportOrganization organization = existing.get();
        boolean detailsChanged = !organization.name().equals(name) || !organization.type().equals(type)
                || organization.externalKey() == null;
        boolean ownerChanged = manager != null && !Objects.equals(organization.ownerManagerId(), manager.id());
        if (!detailsChanged && !ownerChanged) {
            return organization.id();
        }
        if (manager != null && !organization.teamId().equals(manager.teamId())) {
            throw InteractionConflictException.catalogImportVersion(organization.version());
        }
        requireExpectedVersion(plan, "organization", organization.version());
        int version = organization.version();
        if (detailsChanged) {
            if (!repository.updateOrganizationDetails(organization.id(), key, name, type, version, now)) {
                throw InteractionConflictException.catalogImportVersion(version + 1);
            }
            version++;
        }
        if (ownerChanged) {
            changeOwner(organization, manager, version, actorProfileId, commandId, now);
        }
        return organization.id();
    }

    private void changeOwner(
            ImportOrganization organization,
            ImportProfile manager,
            int version,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        if (!organizationAssignmentRepository.updateOwner(organization.id(), organization.teamId(), version, manager.id(), now)) {
            throw InteractionConflictException.catalogImportVersion(version + 1);
        }
        organizationAssignmentRepository.incrementAccessRevisions(organization.ownerManagerId(), manager.id());
        organizationAssignmentRepository.insertEvent(
                UUID.randomUUID(),
                organization.id(),
                commandId,
                organization.ownerManagerId(),
                displayName(organization.ownerManagerId()),
                manager.id(),
                displayName(manager.id()),
                actorProfileId,
                displayName(actorProfileId),
                "catalog-import:" + commandId,
                version + 1,
                now
        );
    }

    private String displayName(UUID profileId) {
        return profileId == null ? null : organizationAssignmentRepository.findDisplayName(profileId)
                .orElseThrow(() -> new IllegalStateException("CRM profile is unavailable for catalog import audit"));
    }

    private void ensureContact(CatalogImportPlan plan, UUID organizationId, UUID actorProfileId, OffsetDateTime now) {
        Map<String, String> values = plan.values();
        String key = values.get("contactExternalKey");
        UUID id = uuid(values.get("contactId"));
        ContactValues next = new ContactValues(
                values.get("contactName"),
                nullable(values.get("contactPosition")),
                nullable(values.get("contactEmail")),
                nullable(values.get("contactPhone"))
        );
        Optional<ImportContact> existing = repository.findContact(id, key);
        if (existing.isEmpty()) {
            requireCreatable(id);
            repository.insertContact(UUID.randomUUID(), key, organizationId, next, actorProfileId, now);
            return;
        }
        ImportContact contact = existing.get();
        if (!contact.organizationId().equals(organizationId)) {
            throw InteractionConflictException.catalogImportVersion(contact.version());
        }
        if (contact.values().equals(next) && contact.externalKey() != null) {
            return;
        }
        requireExpectedVersion(plan, "contact", contact.version());
        if (!repository.updateContact(contact.id(), key, next, contact.version(), now)) {
            throw InteractionConflictException.catalogImportVersion(contact.version() + 1);
        }
    }

    private void ensureProductAgreement(
            CatalogImportPlan plan,
            UUID organizationId,
            UUID productId,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        Map<String, String> values = plan.values();
        String key = values.get("agreementExternalKey");
        UUID id = uuid(values.get("agreementId"));
        AgreementValues next = new AgreementValues(
                nullable(values.get("contractNumber")),
                nullable(values.get("licenseSigned")) == null ? null : Boolean.valueOf(values.get("licenseSigned")),
                nullable(values.get("licenseExpiryYear")) == null ? null : Integer.valueOf(values.get("licenseExpiryYear")),
                nullable(values.get("transferStatus"))
        );
        Optional<ImportAgreement> existing = repository.findAgreement(id, key);
        UUID interactionId;
        if (existing.isEmpty()) {
            requireCreatable(id);
            interactionId = interactionFor(values, organizationId, productId, actorProfileId, commandId, now);
            repository.insertAgreement(UUID.randomUUID(), key, interactionId, productId, next, now);
        } else {
            ImportAgreement agreement = existing.get();
            interactionId = agreement.interactionId();
            if (!agreement.organizationId().equals(organizationId)) {
                throw InteractionConflictException.catalogImportVersion(agreement.version());
            }
            if (!agreement.productId().equals(productId) || !agreement.values().equals(next) || agreement.externalKey() == null) {
                requireExpectedVersion(plan, "agreement", agreement.version());
                if (!repository.updateAgreement(agreement.id(), key, productId, next, agreement.version(), now)) {
                    throw InteractionConflictException.catalogImportVersion(agreement.version() + 1);
                }
            }
        }
        String comment = values.getOrDefault("comment", "");
        if (!comment.isEmpty() && !repository.hasImportEvent(values.get("commentExternalKey"))) {
            ImportInteraction interaction = repository.findInteractionByIdForUpdate(interactionId)
                    .orElseThrow(() -> InteractionConflictException.catalogImportVersion(0));
            if (!repository.advanceInteractionVersion(interaction.id(), interaction.version(), now)) {
                throw InteractionConflictException.catalogImportVersion(interaction.version() + 1);
            }
            repository.insertCommentEvent(
                    UUID.randomUUID(), values.get("commentExternalKey"), commandId, interaction, comment, actorProfileId, now
            );
        }
    }

    private UUID interactionFor(
            Map<String, String> values,
            UUID organizationId,
            UUID productId,
            UUID actorProfileId,
            UUID commandId,
            OffsetDateTime now
    ) {
        UUID explicitId = uuid(values.get("interactionId"));
        if (explicitId != null) {
            repository.findInteractionById(explicitId)
                    .filter(interaction -> interaction.organizationId().equals(organizationId))
                    .orElseThrow(() -> InteractionConflictException.catalogImportVersion(0));
            return explicitId;
        }
        List<UUID> interactionIds = repository.findInteractionIdsWithProduct(organizationId, productId);
        if (interactionIds.size() > 1) {
            throw InteractionConflictException.catalogImportVersion(0);
        }
        if (interactionIds.size() == 1) {
            return interactionIds.getFirst();
        }
        UUID ownerManagerId = repository.findOrganizationById(organizationId)
                .map(ImportOrganization::ownerManagerId)
                .orElse(null);
        return interactionService.createImportedInteraction(
                organizationId, ownerManagerId, values.get("interactionTitle"), actorProfileId, commandId, now
        );
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

    private CatalogImportMapping normalizedMapping(CatalogImportMapping mapping) {
        if (mapping == null || mapping.columns() == null) {
            throw new InteractionValidationException("mapping", "Сопоставление столбцов обязательно");
        }
        Map<String, String> columns = new LinkedHashMap<>();
        mapping.columns().forEach((field, header) -> columns.put(
                requiredText(field, "mapping", 80), requiredText(header, "mapping", 255)
        ));
        Map<Integer, CatalogImportRowTarget> targets = new LinkedHashMap<>();
        if (mapping.rowTargets() != null) {
            mapping.rowTargets().forEach((rowNumber, target) -> targets.put(rowNumber, target == null ? EMPTY_TARGET : target));
        }
        Map<String, String> transferStatuses = new LinkedHashMap<>();
        if (mapping.transferStatuses() != null) {
            mapping.transferStatuses().forEach((source, target) -> transferStatuses.put(
                    requiredText(source, "transferStatuses", 160), requiredText(target, "transferStatuses", 160)
            ));
        }
        return new CatalogImportMapping(Map.copyOf(columns), Map.copyOf(targets), Map.copyOf(transferStatuses));
    }

    private void validateMapping(CatalogImportProfile profile, CatalogImportMapping mapping, List<String> headers) {
        boolean agreement = profile == CatalogImportProfile.AGREEMENT;
        Set<String> required = agreement ? AGREEMENT_REQUIRED_FIELDS : DIRECTION_PROGRAM_REQUIRED_FIELDS;
        Set<String> allowed = agreement ? AGREEMENT_MAPPING_FIELDS : DIRECTION_PROGRAM_MAPPING_FIELDS;
        for (String field : required) {
            if (!mapping.columns().containsKey(field)) {
                throw new InteractionValidationException(field, "Выберите столбец файла для обязательного поля");
            }
        }
        for (Map.Entry<String, String> entry : mapping.columns().entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new InteractionValidationException(entry.getKey(), "Поле не поддерживается выбранным профилем импорта");
            }
            if (!headers.contains(entry.getValue())) {
                throw new InteractionValidationException(entry.getKey(), "Столбца «" + entry.getValue() + "» нет на выбранном листе");
            }
        }
        for (Integer rowNumber : mapping.rowTargets().keySet()) {
            if (rowNumber == null || rowNumber < 2) {
                throw new InteractionValidationException("rowTargets", "Номера строк для явных UUID начинаются с 2");
            }
        }
    }

    private void inspectWorkbookFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AttachmentValidationException("file", "Выберите файл XLS или XLSX");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new AttachmentTooLargeException();
        }
        AttachmentUploadInspection inspection = attachmentContentValidator.inspect(file);
        String name = inspection.originalName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            throw new AttachmentValidationException("file", "Поддерживаются только книги XLS и XLSX");
        }
        AttachmentScanOutcome outcome;
        try (InputStream input = file.getInputStream()) {
            outcome = attachmentScanner.scan(input, inspection.sizeBytes());
        } catch (IOException exception) {
            throw new AttachmentValidationException("file", "Файл не удалось прочитать");
        }
        if (outcome == AttachmentScanOutcome.REJECTED) {
            throw new AttachmentValidationException("file", "Антивирусная проверка отклонила файл");
        }
        if (outcome != AttachmentScanOutcome.CLEAN) {
            throw new AttachmentValidationException("file", "Антивирусная проверка недоступна; повторите позже");
        }
    }

    private CatalogImportApplyRequest normalizedApplyRequest(CatalogImportApplyRequest request) {
        if (request == null || request.version() == null || request.version() < 0
                || request.confirmedRowIds() == null || request.confirmedRowIds().isEmpty()) {
            throw new InteractionValidationException("body", "Укажите версию предпросмотра и выбранные строки");
        }
        List<UUID> rowIds = new ArrayList<>(request.confirmedRowIds());
        if (rowIds.stream().anyMatch(Objects::isNull) || new LinkedHashSet<>(rowIds).size() != rowIds.size()) {
            throw new InteractionValidationException("confirmedRowIds", "Строки не должны повторяться");
        }
        rowIds.sort(Comparator.naturalOrder());
        return new CatalogImportApplyRequest(request.version(), List.copyOf(rowIds));
    }

    private void requireAdmin(CrmProfile profile) {
        if (profile == null || profile.role() != UserRole.ADMIN) {
            throw new CatalogImportAccessDeniedException();
        }
    }

    private static boolean canApply(CatalogImportRowStatus status) {
        return status == CatalogImportRowStatus.CREATE || status == CatalogImportRowStatus.UPDATE
                || status == CatalogImportRowStatus.UNCHANGED;
    }

    private CatalogImportApplyResponse readApplyResponse(String json) {
        try {
            return objectMapper.readValue(json, CatalogImportApplyResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog import idempotency response is invalid", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog import response cannot be written", exception);
        }
    }

    private KeyChoice keyChoice(PlannedRow row, String field, String prefix, String... parts) {
        String explicit = optional(row, field, MAX_KEY_LENGTH);
        if (!explicit.isEmpty()) {
            return new KeyChoice(explicit, false);
        }
        String joined = Arrays.stream(parts).map(CatalogImportIndex::normalized).collect(Collectors.joining("|"));
        String key = prefix + ":" + joined;
        return new KeyChoice(key.length() <= MAX_KEY_LENGTH ? key : prefix + ":" + CommandFingerprint.of(objectMapper, joined), true);
    }

    private static String organizationType(PlannedRow row, Optional<ImportOrganization> organization) {
        String fallback = organization.map(ImportOrganization::type).orElse("UNIVERSITY");
        return switch (normalized(optional(row, "organizationType", 16))) {
            case "" -> fallback;
            case "university", "вуз" -> "UNIVERSITY";
            case "school", "школа" -> "SCHOOL";
            default -> {
                row.error("organizationType", "Укажите «вуз» или «школа»");
                yield fallback;
            }
        };
    }

    private static String typeLabel(String type) {
        return "SCHOOL".equals(type) ? "школа" : "вуз";
    }

    private static Boolean licenseSigned(PlannedRow row) {
        String value = normalized(optional(row, "licenseSigned", 40));
        return switch (value) {
            case "" -> null;
            case "да", "true", "1", "подписана", "подписано", "подписан" -> true;
            case "нет", "false", "0", "не подписана", "не подписано", "не подписан" -> false;
            default -> {
                if (date(value) != null) {
                    yield true;
                }
                row.error("licenseSigned", "Укажите «да», «нет» или дату подписания");
                yield null;
            }
        };
    }

    private static Integer licenseExpiryYear(PlannedRow row) {
        String value = optional(row, "licenseExpiryYear", 40);
        if (value.isEmpty()) {
            return null;
        }
        LocalDate date = date(value);
        Integer year = value.matches("\\d{4}") ? Integer.valueOf(value) : date == null ? null : date.getYear();
        if (year == null || year < 1900 || year > 3000) {
            row.error("licenseExpiryYear", "Укажите год (например, 2027) или дату окончания");
            return null;
        }
        return year;
    }

    private static LocalDate date(String value) {
        DateTimeFormatter format = value.contains(".") ? RUSSIAN_DATE : DateTimeFormatter.ISO_LOCAL_DATE;
        try {
            return LocalDate.parse(value, format);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String transferStatus(PlannedRow row, AgreementCatalogs catalogs) {
        String value = optional(row, "transferStatus", 160);
        if (value.isEmpty()) {
            return null;
        }
        String key = normalized(value);
        String mapped = catalogs.statusDictionary().get(key);
        if (mapped != null) {
            return clean(mapped);
        }
        String known = catalogs.knownStatuses().get(key);
        if (known != null) {
            return known;
        }
        if (catalogs.statusDictionary().isEmpty()) {
            return value;
        }
        row.error("transferStatus", "Значения «" + value + "» нет в словаре статусов и среди статусов CRM");
        return null;
    }

    private static String comment(PlannedRow row) {
        String value = row.values.getOrDefault("comment", "").trim();
        if (value.length() > 4_000) {
            row.error("comment", "Длиннее 4000 символов");
        }
        row.values.put("comment", value);
        return value;
    }

    private static String mappedOrExisting(PlannedRow row, String field, int maxLength, Optional<String> existing) {
        return row.mapped(field) ? nullable(optional(row, field, maxLength)) : existing.orElse(null);
    }

    private static String contactDetails(ContactValues values) {
        return Stream.of(values.position(), values.email(), values.phone())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    private static String yesNo(Boolean value) {
        return value == null ? "" : value ? "да" : "нет";
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String valueOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InteractionValidationException(field, "Заполните значение");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InteractionValidationException(field, "Длиннее " + maxLength + " символов");
        }
        return trimmed;
    }

    private static String required(PlannedRow row, String field, String value) {
        if (value.isEmpty()) {
            row.error(field, "Заполните значение");
        }
        return value;
    }

    private static String required(PlannedRow row, String field, int maxLength) {
        return required(row, field, optional(row, field, maxLength));
    }

    private static String optional(PlannedRow row, String field, int maxLength) {
        String value = clean(row.values.get(field));
        if (value.length() > maxLength) {
            row.error(field, "Длиннее " + maxLength + " символов");
        }
        row.values.put(field, value);
        return value;
    }

    private static UUID uuid(PlannedRow row, String field) {
        String value = optional(row, field, 36);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            row.error(field, "Укажите UUID в формате 8-4-4-4-12");
            return null;
        }
    }

    private CatalogImportView view(CatalogImportStored stored) {
        List<CatalogImportRowView> rows = stored.rows().stream().map(row -> new CatalogImportRowView(
                row.id(),
                row.sheetName(),
                row.rowNumber(),
                row.status(),
                row.fieldErrors(),
                row.plan().oldValues(),
                row.plan().newValues(),
                row.applied()
        )).toList();
        return new CatalogImportView(
                stored.id(),
                stored.profile(),
                stored.status(),
                stored.version(),
                rows,
                stored.createdAt(),
                stored.updatedAt()
        );
    }

    private static final class PlannedRow {
        private final UUID id = UUID.randomUUID();
        private final String sheetName;
        private final int rowNumber;
        private final CatalogImportMapping mapping;
        private final CatalogImportRowTarget target;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>();
        private final Map<String, String> oldValues = new LinkedHashMap<>();
        private final Map<String, String> newValues = new LinkedHashMap<>();
        private final Map<String, Integer> expectedVersions = new LinkedHashMap<>();
        private final Map<String, Claim> claims = new LinkedHashMap<>();
        private String managerPendingFor;
        private boolean created;
        private boolean updated;
        private boolean conflicted;

        private PlannedRow(CatalogImportWorkbookRow row, String sheetName, CatalogImportMapping mapping) {
            this.sheetName = sheetName;
            this.rowNumber = row.rowNumber();
            this.mapping = mapping;
            this.target = mapping.rowTargets().getOrDefault(row.rowNumber(), EMPTY_TARGET);
            for (Map.Entry<String, String> entry : mapping.columns().entrySet()) {
                values.put(entry.getKey(), row.values().getOrDefault(entry.getValue(), ""));
                String cellError = row.fieldErrors().get(entry.getValue());
                if (cellError != null) {
                    errors.put(entry.getKey(), cellError);
                }
            }
        }

        private boolean mapped(String field) {
            return mapping.columns().containsKey(field);
        }

        private void error(String field, String message) {
            errors.putIfAbsent(field, message);
        }

        private void conflict(String field, String message) {
            conflicted = true;
            errors.putIfAbsent(field, message);
        }

        private void compare(boolean exists, String field, String oldValue, String newValue) {
            oldValues.put(field, exists ? valueOf(oldValue) : "");
            newValues.put(field, valueOf(newValue));
            if (!exists) {
                created = true;
            } else if (!valueOf(oldValue).equals(valueOf(newValue))) {
                updated = true;
            }
        }

        private void expected(String entity, int version) {
            expectedVersions.put(entity, version);
        }

        private void claim(String entity, String identity, String field, String signature, String description) {
            claims.put(entity + "|" + identity, new Claim(field, signature, description));
        }

        private boolean hasErrors() {
            return !errors.isEmpty();
        }

        private CatalogImportRowStatus status() {
            if (conflicted) {
                return CatalogImportRowStatus.CONFLICT;
            }
            if (!errors.isEmpty()) {
                return CatalogImportRowStatus.INVALID;
            }
            return created ? CatalogImportRowStatus.CREATE
                    : updated ? CatalogImportRowStatus.UPDATE : CatalogImportRowStatus.UNCHANGED;
        }

        private CatalogImportStoredRow stored() {
            CatalogImportPlan plan = new CatalogImportPlan(
                    Map.copyOf(values),
                    target,
                    Map.copyOf(expectedVersions),
                    Map.copyOf(oldValues),
                    Map.copyOf(newValues)
            );
            return new CatalogImportStoredRow(id, sheetName, rowNumber, status(), plan, Map.copyOf(errors), false);
        }
    }

    private record KeyChoice(String value, boolean derived) {
    }

    private record Resolved<T extends ImportKeyed>(Optional<T> found, String key, String identity) {
        private boolean exists() {
            return found.isPresent();
        }
    }

    private record Claim(String field, String signature, String description) {
    }

    private record AgreementCatalogs(
            CatalogImportIndex<ImportOrganization> organizations,
            CatalogImportIndex<ImportCatalogEntry> vendors,
            CatalogImportIndex<ImportChildEntry> products,
            CatalogImportIndex<ImportContact> contacts,
            CatalogImportIndex<ImportAgreement> agreements,
            Map<String, List<ImportProfile>> managers,
            Map<String, String> knownStatuses,
            Map<String, String> statusDictionary
    ) {
    }

    private record AgreementPlanning(
            Map<String, Integer> interactions,
            Set<String> agreementPairs,
            Map<String, Set<ImportProfile>> managers
    ) {
    }

    private record DirectionCatalogs(
            CatalogImportIndex<ImportCatalogEntry> directions,
            CatalogImportIndex<ImportChildEntry> programs
    ) {
    }

    private record CatalogImportApplyFingerprint(UUID importId, CatalogImportApplyRequest request) {
    }
}
