package ru.rtk.crm.enrolment;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.enrolment.LearnerRepository.Fingerprint;
import ru.rtk.crm.enrolment.LearnerRepository.LearnerRow;
import ru.rtk.crm.enrolment.LearnerRepository.LearnerWrite;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class LearnerService {
    static final String OBJECT_TYPE = "LEARNER";
    static final int FIND_LIMIT = 200;

    private final LearnerRepository repository;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerDataCipher cipher;
    private final AuditJournalRepository auditJournalRepository;

    LearnerService(
            LearnerRepository repository,
            EnrolmentRepository enrolmentRepository,
            LearnerDataCipher cipher,
            AuditJournalRepository auditJournalRepository
    ) {
        this.repository = repository;
        this.enrolmentRepository = enrolmentRepository;
        this.cipher = cipher;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional
    Learner create(LearnerProfile profile, UUID createdBy) {
        LearnerProfile normalized = profile.normalized();
        requireSnilsFree(null, normalized.snils());
        UUID id = UUID.randomUUID();
        try {
            repository.insert(encrypted(id, normalized), createdBy, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw snilsTaken();
        }
        return find(id).orElseThrow();
    }

    Optional<Learner> find(UUID id) {
        return repository.find(id).map(this::decrypted);
    }

    Optional<Learner> lock(UUID id) {
        return repository.lock(id).map(this::decrypted);
    }

    List<Learner> findAll(Collection<UUID> ids) {
        return repository.findByIds(ids).stream().map(this::decrypted).toList();
    }

    void delete(UUID id) {
        repository.delete(id);
    }

    List<Learner> findByContacts(String email, String phone) {
        Map<Fingerprint, Set<String>> fingerprints = new EnumMap<>(Fingerprint.class);
        fingerprints.put(Fingerprint.EMAIL, present(cipher.emailFingerprint(email)));
        fingerprints.put(Fingerprint.PHONE, present(phoneFingerprint(phone)));
        return findBy(fingerprints);
    }

    Optional<Learner> findBySnils(String snils) {
        return findBy(Map.of(Fingerprint.SNILS, present(snilsFingerprint(snils)))).stream().findFirst();
    }

    List<Learner> findByLastName(String lastName) {
        return findBy(Map.of(Fingerprint.LAST_NAME, present(cipher.lastNameFingerprint(lastName))));
    }

    @Transactional
    Learner update(UUID actorId, UUID id, int expectedVersion, LearnerProfile profile, String requestId) {
        Learner current = repository.lock(id).map(this::decrypted).orElseThrow(LearnerNotFoundException::new);
        requireActive(current);
        if (current.version() != expectedVersion) {
            throw InteractionConflictException.learnerVersion(current.version());
        }
        LearnerProfile normalized = profile.normalized();
        List<LearnerField> changed = Arrays.stream(LearnerField.values())
                .filter(field -> !Objects.equals(current.profile().value(field), normalized.value(field)))
                .toList();
        if (changed.isEmpty()) {
            return current;
        }
        requireSnilsFree(id, normalized.snils());
        boolean updated;
        try {
            updated = repository.update(encrypted(id, normalized), expectedVersion, OffsetDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw snilsTaken();
        }
        if (!updated) {
            throw InteractionConflictException.learnerVersion(current.version());
        }
        journal(AuditAction.LEARNER_CHANGED, actorId, id, "поля: " + codes(changed.stream()), requestId);
        return find(id).orElseThrow();
    }

    @Transactional
    LearnerCard card(UUID actorId, UUID id, String requestId) {
        LearnerCard card = view(id);
        journal(AuditAction.LEARNER_VIEWED, actorId, id, null, requestId);
        return card;
    }

    LearnerCard view(UUID id) {
        Learner learner = find(id).orElseThrow(LearnerNotFoundException::new);
        return new LearnerCard(
                id, learner.status(), learner.version(), masked(learner.profile()), learner.missingFields(),
                enrolmentRepository.findByLearners(List.of(id)), learner.createdAt(), learner.updatedAt()
        );
    }

    static Map<LearnerField, String> masked(LearnerProfile profile) {
        Map<LearnerField, String> masked = new EnumMap<>(LearnerField.class);
        if (profile != null) {
            for (LearnerField field : LearnerField.values()) {
                String value = profile.display(field);
                if (value != null) {
                    masked.put(field, LearnerFieldGroup.mask(field, value));
                }
            }
        }
        return masked;
    }

    @Transactional
    Map<LearnerField, String> reveal(UUID actorId, UUID id, Set<LearnerFieldGroup> groups, String requestId) {
        if (groups == null || groups.isEmpty()) {
            throw new InteractionValidationException("groups", "Выберите группы полей, которые нужно показать");
        }
        Learner learner = find(id).orElseThrow(LearnerNotFoundException::new);
        requireActive(learner);
        Map<LearnerField, String> values = new EnumMap<>(LearnerField.class);
        for (LearnerFieldGroup group : groups) {
            for (LearnerField field : group.fields()) {
                String value = learner.profile().display(field);
                if (value != null) {
                    values.put(field, value);
                }
            }
        }
        journal(AuditAction.LEARNER_FIELDS_REVEALED, actorId, id, "группы: " + codes(EnumSet.copyOf(groups).stream()), requestId);
        return values;
    }

    @Transactional
    boolean reencrypt(UUID id) {
        Optional<LearnerRow> row = repository.lock(id);
        if (row.isEmpty() || row.get().fields() == null || row.get().keyVersion().equals(cipher.activeKeyVersion())) {
            return false;
        }
        return repository.reencrypt(encrypted(id, decrypted(row.get()).profile()), row.get().version());
    }

    void journal(AuditAction action, UUID actorId, UUID learnerId, String details, String requestId) {
        auditJournalRepository.record(action, actorId, OBJECT_TYPE, learnerId, objectName(learnerId), details, requestId);
    }

    List<Learner> findBy(Map<Fingerprint, Set<String>> fingerprints) {
        return repository.findByFingerprints(fingerprints, FIND_LIMIT).stream().map(this::decrypted).toList();
    }

    String phoneFingerprint(String phone) {
        return LearnerRules.normalizePhone(phone) == null ? null : cipher.phoneFingerprint(phone);
    }

    String snilsFingerprint(String snils) {
        return LearnerRules.normalizeSnils(snils) == null ? null : cipher.snilsFingerprint(snils);
    }

    static String objectName(UUID learnerId) {
        return "Слушатель " + learnerId.toString().substring(0, 8);
    }

    static Set<String> present(String fingerprint) {
        return fingerprint == null ? Set.of() : Set.of(fingerprint);
    }

    private void requireSnilsFree(UUID learnerId, String snils) {
        Optional<Learner> owner = findBySnils(snils);
        if (owner.isPresent() && !owner.get().id().equals(learnerId)) {
            throw snilsTaken();
        }
    }

    private static InteractionValidationException snilsTaken() {
        return new InteractionValidationException(LearnerField.SNILS.name(), "СНИЛС уже есть у другого слушателя");
    }

    static void requireActive(Learner learner) {
        if (learner.status() == PersonalDataStatus.ANONYMIZED) {
            throw InteractionConflictException.learnerAnonymized();
        }
        if (learner.status() == PersonalDataStatus.RESTRICTED) {
            throw InteractionConflictException.learnerRestricted();
        }
    }

    private LearnerWrite encrypted(UUID id, LearnerProfile profile) {
        Map<LearnerField, String> fields = new EnumMap<>(LearnerField.class);
        Set<LearnerField> missing = EnumSet.noneOf(LearnerField.class);
        for (LearnerField field : LearnerField.values()) {
            String value = profile.stored(field);
            if (value == null) {
                missing.add(field);
            } else {
                fields.put(field, cipher.encryptField(id, field, value));
            }
        }
        return new LearnerWrite(
                id,
                cipher.activeKeyVersion(),
                fields,
                cipher.emailFingerprint(profile.email()),
                phoneFingerprint(profile.phone()),
                snilsFingerprint(profile.snils()),
                cipher.nameFingerprint(profile.lastName(), profile.firstName()),
                cipher.lastNameFingerprint(profile.lastName()),
                missing
        );
    }

    private Learner decrypted(LearnerRow row) {
        LearnerProfile profile = null;
        if (row.fields() != null) {
            Map<LearnerField, String> values = new EnumMap<>(LearnerField.class);
            row.fields().forEach((field, stored) -> values.put(field, cipher.decryptField(row.id(), field, stored)));
            profile = LearnerProfile.fromStored(values);
        }
        return new Learner(row.id(), profile, row.status(), row.version(), row.missingFields(), row.createdAt(), row.updatedAt());
    }

    private static String codes(Stream<? extends Enum<?>> values) {
        return values.map(Enum::name).collect(Collectors.joining(", "));
    }
}
