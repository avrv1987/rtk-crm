package ru.rtk.crm.agreement;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.rtk.crm.agreement.AgreementModels.StageRole;
import ru.rtk.crm.agreement.AgreementModels.WorkStage;
import ru.rtk.crm.interaction.InteractionStage;
import ru.rtk.crm.interaction.InteractionValidationException;

@Component
public class AgreementStages {
    // Роль этапа («обмен», «корректировка», «подписание») определяется сначала по идентификатору этапа базового
    // шаблона, а если этап добавлен вручную или переименован — по названию из настроек (role). Так правило
    // подписания работает и в пользовательских шаблонах, и после переименования этапа в карточке.
    private static final Map<UUID, StageRole> BASE_TEMPLATE_STAGES = Map.of(
            UUID.fromString("00000000-0000-0000-0000-000000000814"), StageRole.EXCHANGE,
            UUID.fromString("00000000-0000-0000-0000-000000000815"), StageRole.CORRECTION,
            UUID.fromString("00000000-0000-0000-0000-000000000816"), StageRole.SIGNING
    );

    private final JdbcClient jdbcClient;
    private final boolean signingRule;
    private final Map<StageRole, List<String>> names;

    public AgreementStages(
            JdbcClient jdbcClient,
            @Value("${app.agreements.signing-stage-rule:true}") boolean signingRule,
            @Value("${app.agreements.exchange-stage-names:Обмен документами}") List<String> exchangeNames,
            @Value("${app.agreements.correction-stage-names:Корректировка документов}") List<String> correctionNames,
            @Value("${app.agreements.signing-stage-names:Подписание}") List<String> signingNames
    ) {
        this.jdbcClient = jdbcClient;
        this.signingRule = signingRule;
        this.names = Map.of(
                StageRole.EXCHANGE, normalized(exchangeNames),
                StageRole.CORRECTION, normalized(correctionNames),
                StageRole.SIGNING, normalized(signingNames)
        );
    }

    public boolean signingRule() {
        return signingRule;
    }

    public List<WorkStage> stages(UUID interactionId) {
        return jdbcClient.sql("""
                SELECT id, name, template_stage_id
                FROM interaction_stages
                WHERE interaction_id = :interactionId
                ORDER BY stage_order, id
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> new StageRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getObject("template_stage_id", UUID.class)
                ))
                .list()
                .stream()
                .flatMap(row -> role(row.templateStageId(), row.name()).map(role -> new WorkStage(row.id(), row.name(), role)).stream())
                .toList();
    }

    // Правило этапа «Подписание»: проверка нужна только для движения вперёд и только если переход «проходит
    // через» этап подписания, то есть уходит с него или перешагивает его (from <= подписание < to). Возврат
    // назад и переходы мимо подписания не блокируются.
    public void requireSignedToLeave(UUID interactionId, List<InteractionStage> stages, InteractionStage from, InteractionStage to) {
        if (!signingRule || to.order() <= from.order()) {
            return;
        }
        List<UUID> signing = signingStageIds(interactionId);
        boolean passesSigning = stages.stream()
                .filter(stage -> signing.contains(stage.id()))
                .anyMatch(stage -> stage.order() >= from.order() && stage.order() < to.order());
        if (passesSigning) {
            requireSignedAgreement(interactionId);
        }
    }

    public void requireSignedToComplete(UUID interactionId, InteractionStage stage) {
        if (signingRule && signingStageIds(interactionId).contains(stage.id())) {
            requireSignedAgreement(interactionId);
        }
    }

    private List<UUID> signingStageIds(UUID interactionId) {
        return stages(interactionId).stream()
                .filter(stage -> stage.role() == StageRole.SIGNING)
                .map(WorkStage::stageId)
                .toList();
    }

    // «Подписан» — статус «Действует» или «Завершён» с заполненной датой подписания. Достаточно одного такого
    // договора среди связанных с работой; иначе в сообщении называется самый свежий по смене статуса договор,
    // чтобы пользователь знал, какой именно перевести в «Действует».
    private void requireSignedAgreement(UUID interactionId) {
        List<String> numbers = jdbcClient.sql("""
                SELECT ag.number, CASE WHEN ag.status IN ('ACTIVE', 'COMPLETED') AND ag.concluded_on IS NOT NULL THEN 1 ELSE 0 END AS signed
                FROM agreement_interactions ai
                JOIN agreements ag ON ag.id = ai.agreement_id
                WHERE ai.interaction_id = :interactionId
                ORDER BY ag.status_changed_at DESC, ag.id
                """)
                .param("interactionId", interactionId)
                .query((resultSet, rowNumber) -> resultSet.getInt("signed") == 1 ? "" : resultSet.getString("number"))
                .list();
        if (numbers.contains("")) {
            return;
        }
        throw new InteractionValidationException("agreement", numbers.isEmpty()
                ? "Этап «Подписание» завершается подписанным договором. Создайте договор с вузом в этом взаимодействии"
                : "Этап «Подписание» завершается подписанным договором. Переведите договор № " + numbers.getFirst()
                        + " в статус «Действует» с датой подписания");
    }

    private Optional<StageRole> role(UUID templateStageId, String name) {
        StageRole byTemplate = templateStageId == null ? null : BASE_TEMPLATE_STAGES.get(templateStageId);
        if (byTemplate != null) {
            return Optional.of(byTemplate);
        }
        String normalized = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return names.entrySet().stream()
                .filter(entry -> entry.getValue().contains(normalized))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private record StageRow(UUID id, String name, UUID templateStageId) {
    }

    private static List<String> normalized(List<String> values) {
        return values.stream().map(value -> value.strip().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty()).toList();
    }
}
