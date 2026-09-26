package ru.rtk.crm.enrolment;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;

@Service
public class PaidOrderEnrolment {
    static final String RUN_OBJECT_TYPE = "SYNC_RUN";

    private final LearnerService learnerService;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerDataCipher cipher;
    private final AuditJournalRepository auditJournalRepository;

    PaidOrderEnrolment(
            LearnerService learnerService,
            EnrolmentRepository enrolmentRepository,
            LearnerDataCipher cipher,
            AuditJournalRepository auditJournalRepository
    ) {
        this.learnerService = learnerService;
        this.enrolmentRepository = enrolmentRepository;
        this.cipher = cipher;
        this.auditJournalRepository = auditJournalRepository;
    }

    public boolean enabled() {
        return cipher.enabled();
    }

    @Transactional
    public LearnerIntake accept(PaidOrder order, String courseKey, UUID sourceRecordId, UUID actorId) {
        OffsetDateTime now = OffsetDateTime.now();
        EnrolmentStream stream = enrolmentRepository.findOrCreateStream(courseKey, order.course(), order.streamNumber(), now);
        Optional<LearnerEnrolment> existing = enrolmentRepository.findBySourceRecord(sourceRecordId);
        if (existing.isPresent()) {
            return acceptKnown(existing.get(), stream, order, actorId, now);
        }
        Optional<Learner> found = match(order);
        if (found.isEmpty()) {
            Learner created = learnerService.create(contacts(order), actorId);
            enrolmentRepository.createEnrolment(created.id(), stream.id(), sourceRecordId, now);
            return new LearnerIntake(1, 0, 1, 0, 0, 0, 0);
        }
        Learner learner = found.get();
        int contactsDiffer = complete(learner, order, actorId) ? 1 : 0;
        if (enrolmentRepository.enrolled(learner.id(), stream.id())) {
            return new LearnerIntake(0, 1, 0, 1, 0, 0, contactsDiffer);
        }
        enrolmentRepository.createEnrolment(learner.id(), stream.id(), sourceRecordId, now);
        return new LearnerIntake(0, 1, 1, 0, 0, 0, contactsDiffer);
    }

    public void journalUpload(UUID actorId, PaidOrderUpload upload, String requestId) {
        auditJournalRepository.record(AuditAction.PAID_ORDERS_UPLOADED, actorId, RUN_OBJECT_TYPE, upload.runId(),
                "Сайт: загрузка файла оплат",
                "получено: " + upload.received() + ", пустых элементов: " + upload.emptyElements() + "; " + upload.learners().details(),
                requestId);
    }

    public void journalSync(UUID runId, LearnerIntake intake) {
        auditJournalRepository.record(AuditAction.LEARNERS_SYNCED, null, RUN_OBJECT_TYPE, runId, "Сайт: синхронизация оплат",
                "запуск: " + runId + "; " + intake.details(), null);
    }

    private LearnerIntake acceptKnown(
            LearnerEnrolment enrolment,
            EnrolmentStream stream,
            PaidOrder order,
            UUID actorId,
            OffsetDateTime now
    ) {
        Learner learner = learnerService.find(enrolment.learnerId()).orElseThrow(LearnerNotFoundException::new);
        int contactsDiffer = complete(learner, order, actorId) ? 1 : 0;
        if (enrolment.streamId().equals(stream.id())) {
            return new LearnerIntake(0, 1, 0, 0, 0, 0, contactsDiffer);
        }
        int afterLms = enrolment.lmsExportedAt() == null ? 0 : 1;
        if (enrolmentRepository.enrolled(learner.id(), stream.id())) {
            enrolmentRepository.delete(enrolment.id());
            return new LearnerIntake(0, 1, 0, 1, 1, afterLms, contactsDiffer);
        }
        enrolmentRepository.moveToStream(enrolment.id(), stream.id(), now);
        return new LearnerIntake(0, 1, 0, 0, 1, afterLms, contactsDiffer);
    }

    private Optional<Learner> match(PaidOrder order) {
        return learnerService.findByContacts(order.email(), order.phone()).stream()
                .filter(learner -> learner.status() == PersonalDataStatus.ACTIVE)
                .filter(learner -> LearnerRules.samePerson(learner.profile(), order.lastName(), order.firstName(), order.middleName()))
                .findFirst();
    }

    private boolean complete(Learner learner, PaidOrder order, UUID actorId) {
        if (learner.status() != PersonalDataStatus.ACTIVE) {
            return false;
        }
        LearnerProfile profile = learner.profile();
        Map<LearnerField, String> changes = new EnumMap<>(LearnerField.class);
        fill(changes, LearnerField.LAST_NAME, profile.lastName(), order.lastName());
        fill(changes, LearnerField.FIRST_NAME, profile.firstName(), order.firstName());
        fill(changes, LearnerField.MIDDLE_NAME, profile.middleName(), order.middleName());
        fill(changes, LearnerField.PHONE, profile.phone(), order.phone());
        fill(changes, LearnerField.EMAIL, profile.email(), order.email());
        if (!changes.isEmpty()) {
            learnerService.update(actorId, learner.id(), learner.version(), profile.with(changes), null);
        }
        boolean phoneDiffers = profile.phone() != null && order.phone() != null && !profile.phone().equals(order.phone());
        boolean emailDiffers = profile.email() != null && order.email() != null
                && !LearnerRules.emailKey(profile.email()).equals(LearnerRules.emailKey(order.email()));
        return phoneDiffers || emailDiffers;
    }

    private static void fill(Map<LearnerField, String> changes, LearnerField field, String current, String value) {
        if (current == null && value != null) {
            changes.put(field, value);
        }
    }

    private static LearnerProfile contacts(PaidOrder order) {
        Map<LearnerField, String> values = new EnumMap<>(LearnerField.class);
        fill(values, LearnerField.LAST_NAME, null, order.lastName());
        fill(values, LearnerField.FIRST_NAME, null, order.firstName());
        fill(values, LearnerField.MIDDLE_NAME, null, order.middleName());
        fill(values, LearnerField.PHONE, null, order.phone());
        fill(values, LearnerField.EMAIL, null, order.email());
        return LearnerProfile.fromStored(values);
    }
}
