package ru.rtk.crm.privacy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.enrolment.EnrolmentAccess;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.settings.SystemSetting;
import ru.rtk.crm.settings.SystemSettings;

// Учёт согласий — включаемая функция: нужен ли он и для кого (контакты вузов, слушатели), решает эксплуатант в
// «Настройках». Записывает согласие тот, кто правит саму запись: контакт — КАМ и руководитель, видящие вуз (как правка
// контакта), анкету — менеджер учебного процесса. Администратор видит согласия на экране «Персональные данные», но не
// правит их. Выключение функции записи не удаляет: они снова видны после включения.
@Service
public class ConsentService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String OBJECT_TYPE = "CONSENT";
    private static final String SELECT = """
            SELECT c.id, c.subject_type, c.subject_id, c.given_on, c.basis, c.document, c.withdrawn_on, c.updated_at,
                   p.display_name AS author_name
            FROM personal_data_consents c
            JOIN crm_user_profiles p ON p.id = c.created_by
            """;

    private final JdbcClient jdbcClient;
    private final SystemSettings settings;
    private final AuditJournalRepository auditJournalRepository;
    private final OrganizationRepository organizationRepository;
    private final EnrolmentAccess enrolmentAccess;

    public ConsentService(JdbcClient jdbcClient, SystemSettings settings, AuditJournalRepository auditJournalRepository,
                          OrganizationRepository organizationRepository, EnrolmentAccess enrolmentAccess) {
        this.jdbcClient = jdbcClient;
        this.settings = settings;
        this.auditJournalRepository = auditJournalRepository;
        this.organizationRepository = organizationRepository;
        this.enrolmentAccess = enrolmentAccess;
    }

    /** Включён ли учёт: сотрудникам, чтобы показывать блок согласий только когда он нужен. */
    public ConsentSettings settings() {
        return new ConsentSettings(enabled(ConsentSubjectType.CONTACT), enabled(ConsentSubjectType.LEARNER));
    }

    @Transactional(readOnly = true)
    public Consents list(CrmProfile actor, Subject subject) {
        if (actor.role() == UserRole.ADMIN && subject.organizationId() == null) {
            status(subject);
        } else {
            authorize(actor, subject, false);
        }
        boolean enabled = enabled(subject.type());
        return new Consents(enabled, enabled ? find(subject) : List.of());
    }

    @Transactional
    public Consent create(CrmProfile actor, Subject subject, ConsentInput input, String requestId) {
        authorize(actor, subject, true);
        Valid valid = validate(input);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbcClient.sql("""
                        INSERT INTO personal_data_consents (
                            id, subject_type, subject_id, given_on, basis, document, withdrawn_on, created_by, created_at, updated_at
                        ) VALUES (:id, :subjectType, :subjectId, :givenOn, :basis, :document, :withdrawnOn, :actor, :now, :now)
                        """)
                .param("id", id)
                .param("subjectType", subject.type().name())
                .param("subjectId", subject.id())
                .param("givenOn", valid.givenOn())
                .param("basis", valid.basis())
                .param("document", valid.document())
                .param("withdrawnOn", valid.withdrawnOn())
                .param("actor", actor.id())
                .param("now", now)
                .update();
        Consent created = one(id, subject);
        record(actor, created, "добавлено согласие от " + DATE.format(created.givenOn()) + ", основание «" + created.basis() + "»"
                + (created.withdrawnOn() == null ? "" : "; отозвано " + DATE.format(created.withdrawnOn())), requestId);
        return created;
    }

    @Transactional
    public Consent update(CrmProfile actor, Subject subject, UUID id, ConsentInput input, String requestId) {
        authorize(actor, subject, true);
        Consent before = one(id, subject);
        Valid valid = validate(input);
        jdbcClient.sql("""
                        UPDATE personal_data_consents
                        SET given_on = :givenOn, basis = :basis, document = :document, withdrawn_on = :withdrawnOn, updated_at = :now
                        WHERE id = :id
                        """)
                .param("givenOn", valid.givenOn())
                .param("basis", valid.basis())
                .param("document", valid.document())
                .param("withdrawnOn", valid.withdrawnOn())
                .param("now", OffsetDateTime.now())
                .param("id", id)
                .update();
        Consent after = one(id, subject);
        List<String> changes = new ArrayList<>();
        if (!before.givenOn().equals(after.givenOn())) {
            changes.add("дата согласия: " + DATE.format(before.givenOn()) + " → " + DATE.format(after.givenOn()));
        }
        if (!before.basis().equals(after.basis())) {
            changes.add("основание: «" + before.basis() + "» → «" + after.basis() + "»");
        }
        if (!Objects.equals(before.document(), after.document())) {
            changes.add("документ изменён");
        }
        if (!Objects.equals(before.withdrawnOn(), after.withdrawnOn())) {
            changes.add(after.withdrawnOn() == null ? "отметка отзыва снята" : "согласие отозвано " + DATE.format(after.withdrawnOn()));
        }
        record(actor, after, changes.isEmpty() ? "сохранено без изменений" : String.join("; ", changes), requestId);
        return after;
    }

    boolean enabled(ConsentSubjectType subjectType) {
        return settings.enabled(subjectType == ConsentSubjectType.CONTACT ? SystemSetting.CONSENTS_CONTACTS : SystemSetting.CONSENTS_LEARNERS);
    }

    // Права — как на правку самой записи. Контакт: вуз в области профиля и роль, правящая карточки (КАМ, руководитель);
    // чтение — всем, кто видит вуз. Слушатель: флаг «Менеджер учебного процесса» и для чтения, и для записи.
    // Записать согласие можно только у действующей записи: ограниченная и обезличенная не меняются.
    private void authorize(CrmProfile actor, Subject subject, boolean write) {
        if (subject.type() == ConsentSubjectType.CONTACT) {
            if (subject.organizationId() == null) {
                throw new OrganizationNotFoundException();
            }
            organizationRepository.findVisibleById(actor, subject.organizationId()).orElseThrow(OrganizationNotFoundException::new);
            if (write) {
                ContactInteractionMutationAuthorization.requireCardEditor(actor);
            }
        } else {
            enrolmentAccess.requireOperator(actor);
        }
        String status = status(subject);
        if (write) {
            if (!enabled(subject.type())) {
                throw PrivacyException.consentsDisabled();
            }
            boolean contact = subject.type() == ConsentSubjectType.CONTACT;
            if ("ANONYMIZED".equals(status)) {
                throw contact ? PrivacyException.contactAnonymized() : PrivacyException.learnerAnonymized();
            }
            if ("RESTRICTED".equals(status)) {
                throw contact ? PrivacyException.contactRestricted() : PrivacyException.learnerRestricted();
            }
        }
    }

    // Статус ПДн субъекта; у контакта заодно проверяется, что он из указанного вуза.
    private String status(Subject subject) {
        if (subject.type() == ConsentSubjectType.CONTACT) {
            return jdbcClient.sql("SELECT personal_data_status FROM contacts WHERE id = :id"
                            + (subject.organizationId() == null ? "" : " AND organization_id = :organizationId"))
                    .param("id", subject.id())
                    .param("organizationId", subject.organizationId())
                    .query(String.class)
                    .optional()
                    .orElseThrow(PrivacyException::contactNotFound);
        }
        return jdbcClient.sql("SELECT personal_data_status FROM learners WHERE id = :id")
                .param("id", subject.id())
                .query(String.class)
                .optional()
                .orElseThrow(PrivacyException::learnerNotFound);
    }

    private Valid validate(ConsentInput input) {
        if (input == null) {
            throw new InteractionValidationException("body", "Передайте данные согласия");
        }
        LocalDate today = LocalDate.now(MOSCOW);
        if (input.givenOn() == null || input.givenOn().isAfter(today)) {
            throw new InteractionValidationException("givenOn", "Укажите дату согласия не позже сегодняшней");
        }
        String basis = input.basis() == null ? "" : input.basis().strip();
        if (basis.isEmpty() || basis.length() > 300) {
            throw new InteractionValidationException("basis", "Укажите основание, не длиннее 300 символов");
        }
        String document = input.document() == null || input.document().isBlank() ? null : input.document().strip();
        if (document != null && document.length() > 300) {
            throw new InteractionValidationException("document", "Документ — не длиннее 300 символов");
        }
        if (input.withdrawnOn() != null && (input.withdrawnOn().isBefore(input.givenOn()) || input.withdrawnOn().isAfter(today))) {
            throw new InteractionValidationException("withdrawnOn", "Дата отзыва — не раньше даты согласия и не позже сегодняшней");
        }
        return new Valid(input.givenOn(), basis, document, input.withdrawnOn());
    }

    private List<Consent> find(Subject subject) {
        return jdbcClient.sql(SELECT + " WHERE c.subject_type = :subjectType AND c.subject_id = :subjectId ORDER BY c.given_on DESC, c.id")
                .param("subjectType", subject.type().name())
                .param("subjectId", subject.id())
                .query((resultSet, rowNumber) -> map(resultSet))
                .list();
    }

    // Запись ищется вместе с субъектом из адреса: чужое согласие по своему адресу не правится.
    private Consent one(UUID id, Subject subject) {
        return jdbcClient.sql(SELECT + " WHERE c.id = :id AND c.subject_type = :subjectType AND c.subject_id = :subjectId")
                .param("id", id)
                .param("subjectType", subject.type().name())
                .param("subjectId", subject.id())
                .query((resultSet, rowNumber) -> map(resultSet))
                .optional()
                .orElseThrow(PrivacyException::consentNotFound);
    }

    private static Consent map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new Consent(
                resultSet.getObject("id", UUID.class),
                ConsentSubjectType.valueOf(resultSet.getString("subject_type")),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getObject("given_on", LocalDate.class),
                resultSet.getString("basis"),
                resultSet.getString("document"),
                resultSet.getObject("withdrawn_on", LocalDate.class),
                resultSet.getString("author_name"),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    // В журнале — субъект по идентификатору и виду, без имени: имя — персональные данные.
    private void record(CrmProfile actor, Consent consent, String details, String requestId) {
        auditJournalRepository.record(AuditAction.CONSENT_CHANGED, actor.id(), OBJECT_TYPE, consent.id(),
                (consent.subjectType() == ConsentSubjectType.CONTACT ? "контакт " : "слушатель ") + consent.subjectId(), details, requestId);
    }

    private record Valid(LocalDate givenOn, String basis, String document, LocalDate withdrawnOn) {
    }

    /** Субъект из адреса запроса; у контакта — ещё и вуз, по которому проверяется область профиля. */
    record Subject(ConsentSubjectType type, UUID id, UUID organizationId) {
    }
}

enum ConsentSubjectType { CONTACT, LEARNER }

record Consent(
        UUID id,
        ConsentSubjectType subjectType,
        UUID subjectId,
        LocalDate givenOn,
        String basis,
        String document,
        LocalDate withdrawnOn,
        String authorName,
        OffsetDateTime updatedAt
) {
}

record ConsentInput(LocalDate givenOn, String basis, String document, LocalDate withdrawnOn) {
}

record Consents(boolean enabled, List<Consent> consents) {
}

record ConsentSettings(boolean contacts, boolean learners) {
}
