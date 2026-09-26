package ru.rtk.crm.enrolment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.enrolment.LearnerRepository.Fingerprint;
import ru.rtk.crm.enrolment.LearnerRepository.LearnerRow;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class LearnerPrivacyService {
    public static final long PROFILES_LIMIT = 100_000;
    static final long PROFILES_WARNING = 80_000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final LearnerService learnerService;
    private final LearnerRepository repository;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerDataCipher cipher;

    LearnerPrivacyService(
            LearnerService learnerService,
            LearnerRepository repository,
            EnrolmentRepository enrolmentRepository,
            LearnerDataCipher cipher
    ) {
        this.learnerService = learnerService;
        this.repository = repository;
        this.enrolmentRepository = enrolmentRepository;
        this.cipher = cipher;
    }

    public List<SubjectLearner> find(String name, String email, String phone, String snils) {
        boolean snilsGiven = snils != null && !snils.isBlank();
        if (snilsGiven && LearnerRules.normalizeSnils(snils) == null) {
            throw new InteractionValidationException("snils", "СНИЛС должен состоять из 11 цифр");
        }
        if (!cipher.enabled()) {
            return List.of();
        }
        Map<Fingerprint, Set<String>> fingerprints = new EnumMap<>(Fingerprint.class);
        fingerprints.put(Fingerprint.EMAIL, LearnerService.present(email == null || email.isBlank() ? null : cipher.emailFingerprint(email)));
        fingerprints.put(Fingerprint.PHONE, LearnerService.present(learnerService.phoneFingerprint(phone)));
        fingerprints.put(Fingerprint.SNILS, LearnerService.present(snilsGiven ? learnerService.snilsFingerprint(snils) : null));
        fingerprints.put(Fingerprint.NAME, nameFingerprints(name));
        return summaries(repository.findByFingerprints(fingerprints, LearnerService.FIND_LIMIT));
    }

    public List<LearnerDisclosure> disclose(Collection<UUID> learnerIds) {
        if (learnerIds.isEmpty() || !cipher.enabled()) {
            return List.of();
        }
        Map<UUID, List<SubjectLearnerEnrolment>> enrolments = enrolments(learnerIds);
        List<LearnerDisclosure> disclosures = new ArrayList<>();
        for (UUID id : learnerIds) {
            learnerService.find(id).ifPresent(learner -> {
                List<DisclosedField> fields = new ArrayList<>();
                if (learner.profile() != null) {
                    for (LearnerField field : LearnerField.values()) {
                        Object value = learner.profile().value(field);
                        if (value != null) {
                            fields.add(new DisclosedField(field.label(), value instanceof LocalDate date
                                    ? DATE.format(date)
                                    : learner.profile().display(field)));
                        }
                    }
                }
                disclosures.add(new LearnerDisclosure(id, learner.status(), fields, enrolments.getOrDefault(id, List.of())));
            });
        }
        return disclosures;
    }

    public Optional<SubjectLearner> lock(UUID learnerId) {
        return repository.lock(learnerId).map(row -> summaries(List.of(row)).getFirst());
    }

    public boolean updateStatus(UUID learnerId, int expectedVersion, PersonalDataStatus status) {
        return repository.updateStatus(learnerId, expectedVersion, status, OffsetDateTime.now());
    }

    public boolean anonymize(UUID learnerId) {
        return repository.anonymize(learnerId, OffsetDateTime.now());
    }

    public int anonymizeExpired(Period term, LocalDate today) {
        Set<UUID> expiredStreams = enrolmentRepository.findStreamEndDates().entrySet().stream()
                .filter(stream -> stream.getValue().plus(term).isBefore(today))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return expiredStreams.isEmpty() ? 0 : repository.anonymizeExpired(expiredStreams, OffsetDateTime.now());
    }

    public Counters counters() {
        long profiles = repository.countProfiles();
        return new Counters(
                cipher.enabled(),
                profiles,
                PROFILES_LIMIT,
                profiles >= PROFILES_WARNING,
                enrolmentRepository.countStreamsWithoutEndDate(),
                cipher.enabled() ? repository.countWithOtherKey(cipher.activeKeyVersion()) : 0
        );
    }

    public void journal(AuditAction action, UUID actorId, Collection<UUID> learnerIds, String details, String requestId) {
        learnerIds.forEach(id -> learnerService.journal(action, actorId, id, details, requestId));
    }

    private Set<String> nameFingerprints(String name) {
        Set<String> fingerprints = new HashSet<>();
        if (name != null) {
            String[] words = LearnerRules.collapseSpaces(name).split(" ");
            if (words.length >= 2) {
                fingerprints.add(cipher.nameFingerprint(words[0], words[1]));
                fingerprints.add(cipher.nameFingerprint(words[1], words[0]));
            }
        }
        return fingerprints;
    }

    private List<SubjectLearner> summaries(List<LearnerRow> rows) {
        Map<UUID, List<SubjectLearnerEnrolment>> enrolments = enrolments(rows.stream().map(LearnerRow::id).toList());
        return rows.stream()
                .map(row -> new SubjectLearner(
                        row.id(),
                        row.status(),
                        row.version(),
                        row.status() == PersonalDataStatus.ANONYMIZED ? 0 : LearnerField.values().length - row.missingFields().size(),
                        enrolments.getOrDefault(row.id(), List.of()),
                        row.createdAt(),
                        row.updatedAt()
                ))
                .toList();
    }

    private Map<UUID, List<SubjectLearnerEnrolment>> enrolments(Collection<UUID> learnerIds) {
        return enrolmentRepository.findByLearners(learnerIds).stream().collect(Collectors.groupingBy(
                LearnerEnrolment::learnerId,
                Collectors.mapping(
                        enrolment -> new SubjectLearnerEnrolment(enrolment.courseName(), enrolment.streamNo(), enrolment.streamEndsOn()),
                        Collectors.toList()
                )
        ));
    }

    public record SubjectLearner(
            UUID id,
            PersonalDataStatus status,
            int version,
            int filledFields,
            List<SubjectLearnerEnrolment> enrolments,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record SubjectLearnerEnrolment(String courseName, int streamNo, LocalDate streamEndsOn) {
    }

    public record DisclosedField(String name, String value) {
        @Override
        public String toString() {
            return "DisclosedField[name=" + name + ", value=masked]";
        }
    }

    public record LearnerDisclosure(
            UUID id,
            PersonalDataStatus status,
            List<DisclosedField> fields,
            List<SubjectLearnerEnrolment> enrolments
    ) {
        @Override
        public String toString() {
            return "LearnerDisclosure[masked]";
        }
    }

    public record Counters(
            boolean moduleEnabled,
            long profiles,
            long profilesLimit,
            boolean nearLimit,
            long streamsWithoutEndDate,
            long profilesWithOldKey
    ) {
    }
}
