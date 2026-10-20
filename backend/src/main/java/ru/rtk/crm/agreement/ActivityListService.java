package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.ContactInteractionMutationAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.agreement.ReportingModels.ActivityList;
import ru.rtk.crm.agreement.ReportingModels.ActivityListCreateRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListItem;
import ru.rtk.crm.agreement.ReportingModels.ActivityListSummary;
import ru.rtk.crm.agreement.ReportingModels.ActivityListTransitionRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListUpdateRequest;
import ru.rtk.crm.agreement.ReportingModels.ActivityListsView;
import ru.rtk.crm.agreement.ReportingModels.ListAction;
import ru.rtk.crm.agreement.ReportingModels.ListChannel;
import ru.rtk.crm.agreement.ReportingModels.ListStatus;
import ru.rtk.crm.agreement.ReportingModels.PartnerDecisionRequest;
import ru.rtk.crm.agreement.ReportingModels.PeriodRange;
import ru.rtk.crm.agreement.ReportingModels.Settings;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.interaction.CommandOperation;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.module.ModuleProperties;
import ru.rtk.crm.partner.PartnerCabinetRepository;
import ru.rtk.crm.partner.PartnerException;

// Перечень мероприятий на период: КАМ формирует его из плана договоров вуза и отправляет вузу. Решение принимает
// координатор вуза в кабинете; у вуза без координатора ответ вуза отмечает КАМ. Без ответа до конца срока (настройка)
// перечень согласуется «молчанием» (ActivityListSilence).
@Service
public class ActivityListService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ReportingRepository repository;
    private final ReportingSettingsService settingsService;
    private final ActivityListSilence silence;
    private final AgreementService agreementService;
    private final OrganizationRepository organizationRepository;
    private final PartnerCabinetRepository partnerCabinetRepository;
    private final AuditJournalRepository auditJournalRepository;
    private final ModuleProperties modules;

    public ActivityListService(
            ReportingRepository repository,
            ReportingSettingsService settingsService,
            ActivityListSilence silence,
            AgreementService agreementService,
            OrganizationRepository organizationRepository,
            PartnerCabinetRepository partnerCabinetRepository,
            AuditJournalRepository auditJournalRepository,
            ModuleProperties modules
    ) {
        this.repository = repository;
        this.settingsService = settingsService;
        this.silence = silence;
        this.agreementService = agreementService;
        this.organizationRepository = organizationRepository;
        this.partnerCabinetRepository = partnerCabinetRepository;
        this.auditJournalRepository = auditJournalRepository;
        this.modules = modules;
    }

    @Transactional
    public ActivityListsView view(CrmProfile profile, UUID organizationId) {
        visibleOrganization(profile, organizationId);
        silence.settle(today());
        Settings settings = settingsService.settings();
        return new ActivityListsView(coordinator(organizationId), settingsService.currentPeriod(settings, today()),
                repository.findLists(organizationId, true));
    }

    @Transactional
    public ActivityList get(CrmProfile profile, UUID listId) {
        ActivityListSummary list = visibleList(profile, listId);
        silence.settle(today());
        return full(profile, listId, list.organizationId());
    }

    @Transactional
    public ActivityList create(CrmProfile profile, UUID organizationId, ActivityListCreateRequest request, String idempotencyKey,
                               String requestId) {
        Organization organization = visibleOrganization(profile, organizationId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        if (request.year() == null || request.year() < 2000 || request.year() > 2100) {
            throw new InteractionValidationException("year", "Укажите год периода");
        }
        PeriodRange period = settingsService.period(settingsService.settings(), request.year());
        String title = title(request.title(), period);
        List<UUID> activityIds = ids(request.activityIds());
        return agreementService.idempotent(profile, CommandOperation.CREATE_ACTIVITY_LIST, idempotencyKey,
                new CreateCommand(organizationId, period, title, activityIds), ActivityList.class, () -> {
                    List<ActivityListItem> items = items(organizationId, period.from(), period.to(), activityIds);
                    UUID id = UUID.randomUUID();
                    OffsetDateTime now = OffsetDateTime.now();
                    repository.insertList(id, organizationId, title, period.from(), period.to(), period.label(), profile.id(), now);
                    repository.replaceItems(id, items);
                    repository.insertEvent(id, null, ListStatus.DRAFT, ListChannel.KAM,
                            "Сформирован из плана договоров: мероприятий " + items.size(), profile.id(), now);
                    auditJournalRepository.record(AuditAction.AGREEMENT_ACTIVITY_LIST_CHANGED, profile.id(), "AGREEMENT_ACTIVITY_LIST", id,
                            organization.name() + ", " + title, "создан проект, мероприятий: " + items.size(), requestId);
                    return full(profile, id, organizationId);
                });
    }

    @Transactional
    public ActivityList update(CrmProfile profile, UUID listId, ActivityListUpdateRequest request, String idempotencyKey) {
        ActivityListSummary list = visibleList(profile, listId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        String title = AgreementService.nullableText(request.title());
        if (title == null) {
            throw new InteractionValidationException("title", "Заполните поле");
        }
        List<UUID> activityIds = ids(request.activityIds());
        return agreementService.idempotent(profile, CommandOperation.UPDATE_ACTIVITY_LIST, idempotencyKey,
                new UpdateCommand(listId, version, title, activityIds), ActivityList.class, () -> {
                    // Статус перечитывается в транзакции: экран мог показывать проект, который тем временем отправили вузу.
                    ActivityListSummary current = repository.findList(listId).orElse(list);
                    if (!current.status().editable()) {
                        throw AgreementException.listNotEditable(current.version(), current.status());
                    }
                    if (current.version() != version) {
                        throw AgreementException.listVersion(current.version());
                    }
                    List<ActivityListItem> items = items(list.organizationId(), list.periodFrom(), list.periodTo(), activityIds);
                    if (repository.updateListTitle(listId, version, title, OffsetDateTime.now()) != 1) {
                        throw AgreementException.listVersion(currentVersion(list));
                    }
                    repository.replaceItems(listId, items);
                    return full(profile, listId, list.organizationId());
                });
    }

    static final String EMPTY_LIST = "В перечне нет позиций: добавьте в состав мероприятия плана договоров, затем отправьте вузу";

    @Transactional
    public ActivityList transition(CrmProfile profile, UUID listId, ActivityListTransitionRequest request, String idempotencyKey,
                                   String requestId) {
        ActivityListSummary visible = visibleList(profile, listId);
        ContactInteractionMutationAuthorization.requireCardEditor(profile);
        int version = requiredVersion(request.version());
        if (request.action() == null) {
            throw new InteractionValidationException("action", "Выберите действие");
        }
        String comment = AgreementService.nullableText(request.comment());
        TransitionCommand command = new TransitionCommand(listId, version, request.action(), comment, request.respondedOn());
        return agreementService.idempotent(profile, CommandOperation.TRANSITION_ACTIVITY_LIST, idempotencyKey, command, ActivityList.class,
                () -> {
                    LocalDate today = today();
                    silence.settle(today);
                    ActivityListSummary list = repository.findList(listId).orElse(visible);
                    if (list.version() != version) {
                        throw AgreementException.listVersion(list.version());
                    }
                    if (request.action() == ListAction.SEND) {
                        send(profile, list, comment, requestId);
                    } else {
                        // Отметка КАМ — только у вуза без координатора: иначе решение принимает координатор в кабинете вуза.
                        if (coordinator(list.organizationId())) {
                            throw new InteractionValidationException("action",
                                    "У вуза есть координатор: решение по перечню принимает он в кабинете вуза");
                        }
                        LocalDate respondedOn = request.respondedOn() == null ? today : request.respondedOn();
                        if (respondedOn.isAfter(today) || (list.sentOn() != null && respondedOn.isBefore(list.sentOn()))) {
                            throw new InteractionValidationException("respondedOn",
                                    "Дата ответа вуза — не раньше отправки и не позже сегодняшней");
                        }
                        String note = "Отметка КАМ: ответ вуза от " + DATE.format(respondedOn) + (comment == null ? "" : ". " + comment);
                        decide(profile, list, request.action(), comment, note, respondedOn, ListChannel.KAM, requestId);
                    }
                    return full(profile, listId, list.organizationId());
                });
    }

    // Кабинет вуза: перечни своего вуза, кроме проектов, с позициями и историей.
    @Transactional
    public List<ActivityList> partnerLists(CrmProfile partner) {
        UUID organizationId = partnerOrganization(partner);
        silence.settle(today());
        return repository.findLists(organizationId, false).stream()
                .map(list -> new ActivityList(list, repository.findItems(list.id()), repository.findEvents(list.id()), true,
                        list.status() == ListStatus.REVIEW ? List.of(ListAction.APPROVE, ListAction.RETURN) : List.of()))
                .toList();
    }

    @Transactional
    public ActivityList partnerDecide(CrmProfile partner, UUID listId, PartnerDecisionRequest request, String idempotencyKey,
                                      String requestId) {
        UUID organizationId = partnerOrganization(partner);
        ActivityListSummary visible = repository.findList(listId)
                .filter(list -> list.organizationId().equals(organizationId) && list.status() != ListStatus.DRAFT)
                .orElseThrow(AgreementException::listNotFound);
        int version = requiredVersion(request.version());
        if (request.decision() == null || request.decision() == ListAction.SEND) {
            throw new InteractionValidationException("decision", "Выберите: согласовать или вернуть");
        }
        String comment = AgreementService.nullableText(request.comment());
        return agreementService.idempotent(partner, CommandOperation.DECIDE_ACTIVITY_LIST, idempotencyKey,
                new DecisionCommand(listId, version, request.decision(), comment), ActivityList.class, () -> {
                    LocalDate today = today();
                    silence.settle(today);
                    ActivityListSummary list = repository.findList(listId).orElse(visible);
                    if (list.version() != version) {
                        throw AgreementException.listVersion(list.version());
                    }
                    decide(partner, list, request.decision(), comment, comment, today, ListChannel.COORDINATOR, requestId);
                    ActivityListSummary saved = repository.findList(listId).orElseThrow(AgreementException::listNotFound);
                    return new ActivityList(saved, repository.findItems(listId), repository.findEvents(listId), true, List.of());
                });
    }

    private void send(CrmProfile profile, ActivityListSummary list, String comment, String requestId) {
        if (!list.status().editable()) {
            throw new InteractionValidationException("action", "Отправить можно проект или возвращённый перечень");
        }
        // Пустой перечень вуз мог бы «согласовать», и цикл отчётности закрылся бы без единой позиции.
        if (list.itemCount() == 0) {
            throw new InteractionValidationException("action", EMPTY_LIST);
        }
        Settings settings = settingsService.settings();
        int round = list.reviewRound() + 1;
        // Срок ответа фиксируется при отправке: смена настройки потом не меняет срок уже отправленного перечня.
        Integer days = round > 1 && settings.repeatSilenceDays() != null ? settings.repeatSilenceDays() : settings.silenceDays();
        LocalDate today = today();
        LocalDate due = days == null ? null : today.plusDays(days);
        OffsetDateTime now = OffsetDateTime.now();
        if (repository.updateListStatus(list.id(), list.version(), ListStatus.REVIEW, round, today, due, null, now) != 1) {
            throw AgreementException.listVersion(currentVersion(list));
        }
        String channel = coordinator(list.organizationId()) ? "в кабинет вуза" : "вузу (ответ отметит КАМ)";
        String note = "Отправлен " + channel + (due == null ? "; срок ответа не задан" : "; срок ответа — " + DATE.format(due))
                + (comment == null ? "" : ". " + comment);
        repository.insertEvent(list.id(), list.status(), ListStatus.REVIEW, ListChannel.KAM, note, profile.id(), now);
        audit(profile, list, list.status(), ListStatus.REVIEW, note, requestId);
    }

    private void decide(CrmProfile actor, ActivityListSummary list, ListAction action, String comment, String note, LocalDate decidedOn,
                        ListChannel channel, String requestId) {
        if (list.status() != ListStatus.REVIEW) {
            throw new InteractionValidationException("status", "Решение принимается по перечню на согласовании");
        }
        if (action == ListAction.RETURN && comment == null) {
            throw new InteractionValidationException("comment", "Укажите, что нужно доработать");
        }
        ListStatus next = action == ListAction.APPROVE ? ListStatus.APPROVED : ListStatus.RETURNED;
        OffsetDateTime now = OffsetDateTime.now();
        if (repository.updateListStatus(list.id(), list.version(), next, list.reviewRound(), list.sentOn(), list.reviewDueOn(), decidedOn,
                now) != 1) {
            throw AgreementException.listVersion(currentVersion(list));
        }
        repository.insertEvent(list.id(), ListStatus.REVIEW, next, channel, note, actor.id(), now);
        audit(actor, list, ListStatus.REVIEW, next, (channel == ListChannel.COORDINATOR ? "координатор вуза" : "отметка КАМ")
                + (note == null ? "" : ": " + note), requestId);
    }

    private void audit(CrmProfile actor, ActivityListSummary list, ListStatus from, ListStatus to, String details, String requestId) {
        auditJournalRepository.record(AuditAction.AGREEMENT_ACTIVITY_LIST_CHANGED, actor.id(), "AGREEMENT_ACTIVITY_LIST", list.id(),
                list.organizationName() + ", " + list.title(),
                (from == null ? "" : from.title().toLowerCase() + " → ") + to.title().toLowerCase() + "; " + details, requestId);
    }

    private ActivityList full(CrmProfile profile, UUID listId, UUID organizationId) {
        ActivityListSummary list = repository.findList(listId).orElseThrow(AgreementException::listNotFound);
        boolean coordinator = coordinator(organizationId);
        return new ActivityList(list, repository.findItems(listId), repository.findEvents(listId), coordinator,
                actions(profile, list.status(), coordinator));
    }

    static List<ListAction> actions(CrmProfile profile, ListStatus status, boolean coordinator) {
        boolean editor = profile.role() == ru.rtk.crm.access.UserRole.USER || profile.role() == ru.rtk.crm.access.UserRole.LEADER;
        if (!editor) {
            return List.of();
        }
        if (status.editable()) {
            return List.of(ListAction.SEND);
        }
        return status == ListStatus.REVIEW && !coordinator ? List.of(ListAction.APPROVE, ListAction.RETURN) : List.of();
    }

    private boolean coordinator(UUID organizationId) {
        return modules.partnerEnabled() && repository.hasCoordinator(organizationId);
    }

    private List<ActivityListItem> items(UUID organizationId, LocalDate from, LocalDate to, List<UUID> activityIds) {
        List<ActivityListItem> items = repository.findPlanItems(organizationId, from, to, activityIds);
        if (activityIds != null && items.size() != activityIds.size()) {
            throw new InteractionValidationException("activityIds",
                    "В перечень входят только мероприятия плана договоров этого вуза на период, кроме отменённых и отклонённых");
        }
        return items;
    }

    private static String title(String requested, PeriodRange period) {
        String title = AgreementService.nullableText(requested);
        return title == null ? "Перечень мероприятий на " + period.label() : title;
    }

    private static List<UUID> ids(List<UUID> values) {
        if (values == null) {
            return null;
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InteractionValidationException("activityIds", "Список не должен содержать пустых значений");
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private Organization visibleOrganization(CrmProfile profile, UUID organizationId) {
        return organizationRepository.findVisibleById(profile, organizationId).orElseThrow(OrganizationNotFoundException::new);
    }

    private ActivityListSummary visibleList(CrmProfile profile, UUID listId) {
        ActivityListSummary list = repository.findList(listId).orElseThrow(AgreementException::listNotFound);
        organizationRepository.findVisibleById(profile, list.organizationId()).orElseThrow(AgreementException::listNotFound);
        return list;
    }

    private UUID partnerOrganization(CrmProfile partner) {
        return partnerCabinetRepository.findOrganizationId(partner.id()).orElseThrow(PartnerException::accessClosed);
    }

    private int currentVersion(ActivityListSummary list) {
        return repository.findList(list.id()).map(ActivityListSummary::version).orElse(list.version());
    }

    private static int requiredVersion(Integer version) {
        if (version == null) {
            throw new InteractionValidationException("version", "Укажите версию, которую вы изменяете");
        }
        return version;
    }

    private static LocalDate today() {
        return LocalDate.now(AgreementService.ZONE);
    }

    private record CreateCommand(UUID organizationId, PeriodRange period, String title, List<UUID> activityIds) {
    }

    private record UpdateCommand(UUID listId, int version, String title, List<UUID> activityIds) {
    }

    private record TransitionCommand(UUID listId, int version, ListAction action, String comment, LocalDate respondedOn) {
    }

    private record DecisionCommand(UUID listId, int version, ListAction decision, String comment) {
    }
}
