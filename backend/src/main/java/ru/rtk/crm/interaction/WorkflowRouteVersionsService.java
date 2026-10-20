package ru.rtk.crm.interaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.rtk.crm.access.AdminCrmProfileAccessDeniedException;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserRole;

// Сводка «какая команда на какой версии маршрута» (решение владельца 21.10 №9). Работы на новую версию переводят
// руководители; администратор и «Руководство» видят все команды, руководитель — свою, и для него это напоминание.
// Только числа по команде и шаблону, без вузов: администратор работу с вузами не видит.
@Service
public class WorkflowRouteVersionsService {
    private final JdbcClient jdbcClient;

    public WorkflowRouteVersionsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<RouteVersionsRow> summary(CrmProfile profile) {
        boolean all = profile.role() == UserRole.ADMIN || profile.role() == UserRole.MANAGEMENT;
        if (!all && (profile.role() != UserRole.LEADER || profile.teamId() == null)) {
            throw new AdminCrmProfileAccessDeniedException();
        }
        Map<String, RouteVersionsRow> rows = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT t.id AS team_id, t.name AS team_name, wt.id AS template_id, wt.name AS template_name,
                               (SELECT COALESCE(MAX(v.version_number), 0) FROM workflow_template_versions v
                                WHERE v.template_id = wt.id) AS latest_version,
                               i.workflow_version, COUNT(*) AS works
                        FROM interactions i
                        JOIN organizations o ON o.id = i.organization_id
                        JOIN teams t ON t.id = o.team_id
                        JOIN workflow_templates wt ON wt.id = i.workflow_template_id
                        WHERE i.work_status <> 'COMPLETED' AND i.workflow_version IS NOT NULL
                          AND (:all = TRUE OR o.team_id = :teamId)
                        GROUP BY t.id, t.name, wt.id, wt.name, i.workflow_version
                        ORDER BY t.name, wt.name, i.workflow_version
                        """)
                .param("all", all)
                .param("teamId", profile.teamId())
                .query(resultSet -> {
                    UUID teamId = resultSet.getObject("team_id", UUID.class);
                    UUID templateId = resultSet.getObject("template_id", UUID.class);
                    int latest = resultSet.getInt("latest_version");
                    int version = resultSet.getInt("workflow_version");
                    long works = resultSet.getLong("works");
                    String key = teamId + ":" + templateId;
                    RouteVersionsRow row = rows.get(key);
                    if (row == null) {
                        row = new RouteVersionsRow(teamId, resultSet.getString("team_name"), templateId,
                                resultSet.getString("template_name"), latest, new ArrayList<>(), 0);
                    }
                    row.versions().add(new RouteVersionWorks(version, works));
                    rows.put(key, row.withOutdated(version < latest ? works : 0));
                });
        return List.copyOf(rows.values());
    }

    public record RouteVersionsRow(UUID teamId, String teamName, UUID templateId, String templateName, int latestVersion,
                                   List<RouteVersionWorks> versions, long outdatedWorks) {
        RouteVersionsRow withOutdated(long works) {
            return new RouteVersionsRow(teamId, teamName, templateId, templateName, latestVersion, versions, outdatedWorks + works);
        }
    }

    public record RouteVersionWorks(int version, long works) {
    }
}

@RestController
class WorkflowRouteVersionsApiController {
    private final CurrentProfileService currentProfileService;
    private final WorkflowRouteVersionsService service;

    WorkflowRouteVersionsApiController(CurrentProfileService currentProfileService, WorkflowRouteVersionsService service) {
        this.currentProfileService = currentProfileService;
        this.service = service;
    }

    @GetMapping("/api/workflow-route-versions")
    List<WorkflowRouteVersionsService.RouteVersionsRow> summary(@AuthenticationPrincipal OidcUser user) {
        return service.summary(currentProfileService.requireActiveProfile(user));
    }
}
