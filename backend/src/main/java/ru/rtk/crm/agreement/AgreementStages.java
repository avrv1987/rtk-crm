package ru.rtk.crm.agreement;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.rtk.crm.agreement.AgreementModels.AgreementStatus;
import ru.rtk.crm.agreement.AgreementModels.StageRole;
import ru.rtk.crm.agreement.AgreementModels.WorkStage;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class AgreementStages {
    // Этап, дальше которого нельзя пройти без действующего договора, отмечен в шаблоне признаком «требует действующего
    // договора» (interaction_stages.requires_agreement, V59); в блоке договора он показывается как «подписание».
    // Этапы «обмен» и «корректировка» правило не включают: они лишь показывают блок договора и останавливают счёт
    // «на этапе дольше N дней». Их по-прежнему узнают по идентификатору этапа базового шаблона или по названию из
    // настроек.
    private static final Map<UUID, StageRole> BASE_TEMPLATE_STAGES = Map.of(
            UUID.fromString("00000000-0000-0000-0000-000000000814"), StageRole.EXCHANGE,
            UUID.fromString("00000000-0000-0000-0000-000000000815"), StageRole.CORRECTION
    );

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcClient jdbcClient;
    private final boolean signingRule;
    private final Map<StageRole, List<String>> names;

    public AgreementStages(
            JdbcClient jdbcClient,
            @Value("${app.agreements.signing-stage-rule:true}") boolean signingRule,
            @Value("${app.agreements.exchange-stage-names:Обмен документами}") List<String> exchangeNames,
            @Value("${app.agreements.correction-stage-names:Корректировка документов}") List<String> correctionNames
    ) {
        this.jdbcClient = jdbcClient;
        this.signingRule = signingRule;
        this.names = Map.of(
                StageRole.EXCHANGE, normalized(exchangeNames),
                StageRole.CORRECTION, normalized(correctionNames)
        );
    }

    /** Общий выключатель правила договора: при false отметки этапов не проверяются. */
    public boolean signingRule() {
        return signingRule;
    }

    // Тот же признак этапа договора, что role(), но в SQL. Параметры — currentStageParameters().
    public String currentStageCondition(String interactionAlias) {
        String byName = names.values().stream().allMatch(List::isEmpty)
                ? ""
                : " OR LOWER(TRIM(agreement_stage.name)) IN (:agreementStageNames)";
        return "EXISTS (SELECT 1 FROM interaction_stages agreement_stage WHERE agreement_stage.id = " + interactionAlias
                + ".current_stage_id AND (agreement_stage.requires_agreement = TRUE"
                + " OR agreement_stage.template_stage_id IN (:agreementStageTemplates)" + byName + "))";
    }

    // Работа на этапе договора, а последний по смене статуса связанный договор на согласовании, корректировке или
    // подписании — то же правило, что WorkAgreementStatus.stageUnderApproval в AgreementRepository.findWorkStatuses.
    public String stageUnderApprovalCondition(String interactionAlias) {
        return "(" + currentStageCondition(interactionAlias) + " AND (SELECT latest.status FROM agreement_interactions latest_link"
                + " JOIN agreements latest ON latest.id = latest_link.agreement_id WHERE latest_link.interaction_id = " + interactionAlias
                + ".id ORDER BY latest.status_changed_at DESC, latest.id LIMIT 1) IN ('REVIEW', 'CORRECTION', 'SIGNING'))";
    }

    public Map<String, Object> currentStageParameters() {
        return Map.of(
                "agreementStageTemplates", List.copyOf(BASE_TEMPLATE_STAGES.keySet()),
                "agreementStageNames", names.values().stream().flatMap(List::stream).toList()
        );
    }

    public List<WorkStage> stages(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT id, name, template_stage_id, requires_agreement
                FROM interaction_stages
                WHERE interaction_id = :interactionId
                ORDER BY stage_order, id
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new StageRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getObject("template_stage_id", UUID.class),
                        resultSet.getBoolean("requires_agreement")
                ))
                .list()
                .stream()
                .flatMap(row -> role(row).map(role -> new WorkStage(row.id(), row.name(), role)).stream())
                .toList();
    }

    // Проверка нужна только для движения вперёд и только если переход «проходит через» отмеченный этап, то есть
    // уходит с него или перешагивает его (from <= этап < to). Возврат назад и переходы мимо этапа не блокируются.
    public void requireSignedToLeave(UUID interactionId, List<InteractionStage> stages, InteractionStage from, InteractionStage to) {
        if (!signingRule || to.order() <= from.order()) {
            return;
        }
        stages.stream()
                .filter(InteractionStage::requiresAgreement)
                .filter(stage -> stage.order() >= from.order() && stage.order() < to.order())
                .findFirst()
                .ifPresent(stage -> requireActiveAgreement(interactionId, stage.name()));
    }

    public void requireSignedToComplete(UUID interactionId, InteractionStage stage) {
        if (signingRule && stage.requiresAgreement()) {
            requireActiveAgreement(interactionId, stage.name());
        }
    }

    // Решение владельца 19.10: достаточно любого действующего договора школы с вузом этой карточки, даже не связанного
    // с ней. «Действующий» — статус «Действует» с датой подписания и не истёкшим сроком (valid_until не раньше
    // сегодняшнего дня по МСК): истёкший договор скрыл бы упущенное продление. «Завершён» действующим не считается.
    // В сообщении называется самый свежий по смене статуса договор вуза, чтобы было понятно, какой перевести.
    private void requireActiveAgreement(UUID interactionId, String stageName) {
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        List<OrganizationAgreement> agreements = organizationAgreements(interactionId);
        if (agreements.stream().anyMatch(agreement -> agreement.signed() && !agreement.expired(today))) {
            return;
        }
        String rule = "Дальше этапа «" + stageName + "» можно пройти только при действующем договоре с вузом.";
        Optional<OrganizationAgreement> expired = agreements.stream()
                .filter(agreement -> agreement.signed() && agreement.expired(today))
                .findFirst();
        if (expired.isPresent()) {
            throw new InteractionValidationException("agreement", rule + " Срок договора № " + expired.get().number() + " истёк "
                    + DATE.format(expired.get().validUntil()) + ": продлите срок в реквизитах договора или оформите новый договор");
        }
        throw new InteractionValidationException("agreement", agreements.isEmpty()
                ? rule + " Создайте договор с вузом в этом взаимодействии"
                : rule + " Переведите договор № " + agreements.getFirst().number() + " в статус «Действует» с датой подписания");
    }

    /** Номер действующего договора школы с вузом карточки (тот же критерий, что у правила), если такой есть. */
    public Optional<String> activeAgreementNumber(UUID interactionId) {
        return activeAgreements(interactionId).stream().map(OrganizationAgreement::number).findFirst();
    }

    // Договор, которым правило выполнено: сначала связанный с этой работой, иначе самый свежий по смене статуса договор
    // вуза. Карточка и окно перехода называют именно его, чтобы не расходиться с сервером (решение владельца 19.10).
    public Optional<ActiveAgreement> activeAgreement(UUID interactionId) {
        List<OrganizationAgreement> active = activeAgreements(interactionId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> linked = jdbcClient.sql("SELECT agreement_id FROM agreement_interactions WHERE interaction_id = :interactionId")
                .param("interactionId", interactionId)
                .query(UUID.class)
                .list();
        OrganizationAgreement chosen = active.stream().filter(agreement -> linked.contains(agreement.id())).findFirst()
                .orElse(active.getFirst());
        return Optional.of(new ActiveAgreement(chosen.id(), chosen.number(), chosen.concludedOn(), chosen.validUntil(),
                linked.contains(chosen.id())));
    }

    private List<OrganizationAgreement> activeAgreements(UUID interactionId) {
        LocalDate today = LocalDate.now(AgreementService.ZONE);
        return organizationAgreements(interactionId).stream()
                .filter(agreement -> agreement.signed() && !agreement.expired(today))
                .toList();
    }

    private List<OrganizationAgreement> organizationAgreements(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT ag.id, ag.number, ag.status, ag.concluded_on, ag.valid_until,
                       CASE WHEN ag.status = 'ACTIVE' AND ag.concluded_on IS NOT NULL THEN 1 ELSE 0 END AS signed
                FROM agreements ag
                JOIN interactions i ON i.organization_id = ag.organization_id
                WHERE i.id = :interactionId
                ORDER BY ag.status_changed_at DESC, ag.id
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new OrganizationAgreement(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("number"),
                        resultSet.getInt("signed") == 1,
                        AgreementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("concluded_on", LocalDate.class),
                        resultSet.getObject("valid_until", LocalDate.class)
                ))
                .list();
    }

    public record ActiveAgreement(UUID id, String number, LocalDate concludedOn, LocalDate validUntil, boolean linked) {
    }

    private record OrganizationAgreement(UUID id, String number, boolean signed, AgreementStatus status, LocalDate concludedOn,
                                         LocalDate validUntil) {
        boolean expired(LocalDate today) {
            return status.expired(validUntil, today);
        }
    }

    private Optional<StageRole> role(StageRow row) {
        if (row.requiresAgreement()) {
            return Optional.of(StageRole.SIGNING);
        }
        StageRole byTemplate = row.templateStageId() == null ? null : BASE_TEMPLATE_STAGES.get(row.templateStageId());
        if (byTemplate != null) {
            return Optional.of(byTemplate);
        }
        String normalized = row.name() == null ? "" : row.name().strip().toLowerCase(Locale.ROOT);
        return names.entrySet().stream()
                .filter(entry -> entry.getValue().contains(normalized))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private record StageRow(UUID id, String name, UUID templateStageId, boolean requiresAgreement) {
    }

    private static List<String> normalized(List<String> values) {
        return values.stream().map(value -> value.strip().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty()).toList();
    }
}
