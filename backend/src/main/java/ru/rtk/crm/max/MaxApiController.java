package ru.rtk.crm.max;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.agreement.AgreementModels.AgreementSummary;
import ru.rtk.crm.agreement.AgreementService;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.catalog.Contact;
import ru.rtk.crm.catalog.ContactService;
import ru.rtk.crm.catalog.Organization;
import ru.rtk.crm.catalog.OrganizationNotFoundException;
import ru.rtk.crm.catalog.OrganizationRepository;
import ru.rtk.crm.catalog.PersonalDataStatus;
import ru.rtk.crm.interaction.Interaction;
import ru.rtk.crm.interaction.InteractionCommentRequest;
import ru.rtk.crm.interaction.InteractionEvent;
import ru.rtk.crm.interaction.InteractionFilter;
import ru.rtk.crm.interaction.InteractionNextStep;
import ru.rtk.crm.interaction.InteractionPage;
import ru.rtk.crm.interaction.InteractionQuery;
import ru.rtk.crm.interaction.InteractionService;
import ru.rtk.crm.interaction.InteractionStepCompletionRequest;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.security.RequestId;
import ru.rtk.crm.work.WorkModels.ReminderFeed;
import ru.rtk.crm.work.WorkModels.TeamIndicators;
import ru.rtk.crm.work.WorkService;

// Тонкий контроллер мини-приложения. Существующие контроллеры берут OidcUser и с сеансом MAX не работают, поэтому
// здесь профиль приходит из сеанса MAX и передаётся в те же сервисы CRM: права, область видимости, версия карточки и
// Idempotency-Key проверяются там же, где и для основной CRM. Своих проверок прав здесь нет; маршрутов к выгрузкам,
// договорам на изменение, назначению КАМ и анкетам нет совсем.
@RestController
@RequestMapping("/api/max/app")
@MaxEnabled
class MaxApiController {
    static final int PAGE_SIZE = 20;
    static final int CARD_EVENTS = 5;

    private final MaxSessionService sessions;
    private final WorkService workService;
    private final InteractionService interactionService;
    private final OrganizationRepository organizationRepository;
    private final ContactService contactService;
    private final AgreementService agreementService;
    private final MaxLinkRepository links;
    private final AuditJournalRepository auditJournalRepository;

    MaxApiController(
            MaxSessionService sessions,
            WorkService workService,
            InteractionService interactionService,
            OrganizationRepository organizationRepository,
            ContactService contactService,
            AgreementService agreementService,
            MaxLinkRepository links,
            AuditJournalRepository auditJournalRepository
    ) {
        this.sessions = sessions;
        this.workService = workService;
        this.interactionService = interactionService;
        this.organizationRepository = organizationRepository;
        this.contactService = contactService;
        this.agreementService = agreementService;
        this.links = links;
        this.auditJournalRepository = auditJournalRepository;
    }

    @PostMapping("/session")
    MaxSessionService.Started start(@RequestBody(required = false) SessionRequest body, HttpServletRequest request) {
        return sessions.start(body == null ? null : body.initData(), RequestId.from(request));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> end(@AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal) {
        sessions.end(principal.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal) {
        CrmProfile profile = principal.profile();
        return new Me(profile.role().name(), links.findDisplayName(profile.id()).orElse(null), profile.role() == UserRole.LEADER);
    }

    // Итоги групп — из той же ленты, что колокольчик и «Моя работа»; список — серверная страница выбранной группы.
    @GetMapping("/work")
    Work work(
            @AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal,
            @RequestParam(defaultValue = "TODAY") String due,
            @RequestParam(defaultValue = "0") int page
    ) {
        CrmProfile profile = principal.profile();
        ReminderFeed feed = workService.reminders(profile).feed();
        InteractionPage items = interactionService.list(profile, InteractionFilter.from(null, null, due, null),
                InteractionQuery.from(page, PAGE_SIZE, "nextActionAt,asc"));
        return new Work(feed, due, items);
    }

    @GetMapping("/interactions/{id}")
    Card card(@AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal, @PathVariable UUID id) {
        CrmProfile profile = principal.profile();
        Interaction interaction = interactionService.get(profile, id);
        Organization organization = organizationRepository.findVisibleById(profile, interaction.organizationId())
                .orElseThrow(OrganizationNotFoundException::new);
        List<InteractionEvent> events = interactionService.events(profile, id);
        List<CardContact> contacts = contactService.list(profile, organization.id()).stream()
                .filter(contact -> interaction.contactIds().contains(contact.id()))
                .filter(contact -> !contact.inactive() && contact.personalDataStatus() == PersonalDataStatus.ACTIVE)
                .map(contact -> new CardContact(contact.id(), contact.name(), contact.position(), contact.phone(), contact.email()))
                .toList();
        List<CardAgreement> agreements = agreementService.list(profile, organization.id()).stream()
                .map(agreement -> new CardAgreement(agreement.id(), agreement.number(), agreement.status().name(),
                        agreement.status().title(), agreement.validUntil()))
                .toList();
        return new Card(
                new CardOrganization(organization.id(), organization.name(), organization.ownerManagerName()),
                interaction,
                events.subList(Math.max(0, events.size() - CARD_EVENTS), events.size()).reversed(),
                contacts,
                agreements
        );
    }

    // Комментарий пишется в текущий этап: этап из мини-приложения не меняется. Шаг обязателен целиком: сервис стирает
    // поле шага, которого нет в запросе, и неполная форма тихо удалила бы срок или текст.
    @PostMapping("/interactions/{id}/comments")
    Interaction comment(
            @AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CommentRequest body,
            HttpServletRequest request
    ) {
        CrmProfile profile = principal.profile();
        if (body == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        InteractionNextStep nextStep = requiredNextStep(body.nextStep());
        UUID stageId = interactionService.get(profile, id).currentStageId();
        Interaction result = interactionService.comment(profile, id,
                new InteractionCommentRequest(body.version(), stageId, body.text(), List.of(), nextStep), idempotencyKey).interaction();
        audit(profile, id, "комментарий со следующим шагом", request);
        return result;
    }

    @PostMapping("/interactions/{id}/step-completions")
    Interaction completeStep(
            @AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) StepRequest body,
            HttpServletRequest request
    ) {
        CrmProfile profile = principal.profile();
        if (body == null) {
            throw new InteractionValidationException("body", "Не переданы данные запроса");
        }
        Interaction result = interactionService.completeStep(profile, id,
                new InteractionStepCompletionRequest(body.version(), body.result(), requiredNextStep(body.nextStep())), idempotencyKey);
        audit(profile, id, "шаг выполнен", request);
        return result;
    }

    // «Контроль» руководителя только на чтение; КАМ получает 403 из WorkService.
    @GetMapping("/control")
    TeamIndicators control(@AuthenticationPrincipal MaxSessionFilter.MaxPrincipal principal) {
        return workService.teamIndicators(principal.profile(), null);
    }

    private static InteractionNextStep requiredNextStep(InteractionNextStep nextStep) {
        if (nextStep == null || nextStep.nextAction() == null || nextStep.nextAction().isBlank() || nextStep.nextActionAt() == null) {
            throw new InteractionValidationException("nextStep", "Укажите следующий шаг и срок");
        }
        return nextStep;
    }

    private void audit(CrmProfile profile, UUID interactionId, String details, HttpServletRequest request) {
        auditJournalRepository.record(AuditAction.MAX_CHANGE, profile.id(), "INTERACTION", interactionId, null, details,
                RequestId.from(request));
    }

    record SessionRequest(String initData) {
    }

    record Me(String role, String displayName, boolean canControl) {
    }

    record Work(ReminderFeed feed, String due, InteractionPage items) {
    }

    record CardOrganization(UUID id, String name, String ownerManagerName) {
    }

    record CardContact(UUID id, String name, String position, String phone, String email) {
    }

    record CardAgreement(UUID id, String number, String status, String statusTitle, LocalDate validUntil) {
    }

    record Card(
            CardOrganization organization,
            Interaction interaction,
            List<InteractionEvent> events,
            List<CardContact> contacts,
            List<CardAgreement> agreements
    ) {
    }

    record CommentRequest(Integer version, String text, InteractionNextStep nextStep) {
    }

    record StepRequest(Integer version, String result, InteractionNextStep nextStep) {
    }
}
