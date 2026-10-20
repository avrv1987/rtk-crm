package ru.rtk.crm.report;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

// Веса индекса востребованности (М17) меняет только администратор; без сохранённой строки действуют значения по
// умолчанию из паспорта. Каждое сохранение и сброс — запись журнала с прежними и новыми значениями.
@Service
public class DemandIndexSettingsService {
    private static final String OBJECT_TYPE = "DEMAND_INDEX";
    private static final String OBJECT_NAME = "Индекс востребованности программ";

    private final JdbcClient jdbcClient;
    private final ReportRepository reportRepository;
    private final AuditJournalRepository auditJournalRepository;

    public DemandIndexSettingsService(JdbcClient jdbcClient, ReportRepository reportRepository,
                                      AuditJournalRepository auditJournalRepository) {
        this.jdbcClient = jdbcClient;
        this.reportRepository = reportRepository;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public DemandIndexSettingsView view(CrmProfile profile) {
        AdminAuthorization.requireAdmin(profile);
        Optional<Saved> saved = jdbcClient.sql("""
                        SELECT s.updated_at, p.display_name FROM demand_index_settings s
                        JOIN crm_user_profiles p ON p.id = s.updated_by WHERE s.id = 1
                        """)
                .query((resultSet, rowNumber) -> new Saved(resultSet.getObject("updated_at", OffsetDateTime.class),
                        resultSet.getString("display_name")))
                .optional();
        return new DemandIndexSettingsView(
                reportRepository.findDemandIndexSettings().orElse(DemandIndex.Settings.DEFAULT),
                DemandIndex.Settings.DEFAULT,
                saved.isPresent(),
                saved.map(Saved::updatedAt).orElse(null),
                saved.map(Saved::updatedByName).orElse(null)
        );
    }

    @Transactional
    public DemandIndexSettingsView update(CrmProfile profile, DemandIndexSettingsRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        if (request == null) {
            throw new InteractionValidationException("body", "Передайте веса индекса востребованности");
        }
        DemandIndex.Settings next = new DemandIndex.Settings(
                required(request.growthWeight(), "growthWeight"),
                required(request.completionWeight(), "completionWeight"),
                required(request.activityWeight(), "activityWeight"),
                required(request.universityWeight(), "universityWeight"),
                required(request.paymentsWeight(), "paymentsWeight"),
                required(request.minCoveragePercent(), "minCoveragePercent"),
                required(request.smallBase(), "smallBase")
        );
        DemandIndex.Settings before = reportRepository.findDemandIndexSettings().orElse(DemandIndex.Settings.DEFAULT);
        jdbcClient.sql("DELETE FROM demand_index_settings").update();
        jdbcClient.sql("""
                        INSERT INTO demand_index_settings (
                            id, growth_weight, completion_weight, activity_weight, university_weight, payments_weight,
                            min_coverage_percent, small_base, updated_by, updated_at
                        ) VALUES (1, :growth, :completion, :activity, :university, :payments, :coverage, :smallBase, :profile, :now)
                        """)
                .param("growth", next.growthWeight())
                .param("completion", next.completionWeight())
                .param("activity", next.activityWeight())
                .param("university", next.universityWeight())
                .param("payments", next.paymentsWeight())
                .param("coverage", next.minCoveragePercent())
                .param("smallBase", next.smallBase())
                .param("profile", profile.id())
                .param("now", OffsetDateTime.now())
                .update();
        auditJournalRepository.record(AuditAction.DEMAND_INDEX_SETTINGS_CHANGED, profile.id(), OBJECT_TYPE, null, OBJECT_NAME,
                changes(before, next), requestId);
        return view(profile);
    }

    @Transactional
    public void reset(CrmProfile profile, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        DemandIndex.Settings before = reportRepository.findDemandIndexSettings().orElse(DemandIndex.Settings.DEFAULT);
        jdbcClient.sql("DELETE FROM demand_index_settings").update();
        auditJournalRepository.record(AuditAction.DEMAND_INDEX_SETTINGS_CHANGED, profile.id(), OBJECT_TYPE, null, OBJECT_NAME,
                "восстановлены значения по умолчанию; " + changes(before, DemandIndex.Settings.DEFAULT), requestId);
    }

    static String changes(DemandIndex.Settings before, DemandIndex.Settings after) {
        List<String> changes = new ArrayList<>();
        Arrays.stream(DemandIndex.Component.values()).forEach(component -> {
            if (before.weight(component) != after.weight(component)) {
                changes.add("вес «" + component.title() + "»: " + before.weight(component) + " → " + after.weight(component));
            }
        });
        if (before.minCoveragePercent() != after.minCoveragePercent()) {
            changes.add("порог покрытия: " + before.minCoveragePercent() + " → " + after.minCoveragePercent() + " %");
        }
        if (before.smallBase() != after.smallBase()) {
            changes.add("малая база: " + before.smallBase() + " → " + after.smallBase() + " участий");
        }
        return changes.isEmpty() ? "значения сохранены без изменений" : String.join("; ", changes);
    }

    private static int required(Integer value, String field) {
        if (value == null) {
            throw new InteractionValidationException(field, "Укажите значение");
        }
        return value;
    }

    private record Saved(OffsetDateTime updatedAt, String updatedByName) {
    }
}

record DemandIndexSettingsView(
        DemandIndex.Settings current,
        DemandIndex.Settings defaults,
        boolean saved,
        OffsetDateTime updatedAt,
        String updatedByName
) {
}

record DemandIndexSettingsRequest(
        Integer growthWeight,
        Integer completionWeight,
        Integer activityWeight,
        Integer universityWeight,
        Integer paymentsWeight,
        Integer minCoveragePercent,
        Integer smallBase
) {
}
