package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.agreement.ReportingModels.ActivityListSummary;
import ru.rtk.crm.agreement.ReportingModels.ListChannel;
import ru.rtk.crm.agreement.ReportingModels.ListStatus;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;

// Согласование «молчанием»: если вуз не ответил до конца срока, перечень считается согласованным со следующего дня.
// Задачи по расписанию нет — статус меняется при первом обращении к перечням после срока (экран, кабинет вуза,
// напоминания, команды). Отдельная транзакция нужна, потому что обращение может идти из транзакции только для чтения.
@Component
public class ActivityListSilence {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ReportingRepository repository;
    private final AuditJournalRepository auditJournalRepository;

    public ActivityListSilence(ReportingRepository repository, AuditJournalRepository auditJournalRepository) {
        this.repository = repository;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int settle(LocalDate today) {
        int settled = 0;
        for (ActivityListSummary list : repository.findSilenceExpired(today)) {
            OffsetDateTime now = OffsetDateTime.now();
            LocalDate decidedOn = list.reviewDueOn().plusDays(1);
            if (repository.approveSilently(list.id(), decidedOn, now) != 1) {
                continue;
            }
            String comment = "Вуз не ответил до " + DATE.format(list.reviewDueOn()) + "; перечень согласован без ответа";
            repository.insertEvent(list.id(), ListStatus.REVIEW, ListStatus.APPROVED_SILENTLY, ListChannel.SYSTEM, comment, null, now);
            auditJournalRepository.record(AuditAction.AGREEMENT_ACTIVITY_LIST_CHANGED, null, "AGREEMENT_ACTIVITY_LIST", list.id(),
                    list.organizationName() + ", " + list.title(), "на согласовании → согласован без ответа (срок истёк "
                            + DATE.format(list.reviewDueOn()) + ")", null);
            settled++;
        }
        return settled;
    }
}
