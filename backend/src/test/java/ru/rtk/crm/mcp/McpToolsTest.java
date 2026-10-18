package ru.rtk.crm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import ru.rtk.crm.interaction.InteractionValidationException;
import ru.rtk.crm.interaction.InteractionWorkStatus;
import ru.rtk.crm.report.ManagerWorkDue;
import ru.rtk.crm.work.WorkControlModels.WorkItem;

class McpToolsTest {
    @Test
    void contactsAreMasked() {
        assertThat(McpTools.maskEmail("ivanov@vuz.example.ru")).isEqualTo("i***@vuz.example.ru");
        assertThat(McpTools.maskEmail("bad")).isEqualTo("***");
        assertThat(McpTools.maskPhone("+7 (900) 345-67-89")).isEqualTo("***89");
        assertThat(McpTools.maskPhone("1")).isEqualTo("***");
        assertThat(McpTools.maskEmail(" ")).isNull();
        assertThat(McpTools.maskPhone(null)).isNull();
    }

    @Test
    void pageTellsWhetherTheListIsComplete() {
        McpTools.Page<Integer> cut = McpTools.Page.of(List.of(1, 2, 3), 10L, 2);
        assertThat(cut.items()).containsExactly(1, 2);
        assertThat(cut.returned()).isEqualTo(2);
        assertThat(cut.isComplete()).isFalse();
        assertThat(McpTools.Page.of(List.of(1, 2), 2L, 25).isComplete()).isTrue();
        assertThat(McpTools.Page.of(List.of(1, 2), null, 25).isComplete()).isFalse();
    }

    @Test
    void userTextIsMarkedAndTruncated() {
        assertThat(McpTools.UserText.of("  ")).isNull();
        assertThat(McpTools.UserText.of("x".repeat(600)).untrustedText()).hasSize(501).endsWith("…");
    }

    @Test
    void argumentsAreValidated() {
        McpTools.Args args = new McpTools.Args(Map.of("limit", 5.0, "bad", "x", "id", "../admin", "from", "вчера"));
        assertThat(args.integer("limit", 1, 50, 25)).isEqualTo(5);
        assertThat(args.integer("missing", 1, 50, 25)).isEqualTo(25);
        assertThatThrownBy(() -> args.integer("bad", 1, 50, 25)).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> args.uuid("id")).isInstanceOf(InteractionValidationException.class);
        assertThatThrownBy(() -> args.date("from", null)).isInstanceOf(InteractionValidationException.class);
        assertThat(new McpTools.Args(Map.of("limit", 5.5)).values()).isNotEmpty();
        assertThatThrownBy(() -> new McpTools.Args(Map.of("limit", 5.5)).integer("limit", 1, 50, 25))
                .isInstanceOf(InteractionValidationException.class);
    }

    @Test
    void stuckRuleMatchesTheControlScreen() {
        assertThat(LeaderTools.stuck(work(InteractionWorkStatus.ACTIVE, 30L, false), 30)).isTrue();
        assertThat(LeaderTools.stuck(work(InteractionWorkStatus.ACTIVE, 29L, false), 30)).isFalse();
        assertThat(LeaderTools.stuck(work(InteractionWorkStatus.PAUSED, 90L, false), 30)).isFalse();
        assertThat(LeaderTools.stuck(work(InteractionWorkStatus.ACTIVE, 90L, true), 30)).isFalse();
        assertThat(LeaderTools.stuck(work(InteractionWorkStatus.ACTIVE, null, false), 30)).isFalse();
    }

    @Test
    void dueGroupFollowsTheCrmRule() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-14T12:00:00+03:00");
        assertThat(KamTools.due(InteractionWorkStatus.ACTIVE, "Позвонить", now.minusHours(1), now)).isEqualTo("OVERDUE");
        assertThat(KamTools.due(InteractionWorkStatus.ACTIVE, "Позвонить", now.plusHours(1), now)).isEqualTo("TODAY");
        assertThat(KamTools.due(InteractionWorkStatus.ACTIVE, "Позвонить", now.plusDays(2), now)).isEqualTo("THIS_WEEK");
        assertThat(KamTools.due(InteractionWorkStatus.ACTIVE, null, null, now)).isEqualTo("NO_NEXT_STEP");
        assertThat(KamTools.due(InteractionWorkStatus.COMPLETED, "Позвонить", now.minusHours(1), now)).isNull();
    }

    private static WorkItem work(InteractionWorkStatus status, Long days, boolean underApproval) {
        return new WorkItem(null, null, null, null, null, null, List.of(), null, 1, 2, days, null, null, null,
                ManagerWorkDue.IN_ORDER, status, 0, 0, null, 0, false, false, null, null, null, null, null, underApproval);
    }
}
