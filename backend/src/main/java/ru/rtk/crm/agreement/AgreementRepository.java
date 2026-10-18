package ru.rtk.crm.agreement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.rtk.crm.agreement.AgreementModels.Activity;
import ru.rtk.crm.agreement.AgreementModels.ActivityKind;
import ru.rtk.crm.agreement.AgreementModels.ActivityStatus;
import ru.rtk.crm.agreement.AgreementModels.Agreement;
import ru.rtk.crm.agreement.AgreementModels.AgreementFile;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Confirmation;
import ru.rtk.crm.agreement.AgreementModels.ConfirmationQuery;
import ru.rtk.crm.agreement.AgreementModels.LinkedAttachment;
import ru.rtk.crm.agreement.AgreementModels.LinkedInteraction;
import ru.rtk.crm.agreement.AgreementModels.Party;
import ru.rtk.crm.agreement.AgreementModels.PeriodTransition;
import ru.rtk.crm.agreement.AgreementModels.PlanKind;
import ru.rtk.crm.agreement.AgreementModels.RegistryQuery;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.agreement.AgreementModels.Responsible;
import ru.rtk.crm.agreement.AgreementModels.SignerContact;
import ru.rtk.crm.agreement.AgreementModels.StatusEntry;
import ru.rtk.crm.agreement.AgreementModels.StatusEvent;
import ru.rtk.crm.agreement.AgreementModels.Supplement;
import ru.rtk.crm.agreement.AgreementModels.SupplementStatus;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;

@Repository
public class AgreementRepository {
    public static final String ACTIVITY_START =
            "COALESCE(ac.actual_start, ac.planned_start, ac.actual_end, ac.planned_end, ag.concluded_on)";
    public static final String ACTIVITY_END =
            "COALESCE(ac.actual_end, ac.planned_end, ac.actual_start, ac.planned_start, ag.valid_until)";

    private static final String ATTACHMENT_COLUMNS = """
            a.id AS attachment_id, a.original_name, a.interaction_id, i.title AS interaction_title,
            st.name AS stage_name, a.status AS attachment_status, a.created_at AS attachment_created_at
            """;
    private static final String ATTACHMENT_FROM = """
            FROM attachments a
            JOIN interactions i ON i.id = a.interaction_id
            JOIN interaction_stages st ON st.id = a.stage_id
            """;

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    // Файл можно скачать только в статусе CLEAN. Версия, взятая из вложения работы, хранится ссылкой на него,
    // поэтому дополнительно требуется, чтобы вложение не было удалено.
    private static final String DOWNLOADABLE = """
            CASE WHEN f.storage_key IS NOT NULL AND f.status = 'CLEAN' THEN TRUE
                 WHEN f.storage_key IS NULL AND a.id IS NOT NULL AND a.deleted_at IS NULL AND a.status = 'CLEAN' THEN TRUE
                 ELSE FALSE END""";

    private final JdbcClient jdbcClient;
    private final AgreementStages stages;
    private final AgreementApproval approval;

    public AgreementRepository(JdbcClient jdbcClient, AgreementStages stages, AgreementApproval approval) {
        this.jdbcClient = jdbcClient;
        this.stages = stages;
        this.approval = approval;
    }

    public List<ActivityKind> findKinds(boolean includeArchived) {
        return jdbcClient.sql("""
                SELECT id, name, sort_order, archived, version
                FROM agreement_activity_kinds
                %s
                ORDER BY sort_order, name, id
                """.formatted(includeArchived ? "" : "WHERE archived = FALSE"))
                .query(this::mapKind)
                .list();
    }

    public Optional<ActivityKind> findKind(UUID id) {
        return jdbcClient.sql("SELECT id, name, sort_order, archived, version FROM agreement_activity_kinds WHERE id = :id")
                .param("id", id)
                .query(this::mapKind)
                .optional();
    }

    public void insertKind(UUID id, String name, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_activity_kinds (id, name, sort_order, archived, version, created_at, updated_at)
                SELECT :id, :name, COALESCE(MAX(sort_order), 0) + 10, FALSE, 0, :now, :now
                FROM agreement_activity_kinds
                """)
                .param("id", id)
                .param("name", name)
                .param("now", now)
                .update();
    }

    public int updateKind(UUID id, int version, String name, boolean archived, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activity_kinds
                SET name = :name, archived = :archived, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("name", name)
                .param("archived", archived)
                .param("now", now)
                .update();
    }

    public List<AgreementSummary> findSummaries(UUID organizationId) {
        return summaries("ag.organization_id = :id", organizationId);
    }

    public List<AgreementSummary> findInteractionSummaries(UUID interactionId) {
        return summaries("ag.id IN (SELECT agreement_id FROM agreement_interactions WHERE interaction_id = :id)", interactionId);
    }

    private List<AgreementSummary> summaries(String condition, UUID id) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, ag.number, ag.concluded_on, ag.valid_until, ag.sent_on, ag.planned_kind, ag.planned_on,
                       ag.status, ag.status_changed_at, ag.version,
                       (SELECT COUNT(*) FROM agreement_activities ac WHERE ac.agreement_id = ag.id) AS activity_count,
                       (SELECT COUNT(*) FROM agreement_activity_attachments aa
                        JOIN agreement_activities ac ON ac.id = aa.activity_id
                        JOIN attachments ca ON ca.id = aa.attachment_id
                        WHERE ac.agreement_id = ag.id AND ca.deleted_at IS NULL) AS confirmation_count
                FROM agreements ag
                WHERE %s
                ORDER BY ag.concluded_on DESC NULLS LAST, ag.number, ag.id
                """.formatted(condition))
                .param("id", id)
                .query((resultSet, rowNumber) -> {
                    AgreementStatus status = AgreementStatus.valueOf(resultSet.getString("status"));
                    return new AgreementSummary(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("number"),
                            resultSet.getObject("concluded_on", LocalDate.class),
                            resultSet.getObject("valid_until", LocalDate.class),
                            resultSet.getObject("sent_on", LocalDate.class),
                            planKind(resultSet.getString("planned_kind")),
                            resultSet.getObject("planned_on", LocalDate.class),
                            status,
                            resultSet.getObject("status_changed_at", OffsetDateTime.class),
                            resultSet.getInt("activity_count"),
                            resultSet.getInt("confirmation_count"),
                            resultSet.getInt("version"),
                            status.transitions()
                    );
                })
                .list();
    }

    public Optional<AgreementRow> findAgreementRow(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT id, organization_id, number, status, concluded_on, valid_until, sent_on, version, planned_kind, planned_on,
                       university_signer_id, school_signer_id
                FROM agreements WHERE id = :id
                """)
                .param("id", agreementId)
                .query((resultSet, rowNumber) -> new AgreementRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("number"),
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getObject("sent_on", LocalDate.class),
                        resultSet.getInt("version"),
                        planKind(resultSet.getString("planned_kind")),
                        resultSet.getObject("planned_on", LocalDate.class),
                        resultSet.getObject("university_signer_id", UUID.class),
                        resultSet.getObject("school_signer_id", UUID.class)
                ))
                .optional();
    }

    // Блокировка строки договора сериализует добавление версий файла: номер версии берётся как MAX + 1, и без
    // блокировки два параллельных запроса получили бы один номер.
    public void lockAgreement(UUID agreementId) {
        jdbcClient.sql("SELECT id FROM agreements WHERE id = :id FOR UPDATE")
                .param("id", agreementId)
                .query(UUID.class)
                .optional();
    }

    public Optional<Agreement> findAgreement(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, o.name AS organization_name, ag.number, ag.concluded_on, ag.valid_until, ag.sent_on,
                       ag.planned_kind, ag.planned_on, ag.parties, ag.status, ag.status_changed_at, ag.version, ag.created_at,
                       ag.updated_at, ag.university_signer_id, us.name AS university_signer_name, ag.school_signer_id,
                       ss.display_name AS school_signer_name
                FROM agreements ag
                JOIN organizations o ON o.id = ag.organization_id
                LEFT JOIN contacts us ON us.id = ag.university_signer_id
                LEFT JOIN crm_user_profiles ss ON ss.id = ag.school_signer_id
                WHERE ag.id = :id
                """)
                .param("id", agreementId)
                .query((resultSet, rowNumber) -> new AgreementHeader(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("number"),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class),
                        resultSet.getObject("sent_on", LocalDate.class),
                        planKind(resultSet.getString("planned_kind")),
                        resultSet.getObject("planned_on", LocalDate.class),
                        resultSet.getString("parties"),
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("status_changed_at", OffsetDateTime.class),
                        party(resultSet.getObject("university_signer_id", UUID.class), resultSet.getString("university_signer_name")),
                        party(resultSet.getObject("school_signer_id", UUID.class), resultSet.getString("school_signer_name")),
                        resultSet.getInt("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional()
                .map(header -> {
                    List<AgreementFile> files = findFiles(header.id());
                    List<StatusEvent> history = findHistory(header.id());
                    UUID signedId = signedFileId(files, history);
                    List<AgreementFile> own = files.stream()
                            .filter(file -> file.supplementId() == null)
                            .map(file -> file.id().equals(signedId) ? file.markSigned() : file)
                            .toList();
                    return new Agreement(
                            header.id(),
                            header.organizationId(),
                            header.organizationName(),
                            header.number(),
                            header.concludedOn(),
                            header.validUntil(),
                            header.sentOn(),
                            header.plannedKind(),
                            header.plannedOn(),
                            header.parties(),
                            header.status(),
                            header.statusChangedAt(),
                            header.universitySigner(),
                            header.schoolSigner(),
                            findLinkedInteractions(header.id()),
                            findSubject(header.id(), SubjectLink.PROGRAMS),
                            findSubject(header.id(), SubjectLink.PRODUCTS),
                            findSubject(header.id(), SubjectLink.DIRECTIONS),
                            current(own),
                            own,
                            findSupplements(header.id(), files),
                            history,
                            header.status().transitions(),
                            header.version(),
                            header.createdAt(),
                            header.updatedAt(),
                            findActivities(header.id()),
                            approval.state(header.status(), header.statusChangedAt(), header.sentOn(),
                                    history.reversed().stream().map(event -> new StatusEntry(event.toStatus(), event.occurredAt())).toList(),
                                    LocalDate.now(ZONE))
                    );
                });
    }

    public void insertAgreement(UUID id, UUID organizationId, AgreementValues values, AgreementStatus status, UUID createdBy,
                                OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreements (
                    id, organization_id, number, concluded_on, valid_until, sent_on, planned_kind, planned_on, planned_base_until,
                    parties, status, status_changed_at, university_signer_id, school_signer_id, version, created_by, created_at,
                    updated_at
                ) VALUES (
                    :id, :organizationId, :number, :concludedOn, :validUntil, :sentOn, :plannedKind, :plannedOn, :validUntil,
                    :parties, :status, :now, :universitySignerId, :schoolSignerId, 0, :createdBy, :now, :now
                )
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .params(values.parameters())
                .param("status", status.name())
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public int updateAgreement(UUID id, int version, AgreementValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreements
                SET number = :number, concluded_on = :concludedOn, valid_until = :validUntil, sent_on = :sentOn, parties = :parties,
                    -- База плана продления: срок действия на момент, когда план был задан или изменён. Продление считается
                    -- выполненным, когда срок стал позже этой базы (SigningPlanService.row); при неизменном плане база остаётся.
                    planned_base_until = CASE
                        WHEN planned_kind IS DISTINCT FROM CAST(:plannedKind AS VARCHAR(16))
                             OR planned_on IS DISTINCT FROM CAST(:plannedOn AS DATE) THEN CAST(:validUntil AS DATE)
                        ELSE planned_base_until END,
                    planned_kind = :plannedKind, planned_on = :plannedOn,
                    university_signer_id = :universitySignerId, school_signer_id = :schoolSignerId,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public int updateStatus(
            UUID id,
            int version,
            AgreementStatus status,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            OffsetDateTime now
    ) {
        return jdbcClient.sql("""
                UPDATE agreements
                SET status = :status, concluded_on = :concludedOn, valid_until = :validUntil, sent_on = :sentOn,
                    status_changed_at = :now, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .param("status", status.name())
                .param("concludedOn", concludedOn)
                .param("validUntil", validUntil)
                .param("sentOn", sentOn)
                .param("now", now)
                .update();
    }

    public void insertStatusEvent(
            UUID agreementId,
            AgreementStatus fromStatus,
            AgreementStatus toStatus,
            String comment,
            UUID actorProfileId,
            UUID interactionId,
            OffsetDateTime now
    ) {
        // История читается по времени, а id случайный: два перехода в одну миллисекунду встали бы в случайном
        // порядке. Переход пишется под блокировкой договора, поэтому время события держим строго больше прежнего.
        OffsetDateTime last = jdbcClient.sql("SELECT MAX(occurred_at) FROM agreement_status_events WHERE agreement_id = :agreementId")
                .param("agreementId", agreementId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        // Сравнивать нужно то, что будет записано: база хранит микросекунды и округляет остаток, а часы Windows дают
        // сотни наносекунд. Без усечения «позже на 0,3 мкс» после округления становилось тем же временем.
        OffsetDateTime candidate = now.truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime occurredAt = last != null && !candidate.isAfter(last) ? last.plus(1, ChronoUnit.MICROS) : candidate;
        jdbcClient.sql("""
                INSERT INTO agreement_status_events (
                    id, agreement_id, from_status, to_status, comment, actor_profile_id, interaction_id, occurred_at
                ) VALUES (:id, :agreementId, :fromStatus, :toStatus, :comment, :actorProfileId, :interactionId, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("agreementId", agreementId)
                .param("fromStatus", fromStatus == null ? null : fromStatus.name())
                .param("toStatus", toStatus.name())
                .param("comment", comment)
                .param("actorProfileId", actorProfileId)
                .param("interactionId", interactionId)
                .param("now", occurredAt)
                .update();
    }

    public void replaceLinks(UUID agreementId, SubjectLink link, List<UUID> ids) {
        jdbcClient.sql("DELETE FROM " + link.table() + " WHERE agreement_id = :agreementId")
                .param("agreementId", agreementId)
                .update();
        for (UUID id : ids) {
            jdbcClient.sql("INSERT INTO " + link.table() + " (agreement_id, " + link.column() + ") VALUES (:agreementId, :id)")
                    .param("agreementId", agreementId)
                    .param("id", id)
                    .update();
        }
    }

    public void replaceInteractions(UUID agreementId, List<UUID> interactionIds) {
        jdbcClient.sql("DELETE FROM agreement_interactions WHERE agreement_id = :agreementId")
                .param("agreementId", agreementId)
                .update();
        for (UUID interactionId : interactionIds) {
            jdbcClient.sql("INSERT INTO agreement_interactions (agreement_id, interaction_id) VALUES (:agreementId, :interactionId)")
                    .param("agreementId", agreementId)
                    .param("interactionId", interactionId)
                    .update();
        }
    }

    public Optional<UUID> findInteractionOrganization(UUID interactionId) {
        return jdbcClient.sql("SELECT organization_id FROM interactions WHERE id = :id")
                .param("id", interactionId)
                .query(UUID.class)
                .optional();
    }

    public Set<UUID> findExisting(SubjectLink link, List<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("SELECT id FROM " + link.catalog() + " WHERE id IN (:ids)")
                .param("ids", ids)
                .query(UUID.class)
                .list());
    }

    public List<Party> findActiveCatalog(SubjectLink link) {
        return jdbcClient.sql("SELECT id, name FROM " + link.catalog() + " WHERE archived = FALSE ORDER BY name, id")
                .query((resultSet, rowNumber) -> new Party(resultSet.getObject("id", UUID.class), resultSet.getString("name")))
                .list();
    }

    public List<LinkedInteraction> findLinkedInteractions(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT i.id, i.title
                FROM agreement_interactions ai
                JOIN interactions i ON i.id = ai.interaction_id
                WHERE ai.agreement_id = :agreementId
                ORDER BY i.title, i.id
                """)
                .param("agreementId", agreementId)
                .query((resultSet, rowNumber) -> new LinkedInteraction(resultSet.getObject("id", UUID.class), resultSet.getString("title")))
                .list();
    }

    public List<Party> findSubject(UUID agreementId, SubjectLink link) {
        return jdbcClient.sql("""
                SELECT c.id, c.name
                FROM %s x
                JOIN %s c ON c.id = x.%s
                WHERE x.agreement_id = :agreementId
                ORDER BY c.name, c.id
                """.formatted(link.table(), link.catalog(), link.column()))
                .param("agreementId", agreementId)
                .query((resultSet, rowNumber) -> new Party(resultSet.getObject("id", UUID.class), resultSet.getString("name")))
                .list();
    }

    public Map<UUID, List<String>> findSubjectNames(List<UUID> agreementIds) {
        Map<UUID, List<String>> names = new HashMap<>();
        if (agreementIds.isEmpty()) {
            return names;
        }
        for (SubjectLink link : SubjectLink.values()) {
            jdbcClient.sql("""
                    SELECT x.agreement_id, c.name
                    FROM %s x
                    JOIN %s c ON c.id = x.%s
                    WHERE x.agreement_id IN (:ids)
                    ORDER BY c.name, c.id
                    """.formatted(link.table(), link.catalog(), link.column()))
                    .param("ids", agreementIds)
                    .query((ResultSet resultSet) -> {
                        names.computeIfAbsent(resultSet.getObject("agreement_id", UUID.class), key -> new ArrayList<>())
                                .add(resultSet.getString("name"));
                    });
        }
        return names;
    }

    public List<SignerContact> findSignerContacts(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, name, position, decision_role
                FROM contacts
                WHERE organization_id = :organizationId AND inactive = FALSE
                ORDER BY CASE WHEN decision_role = 'SIGNATORY' THEN 0 ELSE 1 END, name, id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new SignerContact(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getString("position"),
                        "SIGNATORY".equals(resultSet.getString("decision_role"))
                ))
                .list();
    }

    public boolean isActiveContact(UUID organizationId, UUID contactId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM contacts WHERE id = :id AND organization_id = :organizationId AND inactive = FALSE
                """)
                .param("id", contactId)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single() > 0;
    }

    public List<Responsible> findSchoolSigners(UUID teamId) {
        return jdbcClient.sql("""
                SELECT id, display_name FROM crm_user_profiles
                WHERE active = TRUE AND (role = 'MANAGEMENT' OR (role IN ('USER', 'LEADER') AND team_id = :teamId))
                ORDER BY display_name, id
                """)
                .param("teamId", teamId)
                .query((resultSet, rowNumber) -> new Responsible(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .list();
    }

    public List<AgreementFile> findFiles(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT f.id, f.agreement_id, f.supplement_id, f.version_number, f.original_name, f.media_type, f.size_bytes, f.status,
                       f.comment, p.display_name AS created_by_name, f.created_at, i.title AS interaction_title,
                       %s AS downloadable
                FROM agreement_files f
                LEFT JOIN crm_user_profiles p ON p.id = f.created_by
                LEFT JOIN attachments a ON a.id = f.attachment_id
                LEFT JOIN interactions i ON i.id = a.interaction_id
                WHERE f.agreement_id = :agreementId
                ORDER BY f.version_number DESC, f.created_at DESC, f.id
                """.formatted(DOWNLOADABLE))
                .param("agreementId", agreementId)
                .query((resultSet, rowNumber) -> new AgreementFile(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getObject("supplement_id", UUID.class),
                        resultSet.getInt("version_number"),
                        resultSet.getString("original_name"),
                        resultSet.getString("media_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("status"),
                        resultSet.getBoolean("downloadable"),
                        resultSet.getString("comment"),
                        resultSet.getString("created_by_name"),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getString("interaction_title"),
                        false
                ))
                .list();
    }

    // Подписанная версия — последняя скачиваемая версия файла самого договора, загруженная до перехода в «Действует».
    // Возврат из «Завершён» в «Действует» договор заново не подписывает, поэтому такой переход не учитывается.
    public static UUID signedFileId(List<AgreementFile> files, List<StatusEvent> history) {
        OffsetDateTime signedAt = history.stream()
                .filter(event -> event.toStatus() == AgreementStatus.ACTIVE && event.fromStatus() != AgreementStatus.COMPLETED)
                .map(StatusEvent::occurredAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (signedAt == null) {
            return null;
        }
        return files.stream()
                .filter(file -> file.supplementId() == null && file.downloadable() && !file.createdAt().isAfter(signedAt))
                .max(Comparator.comparingInt(AgreementFile::versionNumber))
                .map(AgreementFile::id)
                .orElse(null);
    }

    public UUID findSignedFileId(UUID agreementId) {
        return signedFileId(findFiles(agreementId), findHistory(agreementId));
    }

    private Map<UUID, List<StatusEntry>> findStatusEntries(List<UUID> agreementIds) {
        Map<UUID, List<StatusEntry>> entries = new HashMap<>();
        if (agreementIds.isEmpty()) {
            return entries;
        }
        jdbcClient.sql("""
                SELECT agreement_id, to_status, occurred_at
                FROM agreement_status_events
                WHERE agreement_id IN (:ids)
                ORDER BY occurred_at, id
                """)
                .param("ids", agreementIds)
                .query((ResultSet resultSet) -> {
                    entries.computeIfAbsent(resultSet.getObject("agreement_id", UUID.class), id -> new ArrayList<>())
                            .add(new StatusEntry(AgreementStatus.valueOf(resultSet.getString("to_status")),
                                    resultSet.getObject("occurred_at", OffsetDateTime.class)));
                });
        return entries;
    }

    private Approval approval(UUID agreementId, AgreementStatus status, OffsetDateTime statusChangedAt, LocalDate sentOn,
                              Map<UUID, List<StatusEntry>> entries, LocalDate today) {
        return approval.state(status, statusChangedAt, sentOn, entries.getOrDefault(agreementId, List.of()), today);
    }

    public Optional<AgreementFile> findFile(UUID fileId) {
        UUID agreementId = jdbcClient.sql("SELECT agreement_id FROM agreement_files WHERE id = :id")
                .param("id", fileId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        return agreementId == null
                ? Optional.empty()
                : findFiles(agreementId).stream().filter(file -> file.id().equals(fileId)).findFirst();
    }

    public Optional<StoredFile> findStoredFile(UUID fileId) {
        return jdbcClient.sql("""
                SELECT f.id, f.agreement_id, f.original_name, f.media_type, f.size_bytes,
                       COALESCE(f.storage_key, a.storage_key) AS storage_key, %s AS downloadable
                FROM agreement_files f
                LEFT JOIN attachments a ON a.id = f.attachment_id
                WHERE f.id = :id
                """.formatted(DOWNLOADABLE))
                .param("id", fileId)
                .query((resultSet, rowNumber) -> new StoredFile(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getString("original_name"),
                        resultSet.getString("media_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getObject("storage_key", UUID.class),
                        resultSet.getBoolean("downloadable")
                ))
                .optional();
    }

    public int nextFileVersion(UUID agreementId, UUID supplementId) {
        String condition = supplementId == null ? "supplement_id IS NULL" : "supplement_id = :supplementId";
        var statement = jdbcClient.sql("""
                SELECT COALESCE(MAX(version_number), 0) + 1 FROM agreement_files
                WHERE agreement_id = :agreementId AND %s
                """.formatted(condition))
                .param("agreementId", agreementId);
        if (supplementId != null) {
            statement = statement.param("supplementId", supplementId);
        }
        return statement.query(Integer.class).single();
    }

    public boolean hasAttachmentVersion(UUID agreementId, UUID attachmentId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM agreement_files
                WHERE agreement_id = :agreementId AND supplement_id IS NULL AND attachment_id = :attachmentId
                """)
                .param("agreementId", agreementId)
                .param("attachmentId", attachmentId)
                .query(Long.class)
                .single() > 0;
    }

    public void insertFile(NewFile file) {
        jdbcClient.sql("""
                INSERT INTO agreement_files (
                    id, agreement_id, supplement_id, version_number, attachment_id, storage_key, original_name, media_type,
                    size_bytes, checksum, status, comment, created_by, created_at
                ) VALUES (
                    :id, :agreementId, :supplementId, :versionNumber, :attachmentId, :storageKey, :originalName, :mediaType,
                    :sizeBytes, :checksum, :status, :comment, :createdBy, :createdAt
                )
                """)
                .param("id", file.id())
                .param("agreementId", file.agreementId())
                .param("supplementId", file.supplementId())
                .param("versionNumber", file.versionNumber())
                .param("attachmentId", file.attachmentId())
                .param("storageKey", file.storageKey())
                .param("originalName", file.originalName())
                .param("mediaType", file.mediaType())
                .param("sizeBytes", file.sizeBytes())
                .param("checksum", file.checksum())
                .param("status", file.status())
                .param("comment", file.comment())
                .param("createdBy", file.createdBy())
                .param("createdAt", file.createdAt())
                .update();
    }

    public void insertAttachmentVersion(UUID agreementId, UUID attachmentId, UUID createdBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_files (
                    id, agreement_id, supplement_id, version_number, attachment_id, storage_key, original_name, media_type,
                    size_bytes, checksum, status, comment, created_by, created_at
                )
                SELECT :id, :agreementId, NULL, :versionNumber, a.id, NULL, a.original_name, a.media_type, a.size_bytes, a.checksum,
                       a.status, :comment, :createdBy, :now
                FROM attachments a
                JOIN interactions i ON i.id = a.interaction_id
                WHERE a.id = :attachmentId
                """)
                .param("id", UUID.randomUUID())
                .param("agreementId", agreementId)
                .param("versionNumber", nextFileVersion(agreementId, null))
                .param("attachmentId", attachmentId)
                .param("comment", "Документ из взаимодействия")
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public Optional<SupplementRow> findSupplementRow(UUID supplementId) {
        return jdbcClient.sql("""
                SELECT s.id, s.agreement_id, ag.organization_id, s.version
                FROM agreement_supplements s
                JOIN agreements ag ON ag.id = s.agreement_id
                WHERE s.id = :id
                """)
                .param("id", supplementId)
                .query((resultSet, rowNumber) -> new SupplementRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    public Optional<Supplement> findSupplement(UUID supplementId) {
        return findSupplementRow(supplementId).flatMap(row -> findSupplements(row.agreementId(), findFiles(row.agreementId()))
                .stream()
                .filter(supplement -> supplement.id().equals(supplementId))
                .findFirst());
    }

    public void insertSupplement(UUID id, UUID agreementId, SupplementValues values, UUID createdBy, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_supplements (
                    id, agreement_id, number, concluded_on, subject, status, version, created_by, created_at, updated_at
                ) VALUES (:id, :agreementId, :number, :concludedOn, :subject, :status, 0, :createdBy, :now, :now)
                """)
                .param("id", id)
                .param("agreementId", agreementId)
                .params(values.parameters())
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public int updateSupplement(UUID id, int version, SupplementValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_supplements
                SET number = :number, concluded_on = :concludedOn, subject = :subject, status = :status, version = version + 1,
                    updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    private List<Supplement> findSupplements(UUID agreementId, List<AgreementFile> files) {
        return jdbcClient.sql("""
                SELECT id, agreement_id, number, concluded_on, subject, status, version
                FROM agreement_supplements
                WHERE agreement_id = :agreementId
                ORDER BY concluded_on NULLS LAST, number, id
                """)
                .param("agreementId", agreementId)
                .query((resultSet, rowNumber) -> {
                    UUID id = resultSet.getObject("id", UUID.class);
                    List<AgreementFile> own = files.stream().filter(file -> id.equals(file.supplementId())).toList();
                    return new Supplement(
                            id,
                            resultSet.getObject("agreement_id", UUID.class),
                            resultSet.getString("number"),
                            resultSet.getObject("concluded_on", LocalDate.class),
                            resultSet.getString("subject"),
                            SupplementStatus.valueOf(resultSet.getString("status")),
                            resultSet.getInt("version"),
                            current(own),
                            own
                    );
                })
                .list();
    }

    private List<StatusEvent> findHistory(UUID agreementId) {
        return jdbcClient.sql("""
                SELECT e.id, e.from_status, e.to_status, e.comment, p.display_name AS actor_name, e.occurred_at,
                       i.title AS interaction_title
                FROM agreement_status_events e
                LEFT JOIN crm_user_profiles p ON p.id = e.actor_profile_id
                LEFT JOIN interactions i ON i.id = e.interaction_id
                WHERE e.agreement_id = :agreementId
                ORDER BY e.occurred_at DESC, e.id DESC
                """)
                .param("agreementId", agreementId)
                .query((resultSet, rowNumber) -> new StatusEvent(
                        resultSet.getObject("id", UUID.class),
                        status(resultSet.getString("from_status")),
                        AgreementStatus.valueOf(resultSet.getString("to_status")),
                        resultSet.getString("comment"),
                        resultSet.getString("actor_name"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getString("interaction_title")
                ))
                .list();
    }

    public List<RegistryRow> findRegistry(VisibilityScope scope, RegistryQuery query, OffsetDateTime reviewStartedBefore, LocalDate today) {
        return findRegistry(scope, query, reviewStartedBefore, today, false);
    }

    public List<RegistryRow> findRegistry(
            VisibilityScope scope,
            RegistryQuery query,
            OffsetDateTime reviewStartedBefore,
            LocalDate today,
            boolean currentOrganizationsOnly
    ) {
        List<String> conditions = new ArrayList<>(List.of(
                "ag.organization_id IN (" + (currentOrganizationsOnly
                        ? scope.currentOrganizations()
                        : "SELECT id FROM organizations WHERE " + scope.condition()) + ")"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            conditions.add("ag.status IN (:statuses)");
            parameters.put("statuses", query.statuses().stream().map(Enum::name).toList());
        }
        if (query.organizationId() != null) {
            conditions.add("ag.organization_id = :organizationId");
            parameters.put("organizationId", query.organizationId());
        }
        if (query.managerId() != null) {
            conditions.add("o.owner_manager_id = :managerId");
            parameters.put("managerId", query.managerId());
        }
        // Своё имя параметра: «teamId» уже занят областью руководителя, и отбор подменил бы его область.
        if (query.teamId() != null) {
            conditions.add("o.team_id = :registryTeamId");
            parameters.put("registryTeamId", query.teamId());
        }
        if (query.expiresBefore() != null) {
            conditions.add("ag.valid_until IS NOT NULL AND ag.valid_until <= :expiresBefore");
            parameters.put("expiresBefore", query.expiresBefore());
        }
        if (reviewStartedBefore != null) {
            conditions.add("ag.status = 'REVIEW' AND ag.status_changed_at < :reviewStartedBefore");
            parameters.put("reviewStartedBefore", reviewStartedBefore);
        }
        if (query.delayed()) {
            conditions.add("ag.status IN ('REVIEW', 'CORRECTION', 'SIGNING')");
        }
        List<RegistryRow> rows = registryRows(String.join(" AND ", conditions), parameters, today);
        return query.delayed() ? rows.stream().filter(row -> row.approval() != null && row.approval().delayed()).toList() : rows;
    }

    // Договоры на согласовании, корректировке и подписании для плитки «Контроля»: та же область и те же статусы, что у
    // плиток реестра, поэтому числа «Контроля» и реестра совпадают.
    public List<RegistryRow> findInProgress(VisibilityScope scope, LocalDate today) {
        return registryRows("ag.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")"
                + " AND ag.status IN ('REVIEW', 'CORRECTION', 'SIGNING')", new HashMap<>(scope.parameters()), today, false);
    }

    public Set<UUID> findOrganizationsWithAgreementInProgress(Collection<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcClient.sql("""
                SELECT DISTINCT organization_id FROM agreements
                WHERE organization_id IN (:ids) AND status IN ('REVIEW', 'CORRECTION', 'SIGNING')
                """)
                .param("ids", organizationIds)
                .query(UUID.class)
                .list());
    }

    public List<RegistryRow> findSignedBetween(VisibilityScope scope, LocalDate from, LocalDate to, LocalDate today) {
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("from", from);
        parameters.put("to", to);
        return registryRows("ag.organization_id IN (" + scope.currentOrganizations() + ")"
                + " AND ag.status IN ('ACTIVE', 'COMPLETED', 'TERMINATED') AND ag.concluded_on BETWEEN :from AND :to", parameters, today);
    }

    public List<RegistryRow> findTerminatedBetween(VisibilityScope scope, OffsetDateTime from, OffsetDateTime to, LocalDate today) {
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("from", from);
        parameters.put("to", to);
        return registryRows("ag.organization_id IN (" + scope.currentOrganizations() + ")"
                + " AND ag.status = 'TERMINATED' AND EXISTS (SELECT 1 FROM agreement_status_events e WHERE e.agreement_id = ag.id"
                + " AND e.to_status = 'TERMINATED' AND e.occurred_at >= :from AND e.occurred_at < :to)", parameters, today);
    }

    public List<PeriodTransition> findTransitionsBetween(VisibilityScope scope, OffsetDateTime from, OffsetDateTime to) {
        return jdbcClient.sql("""
                SELECT e.occurred_at, ag.id AS agreement_id, o.name AS organization_name, ag.number, e.from_status, e.to_status,
                       p.display_name AS actor_name, e.comment
                FROM agreement_status_events e
                JOIN agreements ag ON ag.id = e.agreement_id
                JOIN organizations o ON o.id = ag.organization_id
                LEFT JOIN crm_user_profiles p ON p.id = e.actor_profile_id
                WHERE ag.organization_id IN (%s)
                  AND e.from_status IS NOT NULL AND e.occurred_at >= :from AND e.occurred_at < :to
                ORDER BY e.occurred_at, o.name, ag.number, e.id
                """.formatted(scope.currentOrganizations()))
                .params(scope.parameters())
                .param("from", from)
                .param("to", to)
                .query((resultSet, rowNumber) -> new PeriodTransition(
                        resultSet.getObject("occurred_at", OffsetDateTime.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getString("organization_name"),
                        resultSet.getString("number"),
                        status(resultSet.getString("from_status")),
                        AgreementStatus.valueOf(resultSet.getString("to_status")),
                        resultSet.getString("actor_name"),
                        resultSet.getString("comment")
                ))
                .list();
    }

    // У работы показывается последний по смене статуса связанный договор. «Этап под согласованием» — текущий этап работы
    // относится к договору, а этот договор на согласовании, корректировке или подписании: тогда срок этапа контролируют
    // пороги согласования, а не «на этапе дольше N дней». В SQL то же правило — AgreementStages.stageUnderApprovalCondition.
    public Map<UUID, WorkAgreementStatus> findWorkStatuses(List<UUID> interactionIds) {
        Map<UUID, WorkAgreementStatus> statuses = new HashMap<>();
        if (interactionIds.isEmpty()) {
            return statuses;
        }
        Map<UUID, AgreementPoint> latest = new HashMap<>();
        jdbcClient.sql("""
                SELECT ai.interaction_id, ag.id, ag.status, ag.number, ag.valid_until, ag.status_changed_at, ag.sent_on,
                       CASE WHEN %s THEN 1 ELSE 0 END AS agreement_stage
                FROM agreement_interactions ai
                JOIN agreements ag ON ag.id = ai.agreement_id
                JOIN interactions i ON i.id = ai.interaction_id
                WHERE ai.interaction_id IN (:ids)
                ORDER BY ag.status_changed_at DESC, ag.id
                """.formatted(stages.currentStageCondition("i")))
                .params(stages.currentStageParameters())
                .param("ids", interactionIds)
                .query((ResultSet resultSet) -> {
                    latest.putIfAbsent(resultSet.getObject("interaction_id", UUID.class), new AgreementPoint(
                            resultSet.getObject("id", UUID.class),
                            AgreementStatus.valueOf(resultSet.getString("status")),
                            resultSet.getString("number"),
                            resultSet.getObject("valid_until", LocalDate.class),
                            resultSet.getObject("status_changed_at", OffsetDateTime.class),
                            resultSet.getObject("sent_on", LocalDate.class),
                            resultSet.getInt("agreement_stage") == 1
                    ));
                });
        Map<UUID, List<StatusEntry>> entries = findStatusEntries(latest.values().stream()
                .filter(point -> AgreementApproval.inProgress(point.status())).map(AgreementPoint::id).distinct().toList());
        LocalDate today = LocalDate.now(ZONE);
        latest.forEach((interactionId, point) -> statuses.put(interactionId, new WorkAgreementStatus(
                point.status(),
                point.number(),
                point.validUntil(),
                approval(point.id(), point.status(), point.statusChangedAt(), point.sentOn(), entries, today),
                point.agreementStage() && AgreementApproval.inProgress(point.status())
        )));
        return statuses;
    }

    private record AgreementPoint(UUID id, AgreementStatus status, String number, LocalDate validUntil, OffsetDateTime statusChangedAt,
                                  LocalDate sentOn, boolean agreementStage) {
    }

    private List<RegistryRow> registryRows(String condition, Map<String, Object> parameters, LocalDate today) {
        return registryRows(condition, parameters, today, true);
    }

    // withSubjects = false — облегчённый набор для плитки «Контроля»: без предмета и даты подписания, остальное как в реестре.
    private List<RegistryRow> registryRows(String condition, Map<String, Object> parameters, LocalDate today, boolean withSubjects) {
        List<RegistryRow> rows = jdbcClient.sql("""
                SELECT ag.id, ag.organization_id, o.name AS organization_name, ag.number, ag.status, ag.status_changed_at, ag.sent_on,
                       %s AS concluded_on, ag.valid_until, o.owner_manager_id, m.display_name AS manager_name, o.team_id,
                       t.name AS team_name
                FROM agreements ag
                JOIN organizations o ON o.id = ag.organization_id
                LEFT JOIN crm_user_profiles m ON m.id = o.owner_manager_id
                LEFT JOIN teams t ON t.id = o.team_id
                WHERE %s
                ORDER BY ag.status_changed_at, o.name, ag.number, ag.id
                """.formatted(withSubjects ? "ag.concluded_on" : "CAST(NULL AS DATE)", condition))
                .params(parameters)
                .query((resultSet, rowNumber) -> {
                    OffsetDateTime changedAt = resultSet.getObject("status_changed_at", OffsetDateTime.class);
                    return new RegistryRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("organization_id", UUID.class),
                            resultSet.getString("organization_name"),
                            resultSet.getString("number"),
                            AgreementStatus.valueOf(resultSet.getString("status")),
                            changedAt,
                            Math.max(0, ChronoUnit.DAYS.between(changedAt.atZoneSameInstant(ZONE).toLocalDate(), today)),
                            resultSet.getObject("sent_on", LocalDate.class),
                            resultSet.getObject("concluded_on", LocalDate.class),
                            resultSet.getObject("valid_until", LocalDate.class),
                            null,
                            resultSet.getObject("owner_manager_id", UUID.class),
                            resultSet.getString("manager_name"),
                            null,
                            resultSet.getObject("team_id", UUID.class),
                            resultSet.getString("team_name")
                    );
                })
                .list();
        Map<UUID, List<String>> subjects = withSubjects ? findSubjectNames(rows.stream().map(RegistryRow::id).toList()) : Map.of();
        Map<UUID, List<StatusEntry>> entries = findStatusEntries(rows.stream()
                .filter(row -> AgreementApproval.inProgress(row.status())).map(RegistryRow::id).toList());
        return rows.stream()
                .map(row -> new RegistryRow(row.id(), row.organizationId(), row.organizationName(), row.number(), row.status(),
                        row.statusChangedAt(), row.daysInStatus(), row.sentOn(), row.concludedOn(), row.validUntil(),
                        subjects.containsKey(row.id()) ? String.join("; ", subjects.get(row.id())) : null,
                        row.managerId(), row.managerName(),
                        approval(row.id(), row.status(), row.statusChangedAt(), row.sentOn(), entries, today),
                        row.teamId(), row.teamName()))
                .toList();
    }

    private static AgreementFile current(List<AgreementFile> files) {
        return files.stream().filter(AgreementFile::downloadable).findFirst().orElse(null);
    }

    private static Party party(UUID id, String name) {
        return id == null ? null : new Party(id, name);
    }

    private static AgreementStatus status(String value) {
        return value == null ? null : AgreementStatus.valueOf(value);
    }

    public Optional<ActivityRow> findActivityRow(UUID activityId) {
        return jdbcClient.sql("""
                SELECT ac.id, ac.agreement_id, ag.organization_id, ac.kind_id, ac.responsible_profile_id, ac.version
                FROM agreement_activities ac
                JOIN agreements ag ON ag.id = ac.agreement_id
                WHERE ac.id = :id
                """)
                .param("id", activityId)
                .query((resultSet, rowNumber) -> new ActivityRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("agreement_id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getObject("kind_id", UUID.class),
                        resultSet.getObject("responsible_profile_id", UUID.class),
                        resultSet.getInt("version")
                ))
                .optional();
    }

    public Optional<Activity> findActivity(UUID activityId) {
        return activities("ac.id = :id", Map.of("id", activityId)).stream().findFirst();
    }

    public List<Activity> findActivities(UUID agreementId) {
        return activities("ac.agreement_id = :agreementId", Map.of("agreementId", agreementId));
    }

    public void insertActivity(UUID id, UUID agreementId, ActivityValues values, OffsetDateTime now) {
        jdbcClient.sql("""
                INSERT INTO agreement_activities (
                    id, agreement_id, kind_id, title, unit, planned_volume, actual_volume, planned_start, planned_end,
                    actual_start, actual_end, responsible_profile_id, status, version, created_at, updated_at
                ) VALUES (
                    :id, :agreementId, :kindId, :title, :unit, :plannedVolume, :actualVolume, :plannedStart, :plannedEnd,
                    :actualStart, :actualEnd, :responsibleProfileId, :status, 0, :now, :now
                )
                """)
                .param("id", id)
                .param("agreementId", agreementId)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public int updateActivity(UUID id, int version, ActivityValues values, OffsetDateTime now) {
        return jdbcClient.sql("""
                UPDATE agreement_activities
                SET kind_id = :kindId, title = :title, unit = :unit, planned_volume = :plannedVolume,
                    actual_volume = :actualVolume, planned_start = :plannedStart, planned_end = :plannedEnd,
                    actual_start = :actualStart, actual_end = :actualEnd, responsible_profile_id = :responsibleProfileId,
                    status = :status, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :version
                """)
                .param("id", id)
                .param("version", version)
                .params(values.parameters())
                .param("now", now)
                .update();
    }

    public int deleteActivity(UUID id, int version) {
        return jdbcClient.sql("DELETE FROM agreement_activities WHERE id = :id AND version = :version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    public void replaceActivityLinks(UUID activityId, List<UUID> interactionIds, List<UUID> attachmentIds) {
        jdbcClient.sql("DELETE FROM agreement_activity_interactions WHERE activity_id = :activityId")
                .param("activityId", activityId)
                .update();
        jdbcClient.sql("DELETE FROM agreement_activity_attachments WHERE activity_id = :activityId")
                .param("activityId", activityId)
                .update();
        for (UUID interactionId : interactionIds) {
            jdbcClient.sql("INSERT INTO agreement_activity_interactions (activity_id, interaction_id) VALUES (:activityId, :interactionId)")
                    .param("activityId", activityId)
                    .param("interactionId", interactionId)
                    .update();
        }
        for (UUID attachmentId : attachmentIds) {
            jdbcClient.sql("INSERT INTO agreement_activity_attachments (activity_id, attachment_id) VALUES (:activityId, :attachmentId)")
                    .param("activityId", activityId)
                    .param("attachmentId", attachmentId)
                    .update();
        }
    }

    public List<LinkedInteraction> findOrganizationInteractions(UUID organizationId) {
        return jdbcClient.sql("""
                SELECT id, title FROM interactions
                WHERE organization_id = :organizationId
                ORDER BY created_at DESC, id
                """)
                .param("organizationId", organizationId)
                .query((resultSet, rowNumber) -> new LinkedInteraction(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("title")
                ))
                .list();
    }

    public List<LinkedAttachment> findOrganizationCleanAttachments(UUID organizationId) {
        return jdbcClient.sql("SELECT " + ATTACHMENT_COLUMNS + ATTACHMENT_FROM + """
                WHERE i.organization_id = :organizationId AND a.status = 'CLEAN' AND a.deleted_at IS NULL
                ORDER BY a.created_at DESC, a.id
                """)
                .param("organizationId", organizationId)
                .query(this::mapAttachment)
                .list();
    }

    public List<Responsible> findResponsibles(UUID teamId) {
        return jdbcClient.sql("""
                SELECT id, display_name FROM crm_user_profiles
                WHERE team_id = :teamId AND active = TRUE AND role IN ('USER', 'LEADER')
                ORDER BY display_name, id
                """)
                .param("teamId", teamId)
                .query((resultSet, rowNumber) -> new Responsible(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("display_name")
                ))
                .list();
    }

    public Set<UUID> findOrganizationInteractionIds(UUID organizationId, List<UUID> interactionIds) {
        if (interactionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT id FROM interactions WHERE organization_id = :organizationId AND id IN (:ids)
                """)
                .param("organizationId", organizationId)
                .param("ids", interactionIds)
                .query(UUID.class)
                .list());
    }

    public Set<UUID> findOrganizationCleanAttachmentIds(UUID organizationId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                SELECT a.id FROM attachments a
                JOIN interactions i ON i.id = a.interaction_id
                WHERE i.organization_id = :organizationId AND a.status = 'CLEAN' AND a.deleted_at IS NULL AND a.id IN (:ids)
                """)
                .param("organizationId", organizationId)
                .param("ids", attachmentIds)
                .query(UUID.class)
                .list());
    }

    public List<ConfirmationRow> findConfirmations(VisibilityScope scope, ConfirmationQuery query, int limit) {
        List<String> conditions = new ArrayList<>(List.of(
                "ag.organization_id IN (SELECT id FROM organizations WHERE " + scope.condition() + ")",
                "i.organization_id = ag.organization_id",
                "a.deleted_at IS NULL"
        ));
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (query.organizationId() != null) {
            conditions.add("ag.organization_id = :organizationId");
            parameters.put("organizationId", query.organizationId());
        }
        if (query.agreementId() != null) {
            conditions.add("ag.id = :agreementId");
            parameters.put("agreementId", query.agreementId());
        }
        if (query.kindId() != null) {
            conditions.add("ac.kind_id = :kindId");
            parameters.put("kindId", query.kindId());
        }
        periodCondition(conditions, parameters, query.from(), query.to());
        return jdbcClient.sql("""
                SELECT o.id AS organization_id, o.name AS organization_name, ag.id AS agreement_id, ag.number,
                       ac.id AS activity_id, ac.title AS activity_title, k.id AS kind_id, k.name AS kind_name,
                       %s AS activity_start, %s AS activity_end,
                       a.id AS attachment_id, a.original_name, a.size_bytes, a.status, a.created_at, a.storage_key,
                       i.title AS interaction_title
                FROM agreement_activity_attachments aa
                JOIN agreement_activities ac ON ac.id = aa.activity_id
                JOIN agreements ag ON ag.id = ac.agreement_id
                JOIN organizations o ON o.id = ag.organization_id
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                JOIN attachments a ON a.id = aa.attachment_id
                JOIN interactions i ON i.id = a.interaction_id
                WHERE %s
                ORDER BY k.sort_order, k.name, o.name, ag.number, ac.title, ac.id, a.created_at, a.id
                LIMIT :limit
                """.formatted(ACTIVITY_START, ACTIVITY_END, String.join(" AND ", conditions)))
                .params(parameters)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ConfirmationRow(
                        new Confirmation(
                                resultSet.getObject("organization_id", UUID.class),
                                resultSet.getString("organization_name"),
                                resultSet.getObject("agreement_id", UUID.class),
                                resultSet.getString("number"),
                                resultSet.getObject("activity_id", UUID.class),
                                resultSet.getString("activity_title"),
                                resultSet.getObject("kind_id", UUID.class),
                                resultSet.getString("kind_name"),
                                resultSet.getObject("activity_start", LocalDate.class),
                                resultSet.getObject("activity_end", LocalDate.class),
                                resultSet.getObject("attachment_id", UUID.class),
                                resultSet.getString("original_name"),
                                resultSet.getLong("size_bytes"),
                                resultSet.getString("status"),
                                resultSet.getObject("created_at", OffsetDateTime.class),
                                resultSet.getString("interaction_title")
                        ),
                        resultSet.getObject("storage_key", UUID.class)
                ))
                .list();
    }

    public static void periodCondition(List<String> conditions, Map<String, Object> parameters, LocalDate from, LocalDate to) {
        if (to != null) {
            conditions.add("(" + ACTIVITY_START + " IS NULL OR " + ACTIVITY_START + " <= :periodTo)");
            parameters.put("periodTo", to);
        }
        if (from != null) {
            conditions.add("(" + ACTIVITY_END + " IS NULL OR " + ACTIVITY_END + " >= :periodFrom)");
            parameters.put("periodFrom", from);
        }
    }

    private List<Activity> activities(String condition, Map<String, Object> parameters) {
        List<ActivityHeader> headers = jdbcClient.sql("""
                SELECT ac.id, ac.agreement_id, ac.kind_id, k.name AS kind_name, ac.title, ac.unit, ac.planned_volume,
                       ac.actual_volume, ac.planned_start, ac.planned_end, ac.actual_start, ac.actual_end,
                       ac.responsible_profile_id, rp.display_name AS responsible_name, ac.status, ac.version
                FROM agreement_activities ac
                JOIN agreement_activity_kinds k ON k.id = ac.kind_id
                LEFT JOIN crm_user_profiles rp ON rp.id = ac.responsible_profile_id
                WHERE %s
                ORDER BY k.sort_order, k.name, ac.planned_start NULLS LAST, ac.title, ac.id
                """.formatted(condition))
                .params(parameters)
                .query(this::mapActivityHeader)
                .list();
        if (headers.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = headers.stream().map(ActivityHeader::id).toList();
        Map<UUID, List<LinkedInteraction>> interactions = new HashMap<>();
        jdbcClient.sql("""
                SELECT ai.activity_id, i.id, i.title
                FROM agreement_activity_interactions ai
                JOIN interactions i ON i.id = ai.interaction_id
                WHERE ai.activity_id IN (:ids)
                ORDER BY i.title, i.id
                """)
                .param("ids", ids)
                .query((ResultSet resultSet) -> {
                    interactions.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), key -> new ArrayList<>())
                            .add(new LinkedInteraction(resultSet.getObject("id", UUID.class), resultSet.getString("title")));
                });
        Map<UUID, List<LinkedAttachment>> attachments = new HashMap<>();
        jdbcClient.sql("SELECT aa.activity_id, " + ATTACHMENT_COLUMNS + ATTACHMENT_FROM + """
                JOIN agreement_activity_attachments aa ON aa.attachment_id = a.id
                WHERE aa.activity_id IN (:ids) AND a.deleted_at IS NULL
                ORDER BY a.created_at, a.id
                """)
                .param("ids", ids)
                .query((ResultSet resultSet) -> {
                    attachments.computeIfAbsent(resultSet.getObject("activity_id", UUID.class), key -> new ArrayList<>())
                            .add(mapAttachment(resultSet, 0));
                });
        return headers.stream()
                .map(header -> header.activity(
                        interactions.getOrDefault(header.id(), List.of()),
                        attachments.getOrDefault(header.id(), List.of())
                ))
                .toList();
    }

    private ActivityKind mapKind(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActivityKind(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getInt("sort_order"),
                resultSet.getBoolean("archived"),
                resultSet.getInt("version")
        );
    }

    private LinkedAttachment mapAttachment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LinkedAttachment(
                resultSet.getObject("attachment_id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getObject("interaction_id", UUID.class),
                resultSet.getString("interaction_title"),
                resultSet.getString("stage_name"),
                resultSet.getString("attachment_status"),
                resultSet.getObject("attachment_created_at", OffsetDateTime.class)
        );
    }

    private ActivityHeader mapActivityHeader(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ActivityHeader(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("agreement_id", UUID.class),
                resultSet.getObject("kind_id", UUID.class),
                resultSet.getString("kind_name"),
                resultSet.getString("title"),
                resultSet.getString("unit"),
                resultSet.getObject("planned_volume", Integer.class),
                resultSet.getObject("actual_volume", Integer.class),
                resultSet.getObject("planned_start", LocalDate.class),
                resultSet.getObject("planned_end", LocalDate.class),
                resultSet.getObject("actual_start", LocalDate.class),
                resultSet.getObject("actual_end", LocalDate.class),
                resultSet.getObject("responsible_profile_id", UUID.class),
                resultSet.getString("responsible_name"),
                ActivityStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("version")
        );
    }

    private static PlanKind planKind(String value) {
        return value == null ? null : PlanKind.valueOf(value);
    }

    public record AgreementRow(
            UUID id,
            UUID organizationId,
            String number,
            AgreementStatus status,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            int version,
            PlanKind plannedKind,
            LocalDate plannedOn,
            UUID universitySignerId,
            UUID schoolSignerId
    ) {
    }

    public record SupplementRow(UUID id, UUID agreementId, UUID organizationId, int version) {
    }

    public record StoredFile(
            UUID id,
            UUID agreementId,
            String originalName,
            String mediaType,
            long sizeBytes,
            UUID storageKey,
            boolean downloadable
    ) {
    }

    public record WorkAgreementStatus(AgreementStatus status, String number, LocalDate validUntil, Approval approval,
                                      boolean stageUnderApproval) {
    }

    public record NewFile(
            UUID id,
            UUID agreementId,
            UUID supplementId,
            int versionNumber,
            UUID attachmentId,
            UUID storageKey,
            String originalName,
            String mediaType,
            long sizeBytes,
            String checksum,
            String status,
            String comment,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {
    }

    public enum SubjectLink {
        PROGRAMS("agreement_programs", "program_id", "programs"),
        PRODUCTS("agreement_products", "product_id", "products"),
        DIRECTIONS("agreement_directions", "direction_id", "directions");

        private final String table;
        private final String column;
        private final String catalog;

        SubjectLink(String table, String column, String catalog) {
            this.table = table;
            this.column = column;
            this.catalog = catalog;
        }

        String table() {
            return table;
        }

        String column() {
            return column;
        }

        String catalog() {
            return catalog;
        }
    }

    public record SupplementValues(String number, LocalDate concludedOn, String subject, SupplementStatus status) {
        Map<String, Object> parameters() {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("number", number);
            parameters.put("concludedOn", concludedOn);
            parameters.put("subject", subject);
            parameters.put("status", status.name());
            return parameters;
        }
    }

    public record ActivityRow(UUID id, UUID agreementId, UUID organizationId, UUID kindId, UUID responsibleProfileId, int version) {
    }

    public record ConfirmationRow(Confirmation confirmation, UUID storageKey) {
    }

    public record AgreementValues(
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            String parties,
            PlanKind plannedKind,
            LocalDate plannedOn,
            UUID universitySignerId,
            UUID schoolSignerId
    ) {
        Map<String, Object> parameters() {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("number", number);
            parameters.put("concludedOn", concludedOn);
            parameters.put("validUntil", validUntil);
            parameters.put("sentOn", sentOn);
            parameters.put("parties", parties);
            parameters.put("plannedKind", plannedKind == null ? null : plannedKind.name());
            parameters.put("plannedOn", plannedOn);
            parameters.put("universitySignerId", universitySignerId);
            parameters.put("schoolSignerId", schoolSignerId);
            return parameters;
        }
    }

    public record ActivityValues(
            UUID kindId,
            String title,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            ActivityStatus status
    ) {
        Map<String, Object> parameters() {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("kindId", kindId);
            parameters.put("title", title);
            parameters.put("unit", unit);
            parameters.put("plannedVolume", plannedVolume);
            parameters.put("actualVolume", actualVolume);
            parameters.put("plannedStart", plannedStart);
            parameters.put("plannedEnd", plannedEnd);
            parameters.put("actualStart", actualStart);
            parameters.put("actualEnd", actualEnd);
            parameters.put("responsibleProfileId", responsibleProfileId);
            parameters.put("status", status.name());
            return parameters;
        }
    }

    private record AgreementHeader(
            UUID id,
            UUID organizationId,
            String organizationName,
            String number,
            LocalDate concludedOn,
            LocalDate validUntil,
            LocalDate sentOn,
            PlanKind plannedKind,
            LocalDate plannedOn,
            String parties,
            AgreementStatus status,
            OffsetDateTime statusChangedAt,
            Party universitySigner,
            Party schoolSigner,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    private record ActivityHeader(
            UUID id,
            UUID agreementId,
            UUID kindId,
            String kindName,
            String title,
            String unit,
            Integer plannedVolume,
            Integer actualVolume,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate actualStart,
            LocalDate actualEnd,
            UUID responsibleProfileId,
            String responsibleName,
            ActivityStatus status,
            int version
    ) {
        Activity activity(List<LinkedInteraction> interactions, List<LinkedAttachment> attachments) {
            return new Activity(
                    id,
                    agreementId,
                    kindId,
                    kindName,
                    title,
                    unit,
                    plannedVolume,
                    actualVolume,
                    plannedStart,
                    plannedEnd,
                    actualStart,
                    actualEnd,
                    responsibleProfileId,
                    responsibleName,
                    status,
                    version,
                    List.copyOf(interactions),
                    List.copyOf(attachments)
            );
        }
    }
}
