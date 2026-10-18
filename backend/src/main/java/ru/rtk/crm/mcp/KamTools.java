package ru.rtk.crm.mcp;

import static ru.rtk.crm.mcp.McpTools.enumProperty;
import static ru.rtk.crm.mcp.McpTools.integerProperty;
import static ru.rtk.crm.mcp.McpTools.property;
import static ru.rtk.crm.mcp.McpTools.schema;
import static ru.rtk.crm.mcp.McpTools.uuidProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementModels.Approval;
import ru.rtk.crm.agreement.AgreementModels.RegistryQuery;
import ru.rtk.crm.agreement.AgreementModels.RegistryRow;
import ru.rtk.crm.agreement.AgreementRegistryService;
import ru.rtk.crm.agreement.AgreementService;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationHistoryItem;
import ru.rtk.crm.catalog.OrganizationHistoryKind;
import ru.rtk.crm.catalog.OrganizationHistoryPage;
import ru.rtk.crm.catalog.OrganizationHistoryQuery;
import ru.rtk.crm.catalog.OrganizationHistoryService;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionDue;
import ru.rtk.crm.interaction.InteractionEvent;
import ru.rtk.crm.interaction.InteractionIssue;
import ru.rtk.crm.interaction.InteractionIssueFilter;
import ru.rtk.crm.interaction.InteractionIssuePage;
import ru.rtk.crm.interaction.InteractionIssueService;
import ru.rtk.crm.interaction.InteractionIssueStatus;
import ru.rtk.crm.interaction.InteractionMarks;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.interaction.InteractionNotFoundException;
import ru.rtk.crm.interaction.InteractionPage;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionSummary;
import ru.rtk.crm.interaction.InteractionTransitionOption;
import ru.rtk.crm.mcp.McpTools.Args;
import ru.rtk.crm.mcp.McpTools.Definition;
import ru.rtk.crm.mcp.McpTools.Page;
import ru.rtk.crm.mcp.McpTools.Result;
import ru.rtk.crm.mcp.McpTools.UserText;
import ru.rtk.crm.work.LmsSignalModels.WorkLearning;
import ru.rtk.crm.work.LmsWorkLearningService;
import ru.rtk.crm.work.WorkModels.ReminderAgreement;
import ru.rtk.crm.work.WorkModels.ReminderDigest;
import ru.rtk.crm.work.WorkModels.ReminderStep;
import ru.rtk.crm.work.WorkProperties;
import ru.rtk.crm.work.WorkService;

// Помощник КАМ: что горит, поиск своих взаимодействий, сводка по карточке и подготовка к встрече в вузе.
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
class KamTools {
    private static final List<InteractionDue> DUE_ORDER = List.of(
            InteractionDue.OVERDUE, InteractionDue.TODAY, InteractionDue.THIS_WEEK, InteractionDue.LATER, InteractionDue.NO_NEXT_STEP);
    // История контактов (телефоны и почты в изменениях) в сводку вуза не попадает.
    private static final List<String> HISTORY_KINDS = EnumSet.complementOf(EnumSet.of(OrganizationHistoryKind.CONTACT)).stream()
            .map(Enum::name)
            .toList();
    private static final String NO_RECORD = "Отсутствие записи в CRM не значит, что события не было: фиксируется не всё.";

    private final WorkService workService;
    private final InteractionService interactionService;
    private final InteractionIssueService interactionIssueService;
    private final AgreementService agreementService;
    private final AgreementRegistryService agreementRegistryService;
    private final ContactService contactService;
    private final OrganizationRepository organizationRepository;
    private final OrganizationHistoryService organizationHistoryService;
    private final LmsWorkLearningService lmsWorkLearningService;
    private final WorkProperties workProperties;
    private final McpProperties properties;

    KamTools(
            WorkService workService,
            InteractionService interactionService,
            InteractionIssueService interactionIssueService,
            AgreementService agreementService,
            AgreementRegistryService agreementRegistryService,
            ContactService contactService,
            OrganizationRepository organizationRepository,
            OrganizationHistoryService organizationHistoryService,
            LmsWorkLearningService lmsWorkLearningService,
            WorkProperties workProperties,
            McpProperties properties
    ) {
        this.workService = workService;
        this.interactionService = interactionService;
        this.interactionIssueService = interactionIssueService;
        this.agreementService = agreementService;
        this.agreementRegistryService = agreementRegistryService;
        this.contactService = contactService;
        this.organizationRepository = organizationRepository;
        this.organizationHistoryService = organizationHistoryService;
        this.lmsWorkLearningService = lmsWorkLearningService;
        this.workProperties = workProperties;
        this.properties = properties;
    }

    List<Definition> definitions() {
        return List.of(
                new Definition("my_today", "Что горит сегодня",
                        "Что горит у меня: итоги ленты «Моя работа» (просрочено, сегодня, неделя, без шага), просроченные "
                                + "и ближайшие шаги, мои проблемы и риски, истекающие договоры. Для КАМ и руководителя.",
                        schema(Map.of()), McpTools.KAM, this::myToday),
                new Definition("find_interactions", "Поиск взаимодействий",
                        "Мои взаимодействия (в моей области видимости) по вузу, тексту, сроку шага, этапу и статусу. "
                                + "Ближайшие сроки первыми; total — сколько всего подходит.",
                        schema(Map.of(
                                "query", property("string", "Часть названия вуза или взаимодействия"),
                                "organizationId", uuidProperty("Вуз"),
                                "due", enumProperty("Срок следующего шага", "OVERDUE", "TODAY", "THIS_WEEK", "LATER",
                                        "NO_NEXT_STEP"),
                                "stage", property("string", "Название этапа"),
                                "status", enumProperty("Статус работы, по умолчанию ACTIVE", "ACTIVE", "PAUSED", "COMPLETED", "ALL"),
                                "minDaysOnStage", integerProperty("На текущем этапе не меньше N дней", 1, 3650),
                                "limit", integerProperty("Сколько строк вернуть, по умолчанию 25", 1, 50))),
                        McpTools.KAM_AND_MANAGEMENT, this::findInteractions),
                new Definition("interaction_brief", "Сводка по взаимодействию",
                        "Сводка по одному взаимодействию: этап N из M, следующий шаг и срок, риски и проблемы, договор "
                                + "с вузом и цвет согласования, данные LMS, последние события.",
                        schema(Map.of("interactionId", uuidProperty("Взаимодействие")), "interactionId"),
                        McpTools.KAM_AND_MANAGEMENT, this::interactionBrief),
                new Definition("organization_brief", "Подготовка к встрече в вузе",
                        "Подготовка к встрече в вузе: карточка, все взаимодействия, контакты (телефон и почта "
                                + "замаскированы), договоры, история за 90 дней.",
                        schema(Map.of("organizationId", uuidProperty("Вуз")), "organizationId"),
                        McpTools.KAM_AND_MANAGEMENT, this::organizationBrief)
        );
    }

    private Result myToday(CrmProfile profile, Args args) {
        ReminderDigest digest = workService.reminders(profile);
        int limit = properties.listLimit();
        InteractionIssuePage issues = interactionIssueService.registry(
                profile, InteractionIssueFilter.from(null, null, profile.id().toString(), null, false, null), 0, limit);
        LocalDate today = LocalDate.now(WorkProperties.ZONE);
        Page<Step> overdue = Page.of(digest.overdue().stream().map(KamTools::step).toList(), digest.overdueTotal(), limit);
        Page<Step> upcoming = Page.of(digest.upcoming().stream().map(KamTools::step).toList(), digest.upcomingTotal(), limit);
        Page<Issue> myIssues = Page.of(issues.items().stream().map(issue -> issue(issue, today)).toList(), issues.total(), limit);
        Page<ExpiringAgreement> agreements = Page.of(
                digest.expiringAgreements().stream().map(KamTools::expiring).toList(),
                knownTotal(digest.expiringAgreements(), workProperties.reminderListLimit()), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feed", digest.feed());
        data.put("dueTodayTotal", digest.dueTodayTotal());
        data.put("overdueSteps", overdue);
        data.put("upcomingDays", digest.upcomingDays());
        data.put("upcomingSteps", upcoming);
        data.put("myIssues", myIssues);
        data.put("agreementNoticeDays", digest.agreementNoticeDays());
        data.put("expiringAgreements", agreements);
        return new Result(data, overdue.returned() + upcoming.returned() + myIssues.returned() + agreements.returned(), List.of(
                "feed — те же серверные итоги, что в ленте «Моя работа»: overdue — просрочено, today — сегодня, thisWeek — "
                        + "на этой неделе, later — позже, noNextStep — без следующего шага, resumeDue и resumeNoDate — "
                        + "приостановленные с наступившей датой возврата и без даты.",
                "myIssues — открытые проблемы и риски, где ответственный — вы.",
                NO_RECORD));
    }

    private Result findInteractions(CrmProfile profile, Args args) {
        int limit = args.integer("limit", 1, 50, properties.listLimit());
        int minDays = args.integer("minDaysOnStage", 1, 3650, 0);
        InteractionPage page = interactionService.find(
                profile,
                args.uuid("organizationId"),
                args.text("query"),
                args.text("due"),
                args.text("stage"),
                args.text("status"),
                minDays == 0 ? null : Integer.toString(minDays),
                limit
        );
        OffsetDateTime now = OffsetDateTime.now();
        Page<Work> works = Page.of(page.items().stream().map(item -> work(item, now)).toList(), page.total(), limit);
        return new Result(works, works.returned(), List.of(
                "due — группа срока следующего шага по правилу CRM; границы дня и недели — по Москве.", NO_RECORD));
    }

    private Result interactionBrief(CrmProfile profile, Args args) {
        UUID interactionId = args.requiredUuid("interactionId");
        Interaction interaction = interactionService.get(profile, interactionId);
        Organization organization = organizationRepository.findVisibleById(profile, interaction.organizationId())
                .orElseThrow(InteractionNotFoundException::new);
        InteractionService.RecentEvents events = interactionService.recentEvents(profile, interactionId, properties.eventLimit());
        List<AgreementSummary> agreements = agreementService.workAgreements(profile, interactionId).agreements();
        Map<UUID, Approval> approvals = approvals(profile, organization.id());
        WorkLearning learning = lmsWorkLearningService.byWork(profile, List.of(interactionId)).get(interactionId);
        List<Issue> issues = interactionIssueService.list(profile, interactionId).items().stream()
                .filter(issue -> issue.status() == InteractionIssueStatus.OPEN)
                .map(issue -> issue(issue, LocalDate.now(WorkProperties.ZONE)))
                .toList();
        List<InteractionStage> stages = interaction.stages();
        int position = stages.stream().map(InteractionStage::id).toList().indexOf(interaction.currentStageId()) + 1;
        Page<Event> recent = Page.of(events.items().stream().map(KamTools::event).toList(), events.total(), properties.eventLimit());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("interactionId", interaction.id());
        data.put("title", interaction.title());
        data.put("organizationId", organization.id());
        data.put("organization", organization.name());
        data.put("owner", organization.ownerManagerName());
        data.put("stage", interaction.currentStageName());
        data.put("stagePosition", position);
        data.put("stageTotal", stages.size());
        data.put("nextStages", interaction.allowedTransitions().stream().map(InteractionTransitionOption::stageName).toList());
        data.put("nextAction", UserText.of(interaction.nextAction()));
        data.put("nextActionAt", interaction.nextActionAt());
        data.put("lastContactAt", interaction.lastContactAt());
        data.put("marks", marks(interaction.marks()));
        data.put("openIssues", issues);
        data.put("agreements", agreements.stream().map(agreement -> agreement(agreement, approvals.get(agreement.id()))).toList());
        data.put("learning", learning);
        data.put("recentEvents", recent);
        return new Result(data, 1 + issues.size() + agreements.size() + recent.returned(), List.of(
                "learning = null — по взаимодействию нет потоков LMS; это не «ноль обучающихся».",
                "approval.color считает CRM по порогам согласования договора (NORMAL, YELLOW, RED).",
                "recentEvents — последние события, новые первыми.",
                NO_RECORD));
    }

    private Result organizationBrief(CrmProfile profile, Args args) {
        UUID organizationId = args.requiredUuid("organizationId");
        Organization organization = organizationRepository.findVisibleById(profile, organizationId)
                .orElseThrow(OrganizationNotFoundException::new);
        int limit = properties.listLimit();
        OffsetDateTime now = OffsetDateTime.now();
        InteractionPage works = interactionService.find(profile, organizationId, null, null, null, "ALL", null, limit);
        List<ContactInfo> contacts = contactService.list(profile, organizationId).stream()
                .filter(contact -> contact.personalDataStatus() == null || contact.personalDataStatus() == PersonalDataStatus.ACTIVE)
                .map(KamTools::contact)
                .toList();
        Map<UUID, Approval> approvals = approvals(profile, organizationId);
        List<AgreementInfo> agreements = agreementService.list(profile, organizationId).stream()
                .map(agreement -> agreement(agreement, approvals.get(agreement.id())))
                .toList();
        String from = LocalDate.now(WorkProperties.ZONE).minusDays(properties.historyDays()).toString();
        OrganizationHistoryPage history = organizationHistoryService.history(
                profile, organizationId, OrganizationHistoryQuery.from(HISTORY_KINDS, from, null, 0, limit));
        Page<Work> workPage = Page.of(works.items().stream().map(item -> work(item, now)).toList(), works.total(), limit);
        Page<ContactInfo> contactPage = Page.of(contacts, (long) contacts.size(), limit);
        Page<HistoryItem> historyPage = Page.of(history.items().stream().map(KamTools::history).toList(), history.total(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("organizationId", organization.id());
        data.put("name", organization.name());
        data.put("type", organization.type());
        data.put("status", organization.status());
        data.put("city", organization.city());
        data.put("website", organization.website());
        data.put("team", organization.teamName());
        data.put("owner", organization.ownerManagerName());
        data.put("requiresAssignment", organization.requiresAssignment());
        data.put("deputy", organization.deputyManagerName());
        data.put("deputyEndsOn", organization.deputyEndsOn());
        data.put("interactions", workPage);
        data.put("contacts", contactPage);
        data.put("agreements", agreements);
        data.put("historyDays", properties.historyDays());
        data.put("history", historyPage);
        return new Result(data, 1 + workPage.returned() + contactPage.returned() + agreements.size() + historyPage.returned(),
                List.of("Телефоны и почты замаскированы; полные данные — в карточке вуза в CRM.",
                        "Контакты с ограниченной или обезличенной обработкой ПДн не показываются.",
                        "history — события за последние " + properties.historyDays() + " дней, новые первыми.",
                        NO_RECORD));
    }

    private Map<UUID, Approval> approvals(CrmProfile profile, UUID organizationId) {
        return agreementRegistryService.registry(profile, new RegistryQuery(null, organizationId, null, null, null, false)).stream()
                .filter(row -> row.approval() != null)
                .collect(Collectors.toMap(RegistryRow::id, RegistryRow::approval, (first, second) -> first));
    }

    private static Long knownTotal(List<?> list, int serviceLimit) {
        return list.size() < serviceLimit ? (long) list.size() : null;
    }

    static String due(InteractionWorkStatus status, String nextAction, OffsetDateTime nextActionAt, OffsetDateTime now) {
        return DUE_ORDER.stream().filter(due -> due.matches(status, nextAction, nextActionAt, now)).findFirst().map(Enum::name).orElse(null);
    }

    private static Work work(InteractionSummary item, OffsetDateTime now) {
        InteractionMarks marks = item.marks();
        return new Work(
                item.id(),
                item.organizationId(),
                item.organizationName(),
                item.title(),
                item.currentStageName(),
                item.stageEnteredAt() == null ? null : ChronoUnit.DAYS.between(item.stageEnteredAt(), now),
                UserText.of(item.nextAction()),
                item.nextActionAt(),
                due(marks == null ? null : marks.status(), item.nextAction(), item.nextActionAt(), now),
                marks == null || marks.status() == null ? null : marks.status().name(),
                marks == null || marks.riskLevel() == null ? null : marks.riskLevel().name(),
                marks == null ? 0 : marks.problemCount(),
                marks == null ? 0 : marks.riskCount(),
                item.ownerManagerName(),
                item.deputyManagerName(),
                item.lastContactAt()
        );
    }

    private static Map<String, Object> marks(InteractionMarks marks) {
        if (marks == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", marks.status());
        result.put("statusReason", UserText.of(marks.statusReason()));
        result.put("waitingOn", marks.waitingOn());
        result.put("waitingNote", UserText.of(marks.waitingNote()));
        result.put("riskLevel", marks.riskLevel());
        result.put("problemCount", marks.problemCount());
        result.put("riskCount", marks.riskCount());
        return result;
    }

    private static Step step(ReminderStep step) {
        return new Step(step.interactionId(), step.organizationId(), step.organizationName(), step.title(),
                UserText.of(step.nextAction()), step.nextActionAt(), step.ownerManagerName());
    }

    private static Issue issue(InteractionIssue issue, LocalDate today) {
        return new Issue(issue.id(), issue.interactionId(), issue.organizationName(), issue.interactionTitle(), issue.kind().name(),
                issue.riskLevel() == null ? null : issue.riskLevel().name(), UserText.of(issue.description()), issue.dueOn(),
                issue.dueOn() != null && issue.dueOn().isBefore(today), issue.responsibleName());
    }

    private static ExpiringAgreement expiring(ReminderAgreement agreement) {
        return new ExpiringAgreement(agreement.agreementId(), agreement.organizationId(), agreement.organizationName(),
                agreement.number(), agreement.validUntil(), agreement.ownerManagerName());
    }

    private static AgreementInfo agreement(AgreementSummary agreement, Approval approval) {
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        return new AgreementInfo(agreement.id(), agreement.number(), agreement.status().name(), agreement.status().title(),
                agreement.concludedOn(), agreement.validUntil(), agreement.status().expired(agreement.validUntil(), today),
                agreement.sentOn(), approval);
    }

    private static ContactInfo contact(Contact contact) {
        return new ContactInfo(contact.name(), contact.position(), contact.role() == null ? null : contact.role().name(),
                contact.primary(), contact.inactive(), McpTools.maskEmail(contact.email()), McpTools.maskPhone(contact.phone()));
    }

    private static Event event(InteractionEvent event) {
        return new Event(event.occurredAt(), event.type() == null ? null : event.type().name(), event.actorDisplayName(),
                event.stageNameSnapshot(), event.fromStageNameSnapshot(), event.toStageNameSnapshot(), UserText.of(event.comment()),
                event.nextStep() == null ? null : UserText.of(event.nextStep().nextAction()),
                event.nextStep() == null ? null : event.nextStep().nextActionAt());
    }

    private static HistoryItem history(OrganizationHistoryItem item) {
        return new HistoryItem(item.occurredAt(), item.kind().name(), item.actorName(), item.interactionTitle(),
                item.fromStageName(), item.stageName(), UserText.of(item.description()), UserText.of(item.comment()));
    }

    record Step(UUID interactionId, UUID organizationId, String organization, String title, UserText nextAction,
                OffsetDateTime nextActionAt, String owner) {
    }

    record Issue(UUID id, UUID interactionId, String organization, String interaction, String kind, String riskLevel,
                 UserText description, LocalDate dueOn, boolean overdue, String responsible) {
    }

    record ExpiringAgreement(UUID agreementId, UUID organizationId, String organization, String number, LocalDate validUntil,
                             String owner) {
    }

    record Work(UUID interactionId, UUID organizationId, String organization, String title, String stage, Long daysOnStage,
                UserText nextAction, OffsetDateTime nextActionAt, String due, String status, String riskLevel, int problems,
                int risks, String owner, String deputy, OffsetDateTime lastContactAt) {
    }

    record AgreementInfo(UUID agreementId, String number, String status, String statusTitle, LocalDate concludedOn,
                         LocalDate validUntil, boolean expired, LocalDate sentOn, Approval approval) {
    }

    record ContactInfo(String name, String position, String role, boolean primary, boolean inactive, String email,
                       String phone) {
    }

    record Event(OffsetDateTime occurredAt, String type, String actor, String stage, String fromStage, String toStage,
                 UserText comment, UserText nextAction, OffsetDateTime nextActionAt) {
    }

    record HistoryItem(OffsetDateTime occurredAt, String kind, String actor, String interaction, String fromStage,
                       String stage, UserText description, UserText comment) {
    }
}
