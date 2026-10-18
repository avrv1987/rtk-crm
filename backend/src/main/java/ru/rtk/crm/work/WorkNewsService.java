package ru.rtk.crm.work;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.OrganizationRepository.VisibilityScope;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionValidationException;

@Service
public class WorkNewsService {
    static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final int LIMIT = 50;
    private static final int SUMMARY_LENGTH = 300;

    private final OrganizationRepository organizationRepository;
    private final WorkNewsRepository repository;

    public WorkNewsService(OrganizationRepository organizationRepository, WorkNewsRepository repository) {
        this.organizationRepository = organizationRepository;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public News news(CrmProfile profile, Integer requestedDays) {
        int days = requestedDays == null ? DEFAULT_DAYS : requestedDays;
        if (days < 1 || days > MAX_DAYS) {
            throw new InteractionValidationException("days", "Укажите целое число дней от 1 до " + MAX_DAYS);
        }
        VisibilityScope scope = scope(profile);
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        return new News(days, repository.countUnviewed(scope, profile.id(), since), repository.find(scope, profile.id(), since, LIMIT));
    }

    // Без общей транзакции: вставка идёт отдельным запросом, и гонка двух вкладок (нарушение ключа) не обрывает
    // транзакцию PostgreSQL, а гасится в репозитории.
    public void markViewed(CrmProfile profile, UUID eventId) {
        VisibilityScope scope = scope(profile);
        if (!repository.isVisibleSourceEvent(scope, profile.id(), eventId)) {
            throw new InteractionNotFoundException();
        }
        repository.markViewed(profile.id(), eventId, OffsetDateTime.now());
    }

    private VisibilityScope scope(CrmProfile profile) {
        if (profile.role() != UserRole.USER && profile.role() != UserRole.LEADER) {
            throw new WorkAccessDeniedException();
        }
        return organizationRepository.visibilityScope(profile).orElseThrow(WorkAccessDeniedException::new);
    }

    public enum NewsKind {
        SITE_REQUEST,
        LMS_DATA
    }

    public record News(int days, long unviewed, List<NewsItem> items) {
    }

    public record NewsItem(
            UUID eventId,
            UUID interactionId,
            UUID organizationId,
            String organizationName,
            String interactionTitle,
            NewsKind kind,
            String summary,
            OffsetDateTime occurredAt,
            boolean viewed
    ) {
        // Тексты событий источников пишет сама CRM (SiteRecordApplier, MoodleSnapshotApplier), поэтому вид
        // определяется по их началу. В строку идёт только первая строка: остальное, в том числе контакт из заявки,
        // открывается в истории взаимодействия.
        static NewsItem of(UUID eventId, UUID interactionId, UUID organizationId, String organizationName, String interactionTitle,
                           String comment, OffsetDateTime occurredAt, boolean viewed) {
            String text = comment == null ? "" : comment.strip();
            int lineEnd = text.indexOf('\n');
            String firstLine = lineEnd < 0 ? text : text.substring(0, lineEnd).strip();
            String summary = firstLine.length() <= SUMMARY_LENGTH ? firstLine : firstLine.substring(0, SUMMARY_LENGTH - 1) + "…";
            NewsKind kind = text.startsWith("Заявка с сайта") ? NewsKind.SITE_REQUEST : NewsKind.LMS_DATA;
            return new NewsItem(eventId, interactionId, organizationId, organizationName, interactionTitle, kind, summary, occurredAt, viewed);
        }
    }
}
