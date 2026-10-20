package ru.rtk.crm.settings;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rtk.crm.access.AdminAuthorization;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.audit.AuditAction;
import ru.rtk.crm.audit.AuditJournalRepository;
import ru.rtk.crm.interaction.InteractionValidationException;

// Экран «Настройки» администратора: значение по умолчанию — из конфигурации, правка и сброс пишутся в журнал
// «было → стало» (решение владельца 21.10 №12, по образцу весов индекса востребованности).
@Service
public class SystemSettingsService {
    private static final String OBJECT_TYPE = "SYSTEM_SETTING";

    private final JdbcClient jdbcClient;
    private final SystemSettings settings;
    private final AuditJournalRepository auditJournalRepository;

    public SystemSettingsService(JdbcClient jdbcClient, SystemSettings settings, AuditJournalRepository auditJournalRepository) {
        this.jdbcClient = jdbcClient;
        this.settings = settings;
        this.auditJournalRepository = auditJournalRepository;
    }

    @Transactional(readOnly = true)
    public List<SystemSettingView> list(CrmProfile profile) {
        AdminAuthorization.requireAdmin(profile);
        Map<String, Saved> saved = new HashMap<>();
        jdbcClient.sql("""
                        SELECT s.setting_key, s.setting_value, s.updated_at, p.display_name
                        FROM system_settings s JOIN crm_user_profiles p ON p.id = s.updated_by
                        """)
                .query(resultSet -> {
                    saved.put(resultSet.getString("setting_key"), new Saved(resultSet.getInt("setting_value"),
                            resultSet.getObject("updated_at", OffsetDateTime.class), resultSet.getString("display_name")));
                });
        return Arrays.stream(SystemSetting.values()).map(setting -> {
            int configured = settings.configured(setting);
            Saved row = saved.get(setting.name());
            return new SystemSettingView(setting, setting.group(), setting.title(), setting.unit(), setting.min(), setting.max(),
                    row == null ? configured : row.value(), configured, row != null,
                    row == null ? null : row.updatedAt(), row == null ? null : row.updatedByName());
        }).toList();
    }

    @Transactional
    public List<SystemSettingView> update(CrmProfile profile, String key, SystemSettingRequest request, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        SystemSetting setting = setting(key);
        if (request == null || request.value() == null) {
            throw new InteractionValidationException("value", "Укажите значение");
        }
        int value = request.value();
        if (value < setting.min() || value > setting.max()) {
            throw new InteractionValidationException("value", "Значение «" + setting.title() + "» — от " + setting.min() + " до "
                    + setting.max());
        }
        int before = settings.value(setting);
        jdbcClient.sql("DELETE FROM system_settings WHERE setting_key = :key").param("key", setting.name()).update();
        jdbcClient.sql("""
                        INSERT INTO system_settings (setting_key, setting_value, updated_by, updated_at)
                        VALUES (:key, :value, :profile, :now)
                        """)
                .param("key", setting.name())
                .param("value", value)
                .param("profile", profile.id())
                .param("now", OffsetDateTime.now())
                .update();
        record(profile.id(), setting, setting.format(before) + " → " + setting.format(value), requestId);
        return list(profile);
    }

    @Transactional
    public List<SystemSettingView> reset(CrmProfile profile, String key, String requestId) {
        AdminAuthorization.requireAdmin(profile);
        SystemSetting setting = setting(key);
        int before = settings.value(setting);
        jdbcClient.sql("DELETE FROM system_settings WHERE setting_key = :key").param("key", setting.name()).update();
        record(profile.id(), setting, "возвращено значение конфигурации: " + setting.format(before) + " → "
                + setting.format(settings.configured(setting)), requestId);
        return list(profile);
    }

    private void record(UUID actorId, SystemSetting setting, String details, String requestId) {
        auditJournalRepository.record(AuditAction.SYSTEM_SETTING_CHANGED, actorId, OBJECT_TYPE, null, setting.title(), details,
                requestId);
    }

    private static SystemSetting setting(String key) {
        return Arrays.stream(SystemSetting.values())
                .filter(setting -> setting.name().equals(key))
                .findFirst()
                .orElseThrow(() -> new InteractionValidationException("key", "Неизвестная настройка"));
    }

    private record Saved(int value, OffsetDateTime updatedAt, String updatedByName) {
    }
}

record SystemSettingView(
        SystemSetting key,
        SystemSetting.Group group,
        String title,
        SystemSetting.Unit unit,
        int min,
        int max,
        int value,
        int defaultValue,
        boolean saved,
        OffsetDateTime updatedAt,
        String updatedByName
) {
}

record SystemSettingRequest(Integer value) {
}
