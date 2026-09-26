package ru.rtk.crm.enrolment;

import java.text.Collator;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.privacy.RetentionProperties;

@Service
public class EnrolmentStreamService {
    static final String STREAM_OBJECT_TYPE = "STREAM";
    static final Comparator<String> RUSSIAN = Comparator.nullsLast(Collator.getInstance(Locale.forLanguageTag("ru"))::compare);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final EnrolmentAccess enrolmentAccess;
    private final EnrolmentRepository enrolmentRepository;
    private final LearnerService learnerService;
    private final LearnerPrivacyService learnerPrivacyService;
    private final RetentionProperties retentionProperties;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;

    EnrolmentStreamService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentRepository enrolmentRepository,
            LearnerService learnerService,
            LearnerPrivacyService learnerPrivacyService,
            RetentionProperties retentionProperties,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.enrolmentRepository = enrolmentRepository;
        this.learnerService = learnerService;
        this.learnerPrivacyService = learnerPrivacyService;
        this.retentionProperties = retentionProperties;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
    }

    EnrolmentStreamsView streams(CrmProfile actor) {
        enrolmentAccess.requireOperator(actor);
        Map<UUID, List<StreamEnrolmentRow>> rows = enrolmentRepository.findAllStreamRows().stream()
                .collect(Collectors.groupingBy(StreamEnrolmentRow::streamId));
        List<EnrolmentStreamView> streams = enrolmentRepository.findSummaries().stream()
                .map(summary -> view(summary, rows.getOrDefault(summary.id(), List.of())))
                .toList();
        LearnerPrivacyService.Counters counters = learnerPrivacyService.counters();
        return new EnrolmentStreamsView(streams, new EnrolmentCountersView(
                counters.profiles(), counters.profilesLimit(), counters.nearLimit(), counters.streamsWithoutEndDate()
        ));
    }

    @Transactional
    EnrolmentStreamView update(CrmProfile actor, String id, EnrolmentStreamUpdate request, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID streamId = EnrolmentAccess.parseId(id);
        if (request == null || request.version() == null || request.version() < 0) {
            throw new InteractionValidationException("version", "Укажите версию потока");
        }
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.UPDATE_ENROLMENT_STREAM,
                idempotencyKey, commands.fingerprint(Arrays.asList(streamId, request.version(), request.endsOn())));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), EnrolmentStreamView.class);
        }
        StreamSummary stream = summary(streamId);
        if (stream.version() != request.version()) {
            throw InteractionConflictException.enrolmentStreamVersion(stream.version());
        }
        if (!Objects.equals(stream.endsOn(), request.endsOn())) {
            if (!enrolmentRepository.updateEndDate(streamId, request.version(), request.endsOn(), OffsetDateTime.now())) {
                throw InteractionConflictException.enrolmentStreamVersion(stream.version());
            }
            auditJournalRepository.record(AuditAction.STREAM_END_DATE_CHANGED, actor.id(), STREAM_OBJECT_TYPE, streamId, stream.title(),
                    "дата окончания: " + date(stream.endsOn()) + " → " + date(request.endsOn()), requestId);
        }
        EnrolmentStreamView view = view(streamId);
        commands.complete(reservation, view);
        return view;
    }

    @Transactional
    EnrolmentStreamLearners learners(CrmProfile actor, String id, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID streamId = EnrolmentAccess.parseId(id);
        StreamSummary stream = summary(streamId);
        List<StreamEnrolmentRow> rows = enrolmentRepository.findStreamRows(streamId);
        Map<UUID, Learner> learners = learnerService.findAll(rows.stream().map(StreamEnrolmentRow::learnerId).toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        Map<String, Long> emails = emailCounts(rows);
        List<StreamLearner> items = rows.stream()
                .map(row -> streamLearner(row, learners.get(row.learnerId()), emails))
                .sorted(Comparator.comparing(StreamLearner::lastName, RUSSIAN)
                        .thenComparing(StreamLearner::firstName, RUSSIAN)
                        .thenComparing(StreamLearner::enrolmentId))
                .toList();
        List<RosterExport> exports = rows.stream()
                .filter(row -> row.lmsExportId() != null)
                .collect(Collectors.groupingBy(StreamEnrolmentRow::lmsExportId))
                .entrySet().stream()
                .map(entry -> new RosterExport(
                        entry.getKey(),
                        entry.getValue().stream().map(StreamEnrolmentRow::lmsExportedAt).max(Comparator.naturalOrder()).orElseThrow(),
                        entry.getValue().size(),
                        (int) entry.getValue().stream().filter(row -> row.lmsTransferredAt() != null).count()
                ))
                .sorted(Comparator.comparing(RosterExport::exportedAt).reversed())
                .toList();
        RosterSummary roster = new RosterSummary(
                scope(rows.stream().filter(row -> row.lmsTransferredAt() == null).toList()),
                scope(rows)
        );
        auditJournalRepository.record(AuditAction.LEARNER_LIST_VIEWED, actor.id(), STREAM_OBJECT_TYPE, streamId, stream.title(),
                "строк: " + items.size(), requestId);
        return new EnrolmentStreamLearners(view(stream, rows), items, exports, roster);
    }

    StreamSummary summary(UUID streamId) {
        return enrolmentRepository.findSummary(streamId).orElseThrow(() -> new EnrolmentNotFoundException("Поток не найден"));
    }

    EnrolmentStreamView view(UUID streamId) {
        return view(summary(streamId), enrolmentRepository.findStreamRows(streamId));
    }

    static Map<String, Long> emailCounts(List<StreamEnrolmentRow> rows) {
        return rows.stream()
                .filter(row -> row.emailHmac() != null)
                .collect(Collectors.groupingBy(StreamEnrolmentRow::emailHmac, Collectors.counting()));
    }

    static LearnerCompleteness completeness(PersonalDataStatus status, Set<LearnerField> missing) {
        return LearnerRules.completeness(status == PersonalDataStatus.ANONYMIZED
                ? EnumSet.allOf(LearnerField.class) : missing);
    }

    private EnrolmentStreamView view(StreamSummary stream, List<StreamEnrolmentRow> rows) {
        Period term = retentionProperties.learnerProfiles();
        return new EnrolmentStreamView(
                stream.id(),
                stream.courseName(),
                stream.streamNo(),
                stream.programName(),
                stream.endsOn(),
                stream.endsOn() == null || term == null ? null : stream.endsOn().plus(term),
                stream.version(),
                rows.size(),
                (int) rows.stream().filter(row -> completeness(row.status(), row.missingFields()).complete()).count(),
                (int) rows.stream().filter(row -> row.lmsExportedAt() != null).count(),
                (int) rows.stream().filter(row -> row.lmsTransferredAt() != null).count(),
                (int) rows.stream().filter(row -> row.lmsTransferredAt() == null).count()
        );
    }

    private static StreamLearner streamLearner(StreamEnrolmentRow row, Learner learner, Map<String, Long> emails) {
        LearnerProfile profile = learner == null ? null : learner.profile();
        LearnerCompleteness completeness = completeness(row.status(), row.missingFields());
        return new StreamLearner(
                row.learnerId(),
                row.id(),
                row.orderNumber(),
                row.status(),
                profile == null ? null : profile.lastName(),
                profile == null ? null : profile.firstName(),
                profile == null ? null : profile.middleName(),
                profile == null ? null : LearnerFieldGroup.mask(LearnerField.PHONE, profile.phone()),
                profile == null ? null : LearnerFieldGroup.mask(LearnerField.EMAIL, profile.email()),
                completeness.filled(),
                completeness.required(),
                completeness.complete(),
                row.status() == PersonalDataStatus.ANONYMIZED ? Set.of() : LearnerRules.missingForLms(row.missingFields()),
                row.emailHmac() != null && emails.getOrDefault(row.emailHmac(), 0L) > 1,
                LmsStatus.of(row.lmsExportedAt(), row.lmsTransferredAt()),
                row.lmsExportId(),
                row.lmsExportedAt(),
                row.lmsTransferredAt()
        );
    }

    private static RosterScope scope(List<StreamEnrolmentRow> rows) {
        List<StreamEnrolmentRow> available = rows.stream().filter(StreamEnrolmentRow::available).toList();
        Map<String, Long> emails = emailCounts(available);
        return new RosterScope(
                available.size(),
                (int) available.stream().filter(StreamEnrolmentRow::missingForLms).count(),
                (int) available.stream().filter(row -> row.emailHmac() != null && emails.get(row.emailHmac()) > 1).count(),
                rows.size() - available.size()
        );
    }

    private static String date(LocalDate value) {
        return value == null ? "не указана" : DATE.format(value);
    }
}
