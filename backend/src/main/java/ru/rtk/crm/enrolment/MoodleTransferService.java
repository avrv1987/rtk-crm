package ru.rtk.crm.enrolment;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionConflictException;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.source.MoodleWriteTarget;
import ru.rtk.crm.source.SourceSettingsService;

// Передача партии в Moodle через API. В Moodle уходят только поля учётной записи: логин (почта), фамилия, имя,
// почта; паспорт, СНИЛС, адрес и диплом не отправляются. Результат строки ставится по ответу Moodle и сверке
// зачисления на курсе; повтор отправляет только не принятые строки и сначала ищет пользователя по почте, поэтому
// второго пользователя не создаёт.
@Service
public class MoodleTransferService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoodleTransferService.class);
    static final String SHARED_EMAIL = "Одна почта у нескольких слушателей: для Moodle нужна отдельная почта у каждого, "
            + "исправьте почту в анкете";
    // Moodle требует фамилию и имя; прежде вместо пустого поля уходил «-», и в Moodle появлялся пользователь «-».
    static final String NO_LAST_NAME = "Нет фамилии: заполните фамилию в анкете — Moodle не создаёт пользователя без неё";
    static final String NO_FIRST_NAME = "Нет имени: заполните имя в анкете — Moodle не создаёт пользователя без него";
    static final String NAME_NOT_CHECKED = "найдена учётная запись Moodle с этой почтой; Moodle не сообщил имя, поэтому оно "
            + "не сверено — проверьте имя пользователя в Moodle";

    private final EnrolmentAccess enrolmentAccess;
    private final EnrolmentBatchRepository batchRepository;
    private final EnrolmentRepository enrolmentRepository;
    private final EnrolmentBatchService batchService;
    private final EnrolmentStreamService streamService;
    private final LearnerService learnerService;
    private final MoodleWriteClient client;
    private final SourceSettingsService sourceSettings;
    private final AuditJournalRepository auditJournalRepository;
    private final EnrolmentCommands commands;

    MoodleTransferService(
            EnrolmentAccess enrolmentAccess,
            EnrolmentBatchRepository batchRepository,
            EnrolmentRepository enrolmentRepository,
            EnrolmentBatchService batchService,
            EnrolmentStreamService streamService,
            LearnerService learnerService,
            MoodleWriteClient client,
            SourceSettingsService sourceSettings,
            AuditJournalRepository auditJournalRepository,
            EnrolmentCommands commands
    ) {
        this.enrolmentAccess = enrolmentAccess;
        this.batchRepository = batchRepository;
        this.enrolmentRepository = enrolmentRepository;
        this.batchService = batchService;
        this.streamService = streamService;
        this.learnerService = learnerService;
        this.client = client;
        this.sourceSettings = sourceSettings;
        this.auditJournalRepository = auditJournalRepository;
        this.commands = commands;
    }

    @Transactional
    MoodleTransferResult transfer(CrmProfile actor, String id, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID batchId = EnrolmentAccess.parseId(id);
        MoodleWriteTarget target = requireTarget();
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.MOODLE_TRANSFER, idempotencyKey,
                commands.fingerprint(List.of(batchId)));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), MoodleTransferResult.class);
        }
        batchRepository.lock(batchId).orElseThrow(() -> new EnrolmentNotFoundException("Партия для LMS не найдена"));
        EnrolmentBatchRepository.BatchRow batch = batchRepository.find(batchId).orElseThrow();
        List<EnrolmentBatchRepository.ItemRow> allItems = batchRepository.findItems(batchId);
        List<EnrolmentBatchRepository.ItemRow> items = allItems.stream()
                .filter(item -> item.result() != BatchItemResult.ACCEPTED)
                .toList();
        Map<UUID, Learner> learners = learnerService.findAll(allItems.stream().map(EnrolmentBatchRepository.ItemRow::learnerId).distinct()
                        .toList()).stream()
                .collect(Collectors.toMap(Learner::id, Function.identity()));
        Set<String> sharedEmails = sharedEmails(allItems, learners);
        OffsetDateTime now = OffsetDateTime.now();
        Course course;
        try {
            course = course(batch.streamId());
        } catch (CourseMissing missing) {
            for (EnrolmentBatchRepository.ItemRow item : items) {
                batchRepository.recordResult(item.id(), BatchItemResult.FAILED, missing.getMessage(), "API", actor.id(), now);
            }
            return finish(actor, batchId, reservation, items.size(), 0, items.size(), 0, null, requestId);
        }
        Map<EnrolmentBatchRepository.ItemRow, Long> enrolled = new LinkedHashMap<>();
        Map<EnrolmentBatchRepository.ItemRow, String> notes = new LinkedHashMap<>();
        int failed = 0;
        int calls = 0;
        String stopped = null;
        for (EnrolmentBatchRepository.ItemRow item : items) {
            Learner learner = learners.get(item.learnerId());
            String problem = learner == null || learner.status() != PersonalDataStatus.ACTIVE
                    ? "Анкета недоступна для передачи: обработка ограничена, анкета обезличена или объединена"
                    : learner.profile().email() == null ? "Нет почты: логин Moodle не сформировать"
                    : sharedEmails.contains(emailKey(learner.profile().email())) ? SHARED_EMAIL
                    : blank(learner.profile().lastName()) ? NO_LAST_NAME
                    : blank(learner.profile().firstName()) ? NO_FIRST_NAME : null;
            if (problem != null) {
                batchRepository.recordResult(item.id(), BatchItemResult.FAILED, problem, "API", actor.id(), now);
                failed++;
                continue;
            }
            LearnerProfile profile = learner.profile();
            try {
                Counted<Long> user = ensureUser(target, profile);
                calls += user.calls();
                if (user.note() != null) {
                    notes.put(item, user.note());
                }
                client.enrol(target, user.value(), course.courseId());
                calls++;
                if (course.groupId() != null) {
                    client.addToGroup(target, user.value(), course.groupId());
                    calls++;
                }
                enrolled.put(item, user.value());
            } catch (MoodleWriteException exception) {
                if (exception.stopsBatch()) {
                    stopped = exception.getMessage();
                    break;
                }
                batchRepository.recordResult(item.id(), BatchItemResult.FAILED, limit(exception.getMessage()), "API", actor.id(), now);
                failed++;
            } catch (TooManyUsers | OtherPersonAccount exception) {
                batchRepository.recordResult(item.id(), BatchItemResult.FAILED, exception.getMessage(), "API", actor.id(), now);
                failed++;
            }
        }
        int accepted = 0;
        if (!enrolled.isEmpty()) {
            try {
                // Сверка по списку участников курса: «принята» ставится, только если Moodle показывает пользователя
                // на курсе, а не по факту успешного ответа на запрос зачисления.
                Set<Long> onCourse = client.enrolledUserIds(target, course.courseId());
                calls++;
                for (Map.Entry<EnrolmentBatchRepository.ItemRow, Long> entry : enrolled.entrySet()) {
                    EnrolmentBatchRepository.ItemRow item = entry.getKey();
                    if (onCourse.contains(entry.getValue())) {
                        batchRepository.recordResult(item.id(), BatchItemResult.ACCEPTED, notes.get(item), "API", actor.id(), now);
                        enrolmentRepository.markAccepted(item.enrolmentId(), batchId, batch.streamId(), item.learnerVersion(), now);
                        accepted++;
                    } else {
                        batchRepository.recordResult(item.id(), BatchItemResult.FAILED,
                                "После зачисления пользователь не найден среди участников курса Moodle", "API", actor.id(), now);
                        failed++;
                    }
                }
            } catch (MoodleWriteException exception) {
                stopped = stopped == null ? exception.getMessage() : stopped;
            }
        }
        batchRepository.markSent(batchId, actor.id(), now);
        return finish(actor, batchId, reservation, items.size(), accepted, failed, calls, stopped, requestId);
    }

    @Transactional
    MoodleMoveResult move(CrmProfile actor, String id, String idempotencyKey, String requestId) {
        enrolmentAccess.requireOperator(actor);
        UUID enrolmentId = EnrolmentAccess.parseId(id);
        MoodleWriteTarget target = requireTarget();
        EnrolmentCommands.Reservation reservation = commands.reserve(actor.id(), CommandOperation.MOODLE_MOVE, idempotencyKey,
                commands.fingerprint(List.of(enrolmentId)));
        if (reservation.repeated()) {
            return commands.read(reservation.previousResult(), MoodleMoveResult.class);
        }
        StreamEnrolmentRow row = enrolmentRepository.findStreamRow(enrolmentId)
                .orElseThrow(() -> new EnrolmentNotFoundException("Зачисление не найдено"));
        if (row.lmsReconcileReason() == null) {
            throw InteractionConflictException.stateConflict("Перенос этого зачисления уже сверен");
        }
        UUID previousStream = batchRepository.findPreviousStream(enrolmentId, row.streamId())
                .orElseThrow(() -> new InteractionValidationException("id", "Не найдена партия прежнего потока: перенесите в LMS вручную"));
        Course from = courseOrValidation(previousStream);
        Course to = courseOrValidation(row.streamId());
        Learner learner = learnerService.find(row.learnerId())
                .filter(found -> found.status() == PersonalDataStatus.ACTIVE && found.profile().email() != null)
                .orElseThrow(() -> new InteractionValidationException("id", "Анкета недоступна или без почты: перенесите в LMS вручную"));
        try {
            List<MoodleWriteClient.MoodleUser> users = client.findUsersByEmail(target, emailKey(learner.profile().email()));
            if (users.size() != 1) {
                throw new InteractionValidationException("id", users.isEmpty()
                        ? "В Moodle нет пользователя с почтой слушателя: перенесите в LMS вручную"
                        : "В Moodle несколько пользователей с почтой слушателя: перенесите в LMS вручную");
            }
            long userId = users.getFirst().id();
            if (from.courseId() != to.courseId()) {
                client.unenrol(target, userId, from.courseId());
            }
            client.enrol(target, userId, to.courseId());
            if (to.groupId() != null) {
                client.addToGroup(target, userId, to.groupId());
            }
            if (!client.enrolledUserIds(target, to.courseId()).contains(userId)) {
                throw new InteractionValidationException("id", "После переноса пользователь не найден на новом курсе Moodle");
            }
        } catch (MoodleWriteException exception) {
            throw new InteractionValidationException("id", limit(exception.getMessage()));
        }
        enrolmentRepository.markMovedInLms(enrolmentId, row.learnerVersion(), OffsetDateTime.now());
        StreamSummary stream = streamService.summary(row.streamId());
        auditJournalRepository.record(AuditAction.LMS_API_MOVE, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE, stream.id(),
                stream.title(), "курс Moodle " + from.courseId() + " → " + to.courseId() + "; зачисление сверено", requestId);
        MoodleMoveResult result = new MoodleMoveResult(enrolmentId, from.courseId(), to.courseId());
        commands.complete(reservation, result);
        return result;
    }

    boolean available() {
        return sourceSettings.moodleWrite().isPresent();
    }

    private MoodleTransferResult finish(CrmProfile actor, UUID batchId, EnrolmentCommands.Reservation reservation, int sent, int accepted,
                                        int failed, int calls, String stopped, String requestId) {
        EnrolmentBatchView view = batchService.view(batchId);
        // В журнал — только числа и причина остановки, без почты и имён: ответы Moodle могут содержать логин.
        auditJournalRepository.record(AuditAction.LMS_API_TRANSFER, actor.id(), EnrolmentStreamService.STREAM_OBJECT_TYPE,
                view.streamId(), view.streamTitle(), "партия: " + batchId + "; строк отправлено: " + sent + "; вызовов Moodle: " + calls
                        + "; принято: " + accepted + ", ошибок: " + failed
                        + (stopped == null ? "" : "; остановлено: Moodle недоступен или отклонил токен, остальные строки ожидают"), requestId);
        if (stopped != null) {
            LOGGER.warn("Moodle transfer of batch {} stopped: {}", batchId, stopped);
        }
        MoodleTransferResult result = new MoodleTransferResult(view, sent, accepted, failed, stopped);
        commands.complete(reservation, result);
        return result;
    }

    private Counted<Long> ensureUser(MoodleWriteTarget target, LearnerProfile profile) {
        String email = emailKey(profile.email());
        List<MoodleWriteClient.MoodleUser> users = client.findUsersByEmail(target, email);
        if (users.size() > 1) {
            throw new TooManyUsers();
        }
        String firstName = profile.firstName().strip();
        String lastName = profile.lastName().strip();
        if (users.isEmpty()) {
            return new Counted<>(client.createUser(target, email, firstName, lastName, email), 2, null);
        }
        // Чужую учётную запись не переименовываем и не зачисляем: при несовпадении имени это может быть другой человек
        // (родитель, однофамилец по общей почте), и тихая замена имени отдала бы его учётную запись слушателю.
        MoodleWriteClient.MoodleUser user = users.getFirst();
        if (user.firstName() != null || user.lastName() != null) {
            if (!sameName(user.firstName(), firstName) || !sameName(user.lastName(), lastName)) {
                throw new OtherPersonAccount();
            }
            return new Counted<>(user.id(), 1, null);
        }
        // Токен записи на настоящем Moodle часто отдаёт только fullname. Порядок слов в нём задаёт настройка
        // fullnamedisplay, поэтому сравниваем набор слов; отчество допускаем, если сайт его показывает.
        if (!blank(user.fullName())) {
            List<String> moodle = words(user.fullName());
            if (!moodle.equals(words(firstName + " " + lastName))
                    && !moodle.equals(words(firstName + " " + lastName + " " + (profile.middleName() == null ? "" : profile.middleName())))) {
                throw new OtherPersonAccount();
            }
            return new Counted<>(user.id(), 1, null);
        }
        // Имени в ответе нет совсем (токен без права видеть имена): блокировать нельзя — иначе ни один существующий
        // пользователь не пройдёт, а переименовывать вслепую нельзя тем более. Принимаем совпадение по почте и
        // оставляем пометку в протоколе строки, чтобы оператор мог сверить имя в Moodle.
        return new Counted<>(user.id(), 1, NAME_NOT_CHECKED);
    }

    private static List<String> words(String value) {
        return Arrays.stream(normalizedName(value).split("\\s+")).filter(word -> !word.isEmpty()).sorted().toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // Почта — логин Moodle, поэтому одна почта у разных слушателей партии дала бы одну учётную запись на двоих.
    // Принятые строки тоже учитываются: их учётная запись уже занята.
    private static Set<String> sharedEmails(List<EnrolmentBatchRepository.ItemRow> items, Map<UUID, Learner> learners) {
        Map<String, Set<UUID>> byEmail = new LinkedHashMap<>();
        for (EnrolmentBatchRepository.ItemRow item : items) {
            Learner learner = learners.get(item.learnerId());
            if (learner != null && learner.profile().email() != null) {
                byEmail.computeIfAbsent(emailKey(learner.profile().email()), ignored -> new LinkedHashSet<>()).add(learner.id());
            }
        }
        return byEmail.entrySet().stream().filter(entry -> entry.getValue().size() > 1).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static boolean sameName(String moodle, String crm) {
        return normalizedName(moodle).equals(normalizedName(crm));
    }

    private static String normalizedName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private MoodleWriteTarget requireTarget() {
        return sourceSettings.moodleWrite().orElseThrow(() -> new InteractionValidationException("moodle",
                "Передача через Moodle не включена: используйте файл для LMS"));
    }

    private Course courseOrValidation(UUID streamId) {
        try {
            return course(streamId);
        } catch (CourseMissing missing) {
            throw new InteractionValidationException("id", missing.getMessage());
        }
    }

    // Курс берётся из сопоставлений Moodle программы потока. Несколько курсов — ошибка: угадывать нельзя.
    // Группа добавляется, только если у курса ровно одна сопоставленная группа.
    Course course(UUID streamId) {
        Set<Long> courses = new LinkedHashSet<>();
        List<long[]> groups = new ArrayList<>();
        for (String[] mapping : batchRepository.findMoodleMappings(streamId)) {
            try {
                if ("COURSE".equals(mapping[0])) {
                    courses.add(Long.parseLong(mapping[1].strip()));
                } else {
                    String[] parts = mapping[1].split(":");
                    long courseId = Long.parseLong(parts[0].strip());
                    courses.add(courseId);
                    groups.add(new long[] {courseId, Long.parseLong(parts[1].strip())});
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Moodle mapping key of stream {} is not numeric", streamId);
            }
        }
        if (courses.isEmpty()) {
            throw new CourseMissing("Нет курса Moodle для потока: курс программы не сопоставлен. Анкета тут ни при чём — обратитесь"
                    + " к администратору: «Администрирование → Источники данных → Сопоставления»");
        }
        if (courses.size() > 1) {
            throw new CourseMissing("Для потока сопоставлено несколько курсов Moodle: оставьте один или передайте файлом");
        }
        long courseId = courses.iterator().next();
        List<long[]> courseGroups = groups.stream().filter(group -> group[0] == courseId).toList();
        return new Course(courseId, courseGroups.size() == 1 ? courseGroups.getFirst()[1] : null);
    }

    private static String emailKey(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String limit(String message) {
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    record Course(long courseId, Long groupId) {
    }

    private record Counted<T>(T value, int calls, String note) {
    }

    private static final class CourseMissing extends RuntimeException {
        CourseMissing(String message) {
            super(message, null, false, false);
        }
    }

    private static final class OtherPersonAccount extends RuntimeException {
        OtherPersonAccount() {
            super("В Moodle уже есть учётная запись с этой почтой на другое имя; CRM её не переименовывает. Если это тот же "
                    + "слушатель — исправьте имя в Moodle или в анкете и повторите; если другой человек — укажите в анкете "
                    + "отдельную почту", null, false, false);
        }
    }

    private static final class TooManyUsers extends RuntimeException {
        TooManyUsers() {
            super("В Moodle несколько пользователей с этой почтой: выберите учётную запись вручную", null, false, false);
        }
    }
}
